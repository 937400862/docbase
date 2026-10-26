package com.zhu.docbase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量召回 + 关键词召回，用 RRF 融合后取前 finalK 条。
 *
 * <h3>RRF 是什么</h3>
 * Reciprocal Rank Fusion，只使用「排名」不用「分数」：
 * <pre>
 *   score(d) = Σ_i  1 / (k + rank_i(d))        k 默认 60
 * </pre>
 * 为什么不用分数相加：向量是 cosine（0~1，集中在 0.5~0.8），BM25 是无上界分值，
 * 两者尺度不可比；归一化又得假设分布。排名是天然可比的，所以只用排名。
 * k 控制头部权重：k 小则第 1 名碾压一切，k 大则各名次趋同，60 是原论文经验值。
 *
 * <h3>实测效果（30 题评测集，gold 是否进 top-5）</h3>
 * <pre>
 *   纯向量        20/30
 *   纯 BM25       25/30
 *   RRF 融合      26/30   ← 采用
 *   cross-encoder 重排 26/30，但单题多 35 秒，且叠在 RRF 上反而降到 25/30
 * </pre>
 *
 * <h3>已知代价（RRF 的固有特性，不是 bug）</h3>
 * 它奖励「两路都还行」，惩罚「一路极好、另一路没有」：
 * 向量第 1 名 + BM25 第 26 名（超出 top-20 不计分）只得 1/61=0.0164，
 * 而「向量第 5 + BM25 第 3」能得 1/65+1/63=0.0313，前者会被压下去。
 * 实测有 3 道题因此变差（P10 16→8、P14 1→11、P11 直接出池）。
 *
 * @author ：zhuzj
 * @date ：2026/10/23
 */
@Slf4j
@Service
public class HybridRetriever {

    private final VectorStore vectorStore;
    private final Bm25IndexService bm25Index;

    /** 每路召回多少条候选（融合的输入） */
    @Value("${rag.chat.recall-k:20}")
    private int recallK;

    /** 最终喂给模型几条（受 num_ctx 约束，见 application.yaml） */
    @Value("${rag.chat.top-k:5}")
    private int finalK;

    /** RRF 的 k。60 是 Cormack 2009 的经验值 */
    @Value("${rag.chat.rrf-k:60}")
    private int rrfK;

    /**
     * 同一份文档最多保留几个块。0 或负数 = 不限制（<b>默认，见下方实测结论</b>）。
     *
     * <p>【2026-10-26 加，同日实测否证】起因是 RRF 只合并排名、不做来源去重，
     * 而同一份文档的相邻块内容高度相似，top-5 的 5 个名额可能被一份文档吃光
     * （104 题里 13 道 0 覆盖题的 top-5 有 3 块以上同源；有题目明确点名了某张表，
     * 而 top-5 里那块表一块都没进）。
     *
     * <p>但实测 104 题（只量检索层）：gold 命中 <b>198 → 186</b>，变好 2 题、变差 7 题。
     * 原因是变差的题去重前都只有 1~2 个源 —— 那几块同源块<b>恰恰拼出了全部 gold</b>：
     * 同一张表被切成多块、每块带一部分行，合起来才完整，去掉就丢答案
     * （有一题从 4/4 直接掉到 0/4）。只有去重前本来就 3 源的题才受益。
     *
     * <p>结论：<b>同源多块在多数情况下是必要的材料，不是挤占名额的浪费。</b>
     * 代码和单测保留，默认关闭；要做「同一文档多版本去重」时 docKey 可以复用。
     */
    @Value("${rag.chat.max-per-doc:0}")
    private int maxPerDoc;

    public HybridRetriever(VectorStore vectorStore, Bm25IndexService bm25Index) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
    }

    /** 便捷入口，用配置里的默认参数 */
    public List<Document> retrieve(String question) {
        return retrieve(question, recallK, finalK, rrfK);
    }

    /**
     * 融合后取前 top 条（召回宽度也用 top）。给诊断接口用：
     * 想知道「融合后 gold 排第几」时，召回和返回都放宽到同一个数才有意义。
     */
    public List<Document> retrieveFused(String question, int top) {
        return retrieveFused(question, top, true);
    }

    /**
     * @param dedup 是否做来源去重。评测要量「实际喂给模型的块」，所以默认 true；
     *              想知道「不去重时 gold 真实排第几」时传 false 做对照
     */
    public List<Document> retrieveFused(String question, int top, boolean dedup) {
        return retrieve(question, Math.max(top, recallK), top, rrfK, dedup);
    }

    public List<Document> retrieve(String question, int recall, int top, int k) {
        return retrieve(question, recall, top, k, true);
    }

    public List<Document> retrieve(String question, int recall, int top, int k, boolean dedup) {
        List<Document> vec = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(recall).build());

        // BM25 未就绪（启动后约 12 秒内）或已禁用时，退化为纯向量，不能因此报错
        if (!bm25Index.isReady()) {
            log.debug("BM25 索引未就绪，本次退化为纯向量检索");
            List<Document> pure = dedup ? dedup(vec, top) : vec;
            return pure.size() > top ? pure.subList(0, top) : pure;
        }

        List<String> kwIds = bm25Index.search(question, recall);
        return fuse(vec, kwIds, top, k, dedup);
    }

    /**
     * RRF 融合。向量那一路的 Document 直接复用（已带正文），
     * 关键词那一路新出现的 id 从索引里取正文。
     */
    private List<Document> fuse(List<Document> vec, List<String> kwIds, int top, int k, boolean dedup) {
        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, Document> byId = new HashMap<>();

        for (int i = 0; i < vec.size(); i++) {
            Document d = vec.get(i);
            score.merge(d.getId(), 1.0 / (k + i + 1), Double::sum);
            byId.put(d.getId(), d);
        }
        for (int i = 0; i < kwIds.size(); i++) {
            String id = kwIds.get(i);
            score.merge(id, 1.0 / (k + i + 1), Double::sum);
            if (!byId.containsKey(id)) {
                String text = bm25Index.textOf(id);
                // Spring AI 2.0 的 Document 没有 (id, text) 两参构造器，正文必须走三参
                byId.put(id, new Document(id, text == null ? "" : text, Map.of()));
            }
        }

        List<Document> ranked = score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> byId.get(e.getKey()))
                .toList();
        return dedup ? dedup(ranked, top) : head(ranked, top);
    }

    /**
     * 按文档限额挑选：同一份文档最多留 {@link #maxPerDoc} 块。
     *
     * <p>关键一点是<b>返回条数不能变少</b>：名额没用完时用被挤掉的块补回来。
     * 否则「上下文变少」就成了第二个变量，涨跌说不清是去重带来的还是少喂了带来的。
     */
    private List<Document> dedup(List<Document> ranked, int top) {
        return dedup(ranked, top, maxPerDoc);
    }

    /** 静态 + 显式传限额，给单测直接调（不启动 Spring 也能验限额逻辑） */
    static List<Document> dedup(List<Document> ranked, int top, int perDocLimit) {
        if (perDocLimit <= 0 || ranked.size() <= top) {
            return head(ranked, top);
        }
        List<Document> out = new ArrayList<>(top);
        List<Document> overflow = new ArrayList<>();
        Map<String, Integer> perDoc = new HashMap<>();
        for (Document d : ranked) {
            if (out.size() >= top) {
                break;
            }
            String key = docKey(d.getId());
            if (perDoc.getOrDefault(key, 0) < perDocLimit) {
                perDoc.merge(key, 1, Integer::sum);
                out.add(d);
            } else {
                overflow.add(d);
            }
        }
        for (Document d : overflow) {
            if (out.size() >= top) {
                break;
            }
            out.add(d);
        }
        return out;
    }

    private static List<Document> head(List<Document> list, int top) {
        return list.size() > top ? new ArrayList<>(list.subList(0, top)) : list;
    }

    /**
     * 块 id 形如 {@code doc:<文档哈希>#<序号>}，哈希相同即同一份文档。
     *
     * <p>不从 metadata 取来源：BM25 那一路补出来的 Document 是空 metadata，
     * 靠它去重会漏掉一半候选。id 前缀是两路都有的。
     */
    static String docKey(String id) {
        int i = id == null ? -1 : id.indexOf('#');
        return i > 0 ? id.substring(0, i) : String.valueOf(id);
    }

    /** 诊断用：返回两路各自的原始名次，方便排查"某题为什么没命中" */
    public Map<String, List<String>> debug(String question, int recall) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("vector", vectorStore.similaritySearch(
                        SearchRequest.builder().query(question).topK(recall).build())
                .stream().map(Document::getId).toList());
        m.put("bm25", bm25Index.search(question, recall));
        return m;
    }
}
