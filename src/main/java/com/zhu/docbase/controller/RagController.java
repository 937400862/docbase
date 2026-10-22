package com.zhu.docbase.controller;

import com.zhu.docbase.service.DocumentIngestionService;
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

    public RagController(DocumentIngestionService ingestionService,
                         RagChatService ragChatService,
                         RagMaintenanceService maintenanceService,
                         VectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.ragChatService = ragChatService;
        this.maintenanceService = maintenanceService;
        this.vectorStore = vectorStore;
    }

    /**
     * 【2026-10-22 新增】检索诊断：只做向量检索，不调用 LLM，把候选片段原样返回。
     *
     * <p>为什么需要：/ask 只给最终答案，看不出"答不上"是因为没检索到，还是检索到了但模型没组织好。
     * 评测集要判断一道题的 gold 到底在不在 top-20 候选里，只能靠这个接口。
     *
     * @param question 问题
     * @param topK     返回多少个候选，默认 20（比问答时的 5 宽，用于观察召回上限）
     */
    @GetMapping("/retrieve")
    public String retrieve(@RequestParam String question,
                           @RequestParam(defaultValue = "20") int topK) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(docs.size()).append(" 个候选\n\n");
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
