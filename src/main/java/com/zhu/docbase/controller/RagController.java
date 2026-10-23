package com.zhu.docbase.controller;

import com.zhu.docbase.service.Bm25IndexService;
import com.zhu.docbase.service.DocumentIngestionService;
import com.zhu.docbase.service.HybridRetriever;
import com.zhu.docbase.service.RagChatService;
import com.zhu.docbase.service.RagMaintenanceService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 *
 * @author ：zhuzj
 * @date ：2026/10/16 13:19
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private final DocumentIngestionService ingestionService;
    private final RagChatService ragChatService;
    /** 【改动 2026-10-22】体检服务，给 /maintenance/* 用 */
    private final RagMaintenanceService maintenanceService;
    /** 【改动 2026-10-22】给 /retrieve 用：直接看检索结果，不经过 LLM */
    private final VectorStore vectorStore;
    /** 【改动 2026-10-23】混合检索与 BM25 索引诊断 */
    private final HybridRetriever hybridRetriever;
    private final Bm25IndexService bm25IndexService;

    public RagController(DocumentIngestionService ingestionService,
                         RagChatService ragChatService,
                         RagMaintenanceService maintenanceService,
                         VectorStore vectorStore,
                         HybridRetriever hybridRetriever,
                         Bm25IndexService bm25IndexService) {
        this.ingestionService = ingestionService;
        this.ragChatService = ragChatService;
        this.maintenanceService = maintenanceService;
        this.vectorStore = vectorStore;
        this.hybridRetriever = hybridRetriever;
        this.bm25IndexService = bm25IndexService;
    }

    /**
     * 【2026-10-23 新增】混合检索诊断：把「向量名次 / BM25 名次 / RRF 融合结果」并排打出来。
     *
     * <p>用途：验证 Java 版的 BM25 名次与离线评测（Python jieba）是否一致。
     * 两边分词器不同（jieba-analysis vs Python jieba），如果名次差异很大，
     * 说明分词不一致，离线测出来的 26/30 就不能直接当作上线后的预期。
     *
     * @param question 问题
     * @param recallK  每路召回多少条，默认 20（与线上一致）
     */
    @GetMapping("/retrieve-hybrid")
    public String retrieveHybrid(@RequestParam String question,
                                 @RequestParam(defaultValue = "20") int recallK) {
        StringBuilder sb = new StringBuilder();
        sb.append("BM25 索引: ").append(bm25IndexService.isReady()
                ? "就绪，" + bm25IndexService.size() + " 条" : "未就绪（本次退化为纯向量）").append("\n\n");

        var debug = hybridRetriever.debug(question, recallK);
        List<String> vec = debug.getOrDefault("vector", List.of());
        List<String> kw = debug.getOrDefault("bm25", List.of());
        sb.append("--- 向量 top").append(recallK).append(" ---\n");
        for (int i = 0; i < vec.size(); i++) {
            sb.append(i + 1).append(". ").append(vec.get(i)).append('\n');
        }
        sb.append("\n--- BM25 top").append(recallK).append(" ---\n");
        for (int i = 0; i < kw.size(); i++) {
            sb.append(i + 1).append(". ").append(kw.get(i)).append('\n');
        }

        sb.append("\n--- RRF 融合后喂给模型的片段 ---\n");
        List<Document> fused = hybridRetriever.retrieve(question);
        for (int i = 0; i < fused.size(); i++) {
            Document d = fused.get(i);
            sb.append(i + 1).append(". ").append(d.getId())
                    .append("  | 向量").append(pos(vec, d.getId()))
                    .append(" BM25").append(pos(kw, d.getId()))
                    .append('\n').append(d.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /** 名次展示：不在这一路里就显示「—」，不要显示 0（0 容易被误读成"排第 0 名"） */
    private static String pos(List<String> list, String id) {
        int i = list.indexOf(id);
        return i < 0 ? "—" : "#" + (i + 1);
    }

    /**
     * 检索诊断：不调用 LLM，把候选片段原样返回。
     *
     * <p>为什么需要：/ask 只给最终答案，看不出"答不上"是因为没检索到，还是检索到了但模型没组织好。
     * 评测集要判断一道题的 gold 到底在不在 top-20 候选里，只能靠这个接口。
     *
     * <p>【2026-10-23 改动】默认改走混合检索（向量 + BM25 → RRF），
     * 因为这才是 /ask 实际用的路径 —— 评测必须量真实行为，不能量一个已经不在用的旧链路。
     * 要对比纯向量，加 {@code vectorOnly=true}。
     *
     * @param question   问题
     * @param topK       返回多少个候选，默认 20（比问答时的 5 宽，用于观察召回上限）
     * @param vectorOnly 只看纯向量检索（2026-10-23 之前的链路）
     */
    @GetMapping("/retrieve")
    public String retrieve(@RequestParam String question,
                           @RequestParam(defaultValue = "20") int topK,
                           @RequestParam(defaultValue = "false") boolean vectorOnly) {
        List<Document> docs = vectorOnly
                ? vectorStore.similaritySearch(
                        SearchRequest.builder().query(question).topK(topK).build())
                : hybridRetriever.retrieveFused(question, topK);
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(docs.size()).append(" 个候选")
                .append(vectorOnly ? "（纯向量）" : "（混合检索：向量+BM25→RRF）").append("\n\n");
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("### RANK ").append(i + 1)
                    .append(" | id=").append(d.getId())
                    .append(" | source=").append(d.getMetadata().get("source"))
                    .append('\n')
                    .append(d.getText())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 摄入单个文件。
     *
     * @param path 文件绝对路径
     * @param mode 【改动 2026-10-22】skip（默认）= 已摄入且未改动就跳过；overwrite = 强制删掉重摄
     */
    @PostMapping("/ingest/file")
    public String ingestFile(@RequestParam String path,
                             @RequestParam(defaultValue = "skip") String mode) {
        // 【改动 2026-10-20】ingestFile 现在返回 IngestResult（片段数 + 状态），
        // 因为"解析出内容过短"和"之前已摄入过"既不是成功也不是失败，需要分别提示
        boolean overwrite = "overwrite".equalsIgnoreCase(mode);
        DocumentIngestionService.IngestResult result = ingestionService.ingestFile(path, overwrite);
        return switch (result.status()) {
            case SKIPPED_SHORT -> "跳过：解析出的内容过短（少于 20 字符），未入库";
            // 【改动 2026-10-22】命中 chunk id 里的文件指纹：已摄入且 mtime 未变
            case SKIPPED_DONE -> "跳过：该文件已摄入且未改动（加 mode=overwrite 可强制重摄）";
            case FAILED -> "摄入失败：0 个片段（详见日志）";
            case OK -> (overwrite ? "已覆盖摄入 " : "已摄入 ") + result.chunks() + " 个片段";
        };
    }

    /**
     * 摄入整个目录。
     *
     * @param mode 【2026-10-22 新增】skip（默认）= 已摄入且未改动就跳过；
     *             overwrite = 无视 mtime，逐文件删掉重摄。
     *             改了入库逻辑（切分规则、metadata、来源前缀）之后必须用 overwrite，
     *             否则 mtime 没变，所有文件都会被判成"已摄入且未改动"跳过。
     */
    @PostMapping("/ingest/dir")
    public String ingestDir(@RequestParam String path,
                            @RequestParam(defaultValue = "skip") String mode) {
        int chunks = ingestionService.ingestDirectory(path, "overwrite".equalsIgnoreCase(mode));
        return "目录摄入完成，共 " + chunks + " 个片段";
    }

    /** RAG 问答（基于知识库） */
    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        return ragChatService.ask(question);
    }

    /** 纯对话（不检索知识库） */
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return ragChatService.chat(message);
    }

    // ==================== 知识库体检（2026-10-22 新增）====================
    // 正式用法是定时任务（见 RagMaintenanceJob），这几个接口是为了"手动跑一次"和排查用。
    // 全部只读，除了 /maintenance/fix。

    /**
     * 体检：孤儿 / 残缺 / 重复文件，只报告不修改。
     *
     * @param duplicates 是否检查重复文件（要读文件内容，比较慢，默认 true）
     */
    @GetMapping("/maintenance/check")
    public String maintenanceCheck(@RequestParam(defaultValue = "true") boolean duplicates) {
        return maintenanceService.format(maintenanceService.check(duplicates));
    }

    /**
     * 修复残缺文件（`#0` 在但最后一个 chunk 不在的那种），会删掉重摄。
     *
     * @param limit 单次最多修几个，默认 20
     */
    @PostMapping("/maintenance/fix")
    public String maintenanceFix(@RequestParam(defaultValue = "20") int limit) {
        int fixed = maintenanceService.fixIncomplete(maintenanceService.scanIndexed(), limit);
        return "修复完成，共修复 " + fixed + " 个文件（详见日志）";
    }

    /** 列出库里 chunk 数最多的文件，排查"内存被谁吃了"用 */
    @GetMapping("/maintenance/top")
    public String maintenanceTop(@RequestParam(defaultValue = "20") int n) {
        return String.join("\n", maintenanceService.topFiles(n));
    }

}
