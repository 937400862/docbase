package com.zhu.docbase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摄入过滤的优先级：**按后缀判垃圾，必须早于「内容过短 → 文件名兜底」**。
 *
 * <p>背景：为了让"正文为空但文件名就是结论"的文档能进库，加了「文件名 ≥10 个汉字就兜底入库」的规则。
 * 这条规则的风险是：一个叫「xxx说明」的 .rpm / .bcmap 也可能被当成正文。所以后缀黑名单要在它之前生效。
 *
 * <p>这里只测得到批量摄入那一层（{@link DocumentIngestionService#shouldIngest}）——
 * 单文件摄入接口是直接调 {@code ingestFile}，绕过 shouldIngest，那边靠兜底分支里的第二次后缀判断。
 */
class DocumentIngestionServiceTest {

    /** 默认上限 10 MB，和配置 rag.ingest.max-file-size-mb 的默认值一致 */
    private static final long MAX = 10L * 1024 * 1024;

    @Test
    void junkExtensionIsRejectedEvenWithLongChineseName(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("这是一份名字很长的说明文档用来测试后缀优先级.rpm");
        Files.writeString(junk, "x");
        assertFalse(DocumentIngestionService.shouldIngest(junk, MAX),
                "名字够长也不行：后缀是垃圾格式，必须在文件名兜底之前被挡住");
    }

    @Test
    void officeTempLockIsRejected(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("~$这是一份名字很长的说明文档用来测试.docx");
        Files.writeString(lock, "x");
        assertFalse(DocumentIngestionService.shouldIngest(lock, MAX));
    }

    @Test
    void longChineseNameDocumentIsKept(@TempDir Path dir) throws Exception {
        Path doc = dir.resolve("这是一份名字很长的说明文档用来测试后缀优先级.doc");
        Files.writeString(doc, "x");
        assertTrue(DocumentIngestionService.shouldIngest(doc, MAX),
                "正常文档后缀不该被挡");
    }

    @Test
    void oversizedFileIsRejected(@TempDir Path dir) throws Exception {
        Path big = dir.resolve("超大文件.txt");
        Files.write(big, new byte[11 * 1024 * 1024]);
        assertFalse(DocumentIngestionService.shouldIngest(big, MAX));
    }

    @Test
    void sizeLimitIsConfigurable(@TempDir Path dir) throws Exception {
        Path mid = dir.resolve("中等文件.txt");
        Files.write(mid, new byte[3 * 1024 * 1024]);
        // 同一个文件：默认 10 MB 放行，调成 1 MB 就该拒 —— 上限来自配置，不是写死的
        assertTrue(DocumentIngestionService.shouldIngest(mid, MAX));
        assertFalse(DocumentIngestionService.shouldIngest(mid, 1024 * 1024));
    }

    // ============ 块首「文件日期 / 版本」标签（2026-10-27 新增）============

    @Test
    @DisplayName("文件名里的 8 位日期：转成 yyyy-MM-dd")
    void dateTagFromFileName() {
        assertEquals("【文件日期：2025-03-28】",
                DocumentIngestionService.buildDateTag("某表格20250328.xlsx"));
    }

    @Test
    @DisplayName("文件名里的版本号：统一写成大写 V")
    void versionTagFromFileName() {
        assertEquals("【版本：V1.2】",
                DocumentIngestionService.buildDateTag("某系统规范v1.2.docx"));
    }

    @Test
    @DisplayName("日期和版本都有时，标签里两样都给")
    void dateAndVersionTogether() {
        assertEquals("【文件日期：2024-09-06｜版本：V1.0】",
                DocumentIngestionService.buildDateTag("某核算表20240906V1.0.xlsx"));
    }

    @Test
    @DisplayName("文件名里没有日期也没有版本时：不加标签（不用 mtime 兜底）")
    void noDateNoVersionGivesNoTag() {
        // mtime 是拷贝/同步时间，不是文档日期。拿它兜底会把旧文档标成"新版本"，
        // 实测反而让一道题的 gold 掉了。宁可没有标签。
        assertEquals("", DocumentIngestionService.buildDateTag("无日期无版本.docx"));
        assertEquals("", DocumentIngestionService.buildDateTag("2024年工作总结.docx"));
    }

    @Test
    @DisplayName("非法日期（13 月 32 日）不能被当成日期")
    void ignoresIllegalDate() {
        assertEquals("", DocumentIngestionService.buildDateTag("abc20241332def"));
    }

    @Test
    @DisplayName("空文件名返回空标签，不抛异常")
    void emptyNameGivesEmptyTag() {
        assertEquals("", DocumentIngestionService.buildDateTag(""));
        assertEquals("", DocumentIngestionService.buildDateTag(null));
    }
}
