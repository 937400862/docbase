package com.zhu.docbase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhu.docbase.event.IngestCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 文档摄入服务：负责将本地工作资料读取、切分、向量化，并存入 Redis 向量库。
 * 这是 RAG 的"离线阶段"，通常在系统初始化时或资料更新时执行一次。
 *
 * 脱敏策略（按文件类型分流）：
 *   - 叙述性文档（docx、pdf、md、txt、log）：正则脱敏 → AI 脱敏
 *   - 结构化文本（代码、配置、SQL、Shell、HTML、CSS 等）：仅正则脱敏
 *
 * 这样做的原因：
 *   1. 3B 模型对代码文件容易"误解任务"，输出解释或改进建议而非脱敏结果
 *   2. AI 处理代码文件耗时极长（几十秒），且效果不如正则稳定
 *   3. 代码中的敏感信息（密码、密钥、IP）本身高度结构化，正则已足够覆盖
 *
 * @author ：zhuzj
 * @date ：2026/10/16 13:07
 *
 * <p><b>【2026-10-20 改动摘要】</b>（逐处都有 {@code 【改动】} 注释）：
 * <ol>
 *   <li>正文抽取从"一律 Tika"改为交给 {@link DocumentTextExtractor}：
 *       docx 走 POI 还原表格，纯文本自己按 UTF-8/GB18030 解码，其余仍走 Tika 大栈线程。</li>
 *   <li>新增目录（TOC）噪音清理：{@link DocumentTextExtractor#stripTocNoise(String)}。</li>
 *   <li>新增"解析出内容过短就跳过"的判断，并把"跳过"和"失败"分开计数。</li>
 *   <li>{@code ingestFile} 返回值从 {@code int} 改为 {@link IngestResult}，
 *       这样才能区分"摄入 0 片段是因为失败"还是"因为文件本来就没内容"。</li>
 *   <li>目录遍历过滤新增：{@code ~$} 开头的 Office 临时锁文件，以及 bcmap/rpm/pfx 等二进制扩展名。</li>
 * </ol>
 */
@Slf4j
@Service
public class DocumentIngestionService {

    @Value("${rag.sanitize.ai-enabled:true}")
    private boolean aiSanitizeEnabled;

    private final VectorStore vectorStore;

    private final SanitizeService sanitizeService;

    /**
     * 本次运行内的文件序号，用于日志里的短标识（#001、#002…）。
     *
     * <p>为什么不把完整路径放进 MDC：那样每行都要重复一遍 60~100 字符的路径，
     * 而消息体里往往还写了一次 —— 同一行出现两遍，日志被撑成 200 字符。
     * 改成序号后，完整路径只在"开始"行打印一次，
     * 用 {@code grep '#007'} 就能把该文件的全部日志串起来。
     */
    private final java.util.concurrent.atomic.AtomicInteger fileSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 【改动】新增：正文抽取器（docx 表格还原 / 文本编码兜底 / Tika 大栈）。
     * 原来这些逻辑都堆在本类里，现在拆出去，本类只负责"切分 + 脱敏 + 入库"。
     */
    private final DocumentTextExtractor textExtractor;

    /**
     * 【改动】新增：解析出来的正文短于这个字符数，就认为"没有可检索内容"，直接跳过。
     *
     * <p>为什么需要：实测有 1704 个文件（bcmap/rpm/pfx/cer/vsdx/~$ 锁文件等）Tika 只能抽出
     * 0~1 个字符，走完切分得到 0 片段，却被记成"摄入失败"，把失败统计彻底冲乱。
     * 20 个字符连一个完整句子都不够，没有任何检索价值。
     */
    private static final int MIN_TEXT_CHARS = 20;

    /**
     * 【改动 2026-10-22】id 规则：`<路径哈希>#<chunk 序号>`。
     *
     * <p>背景：10-20 那版用了一个 Redis SET 记录"已摄入文件"，等于多维护一套状态。
     * 现在改成**把文件标识编进 chunk 的 id 里**，Redis 键就变成 `doc:<哈希>#0`、`doc:<哈希>#1`……
     * 判断"这个文件摄入过没有"只需要查 `doc:<哈希>#0`，**不需要任何额外映射**。
     *
     * <p>哈希取 SHA-256 的前 32 位十六进制（128 bit）。截断后仍是均匀分布，
     * 6.5 万个文件的碰撞概率约 6×10⁻³⁰；真撞了还有 `~1`、`~2` 后缀的碰撞链兜着。
     */
    private static final int HASH_HEX_LEN = 32;

    /** 碰撞链最多试几个后缀（实际永远不会用到第 2 个） */
    private static final int MAX_COLLISION_PROBE = 8;

    /**
     * 【改动 2026-10-22】向量库的 key 前缀，必须和 `spring.ai.vectorstore.redis.prefix` 一致。
     * RedisVectorStore 内部就是 `prefix + document.getId()`，所以我们要自己拼 key 时也得用同一个前缀。
     */
    @Value("${spring.ai.vectorstore.redis.prefix:doc:}")
    private String keyPrefix;

    /**
     * 【改动 2026-10-20 晚】新增：Redis 内存软上限（MB），0 表示不限制。
     *
     * <p>为什么需要：Redis 所在虚拟机只有 1.78 GB 内存，且 `maxmemory=0`（不限制）、
     * 策略 `noeviction`。跑大批次时如果不管，会把整台机器吃爆，连 Redis 都连不上。
     * 设了上限后，每处理 N 个文件检查一次 `used_memory`，超了就停下并报错，
     * 保证"停下来"而不是"整机挂掉"。
     *
     * <p>实测单 chunk 约 26.6 KB，1.78 GB 的机器建议设 1000（即 1 GB）。
     */
    @Value("${rag.ingest.max-redis-memory-mb:0}")
    private long maxRedisMemoryMb;

    /**
     * 【改动 2026-10-20 晚】新增：目录摄入时"每处理多少个文件打一次进度"。
     *
     * <p>原来每个文件都打一行 INFO，6 万多个文件会产生几百 MB 日志（10-17 那次 2250 个文件
     * 就写了 30 MB）。改成默认每 50 个打一次，失败日志仍然逐条打。
     */
    @Value("${rag.ingest.progress-every:50}")
    private int progressEvery;

    /**
     * 【改动 2026-10-22】单次嵌入的批大小。
     *
     * <p>为什么要分批：`vectorStore.add()` 会把**整批 chunk 的文本一次性**发给 Ollama 做 embedding。
     * 2026-10-22 实测：一个切出 686 个 chunk 的 xlsx，一次性发过去要 17 分钟还没回来
     * （PDF 小批量实测是 0.18 秒/chunk，这里退化到 1 秒以上），而且期间日志一个字都不打，
     * 看起来像卡死，最后还把整个请求拖到超时。
     *
     * <p>改成每 64 个一批之后：每批十几秒就有进度日志，失败也只影响一批
     * （catch 里仍然按总数量整段删，不会留残缺）。
     */
    @Value("${rag.ingest.embed-batch-size:64}")
    private int embedBatchSize;

    /**
     * 【改动 2026-10-22】单个文件最多保留多少个 chunk，超出截断。
     *
     * <p>防的是"一个巨型表格吃掉十几分钟"：实测一张几百行的统计表切出 686 个 chunk，
     * 而 xlsx 抽样的平均值只有 22.8 个——这种离群值对问答的边际价值极低，却要占掉大量时间。
     *
     * <p>设 0 表示不限制。截断会打 WARN 日志，方便事后判断是不是该单独处理这个文件。
     */
    @Value("${rag.ingest.max-chunks-per-file:0}")
    private int maxChunksPerFile;

    /**
     * 【改动 2026-10-22】Redis 客户端。
     * 用途变了：不再维护"已摄入文件集合"，改成查 `doc:<哈希>#0` 的 metadata、按 id 删 chunk、查内存。
     */
    private final StringRedisTemplate redisTemplate;

    /** 【改动 2026-10-22】解析 JSON.GET / JSON.MGET 的返回，Jackson 由 spring-boot-starter-web 带进来 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 【改动 2026-10-23】摄入完成后发事件，让检索层（BM25 索引）自己决定要不要刷新 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 批量模式标记：目录摄入期间为 true，此时单文件摄入不发事件。
     *
     * <p>为什么用 ThreadLocal 而不是字段：{@code ingestDirectory} 在同一个线程里
     * 逐个调 {@code ingestFile}，用 ThreadLocal 不会影响并发的单文件摄入请求。
     */
    private static final ThreadLocal<Boolean> BATCH_MODE = ThreadLocal.withInitial(() -> false);

    public DocumentIngestionService(VectorStore vectorStore,
                                    SanitizeService sanitizeService,
                                    DocumentTextExtractor textExtractor,
                                    StringRedisTemplate redisTemplate,
                                    ApplicationEventPublisher eventPublisher) {
        this.vectorStore = vectorStore;
        this.sanitizeService = sanitizeService;
        this.textExtractor = textExtractor;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 【改动 2026-10-20 晚】新增：摄入结果的状态。
     *
     * <p>原来只有一个 {@code skipped} 布尔，现在多了"已摄入过"这种跳过，
     * 布尔区分不了，改成枚举。
     */
    public enum Status {
        /** 正常摄入 */
        OK,
        /** 解析出的内容过短（空文件、纯二进制），主动跳过 */
        SKIPPED_SHORT,
        /** 之前已经摄入过（断点续传/去重命中） */
        SKIPPED_DONE,
        /** 摄入失败（解析异常等） */
        FAILED
    }

    /**
     * 【改动 2026-10-20 晚】新增：单文件摄入结果。
     *
     * <p>原来是直接返回 {@code int}，用 0 同时表示"失败"和"没内容"，
     * 目录统计只能把两者都算成 failCount。现在分开：
     *
     * @param chunks  切分出的片段数（失败/跳过时为 0）
     * @param status  结果状态
     */
    public record IngestResult(int chunks, Status status) {

        /** 正常摄入 */
        public static IngestResult ok(int chunks) {
            return new IngestResult(chunks, Status.OK);
        }

        /** 内容过短，主动跳过 */
        public static IngestResult skipShort() {
            return new IngestResult(0, Status.SKIPPED_SHORT);
        }

        /** 之前已摄入过，跳过（断点续传/去重命中） */
        public static IngestResult skipDone() {
            return new IngestResult(0, Status.SKIPPED_DONE);
        }

        /** 摄入失败（解析异常等），与"跳过"区分 */
        public static IngestResult failed() {
            return new IngestResult(0, Status.FAILED);
        }

        /** 是否属于"跳过"（两种情况都算） */
        public boolean skipped() {
            return status == Status.SKIPPED_SHORT || status == Status.SKIPPED_DONE;
        }
    }

    // ==================== 断点续传 / 内存保护（2026-10-20 晚新增）====================

    // ==================== id 规则 / 断点续传 / 覆盖（2026-10-22 重写）====================

    /**
     * 【改动 2026-10-22】#0 chunk 里存的"文件指纹"。
     *
     * @param path       文件的绝对路径（用来判断哈希是否撞了别的文件）
     * @param mtime      文件最后修改时间（毫秒），变了就重新摄入
     * @param chunkCount 该文件切出了多少个 chunk（用来算最后一个 chunk 的序号）
     */
    public record FileStamp(String path, long mtime, int chunkCount) {
    }

    /**
     * 【改动 2026-10-22】解析结果：确定用哪个 hash，以及库里已有的指纹（没摄入过则为 null）。
     *
     * @param hash     最终使用的 id 前缀（可能是 `base~1` 这种带碰撞后缀的）
     * @param existing 库里 `doc:<hash>#0` 的指纹，null 表示这个文件还没摄入过
     */
    private record Resolved(String hash, FileStamp existing) {
    }

    /**
     * 【改动 2026-10-22】算文件路径的哈希（SHA-256 前 32 位十六进制）。
     *
     * <p>注意哈希的是**绝对路径**，不是文件名——语料里 `参数.txt`、`README.md` 这种同名文件
     * 在多个目录重复出现，用文件名做哈希必然冲突。
     *
     * @param file 文件
     * @return 32 位十六进制字符串
     */
    private String fileKey(File file) {
        return sha256Hex(file.getAbsolutePath()).substring(0, HASH_HEX_LEN);
    }

    /** 【改动 2026-10-22】完整 SHA-256（碰撞链用尽时的兜底，实际不会走到） */
    private String fullFileKey(File file) {
        return sha256Hex(file.getAbsolutePath());
    }

    /** 【改动 2026-10-22】SHA-256 → 小写十六进制 */
    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定带 SHA-256，理论上不会发生；兜底用 hashCode 保证不中断
            return String.format("%032x", text.hashCode());
        }
    }

    /**
     * 【改动 2026-10-22】拼 Redis 键。必须和 RedisVectorStore 内部一致：`prefix + id`。
     *
     * @param hash 文件哈希（含碰撞后缀）
     * @param idx  chunk 序号，从 0 开始
     * @return 例如 `doc:3f2a...c1#12`
     */
    private String chunkKey(String hash, int idx) {
        return keyPrefix + hash + "#" + idx;
    }

    /**
     * 【改动 2026-10-22】读 `doc:<hash>#0` 的文件指纹。
     *
     * <p>一次 `JSON.GET` 同时拿 path / mtime / chunk_count 三个字段，
     * 这样"存在性判断 + 变化检测 + 完整性判断"共用一次往返。
     *
     * @param hash 文件哈希
     * @return 指纹；键不存在或解析失败返回 null（按"未摄入"处理，宁可重复不可漏）
     */
    private FileStamp probe(String hash) {
        String key = chunkKey(hash, 0);
        try {
            Object raw = redisTemplate.execute((RedisCallback<Object>) conn -> conn.execute(
                    "JSON.GET",
                    key.getBytes(StandardCharsets.UTF_8),
                    "$.path".getBytes(StandardCharsets.UTF_8),
                    "$.mtime".getBytes(StandardCharsets.UTF_8),
                    "$.chunk_count".getBytes(StandardCharsets.UTF_8)));
            if (!(raw instanceof byte[] bytes) || bytes.length == 0) {
                return null;
            }
            JsonNode node = JSON.readTree(bytes);
            String path = node.path("$.path").path(0).asText(null);
            if (path == null) {
                return null;
            }
            long mtime = node.path("$.mtime").path(0).asLong(0);
            int count = node.path("$.chunk_count").path(0).asInt(0);
            return new FileStamp(path, mtime, count);
        } catch (Exception e) {
            log.warn("读取 chunk 指纹失败（按未摄入处理）: {} | {}", key, e.toString());
            return null;
        }
    }

    /**
     * 【改动 2026-10-22】确定这个文件该用哪个 hash —— 处理哈希碰撞。
     *
     * <p>思路和 HashMap 的链表一样：先试 `base`，如果 `base#0` 已经属于**另一个路径**，
     * 就依次试 `base~1`、`base~2`……直到找到空位或属于自己的位置。
     *
     * <p>碰撞概率是 6×10⁻³⁰（128 bit），实际永远不会进这个循环；
     * 但一旦真撞了，也不会互相覆盖，属于"零成本保险"。
     *
     * @param file 文件
     * @return 解析结果
     */
    private Resolved resolve(File file) {
        String absPath = file.getAbsolutePath();
        String base = fileKey(file);
        String candidate = base;

        for (int i = 1; i <= MAX_COLLISION_PROBE; i++) {
            FileStamp st = probe(candidate);
            if (st == null || absPath.equals(st.path())) {
                return new Resolved(candidate, st);
            }
            log.warn("哈希碰撞：{} 已被 {} 占用，改用后缀 ~{}", candidate, st.path(), i);
            candidate = base + "~" + i;
        }

        // 8 个后缀都被占（不可能发生）：退回不截断的完整 SHA-256
        String full = fullFileKey(file);
        log.warn("碰撞链已用尽，退回完整 SHA-256: {}", full);
        return new Resolved(full, probe(full));
    }

    /**
     * 【改动 2026-10-22】判断某个 chunk 是否在库里。
     *
     * <p>用于完整性检查：`#0` 存在但最后一个 chunk 不在 → 上次崩在文件中间（或断电），
     * 属于残缺文件，要删掉重摄。
     *
     * @param hash 文件哈希
     * @param idx  chunk 序号
     * @return true 表示存在
     */
    private boolean chunkExists(String hash, int idx) {
        if (idx < 0) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(chunkKey(hash, idx)));
        } catch (Exception e) {
            log.warn("查询 chunk 是否存在失败，按不存在处理: {}", e.toString());
            return false;
        }
    }

    /**
     * 【改动 2026-10-22】删掉某个文件的全部 chunk（`0 ~ count-1`）。
     *
     * <p>为什么整段删而不是"删到失败的那条为止"：
     * `vectorStore.add()` 内部是一次性管道发 N 条命令，抛异常时只知道整批失败、不知道挂在哪条；
     * 而且不管挂在哪条，清理动作都是删全段、成本完全一样，记序号没有收益。
     *
     * @param hash  文件哈希
     * @param count 要删的个数（用旧指纹里的 chunk_count，一定覆盖实际存在的）
     */
    private void deleteChunks(String hash, int count) {
        if (count <= 0) {
            return;
        }
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(chunkKey(hash, i));
        }
        try {
            Long deleted = redisTemplate.delete(keys);
            log.info("删除旧 chunk: hash={}, 计划 {} 个, 实际删除 {} 个", hash, count, deleted);
        } catch (Exception e) {
            log.warn("删除旧 chunk 失败（继续摄入，可能产生重复）: hash={}, {}", hash, e.toString());
        }
    }

    /**
     * 【改动 2026-10-22】给切分后的 chunk 赋 id，并写入元数据。
     *
     * <p>id = `<hash>#<序号>`，RedisVectorStore 会自动加上 `doc:` 前缀，
     * 所以最终键是 `doc:<hash>#0`、`doc:<hash>#1`……
     *
     * <p>元数据分两级：
     * <ul>
     *   <li><b>每个 chunk</b> 都带 `source`（文件名）——检索命中的通常不是 #0，
     *       没有 source 的话回答里引不出"这条来自哪个文件"；</li>
     *   <li><b>只有 #0</b> 带 `path` / `mtime` / `chunk_count`——这三个是给断点续传和体检用的，
     *       每个 chunk 都存会多占 40 多 MB。</li>
     * </ul>
     *
     * @param chunks 切分 + 脱敏后的 chunk 列表（顺序即序号）
     * @param hash   文件哈希
     * @param file   源文件
     * @return 带 id 的新列表
     */
    private List<Document> assignIds(List<Document> chunks, String hash, File file) {
        int total = chunks.size();
        List<Document> out = new ArrayList<>(total);
        // 【2026-10-22 新增】给每个 chunk 的正文加一行来源前缀，让文件名进入向量索引。
        //
        // 起因（实测）：有些文件的关键信息只写在文件名里 —— 比如版本号、日期 ——
        // 正文几十个 chunk 里一次都没提到（grep 命中 0 次），纯正文向量检索永远召不回。
        // 纯正文向量检索永远召不回它 —— 这不是检索算法的问题，是索引里根本没这个信息。
        // 加前缀后，文件名同时进入两个地方：向量（text 被 embedding）和 prompt（模型能看到来源）。
        //
        // ⚠️ 必须加在切分之后：
        //   加在切分前，这 30 个 token 会占掉 600 token 的预算，chunk 会变多、边界会变。
        //   加在这里，chunk 数量和边界完全不变，diff 干净。
        //
        // 内存成本几乎为零：每个 chunk 在 Redis 里占 26.3 KB，其中正文只占约 0.9 KB，
        // 剩下是 1024 维向量的 JSON 文本。内存由 chunk 数量决定，不由正文长度决定，
        // 加 30 字符只让单键从 26,298 变成约 26,328 字节（+0.1%）。
        // 真正的成本在 prompt：每个检索片段多约 30 token，topK=5 多 150 token。
        String prefix = "【来源：" + file.getName() + "】\n";
        for (int i = 0; i < total; i++) {
            Document d = chunks.get(i);
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", file.getName());
            if (i == 0) {
                meta.put("path", file.getAbsolutePath());
                meta.put("mtime", file.lastModified());
                meta.put("chunk_count", total);
            }
            out.add(new Document(hash + "#" + i, prefix + d.getText(), meta));
        }
        return out;
    }

    /**
     * 【改动 2026-10-20 晚】读 Redis 当前已用内存（MB）。
     *
     * @return 已用内存 MB；取不到返回 -1
     */
    private long redisUsedMemoryMb() {
        try {
            Properties p = redisTemplate.execute(
                    (RedisCallback<Properties>) c -> c.serverCommands().info("memory"));
            if (p == null) {
                return -1;
            }
            String v = p.getProperty("used_memory");
            return v == null ? -1 : Long.parseLong(v) / 1024 / 1024;
        } catch (Exception e) {
            log.warn("读取 Redis 内存失败: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 摄入单个文件：读取 → 正则脱敏 → 切分 → AI 脱敏 → 向量化 → 存入 Redis
     *
     * 注意脱敏顺序的设计：
     *   1. 先做正则脱敏：快速过滤掉明显敏感信息（密钥、密码、IP 等），避免这些内容进入 LLM
     *   2. 再切分成小块：切分后的 chunk 长度可控，不会超过 LLM 的上下文窗口
     *   3. 最后对每个 chunk 做 AI 脱敏：粒度细，效果好，且每个 chunk 独立处理
     *
     * @param filePath 文件的绝对路径
     * @return 摄入结果（片段数 + 状态）
     */
    public IngestResult ingestFile(String filePath) {
        return ingestFile(filePath, false);
    }

    /**
     * 【改动 2026-10-22】摄入单个文件（带覆盖开关）。
     *
     * <p>幂等规则（不再依赖任何额外映射，全靠 chunk id 自带的文件指纹）：
     * <ol>
     *   <li>读 `doc:<hash>#0` 的指纹（path / mtime / chunk_count），一次 JSON.GET</li>
     *   <li>path 不是自己 → 哈希碰撞，走碰撞链找自己的位置</li>
     *   <li>mtime 相同 且 最后一个 chunk 存在 → 文件完整且没变，直接跳过</li>
     *   <li>mtime 变了，或者最后一个 chunk 不存在（残缺）→ 删掉整段旧 chunk，重新摄入</li>
     *   <li>{@code overwrite=true} 时无条件删掉重摄</li>
     * </ol>
     *
     * @param filePath  文件绝对路径
     * @param overwrite true 表示强制覆盖（不看 mtime，直接删了重摄）
     * @return 摄入结果
     */
    public IngestResult ingestFile(String filePath, boolean overwrite) {
        // MDC 只放短序号，不放完整路径 —— 完整路径在下面"开始"行打一次即可。
        // 先存旧值：目录摄入时外层标签是 DIR，处理完必须恢复回去，不能直接清空。
        String prevTag = MDC.get("f");
        String tag = String.format("#%03d", fileSeq.incrementAndGet());
        MDC.put("f", tag);
        try {
            File file = new File(filePath);
            String fileName = file.getName();
            log.info("开始: {}", filePath);
            int[] params = getSplitParams(fileName);
            int chunkSize = params[0];
            int minChunkSizeChars = params[1];

            boolean useAi = aiSanitizeEnabled && shouldUseAiSanitize(fileName);

            // 0. 【改动 2026-10-22】确定 id 前缀（含碰撞处理），并拿到库里已有的指纹。
            //    正常情况只花 1 次 JSON.GET；碰撞时才多探几次（实际不会发生）。
            Resolved resolved = resolve(file);
            String hash = resolved.hash();
            FileStamp stamp = resolved.existing();

            // 1. 【改动 2026-10-22】幂等判断：跳过 / 需要重摄
            //    ⚠️ 这里只做"判断"，不删数据。真正的删除放在写入之前（第 9 步），
            //    否则一旦后面解析失败（文件损坏、编码异常），旧数据已经删了、新的又没写进去，等于把好好的数据搞丢。
            boolean needReplace = false;
            if (stamp != null) {
                if (!overwrite && stamp.mtime() == file.lastModified()) {
                    // 完整性校验：只查最后一个 chunk 在不在，不用先切分就知道文不完整
                    if (chunkExists(hash, stamp.chunkCount() - 1)) {
                        log.debug("跳过: 已摄入且未改动");
                        return IngestResult.skipDone();
                    }
                    log.warn("发现残缺文件（声明 {} 个 chunk，最后一个不存在），删除后重新摄入: {}",
                            stamp.chunkCount(), filePath);
                } else if (!overwrite) {
                    log.info("文件已改动（mtime {} → {}），删除旧 chunk 重新摄入: {}",
                            stamp.mtime(), file.lastModified(), filePath);
                } else {
                    log.info("强制覆盖摄入（忽略 mtime，直接删了重摄）");
                }
                needReplace = true;
            }

            // 2. 【改动】读取文档
            //    原来这里是 new TikaDocumentReader(...).get() 一律走 Tika，现在交给抽取器分流：
            //      - .docx            → POI 遍历 body，表格还原成 markdown 行（Tika 会把表格拍成每格一行）
            //      - .java/.txt/.md…  → 自己按 UTF-8 → GB18030 解码（Tika 对 GBK 中文会返回 0 字符）
            //      - .doc/.pdf/.xls…  → 仍然走 Tika，且跑在 8MB 栈的独立线程里
            //                            （2026-10-19 实测：POI/HWPF 解析老式 .doc 时正则递归过深，
            //                             默认 1MB 栈直接 StackOverflowError，4MB 通过、8MB 稳）
            // 抽取结果是"若干段"而不是一整串：表格的列取值摘要要单独成块（见步骤 6）。
            // 其余类型永远只有一段，行为和以前一样。
            List<String> parts = textExtractor.extractParts(new File(filePath));

            // 3. 【改动】清掉目录（TOC）噪音
            //    Tika 会把渲染后的目录条目（"1.1. 标题\t3"）当正文输出。目录行包含全文档所有章节标题，
            //    向量化后对"这文档讲了什么"这类查询相似度极高，是"高相似度、零信息量"的强干扰。
            //    只在"文档类"上做，代码/配置文件里不会有这种形态，没必要多跑一遍正则。
            List<String> cleaned = new ArrayList<>(parts.size());
            for (String part : parts) {
                String text = isDocumentType(fileName) ? DocumentTextExtractor.stripTocNoise(part) : part;
                if (text != null && !text.isBlank()) {
                    cleaned.add(text);
                }
            }

            // 4. 【改动】内容过短直接跳过（不算失败）
            //    实测 bcmap/rpm/~$ 锁文件等 1704 个文件只能抽出 0~1 个字符，
            //    以前会走完切分得到 0 片段并被记成"摄入失败"。
            int totalChars = cleaned.stream().mapToInt(String::length).sum();
            if (totalChars < MIN_TEXT_CHARS) {
                log.warn("跳过: 解析出内容过短（{} 字符）", totalChars);
                return IngestResult.skipShort();
            }

            // 5. 组装成 Spring AI 的 Document
            //    metadata 里的 source 保持与原来 Tika 的行为一致（文件名），便于后续排查来源
            List<Document> documents = new ArrayList<>(cleaned.size());
            for (String text : cleaned) {
                documents.add(new Document(text, Map.of("source", fileName)));
            }

            // 6. 文本切分：按 Token 数切分，默认 chunk 800 token，overlap 100 token
            // overlap 确保句子不会被粗暴切断[reference:6]
            // 为什么要切分？
            //   1. LLM 的上下文窗口有限（当前问答模型 qwen2.5-3b-lowvram，Modelfile 里 num_ctx 4096），
            //      不能把整本书塞进去
            //   2. 向量检索的粒度越细，越能精准命中相关内容
            //   3. 太长的文本块会稀释语义，导致检索相关性下降
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    // withChunkSize(800)：每个文本块的目标 Token 数。
                    //   - Token 是模型理解文本的最小单位，不是字符数。
                    //   - 中文场景下，1 个 token ≈ 1-2 个汉字，800 token ≈ 800-1600 个汉字。
                    //   - 调优建议：
                    //       技术文档/代码 → 500-800（粒度细，检索精准）
                    //       长篇叙述文档 → 1000-1500（保留更多上下文）
                    //   - 调太小：检索精准但上下文碎片化，LLM 可能"断章取义"
                    //   - 调太大：上下文完整但检索模糊，容易命中无关内容
                    .withChunkSize(chunkSize)
                    // withMinChunkSizeChars(350)：文本块的最小字符数。
                    //   - 如果一个文本块切分后字符数少于 350，它会被合并到相邻块或丢弃。
                    //   - 作用：避免产生"太短而无意义"的碎片块（比如单独一行标题、页码）。
                    //   - 调优建议：对于中文文档，350 是较合理的默认值；对于代码文件可以调低到 100-200。
                    .withMinChunkSizeChars(minChunkSizeChars)
                    // 切点字符表：默认 `.` `?` `!` `\n` 里取位置最靠后的那个。
                    // 表格正文里全是 IP / 金额 / 日期 / 版本号，最后一个 `.` 常落在数字中间，
                    // 一条记录就被腰斩，续块开头没有键名（实测 3,591 块里 1,724 块如此）。
                    // 所以表格只认换行：一行就是一条记录，切在行尾才是安全边界。
                    .withPunctuationMarks(punctuationFor(fileName))
                    // 以下为可选参数，默认值已能满足大多数场景，按需启用：

                    // .withMinChunkLengthToEmbed(5)：短于该长度（字符数）的块直接丢弃。
                    //   默认 5，用于过滤掉"的"、"了"这类无意义的超短片段。
                    // .withMaxNumChunks(10000)：从单个文档中最多生成多少个块。
                    //   防止一份超长文档切出几十万个块，把向量库撑爆。
                    // .withKeepSeparator(true)：是否在切分时保留分隔符（如换行、句号）。
                    //   默认 true，保留分隔符有助于 LLM 理解文本结构。
                    .build();
            // apply() 是 Transformer 接口的核心方法，对传入的文档列表执行切分。
            // 返回的 List<Document> 中，每个元素都是原始文档的一个片段。
            // 逐段切：段与段之间不共享窗口，所以列取值摘要不会被并进前一块
            List<Document> splitDocuments = new ArrayList<>();
            for (Document doc : documents) {
                splitDocuments.addAll(splitter.apply(List.of(doc)));
            }

            // 7. 正则脱敏（针对每个切分后的小块执行）
            List<Document> regexSanitized = splitDocuments.stream()
                    .map(doc -> new Document(SanitizeService.sanitize(doc.getText()), doc.getMetadata()))
                    .toList();

            // 8. AI 脱敏（仅对叙述性文档启用）：对每个 chunk 单独调用 LLM，失败时自动降级为正则结果
            List<Document> sanitized;
            if (useAi) {
                sanitized = new ArrayList<>();
                for (Document doc : regexSanitized) {
                    // 如果配置rag.sanitize.ai-enabled=false，这个方法不会触发ai脱敏，而是直接返回原文
                    String aiSanitized = sanitizeService.aiSanitize(doc.getText());
                    sanitized.add(new Document(aiSanitized, doc.getMetadata()));
                }
            } else {
                sanitized = regexSanitized;
            }

            // 9. 【改动 2026-10-22】给每个 chunk 赋 id 和元数据。
            //    必须放在所有文本变换（切分、正则脱敏、AI 脱敏）之后——
            //    前面每一步都会 new Document(...) 生成新的 UUID，id 会被冲掉。
            //    赋完之后 id 才是 `<hash>#<序号>`，RedisVectorStore 会用 `doc:<hash>#<序号>` 作键。
            List<Document> finalDocuments = assignIds(sanitized, hash, file);

            // 9.1 【改动 2026-10-22】单文件 chunk 上限：超过就截断。
            //     防的是"一个 686 chunk 的巨型 xlsx 吃掉十几分钟"，那种离群值对问答价值极低。
            //     截断必须在 assignIds 之后做的话 chunk_count 会不一致，所以这里先截断再重建 id。
            if (maxChunksPerFile > 0 && finalDocuments.size() > maxChunksPerFile) {
                log.warn("切分出 {} 个片段，超过单文件上限 {}，截断后只保留前 {} 个（文件: {}）",
                        finalDocuments.size(), maxChunksPerFile, maxChunksPerFile, filePath);
                finalDocuments = assignIds(sanitized.subList(0, maxChunksPerFile), hash, file);
            }

            // 9.2 【改动 2026-10-22】切分结果为空（理论上不会，前面已挡过 <20 字符）：
            //     直接返回，别把旧数据删了又什么都不写。
            if (finalDocuments.isEmpty()) {
                log.warn("切分后没有任何片段，保留库里原有数据");
                return IngestResult.skipShort();
            }

            // 9.2 【改动 2026-10-22】确认要重摄才删旧数据。
            //     放在这里（解析+切分+脱敏都成功之后）而不是最前面，
            //     是为了避免"旧数据删了、新数据因为解析失败没写进去"。
            if (needReplace && stamp != null) {
                deleteChunks(hash, stamp.chunkCount());
            }

            // 10. 写入向量库（自动完成向量化 + 存储）
            // vectorStore.add() 内部做了两件事：
            //   1. 调用 Embedding 模型，把每个片段的文本转为高维向量（当前是 bge-m3，1024 维）
            //   2. 将"向量 + 原始文本 + 元数据"一起写入 Redis Stack
            // 这一步是自动的，你不需要手动调用 EmbeddingModel。
            //
            // 【改动 2026-10-22】分批写入：一次最多 embedBatchSize 个。
            // 原来是一次性把全部 chunk 发过去，实测大文件（686 个 chunk）会让吞吐从 0.18 秒/chunk
            // 退化到 1 秒以上，而且期间没有任何日志，看起来像卡死。
            //
            // 【改动 2026-10-22】失败时整段删掉，避免留下半个文件。
            // 注意：add() 是一次性管道写一批，抛异常时不知道挂在哪条，
            // 所以直接按本次的 chunk 总数删 0~N-1（前面成功的批次也一起删掉），多删不存在的 id 没有代价。
            int totalChunks = finalDocuments.size();
            int batchSize = embedBatchSize > 0 ? embedBatchSize : totalChunks;
            try {
                for (int from = 0; from < totalChunks; from += batchSize) {
                    int to = Math.min(from + batchSize, totalChunks);
                    vectorStore.add(finalDocuments.subList(from, to));
                    if (totalChunks > batchSize) {
                        log.info("嵌入进度 [{}/{}]", to, totalChunks);
                    }
                }
            } catch (Throwable t) {
                log.error("写入向量库失败，清理本次已写入的 chunk: hash={}, 数量={}", hash, totalChunks);
                deleteChunks(hash, totalChunks);
                throw t;
            }

            log.info("完成: {} 个片段（hash={}）{}",
                    totalChunks, hash, useAi ? " 正则+AI脱敏" : " 仅正则脱敏");
            // 【2026-10-23】通知检索层刷新关键词索引。
            // 只在真的写入时发；"已摄入过而跳过""内容过短""失败"都不发。
            // 目录摄入期间（BATCH_MODE=true）不发，由 ingestDirectory 在整批结束时发一次，
            // 否则几百个文件会让检索层在摄入过程中反复重建。
            if (!BATCH_MODE.get()) {
                eventPublisher.publishEvent(new IngestCompletedEvent(totalChunks));
            }
            return IngestResult.ok(totalChunks);
        } catch (Throwable t) {
            // 必须是 Throwable 而不是 Exception：
            // StackOverflowError 属于 Error 分支，catch (Exception) 兜不住，
            // 会一路冒到 Spring MVC 变成 500，把整个批量任务打断。
            // 这里兜住 → 单个文件失败只算一次失败，后面的文件继续跑。
            // （副作用：OutOfMemoryError 也会被吞掉，若真遇到 OOM 需看日志确认）
            // 失败行带上文件名：错误很少见，长一点不影响可读性，
            // 但 grep "失败" 时能直接看出是哪个文件，不用再按序号反查。
            log.error("失败: {} | {}", new File(filePath).getName(), t.toString(), t);
            // 【改动】失败返回 failed()，和"内容过短跳过"区分开
            return IngestResult.failed();
        } finally {
            // 恢复而不是清空：目录摄入时外层是 DIR，直接 remove 会让后续目录级日志变成 []
            if (prevTag == null) {
                MDC.remove("f");
            } else {
                MDC.put("f", prevTag);
            }
        }
    }

    /**
     * 批量摄入整个目录下的所有文件。
     * 注意：当前实现只处理一级目录下的文件，不递归子目录。
     * 如果你的工作资料是嵌套目录结构，可以改用 Files.walk() 递归遍历。
     */
    public int ingestDirectory(String dirPath) {
        return ingestDirectory(dirPath, false);
    }

    /**
     * 【2026-10-22 新增】带 overwrite 的目录摄入。
     *
     * <p>为什么需要：改了入库逻辑（比如给 chunk 加来源前缀）之后，文件的 mtime 没变，
     * 默认的 skip 模式会把所有文件判成"已摄入且未改动"全部跳过 —— 看起来跑了，其实什么都没变。
     * 这时必须整目录 overwrite 重摄。
     *
     * @param overwrite true = 无视 mtime，逐文件删掉重摄
     */
    public int ingestDirectory(String dirPath, boolean overwrite) {
        Path rootPath = Paths.get(dirPath);
        int totalChunks = 0;
        int fileCount = 0;
        int aiFileCount = 0;
        int regexOnlyFileCount = 0;
        int failCount = 0;
        // 【改动】新增：内容过短被跳过的文件数，不再混进 failCount
        int skipCount = 0;
        // 【改动 2026-10-20 晚】新增：之前已摄入过（断点续传命中）的文件数
        int doneCount = 0;

        // 目录级日志用固定标签 DIR；路径由下面那行 === 开始递归摄入目录 === 打一次，不重复
        MDC.put("f", "DIR");

        // 进入批量模式：整批只在最后发一次事件，避免摄入过程中反复重建索引
        BATCH_MODE.set(true);

        try (Stream<Path> paths = Files.walk(rootPath)) {
            List<Path> fileList = paths
                    .filter(Files::isRegularFile)          // 只处理普通文件
                    // 【改动】原来的过滤逻辑是写在这里的一大坨 lambda，现在抽成 shouldIngest(Path)，
                    // 一是加注释后不至于挤成一团，二是可以单独测（见本轮验证）
                    .filter(DocumentIngestionService::shouldIngest)
                    .toList();

            int total = fileList.size();
            long startTime = System.currentTimeMillis();
            log.info("=== 开始递归摄入目录: {} ===", dirPath);
            log.info("共发现 {} 个文件", total);

            int processed = 0;
            boolean memoryAborted = false;
            for (Path path : fileList) {
                processed++;
                String fileName = path.getFileName().toString();
                int remaining = total - processed;

                // 【改动 2026-10-20 晚】进度日志改成节流：每 progressEvery 个文件打一行。
                // 原来每个文件一行 INFO，6 万多个文件会写几百 MB 日志。
                // 最后一个文件仍然打，方便确认"跑到了哪"。
                // 【2026-10-22】不再打印当前文件名：紧跟其后的 [#NNN] 开始: 行已经带了完整路径，
                // 这里只保留"进度 + 剩余"，避免同一信息出现两遍。
                if (processed % Math.max(progressEvery, 1) == 0 || processed == total) {
                    log.info("=== 进度 [{}/{}] | 剩余 {} 个文件 ===", processed, total, remaining);
                }

                // 【改动 2026-10-20 晚】内存软上限：每 progressEvery 个文件查一次 Redis 已用内存。
                // Redis 所在虚拟机只有 1.78 GB 且 maxmemory=0（不限制），
                // 不管的话会把整台机器吃爆，连 Redis 都连不上。超限就停，保证"停下来"而不是"整机挂掉"。
                if (maxRedisMemoryMb > 0 && processed % Math.max(progressEvery, 1) == 0) {
                    long usedMb = redisUsedMemoryMb();
                    if (usedMb >= 0) {
                        log.info("Redis 已用内存: {} MB（上限 {} MB）", usedMb, maxRedisMemoryMb);
                        if (usedMb >= maxRedisMemoryMb) {
                            log.error("=== 因 Redis 内存达到上限而停止 === 已用 {} MB ≥ 上限 {} MB，"
                                            + "已处理 {}/{} 个文件。请扩容或缩小摄入范围后，重跑本目录（已完成的文件会自动跳过）",
                                    usedMb, maxRedisMemoryMb, processed, total);
                            memoryAborted = true;
                            break;
                        }
                    }
                }

                try {
                    // 【2026-10-22 修正】这里原来只判断"文件类型是否适合走 AI 脱敏"，
                    // 没判断总开关 aiSanitizeEnabled，导致关掉 AI 时统计行仍显示
                    // "N 个走 正则+AI 脱敏"（实际一个都没走），日志会误导人。
                    boolean useAi = aiSanitizeEnabled && shouldUseAiSanitize(path.getFileName().toString());
                    // 【改动】ingestFile 现在返回 IngestResult（片段数 + 状态）
                    IngestResult result = ingestFile(path.toAbsolutePath().toString(), overwrite);
                    switch (result.status()) {
                        // 【改动 2026-10-20 晚】"已摄入过"单独计数，不算跳过也不算失败
                        case SKIPPED_DONE -> doneCount++;
                        case SKIPPED_SHORT -> skipCount++;
                        case FAILED -> failCount++;
                        case OK -> {
                            totalChunks += result.chunks();
                            fileCount++;
                            if (useAi) {
                                aiFileCount++;
                            } else {
                                regexOnlyFileCount++;
                            }
                        }
                    }
                } catch (Throwable t) {
                    // 同 ingestFile：这里也必须是 Throwable，
                    // 否则 StackOverflowError 会穿透目录循环，终止整批任务。
                    failCount++;
                    // 继续处理下一个文件，不中断整个流程
                    // 这里补一句带路径的错误：ingestFile 内部已经兜住 Throwable 并记过日志，
                    // 走到这里说明异常发生在 ingestFile 之外（内存检查等），路径需要单独给出。
                    log.error("目录循环异常: {} | {}", path, t.toString());
                }
            }
            long duration = System.currentTimeMillis() - startTime;

            log.info("=== 目录摄入完成 ===");
            // 【改动】统计行加上"跳过"和"发现文件数"，避免把跳过当成失败看
            // 【改动 2026-10-20 晚】再拆出"已摄入过"，并标出是否因内存上限提前停止
            log.info("发现: {} 个文件 ｜ 成功处理: {} 个（其中 {} 个走 正则+AI 脱敏，{} 个仅正则）"
                            + " ｜ 跳过(内容过短): {} 个 ｜ 已摄入过(去重): {} 个 ｜ 失败: {} 个"
                            + (memoryAborted ? " ｜ ⚠️ 因内存上限提前停止" : ""),
                    total, fileCount, aiFileCount, regexOnlyFileCount, skipCount, doneCount, failCount);
            // 【改动 2026-10-22】全部命中去重的提示。
            // 现在不再有独立的去重集合，全部靠 chunk id 自带指纹，所以只需要说明怎么重来。
            if (doneCount > 0 && fileCount == 0 && failCount == 0 && !memoryAborted) {
                log.warn("本批全部命中\"已摄入且未改动\"。若要强制重摄，"
                        + "请用 /api/ingest/file?mode=overwrite 逐个重摄，或 RedisTool.java dropall 清库后重来");
            }
            log.info("共生成: {} 个片段", totalChunks);
            log.info("总耗时: {} 秒，平均每文件 {} ms",
                    duration / 1000.0,
                    fileCount > 0 ? duration / fileCount : 0);
            log.info("Redis 当前已用内存: {} MB", redisUsedMemoryMb());
        } catch (IOException e) {
            log.error("遍历目录失败: {} | 原因: {}", dirPath, e.getMessage());
            return 0;
        } finally {
            // 退出批量模式：之后单文件摄入恢复"每次发事件"
            BATCH_MODE.remove();
        }
        // 整批只发一次事件。目录里 200 多个文件逐个发事件的话，
        // 检索层会被迫在摄入过程中反复重建索引（实测 9 个文件触发 3 轮）。
        if (totalChunks > 0) {
            eventPublisher.publishEvent(new IngestCompletedEvent(totalChunks));
        }
        return totalChunks;
    }

    /**
     * 【改动】新增：判断一个文件要不要摄入。
     *
     * <p>逻辑是从原来 ingestDirectory 里的内联 lambda 原样搬出来的，只是补了下面两组规则。
     * 抽成独立方法的目的：规则越加越多，内联 lambda 已经很难读，也没法单独测。
     *
     * <p>返回 false 的四种情况：
     * <ol>
     *   <li>隐藏文件（. 开头，如 .gitignore）</li>
     *   <li>位于 .git / node_modules / target / build 等目录下</li>
     *   <li>Office 临时锁文件（~$ 开头）或二进制/媒体扩展名</li>
     *   <li>大于 10MB</li>
     * </ol>
     */
    static boolean shouldIngest(Path p) {
        // 跳过隐藏文件（如 .gitignore、.DS_Store）
        if (p.getFileName().toString().startsWith(".")) {
            return false;
        }

        String path = p.toString().replace("\\", "/");
        // 跳过常见无需摄入的目录
        if (path.contains("/.git/") || path.contains("/node_modules/")
                || path.contains("/target/") || path.contains("/build/")
                || path.contains("/dist/") || path.contains("/.idea/")
                || path.contains("/.vscode/") || path.contains("/.svn/")) {
            return false;
        }

        String name = p.getFileName().toString().toLowerCase();

        // 【改动】新增：Office 临时锁文件。Word/Excel 打开文档时会生成 ~$xxx.docx，
        // 内容是锁信息，Tika 只能抽出 0 个字符。全库实测 50 个，纯属浪费解析时间。
        if (name.startsWith("~$")) {
            return false;
        }

        String[] skipExts = {".jar", ".war", ".class", ".exe", ".dll",
                ".png", ".jpg", ".jpeg", ".gif", ".mp4", ".mp3",
                ".zip", ".rar", ".7z", ".tar", ".gz",
                // 【改动】新增：实测 Tika 只能抽出 0~1 个字符的二进制格式，全库共 1704 个
                //   （bcmap 1176 / rpm 250 / pfx+cer 152 / vsdx 58 / rp 18）。
                //   其中 .bcmap 是 PDF 的字体映射文件，会被当 PDF 解析，最费时间。
                ".bcmap", ".rpm", ".pfx", ".cer", ".vsdx", ".rp"};
        for (String ext : skipExts) {
            if (name.endsWith(ext)) {
                return false;
            }
        }

        // 跳过超大文件
        try {
            return Files.size(p) <= 10 * 1024 * 1024;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 【改动】新增：判断是否"文档类"文件（用于决定要不要做目录清理）。
     *
     * <p>只对 .doc/.docx/.pdf/.ppt/.pptx 做 TOC 清理；代码、配置、txt 里不会出现
     * "编号+标题+制表符+页码"这种形态，没必要多跑一遍正则。
     */
    private boolean isDocumentType(String fileName) {
        String ext = getExtension(fileName).toLowerCase();
        return switch (ext) {
            case "doc", "docx", "pdf", "ppt", "pptx" -> true;
            default -> false;
        };
    }

    // ==================== 文件分类与参数配置 ====================

    /**
     * 根据文件扩展名返回对应的切分参数。
     * 返回值格式：[chunkSize, minChunkSizeChars]
     */
    private int[] getSplitParams(String fileName) {
        String ext = getExtension(fileName).toLowerCase();

        return switch (ext) {
            // Office 文档：叙述性内容，需要较大的块保留完整语义
            case "docx", "doc", "pdf", "pptx", "ppt", "xlsx", "xls" ->
                    new int[]{600, 200};

            // Markdown：有标题层级结构，中等粒度
            case "md", "markdown" ->
                    new int[]{500, 150};

            // 纯文本：无结构，较小粒度提高检索精度
            case "txt", "log" ->
                    new int[]{400, 100};

            // 后端代码：以方法/类为单位，粒度要细
            case "java", "xml", "yml", "yaml", "properties", "sql" ->
                    new int[]{200, 50};

            // 前端代码：组件更短，需要更细的粒度
            case "js", "ts", "jsx", "tsx", "vue", "html", "css", "scss" ->
                    new int[]{150, 40};

            // 默认：保守值
            default -> new int[]{400, 100};
        };
    }

    /**
     * 切点字符表。切块器在窗口填满后会回退到这些字符里**位置最靠后**的那个处切断。
     *
     * <p>默认的 `.` `?` `!` `\n` 对叙述性文档是对的，但表格渲染出来的正文里
     * 全是 IP、金额、日期、版本号，最后一个 `.` 几乎总在数字中间 —— 于是切点落在记录中间，
     * 续块开头是半条记录、键名丢了。实测 3,591 个 xlsx 块里 1,724 块（48%）如此。
     *
     * <p>表格里一行就是一条记录，所以只把换行当切点。代价是块装不满：
     * 一条记录必须整条进块，36 个文件实测块数 3,591 → 4,316（+20.2%）。
     */
    private List<Character> punctuationFor(String fileName) {
        String ext = getExtension(fileName).toLowerCase();
        return switch (ext) {
            case "xlsx", "xls" -> List.of('\n');
            default -> List.of('.', '?', '!', '\n');
        };
    }

    /**
     * 提取文件扩展名（不含点号）
     */
    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * 判断该文件是否适合走 AI 脱敏。
     *
     * 只有叙述性文档才走 AI，代码、配置、SQL、Shell 等结构化文本一律跳过。
     * 原因：3B 模型无法区分"脱敏任务"和"代码 review 请求"，对代码文件会输出
     * 解释性内容而非脱敏结果，且耗时极长（几十秒）。
     *
     * @param fileName 文件名（含扩展名）
     * @return true 表示走 AI 脱敏，false 表示仅正则脱敏
     */
    private boolean shouldUseAiSanitize(String fileName) {
        String ext = getExtension(fileName).toLowerCase();

        return switch (ext) {
            // 叙述性文档：适合 AI 脱敏
            case "docx", "doc", "pdf", "pptx", "ppt",
                 "md", "markdown", "txt", "log" -> true;

            // 代码、配置、SQL、Shell、HTML、CSS 等：仅正则脱敏
            case "java", "xml", "yml", "yaml", "properties", "sql",
                 "js", "ts", "jsx", "tsx", "vue", "html", "css", "scss",
                 "sh", "bat", "ps1", "py", "go", "rs", "c", "cpp", "h",
                 "json", "conf", "ini", "toml" -> false;

            // 未知类型：保守起见只走正则
            default -> false;
        };
    }

}
