package com.zhu.docbase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源去重的单元测试（2026-10-26 新增）。
 *
 * <p>为什么要有这组测试：去重是个「少给东西」的改动，最容易犯两种错 ——
 * <ul>
 *   <li>把同一份文档的块全砍光，喂给模型的上下文比之前还少；</li>
 *   <li>返回条数少了，于是"涨跌"里混进了一个"上下文变少"的变量，说不清是谁带来的。</li>
 * </ul>
 * 所以核心断言是：<b>条数永远等于 min(top, 候选数)</b>，去重只换人不换数量。
 */
class HybridRetrieverTest {

    private static Document doc(String id) {
        return new Document(id, "正文 " + id, Map.of());
    }

    private static List<Document> docs(String... ids) {
        List<Document> list = new ArrayList<>();
        for (String id : ids) {
            list.add(doc(id));
        }
        return list;
    }

    private static List<String> idsOf(List<Document> list) {
        return list.stream().map(Document::getId).toList();
    }

    @Test
    @DisplayName("docKey：同一文档的不同块归到同一个 key")
    void docKeyGroupsChunksOfSameFile() {
        assertEquals(HybridRetriever.docKey("doc:abc#0"), HybridRetriever.docKey("doc:abc#17"));
        assertEquals("doc:abc", HybridRetriever.docKey("doc:abc#17"));
    }

    @Test
    @DisplayName("docKey：不同文档不能撞 key")
    void docKeyKeepsDifferentFilesApart() {
        assertTrue(!HybridRetriever.docKey("doc:aaa#0").equals(HybridRetriever.docKey("doc:bbb#0")));
    }

    @Test
    @DisplayName("docKey：没有 # 的 id 原样返回，不抛异常")
    void docKeyHandlesIdWithoutHash() {
        assertEquals("plain-id", HybridRetriever.docKey("plain-id"));
        assertEquals("#5", HybridRetriever.docKey("#5"));
        assertEquals("null", HybridRetriever.docKey(null));
    }

    @Test
    @DisplayName("5 块同源：只留 2 块，条数仍然是 5（用别的文档补满）")
    void dedupBreaksMonopolyButKeepsCount() {
        List<Document> ranked = docs(
                "doc:A#0", "doc:A#1", "doc:A#2", "doc:A#3", "doc:A#4",
                "doc:B#0", "doc:B#1", "doc:C#0");

        List<Document> out = HybridRetriever.dedup(ranked, 5, 2);

        assertEquals(5, out.size(), "条数必须不变，否则等于顺手改了上下文长度");
        assertEquals(List.of("doc:A#0", "doc:A#1", "doc:B#0", "doc:B#1", "doc:C#0"), idsOf(out));
    }

    @Test
    @DisplayName("候选不够分时不能少给：只有一份文档，就还它 5 块")
    void dedupFallsBackWhenNotEnoughSources() {
        List<Document> ranked = docs("doc:A#0", "doc:A#1", "doc:A#2", "doc:A#3", "doc:A#4");

        List<Document> out = HybridRetriever.dedup(ranked, 5, 2);

        assertEquals(5, out.size());
        assertEquals(List.of("doc:A#0", "doc:A#1", "doc:A#2", "doc:A#3", "doc:A#4"), idsOf(out));
    }

    @Test
    @DisplayName("限额 0 = 关闭去重，行为与改动前完全一致")
    void zeroLimitDisablesDedup() {
        List<Document> ranked = docs("doc:A#0", "doc:A#1", "doc:A#2", "doc:B#0", "doc:C#0");

        assertEquals(idsOf(ranked), idsOf(HybridRetriever.dedup(ranked, 5, 0)));
    }

    @Test
    @DisplayName("候选比 top 少时原样返回，不补空")
    void dedupKeepsShortListAsIs() {
        List<Document> ranked = docs("doc:A#0", "doc:A#1");

        List<Document> out = HybridRetriever.dedup(ranked, 5, 2);

        assertEquals(2, out.size());
        assertEquals(List.of("doc:A#0", "doc:A#1"), idsOf(out));
    }

    @Test
    @DisplayName("保序：去重不改变块之间的相对先后")
    void dedupPreservesOrder() {
        List<Document> ranked = docs(
                "doc:A#0", "doc:B#0", "doc:A#1", "doc:C#0", "doc:B#1", "doc:A#2");

        List<Document> out = HybridRetriever.dedup(ranked, 4, 2);

        assertEquals(List.of("doc:A#0", "doc:B#0", "doc:A#1", "doc:C#0"), idsOf(out));
    }

    // ================= 元数据块名额（2026-10-27 新增）=================

    private static Document metaDoc(String id, String tag) {
        return new Document(id, "【来源：某表.xlsx】 " + tag + "某表.xlsx｜sheet: S1｜列: A、B", Map.of());
    }

    @Test
    @DisplayName("元数据块识别：要跳过【来源：…】前缀，别直接 startsWith")
    void detectsMetaBlockAfterSourcePrefix() {
        assertTrue(HybridRetriever.isMetaBlock("【来源：a.xlsx】 【表】a.xlsx｜sheet: S1"));
        assertTrue(HybridRetriever.isMetaBlock("【来源：a.xlsx】\n【列取值】a.xlsx｜sheet: S1"));
        assertTrue(HybridRetriever.isMetaBlock("【表】没有来源前缀的块"));
        assertEquals(false, HybridRetriever.isMetaBlock("【来源：a.xlsx】 正文第一段"));
        assertEquals(false, HybridRetriever.isMetaBlock("正文里提到【表】但不在块首"));
        assertEquals(false, HybridRetriever.isMetaBlock(null));
    }

    @Test
    @DisplayName("名额=1：top5 里【表】+【列取值】合计只占 1 个，其余用后面的正文块补满")
    void metaQuotaKeepsOnlyOneMetadataBlock() {
        List<Document> ranked = List.of(
                metaDoc("doc:A#0", "【表】"),
                metaDoc("doc:B#0", "【列取值】"),
                new Document("doc:C#0", "【来源：c.docx】 正文块一", Map.of()),
                new Document("doc:D#0", "【来源：d.docx】 正文块二", Map.of()),
                new Document("doc:E#0", "【来源：e.docx】 正文块三", Map.of()),
                new Document("doc:F#0", "【来源：f.docx】 正文块四", Map.of()));

        List<Document> out = HybridRetriever.select(ranked, 5, 0, 1);

        assertEquals(5, out.size());
        // 名额给第一个元数据块（A#0），B#0 被跳过，后面的正文块整体上移补满
        assertEquals(List.of("doc:A#0", "doc:C#0", "doc:D#0", "doc:E#0", "doc:F#0"), idsOf(out));
        assertEquals(1, out.stream().filter(d -> HybridRetriever.isMetaBlock(d.getText())).count());
    }

    @Test
    @DisplayName("名额=0 时行为与改动前一致（不限元数据块）")
    void zeroMetaQuotaIsNoop() {
        List<Document> ranked = List.of(
                metaDoc("doc:A#0", "【表】"),
                metaDoc("doc:B#0", "【列取值】"),
                new Document("doc:C#0", "正文", Map.of()));

        assertEquals(idsOf(ranked), idsOf(HybridRetriever.select(ranked, 3, 0, 0)));
    }

    @Test
    @DisplayName("元数据块太少补不满时，宁可放宽名额也要凑够条数")
    void metaQuotaRelaxesRatherThanReturningShortList() {
        List<Document> ranked = List.of(
                metaDoc("doc:A#0", "【表】"),
                metaDoc("doc:B#0", "【列取值】"));

        List<Document> out = HybridRetriever.select(ranked, 2, 0, 1);

        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("两个限额同时生效：文档限额和元数据名额都不破")
    void bothLimitsTogether() {
        List<Document> ranked = List.of(
                metaDoc("doc:A#0", "【表】"),
                metaDoc("doc:A#1", "【列取值】"),
                new Document("doc:A#2", "【来源：a.xlsx】 正文 A2", Map.of()),
                new Document("doc:B#0", "【来源：b.docx】 正文 B0", Map.of()),
                new Document("doc:C#0", "【来源：c.docx】 正文 C0", Map.of()));

        List<Document> out = HybridRetriever.select(ranked, 3, 1, 1);

        assertEquals(3, out.size());
        assertEquals(1, out.stream().filter(d -> HybridRetriever.isMetaBlock(d.getText())).count());
        // 每份文档最多 1 块
        assertEquals(3, out.stream().map(d -> HybridRetriever.docKey(d.getId())).distinct().count());
    }
}
