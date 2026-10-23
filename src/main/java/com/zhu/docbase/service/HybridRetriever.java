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
        return retrieve(question, Math.max(top, recallK), top, rrfK);
    }

    public List<Document> retrieve(String question, int recall, int top, int k) {
        List<Document> vec = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(recall).build());

        // BM25 未就绪（启动后约 12 秒内）或已禁用时，退化为纯向量，不能因此报错
        if (!bm25Index.isReady()) {
            log.debug("BM25 索引未就绪，本次退化为纯向量检索");
            return vec.size() > top ? vec.subList(0, top) : vec;
        }

        List<String> kwIds = bm25Index.search(question, recall);
        return fuse(vec, kwIds, top, k);
    }

    /**
     * RRF 融合。向量那一路的 Document 直接复用（已带正文），
     * 关键词那一路新出现的 id 从索引里取正文。
     */
    private List<Document> fuse(List<Document> vec, List<String> kwIds, int top, int k) {
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

        List<Document> out = new ArrayList<>(top);
        score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(top)
                .forEach(e -> out.add(byId.get(e.getKey())));
        return out;
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
