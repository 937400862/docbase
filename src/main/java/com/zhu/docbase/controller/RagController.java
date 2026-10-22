package com.zhu.docbase.controller;

import com.zhu.docbase.service.DocumentIngestionService;
import com.zhu.docbase.service.RagChatService;
import com.zhu.docbase.service.RagMaintenanceService;
import org.springframework.web.bind.annotation.*;

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

    public RagController(DocumentIngestionService ingestionService,
                         RagChatService ragChatService,
                         RagMaintenanceService maintenanceService) {
        this.ingestionService = ingestionService;
        this.ragChatService = ragChatService;
        this.maintenanceService = maintenanceService;
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

    /** 摄入整个目录 */
    @PostMapping("/ingest/dir")
    public String ingestDir(@RequestParam String path) {
        int chunks = ingestionService.ingestDirectory(path);
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
