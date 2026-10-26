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
}
