package com.zhu.docbase.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存 BM25 索引，给「向量 + 关键词」混合检索提供关键词这一路。
 *
 * <h3>为什么要在 JVM 里自己建，而不是用 Redis 自带的全文检索</h3>
 * Redis 的 {@code $.content} 本来就是 TEXT 字段，加 {@code LANGUAGE chinese} 就能中文检索。
 * 但实测 30 题只有 19/30 命中 top-5，<b>比纯向量（20/30）还差</b> ——
 * Friso 对中文复合词的切分质量明显不如 jieba，业务术语尤其明显。
 * jieba 切词后同样的 BM25 能到 25/30，所以只能自己在 JVM 里建。
 *
 * <h3>为什么启动后异步建，而不是构造器里建</h3>
 * 4,497 条建索引实测约 12 秒。放在构造器里会拖慢启动；放在
 * {@link ApplicationReadyEvent} 里，端口已经能对外服务，只是前十几秒
 * 关键词这一路还没就绪（{@link #isReady()} 为 false，上层降级为纯向量）。
 *
 * <h3>内存占用</h3>
 * 4,497 chunk：正文副本约 4 MB，倒排（24 万条 posting，每条 2 个 int）约 2 MB，
 * 词表 2.2 万项约 2 MB，合计约 10 MB。扩到 6.5 万 chunk 时会到约 150 MB，
 * 那时需要改成索引持久化 + 增量更新，见 AGENTS.md 第十五节。
 *
 * @author ：zhuzj
 * @date ：2026/10/23
 */
@Slf4j
@Service
public class Bm25IndexService {

    /** BM25 调参。k1 控制词频饱和，b 控制文档长度归一化，都是文献默认值 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /**
     * 英文/数字串整体保留：版本号 V1.6、日期 20220314、字段名 corpId 这类
     * 一旦被切开就失去检索价值，而它们恰好是向量检索最弱、关键词最强的部分。
     * 与离线评测脚本 {@code 评测/测BM25.py} 里的 ALNUM 正则保持一致。
     */
    private static final Pattern ALNUM = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]*|\\d[\\d.\\-]*");

    /** 含至少一个字母数字或汉字才算有效词（过滤纯标点） */
    private static final Pattern HAS_WORD = Pattern.compile("[\\w\\u4e00-\\u9fff]");

    /**
     * 停用词。与 {@code 评测/测BM25.py} 的 STOP 集合保持一致 ——
     * 两边不一致会导致 Java 版复现不出离线测出来的名次。
     */
    private static final Set<String> STOP = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "有", "无", "这", "那", "个", "们",
            "为", "以", "及", "对", "从", "到", "上", "下", "中",
            "什么", "怎么", "哪些", "如何", "多少", "吗", "呢", "啊",
            "会", "要", "能", "可以", "需要", "进行", "一个", "我们", "他们", "它", "其", "该", "等",
            "就", "也", "都", "还", "又", "并", "但", "而", "被", "把", "让", "使", "由", "于",
            "之", "所", "者", "后", "前", "里", "外", "时", "时候");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    @Value("${spring.ai.vectorstore.redis.prefix:doc:}")
    private String keyPrefix;

    @Value("${rag.chat.bm25.enabled:true}")
    private boolean enabled;

    /** 不可变快照。重建时整体替换，读的一方永远看到完整的一份，不会读到半成品 */
    private volatile Index index;

    public Bm25IndexService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 对外接口 ====================

    public boolean isReady() {
        return index != null;
    }

    public int size() {
        Index idx = index;
        return idx == null ? 0 : idx.ids.length;
    }

    /**
     * 按 BM25 打分返回前 topN 个 chunk 的 id（不带 {@code doc:} 前缀，与
     * {@code Document.getId()} 的形式一致）。
     *
     * @return 未就绪时返回空列表，调用方应降级为纯向量检索
     */
    public List<String> search(String query, int topN) {
        Index idx = index;
        if (idx == null || query == null || query.isBlank()) {
            return List.of();
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : new LinkedHashSet<>(tokenize(query))) {
            int[] postings = idx.postings.get(term);
            if (postings == null) {
                continue;
            }
            double idf = idx.idf.get(term);
            for (int i = 0; i < postings.length; i += 2) {
                int doc = postings[i];
                int tf = postings[i + 1];
                double denom = tf + K1 * (1 - B + B * idx.dl[doc] / idx.avgdl);
                scores.merge(doc, idf * tf * (K1 + 1) / denom, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topN)
                .map(e -> idx.ids[e.getKey()])
                .toList();
    }

    /** 取某条 chunk 的正文（RRF 融合后，关键词这一路召回的条目要拿正文拼上下文） */
    public String textOf(String id) {
        Index idx = index;
        return idx == null ? null : idx.textById.get(id);
    }

    // ==================== 建索引 ====================

    @EventListener(ApplicationReadyEvent.class)
    public void buildOnStartup() {
        if (!enabled) {
            log.info("BM25 索引已禁用（rag.chat.bm25.enabled=false），检索退化为纯向量");
            return;
        }
        // 单独线程：建索引约 12 秒，不阻塞启动
        Thread t = new Thread(this::rebuild, "bm25-index-builder");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 从 Redis 全量重建索引。摄入新文档后需要调用，否则新 chunk 只能被向量检索到。
     */
    public synchronized void rebuild() {
        long t0 = System.currentTimeMillis();
        try {
            List<String> keys = scanAllKeys();
            if (keys.isEmpty()) {
                log.warn("BM25 索引：库里没有 {}* 键，跳过", keyPrefix);
                return;
            }
            List<String> texts = mgetContent(keys);

            int n = keys.size();
            String[] ids = new String[n];
            String[] bodies = new String[n];
            Map<String, String> textById = new HashMap<>(n * 2);
            int[] dl = new int[n];
            Map<String, int[]> postings = new HashMap<>(1 << 15);
            Map<String, Integer> df = new HashMap<>(1 << 15);
            long totalLen = 0;

            for (int i = 0; i < n; i++) {
                String id = keys.get(i).substring(keyPrefix.length());
                String text = texts.get(i) == null ? "" : texts.get(i);
                ids[i] = id;
                bodies[i] = text;
                textById.put(id, text);

                Map<String, Integer> tf = new HashMap<>();
                List<String> toks = tokenize(text);
                dl[i] = toks.size();
                totalLen += dl[i];
                for (String w : toks) {
                    tf.merge(w, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : tf.entrySet()) {
                    postings.merge(e.getKey(), new int[]{i, e.getValue()}, Bm25IndexService::concat);
                    df.merge(e.getKey(), 1, Integer::sum);
                }
                if ((i + 1) % 1000 == 0) {
                    log.info("BM25 索引：已分词 {}/{}", i + 1, n);
                }
            }

            double avgdl = n == 0 ? 0 : (double) totalLen / n;
            Map<String, Double> idf = new HashMap<>(df.size() * 2);
            for (Map.Entry<String, Integer> e : df.entrySet()) {
                int d = e.getValue();
                idf.put(e.getKey(), Math.log(1 + (n - d + 0.5) / (d + 0.5)));
            }

            this.index = new Index(ids, bodies, dl, avgdl, postings, idf, textById);
            log.info("BM25 索引就绪：{} 条 chunk，词表 {}，平均长度 {} token，耗时 {} ms",
                    n, postings.size(), Math.round(avgdl), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("BM25 索引构建失败，检索将退化为纯向量: {}", e.toString(), e);
        }
    }

    private static int[] concat(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private List<String> scanAllKeys() {
        List<String> keys = new ArrayList<>();
        redisTemplate.execute((RedisCallback<Void>) conn -> {
            ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + "*").count(1000).build();
            try (Cursor<byte[]> cursor = scan(conn, options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        Collections.sort(keys);
        return keys;
    }

    private Cursor<byte[]> scan(RedisConnection conn, ScanOptions options) {
        return conn.keyCommands().scan(options);
    }

    /**
     * 批量取正文。用 JSON.MGET 一次拿 500 个键，比逐个 JSON.GET 快一个数量级
     * （4,497 次往返 vs 9 次）。
     */
    private List<String> mgetContent(List<String> keys) {
        List<String> out = new ArrayList<>(keys.size());
        int batch = 500;
        for (int s = 0; s < keys.size(); s += batch) {
            List<String> part = keys.subList(s, Math.min(keys.size(), s + batch));
            Object raw = redisTemplate.execute((RedisCallback<Object>) conn -> {
                byte[][] args = new byte[part.size() + 1][];
                for (int i = 0; i < part.size(); i++) {
                    args[i] = part.get(i).getBytes(StandardCharsets.UTF_8);
                }
                args[part.size()] = "$.content".getBytes(StandardCharsets.UTF_8);
                return conn.execute("JSON.MGET", args);
            });
            if (!(raw instanceof List<?> list)) {
                throw new IllegalStateException("JSON.MGET 返回异常: " + raw);
            }
            for (Object item : list) {
                out.add(item == null ? null : unwrapJsonArray(new String((byte[]) item, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** JSON.MGET 单路径返回 {@code ["正文"]}，这里取出正文本身（含反转义） */
    private String unwrapJsonArray(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isArray() && !node.isEmpty() ? node.get(0).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 分词 ====================

    /**
     * 与 {@code 评测/测BM25.py} 的 tokenize() 逐条对齐：
     * 小写 → jieba 切词 → 过滤停用词/纯符号 → 追加英文数字串。
     *
     * <p>两边不一致的话，Java 版复现不出离线测出来的名次，那 26/30 就没有参考价值了。
     */
    List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(Math.max(16, lower.length() / 2));
        for (String w : segmenter.sentenceProcess(lower)) {
            String t = w.strip();
            if (t.isEmpty() || STOP.contains(t) || !HAS_WORD.matcher(t).find()) {
                continue;
            }
            out.add(t);
        }
        Matcher m = ALNUM.matcher(lower);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 不可变索引快照 */
    private record Index(String[] ids,
                         String[] texts,
                         int[] dl,
                         double avgdl,
                         Map<String, int[]> postings,
                         Map<String, Double> idf,
                         Map<String, String> textById) {
    }
}
