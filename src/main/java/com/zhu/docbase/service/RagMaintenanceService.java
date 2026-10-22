package com.zhu.docbase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库体检服务（2026-10-22 新增）。
 *
 * <p>用途：摄入是"一次写进去"的，时间长了库和磁盘会不一致。这里做三件只读检查 + 一个修复动作：
 * <ol>
 *   <li><b>孤儿</b>：库里还留着，但磁盘上文件已经没了（改名、移动、删除都会造成）</li>
 *   <li><b>残缺</b>：`#0` 在、但最后一个 chunk 不在（上次崩在文件中间 / 断电）</li>
 *   <li><b>重复</b>：不同路径但内容完全相同的文件（副本、多目录备份）</li>
 *   <li><b>修复</b>：把残缺文件删掉重新摄入</li>
 * </ol>
 *
 * <p>为什么不需要额外维护映射：每个文件的 `#0` chunk 里存了 `path` / `mtime` / `chunk_count`，
 * 扫一遍 `doc:*#0` 就能拿到"库里有哪 6.5 万个文件"，和磁盘比对即可。
 *
 * <p>性能：扫 6.5 万个 `#0` 用 SCAN + `JSON.MGET` 批量取（每批 500 个），
 * 大约 130 批 × 3 次往返，几秒到几十秒量级；比对磁盘是本地 IO。
 *
 * <p>所有检查方法**只读**，不删任何东西；只有 {@link #fixIncomplete} 会写。
 *
 * @author ：zhuzj（2026-10-22 新增）
 */
@Slf4j
@Service
public class RagMaintenanceService {

    /** 向量库 key 前缀，和 RedisVectorStore 保持一致 */
    @Value("${spring.ai.vectorstore.redis.prefix:doc:}")
    private String keyPrefix;

    /** 单次 JSON.MGET 的批大小，太大容易把返回包撑爆 */
    private static final int BATCH = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;

    private final DocumentIngestionService ingestionService;

    public RagMaintenanceService(StringRedisTemplate redisTemplate,
                                 DocumentIngestionService ingestionService) {
        this.redisTemplate = redisTemplate;
        this.ingestionService = ingestionService;
    }

    // ==================== 数据结构 ====================

    /**
     * 库里一个文件的索引信息（来自 `doc:<hash>#0` 的元数据）。
     *
     * @param hash       文件哈希
     * @param path       原始绝对路径
     * @param mtime      摄入时的文件修改时间
     * @param chunkCount 摄入时切出的 chunk 数
     */
    public record IndexedFile(String hash, String path, long mtime, int chunkCount) {
    }

    /**
     * 体检报告。所有列表只放前 {@link #SAMPLE} 条样例，避免日志爆炸。
     *
     * @param indexed        库里一共多少个文件
     * @param orphans        磁盘已不存在的（前 N 条样例）
     * @param incomplete     残缺的（前 N 条样例）
     * @param duplicatePairs 重复文件分组（前 N 组样例）
     */
    public record Report(int indexed,
                         List<String> orphans,
                         List<String> incomplete,
                         List<String> duplicatePairs,
                         int orphanCount,
                         int incompleteCount,
                         int duplicateGroupCount) {
    }

    /** 报告里每类最多列多少条 */
    private static final int SAMPLE = 20;

    // ==================== 扫描 ====================

    /**
     * 扫出库里所有已索引文件（读 `doc:*#0` 的元数据）。
     *
     * @return 文件列表；Redis 不可用时返回空列表
     */
    public List<IndexedFile> scanIndexed() {
        List<String> keys = scanZeroKeys();
        if (keys.isEmpty()) {
            return List.of();
        }

        List<String> paths = jsonMGet(keys, "$.path");
        List<String> mtimes = jsonMGet(keys, "$.mtime");
        List<String> counts = jsonMGet(keys, "$.chunk_count");

        List<IndexedFile> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String path = firstValue(paths, i);
            if (path == null) {
                continue;   // 元数据缺失（老数据），跳过
            }
            // key 形如 doc:<hash>#0，把 hash 抠出来
            String key = keys.get(i);
            String hash = key.substring(keyPrefix.length(), key.length() - 2);
            out.add(new IndexedFile(hash, path,
                    parseLong(firstValue(mtimes, i), 0),
                    parseInt(firstValue(counts, i), 0)));
        }
        log.info("扫描到 {} 个已索引文件（原始 key {} 个）", out.size(), keys.size());
        return out;
    }

    /** SCAN 出所有 `doc:*#0` 的键 */
    private List<String> scanZeroKeys() {
        List<String> keys = new ArrayList<>();
        try {
            redisTemplate.execute((RedisCallback<Void>) conn -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(keyPrefix + "*#0")
                        .count(1000)
                        .build();
                try (Cursor<byte[]> cursor = scan(conn, options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("SCAN 失败: {}", e.toString());
        }
        return keys;
    }

    /** 兼容不同 Spring Data Redis 版本的 scan 入口 */
    @SuppressWarnings("deprecation")
    private Cursor<byte[]> scan(RedisConnection conn, ScanOptions options) {
        return conn.keyCommands().scan(options);
    }

    /**
     * 批量 JSON.MGET：一次取多个键的同一个路径值。
     *
     * @param keys     键列表
     * @param pathExpr 例如 `$.path`
     * @return 与 keys 等长的列表，取不到的为 null
     */
    private List<String> jsonMGet(List<String> keys, String pathExpr) {
        List<String> result = new ArrayList<>(keys.size());
        for (int from = 0; from < keys.size(); from += BATCH) {
            int to = Math.min(from + BATCH, keys.size());
            List<String> batch = keys.subList(from, to);
            List<String> part = jsonMGetBatch(batch, pathExpr);
            result.addAll(part);
        }
        return result;
    }

    private List<String> jsonMGetBatch(List<String> keys, String pathExpr) {
        List<String> out = new ArrayList<>(keys.size());
        try {
            Object raw = redisTemplate.execute((RedisCallback<Object>) conn -> {
                byte[][] args = new byte[keys.size() + 1][];
                for (int i = 0; i < keys.size(); i++) {
                    args[i] = keys.get(i).getBytes(StandardCharsets.UTF_8);
                }
                args[keys.size()] = pathExpr.getBytes(StandardCharsets.UTF_8);
                return conn.execute("JSON.MGET", args);
            });
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    out.add(item == null ? null : new String((byte[]) item, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            log.warn("JSON.MGET 失败（{} 个键）: {}", keys.size(), e.toString());
        }
        // 补齐长度，避免下标错位
        while (out.size() < keys.size()) {
            out.add(null);
        }
        return out;
    }

    /** JSON.MGET 返回形如 `["D:\\a.docx"]`，取出第一个元素 */
    private String firstValue(List<String> raw, int idx) {
        if (idx >= raw.size() || raw.get(idx) == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw.get(idx));
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            JsonNode first = node.get(0);
            return first.isNull() ? null : first.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private long parseLong(String s, long dft) {
        try {
            return s == null ? dft : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private int parseInt(String s, int dft) {
        try {
            return s == null ? dft : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    // ==================== 三项检查 ====================

    /**
     * 孤儿：库里还留着，但磁盘上的文件已经不存在了（改名/移动/删除造成）。
     *
     * @param indexed 已索引文件列表
     * @return 孤儿文件（只返回前 20 条样例 + 总数）
     */
    public List<String> findOrphans(List<IndexedFile> indexed) {
        List<String> orphans = new ArrayList<>();
        for (IndexedFile f : indexed) {
            if (!Files.exists(Path.of(f.path()))) {
                orphans.add(f.path());
            }
        }
        return orphans;
    }

    /**
     * 残缺：`#0` 在，但最后一个 chunk（`#chunkCount-1`）不在。
     *
     * <p>典型成因：写入过程中进程被 kill / 断电，`catch` 里的清理没跑成。
     *
     * @param indexed 已索引文件列表
     * @return 残缺文件的路径
     */
    public List<String> findIncomplete(List<IndexedFile> indexed) {
        // 把所有"最后一个 chunk 的键"拼出来，批量问"这些键还在不在"
        List<IndexedFile> candidates = indexed.stream()
                .filter(f -> f.chunkCount() > 0)
                .toList();
        List<String> lastKeys = candidates.stream()
                .map(f -> keyPrefix + f.hash() + "#" + (f.chunkCount() - 1))
                .toList();

        // 【2026-10-22 改】这里改用批量 EXISTS（管道），不再查 metadata 字段。
        // 第一版查的是 `$.path`，但非 #0 的 chunk 没有 path 字段，JSON.GET 返回 [] 被当成"不存在"，
        // 于是每个文件都被误判成残缺。第二版改查 `$.source` 能跑通，但本质还是"靠某个字段间接判断存在性"。
        // 现在直接用 EXISTS —— 就是 Map.containsKey 的语义，返回布尔，跟 metadata 有没有字段无关。
        List<Boolean> exists = existsBatch(lastKeys);

        List<String> incomplete = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (i >= exists.size() || !exists.get(i)) {
                incomplete.add(candidates.get(i).path());
            }
        }
        return incomplete;
    }

    /**
     * 批量判断键是否存在（等价于对每个键调一次 `EXISTS`，但只花一次往返）。
     *
     * <p>为什么不用 `EXISTS k1 k2 ...`：Redis 的 EXISTS 支持多键，但**只返回一个总数**，
     * 没法知道"具体哪个不存在"。所以这里用管道（pipeline）把 N 条 EXISTS 一次性发出去，
     * 逐条拿回布尔值——既有批量性能，又有逐键结果。
     *
     * <p>为什么不用 `JSON.MGET`：那要依赖某个 metadata 字段存在（见上面 findIncomplete 的注释），
     * 而 EXISTS 只关心"键在不在"，与 chunk 里存了什么无关，语义更准。
     *
     * @param keys 键列表
     * @return 与 keys 等长的布尔列表，true 表示存在
     */
    private List<Boolean> existsBatch(List<String> keys) {
        List<Boolean> out = new ArrayList<>(keys.size());
        for (int from = 0; from < keys.size(); from += BATCH) {
            int to = Math.min(from + BATCH, keys.size());
            List<String> batch = keys.subList(from, to);
            try {
                List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                    for (String k : batch) {
                        conn.keyCommands().exists(k.getBytes(StandardCharsets.UTF_8));
                    }
                    // 管道模式下回调必须返回 null，结果由 executePipelined 统一收集
                    return null;
                });
                if (log.isDebugEnabled() && from == 0) {
                    log.debug("EXISTS 管道返回样例: {}", results.stream().limit(3)
                            .map(r -> r == null ? "null" : r.getClass().getSimpleName() + ":" + r).toList());
                }
                for (Object r : results) {
                    out.add(isTruthy(r));
                }
            } catch (Exception e) {
                log.warn("批量 EXISTS 失败（{} 个键）: {}", batch.size(), e.toString());
            }
            // 出错时补齐长度，避免下标错位（缺的按"不存在"处理，宁可多报残缺也别漏）
            while (out.size() < to) {
                out.add(false);
            }
        }
        return out;
    }

    /**
     * 重复：不同路径但内容完全相同的文件。
     *
     * <p>先按文件大小分组，只有同组大于 1 个的才算内容哈希 —— 避免把 6.5 万个文件全读一遍。
     *
     * @param indexed 已索引文件列表
     * @return 每组重复文件（组内路径用 " | " 连接）
     */
    public List<String> findDuplicates(List<IndexedFile> indexed) {
        // 1) 按大小分组（磁盘上存在的才算）
        Map<Long, List<IndexedFile>> bySize = new HashMap<>();
        for (IndexedFile f : indexed) {
            Path p = Path.of(f.path());
            if (!Files.exists(p)) {
                continue;
            }
            try {
                bySize.computeIfAbsent(Files.size(p), k -> new ArrayList<>()).add(f);
            } catch (Exception ignored) {
                // 读不到大小就跳过
            }
        }

        // 2) 同大小的再按内容 SHA-256 分组
        List<String> groups = new ArrayList<>();
        for (List<IndexedFile> sameSize : bySize.values()) {
            if (sameSize.size() < 2) {
                continue;
            }
            Map<String, List<String>> byHash = new LinkedHashMap<>();
            for (IndexedFile f : sameSize) {
                String contentHash = sha256OfFile(Path.of(f.path()));
                if (contentHash == null) {
                    continue;
                }
                byHash.computeIfAbsent(contentHash, k -> new ArrayList<>()).add(f.path());
            }
            for (List<String> same : byHash.values()) {
                if (same.size() > 1) {
                    groups.add(String.join(" | ", same));
                }
            }
        }
        return groups;
    }

    /** 判断 EXISTS 的返回值是否为"存在"。不同版本的 Spring Data Redis 可能返回 Long / Boolean / byte[] */
    private static boolean isTruthy(Object r) {
        if (r == null) {
            return false;
        }
        if (r instanceof Boolean b) {
            return b;
        }
        if (r instanceof Number n) {
            return n.longValue() > 0;
        }
        if (r instanceof byte[] b) {
            return b.length > 0 && b[0] != '0';
        }
        return false;
    }

    /** 算文件的 SHA-256，读不到返回 null */
    private String sha256OfFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 修复 ====================

    /**
     * 修复残缺文件：删掉残留的 chunk，重新摄入。
     *
     * <p>只处理**磁盘上还存在**的文件（不存在的属于孤儿，删了就行，不该重摄）。
     *
     * @param indexed 已索引文件列表
     * @param limit   单次最多修几个（防止一次跑几个小时），<=0 表示不限制
     * @return 实际修复成功的个数
     */
    public int fixIncomplete(List<IndexedFile> indexed, int limit) {
        List<String> incomplete = findIncomplete(indexed);
        if (incomplete.isEmpty()) {
            log.info("没有发现残缺文件");
            return 0;
        }
        int max = (limit > 0 && incomplete.size() > limit) ? limit : incomplete.size();
        log.info("发现 {} 个残缺文件，本次修复 {} 个", incomplete.size(), max);

        int fixed = 0;
        for (int i = 0; i < max; i++) {
            String path = incomplete.get(i);
            if (!Files.exists(Path.of(path))) {
                log.warn("残缺文件已不在磁盘上，跳过（属于孤儿）: {}", path);
                continue;
            }
            try {
                DocumentIngestionService.IngestResult r = ingestionService.ingestFile(path, true);
                if (r.status() == DocumentIngestionService.Status.OK) {
                    fixed++;
                    log.info("已修复: {}（{} 个片段）", path, r.chunks());
                } else {
                    log.warn("修复未成功: {} | 状态={}", path, r.status());
                }
            } catch (Throwable t) {
                log.error("修复失败: {} | {}", path, t.toString());
            }
        }
        return fixed;
    }

    /**
     * 跑一遍完整体检，返回报告（只读）。
     *
     * @param withDuplicates 是否检查重复文件（要读文件内容，比较慢）
     * @return 体检报告
     */
    public Report check(boolean withDuplicates) {
        long t0 = System.currentTimeMillis();
        List<IndexedFile> indexed = scanIndexed();

        List<String> orphans = findOrphans(indexed);
        List<String> incomplete = findIncomplete(indexed);
        List<String> duplicates = withDuplicates ? findDuplicates(indexed) : List.of();

        Report report = new Report(
                indexed.size(),
                sample(orphans),
                sample(incomplete),
                sample(duplicates),
                orphans.size(),
                incomplete.size(),
                duplicates.size());

        log.info("=== 知识库体检完成（{} ms）===", System.currentTimeMillis() - t0);
        log.info("已索引文件: {} 个 ｜ 孤儿: {} 个 ｜ 残缺: {} 个 ｜ 重复组: {} 组",
                report.indexed(), report.orphanCount(), report.incompleteCount(), report.duplicateGroupCount());
        report.orphans().forEach(p -> log.warn("  孤儿: {}", p));
        report.incomplete().forEach(p -> log.warn("  残缺: {}", p));
        report.duplicatePairs().forEach(p -> log.warn("  重复: {}", p));
        if (report.orphanCount() > SAMPLE || report.incompleteCount() > SAMPLE || report.duplicateGroupCount() > SAMPLE) {
            log.info("（日志只列前 {} 条，完整数量见上面的汇总行）", SAMPLE);
        }
        return report;
    }

    private List<String> sample(List<String> list) {
        return list.stream().limit(SAMPLE).collect(Collectors.toList());
    }

    /**
     * 只读诊断用：按 chunk 数倒序列出库里最大的 N 个文件，排查"内存被谁吃了"很方便。
     *
     * @param topN 取前几个
     * @return 每行形如 `chunk=190  path`
     */
    public List<String> topFiles(int topN) {
        return scanIndexed().stream()
                .sorted(Comparator.comparingInt(IndexedFile::chunkCount).reversed())
                .limit(Math.max(topN, 1))
                .map(f -> "chunk=" + f.chunkCount() + "  " + f.path())
                .toList();
    }

    /** 给 controller 用的简单格式化（避免日志和返回值重复拼） */
    public String format(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("已索引文件: ").append(r.indexed()).append(" 个\n");
        sb.append("孤儿(磁盘已无): ").append(r.orphanCount()).append(" 个\n");
        sb.append("残缺(最后 chunk 缺失): ").append(r.incompleteCount()).append(" 个\n");
        sb.append("重复文件组: ").append(r.duplicateGroupCount()).append(" 组\n");
        if (!r.orphans().isEmpty()) {
            sb.append("\n孤儿样例:\n");
            r.orphans().forEach(p -> sb.append("  ").append(p).append('\n'));
        }
        if (!r.incomplete().isEmpty()) {
            sb.append("\n残缺样例:\n");
            r.incomplete().forEach(p -> sb.append("  ").append(p).append('\n'));
        }
        if (!r.duplicatePairs().isEmpty()) {
            sb.append("\n重复样例:\n");
            r.duplicatePairs().forEach(p -> sb.append("  ").append(p).append('\n'));
        }
        return sb.toString();
    }

    /** 文件是否存在（供 controller 输出用） */
    public boolean exists(String path) {
        return path != null && new File(path).exists();
    }
}
