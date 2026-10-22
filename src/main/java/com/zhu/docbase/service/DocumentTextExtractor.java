package com.zhu.docbase.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 文档正文抽取器（2026-10-20 新增）。
 *
 * <p>背景：原来所有文件都直接交给 {@link TikaDocumentReader}，实测有三个问题：
 * <ol>
 *   <li><b>docx 表格被拍平</b>：Tika 把每个单元格渲染成单独一行、行首加制表符，
 *       一个 6 列的表头变成竖排 6 行，行列关系全丢。已入库的 2520 个 chunk 里，
 *       16.4% 的 chunk 一半以上是这种表格碎片。</li>
 *   <li><b>docx 目录当正文</b>：Tika 会把渲染后的目录条目（"1.1. 标题\t3"）输出成正文，
 *       51% 的 docx 带这种噪音，而且目录行含全部章节标题，是检索的"强磁铁"。</li>
 *   <li><b>GBK 文本静默丢光</b>：Tika 对 GBK 编码的中文 txt 探测失败，返回 0 个字符，
 *       内容彻底丢掉，日志里只有一行"已摄入 0 个片段"。</li>
 * </ol>
 *
 * <p>所以这里按扩展名分三条路：
 * <table border="1">
 *   <tr><th>类型</th><th>走哪条路</th><th>为什么</th></tr>
 *   <tr><td>.docx</td><td>{@link #readDocx(File)}（POI 遍历 body）</td>
 *       <td>表格能还原成 markdown 行；目录在 POI 里本来就抽不到，天然干净</td></tr>
 *   <tr><td>纯文本类（java/txt/md/sql/json...）</td><td>{@link #readPlainText(File)}</td>
 *       <td>自己按 UTF-8 → GB18030 兜底解码，避免 GBK 丢数据；顺带比 Tika 快很多</td></tr>
 *   <tr><td>其余（.doc/.pdf/.xls/.ppt...）</td><td>{@link #readWithTika(File)}</td>
 *       <td>二进制格式只能靠 Tika，且必须跑在 8MB 栈的线程里（见方法注释）</td></tr>
 * </table>
 *
 * <p><b>注意</b>：本类只负责"把文件变成文本"，不做脱敏、不做切分。
 *
 * @author ：zhuzj（2026-10-20 新增）
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /**
     * Tika 解析线程的栈大小：8MB。
     *
     * <p>2026-10-19 实测：Tika 解析老式 .doc（Word 97 二进制）走 POI 的 HWPF，
     * HWPF 内部带嵌套量词的正则在这个文件上递归过深，默认 1MB 栈直接 StackOverflowError
     * （1MB/2MB/3MB 全爆，4MB 通过，8MB 稳）。
     *
     * <p>用 Thread 构造函数的第三个参数只放大这一个线程的栈，
     * 不用 -Xss，避免把 Tomcat 那 200 个工作线程全部撑大。
     */
    private static final long TIKA_STACK_SIZE = 8L * 1024 * 1024;

    /**
     * 走"自己解码"这条路的纯文本扩展名。
     *
     * <p>判断标准：文件内容本身就是文本，Tika 对它也只是做"编码探测 + 原样返回"，
     * 那不如自己做——既不会把 GBK 判错，也省掉 Tika 的解析开销（全库 5 万多个 java/js 文件）。
     */
    private static final Set<String> PLAIN_TEXT_EXTS = Set.of(
            // 文档类
            "txt", "log", "md", "markdown", "csv", "tsv",
            // 配置类
            "properties", "yml", "yaml", "json", "xml", "ini", "conf", "toml", "gradle",
            // 代码类
            "java", "kt", "js", "ts", "jsx", "tsx", "vue", "html", "htm",
            "css", "scss", "less", "sql", "sh", "bat", "ps1", "py", "go", "rs",
            "c", "cpp", "h", "jsp", "ftl"
    );

    /** docx 走 POI 表格还原这条路 */
    private static final Set<String> DOCX_EXTS = Set.of("docx");

    /** POI 抽出来的文本少于这个长度时，认为 POI 没读到东西，退回 Tika 兜底 */
    private static final int POI_MIN_CHARS = 50;

    /**
     * 抽取文件正文。
     *
     * @param file 待抽取的文件
     * @return 纯文本内容（未脱敏、未切分）；抽不出内容时返回空字符串，不抛异常
     * @throws Exception 读取过程中的 IO / 解析异常（由调用方 catch Throwable 兜底）
     */
    public String extract(File file) throws Exception {
        String name = file.getName();
        String ext = extensionOf(name).toLowerCase();

        // ---------- 路线 1：docx → POI，能还原表格 ----------
        if (DOCX_EXTS.contains(ext)) {
            // 【改动 2026-10-20】POI 读取失败也要退回 Tika，不能直接抛。
            // 实测踩到的坑：docx 里嵌了 EMF 图片时，POI 的 ZipSecureFile 会按"压缩比过低"判定
            // Zip bomb 并抛 IOException（样例：范本-…测试报告.docx，ratio 0.00998 < 阈值 0.01），
            // 但同一个文件 Tika 能正常读出 349 个字符——不兜住的话这类文件会从"能摄入"变成"失败"。
            String text = null;
            try {
                text = readDocx(file);
            } catch (Throwable t) {
                log.warn("POI 解析 docx 失败（{}），退回 Tika 兜底: {}", t.toString(), name);
            }
            if (text != null && text.strip().length() >= POI_MIN_CHARS) {
                return text;
            }
            // POI 读不出东西（加密、损坏、内容全在 sdt/文本框里），同样退回 Tika
            if (text != null) {
                log.warn("POI 抽取内容过少（{} 字符），退回 Tika 兜底: {}", text.strip().length(), name);
            }
            return readWithTika(file);
        }

        // ---------- 路线 2：纯文本 → 自己解码，UTF-8 失败按 GB18030 兜底 ----------
        if (PLAIN_TEXT_EXTS.contains(ext)) {
            return readPlainText(file);
        }

        // ---------- 路线 3：其余二进制格式 → Tika（大栈线程） ----------
        return readWithTika(file);
    }

    // ==================== 路线 1：docx 用 POI 还原表格 ====================

    /**
     * 用 POI 的 XWPF 遍历 docx 的 body，按文档顺序输出段落和表格。
     *
     * <p>与 Tika 的关键差别：表格不再是"每个单元格一行"，而是还原成 markdown 行：
     * <pre>
     * | 字段名称 | 字段类型 | Not Null | 缺省值 | 备注 |
     * | OFFSET_STATUS | NUMBER(2,0) | N |  | 冲红状态，1：未冲红 2：已冲红 |
     * </pre>
     * 这样 LLM 能看懂"哪个值是哪个字段的"，切 chunk 时也不会把一行拆散。
     *
     * <p>另外两个细节：
     * <ul>
     *   <li><b>合并单元格</b>：横向合并（gridSpan=2）会补空列，保证每行列数一致，
     *       否则表格看着是歪的；纵向合并（vMerge=continue）输出空串，避免同一内容重复 N 遍。</li>
     *   <li><b>目录</b>：POI 的 getBodyElements() 拿不到 TOC 域的内容（TOC 在 w:sdt 里），
     *       所以不需要再正则清理——这一点在 交接文档.docx 上实测过（Tika 9952 字符 vs POI 9060 字符，
     *       差的 892 字符正好是那 48 行目录）。</li>
     * </ul>
     *
     * @param file docx 文件
     * @return 正文文本，段落之间用换行分隔
     */
    private String readDocx(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(file.toPath());
             XWPFDocument doc = new XWPFDocument(in)) {

            // getBodyElements() 保持文档里的原始顺序（段落、表格交替出现）
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String t = p.getText();
                    if (t != null && !t.isBlank()) {
                        sb.append(t).append('\n');
                    }
                } else if (el instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 把一个表格追加成 markdown 行。
     *
     * @param sb    输出缓冲
     * @param table POI 表格对象
     */
    private void appendTable(StringBuilder sb, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            sb.append('|');
            for (XWPFTableCell cell : cells) {
                // 默认跨 1 列
                int span = 1;
                // 是否是"纵向合并的续行"（内容与上面那格相同，重复输出没意义）
                boolean vMergeContinue = false;

                CTTcPr pr = cell.getCTTc() == null ? null : cell.getCTTc().getTcPr();
                if (pr != null) {
                    if (pr.isSetGridSpan() && pr.getGridSpan() != null && pr.getGridSpan().getVal() != null) {
                        span = pr.getGridSpan().getVal().intValue();
                    }
                    if (pr.isSetVMerge() && pr.getVMerge() != null
                            && pr.getVMerge().getVal() == STMerge.CONTINUE) {
                        vMergeContinue = true;
                    }
                }

                String text = vMergeContinue ? "" : cell.getText();
                if (text == null) {
                    text = "";
                }
                // 单元格内的换行压成空格，避免一行表格被拆成多行
                text = text.replace('\r', ' ').replace('\n', ' ').strip();
                sb.append(' ').append(text).append(" |");

                // 横向合并：补 (span-1) 个空列，让每行列数对齐
                for (int i = 1; i < span; i++) {
                    sb.append("  |");
                }
            }
            sb.append('\n');
        }
        // 表格和后面的段落之间留一个空行，切分时不会粘在一起
        sb.append('\n');
    }

    // ==================== 路线 2：纯文本自己解码 ====================

    /**
     * 读纯文本文件，按 UTF-8 → GB18030 → ISO-8859-1 的顺序尝试解码。
     *
     * <p>为什么不用 Tika：实测同一个 GBK 文件（参数.txt，184 字节），
     * Tika 返回 <b>0 个字符</b>（内容全丢），同内容转成 UTF-8 后返回 138 字符。
     * 自己解码就不会有这个问题。
     *
     * <p>顺序说明：
     * <ol>
     *   <li>先看 BOM：UTF-16LE/BE 的 BOM 直接按 UTF-16 解（老 Windows 记事本另存的 txt 常见）</li>
     *   <li>UTF-8 用<b>严格模式</b>（遇到非法字节就报错），能解出来的就是真 UTF-8</li>
     *   <li>解不出来就当 GB18030（GBK 的超集，能覆盖 GB2312/GBK/GB18030）</li>
     *   <li>GB18030 也解不出来（极少见），最后用 ISO-8859-1 兜底，至少不丢字节</li>
     * </ol>
     *
     * @param file 文本文件
     * @return 解码后的文本
     */
    private String readPlainText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) {
            return "";
        }

        // 1) UTF-16 BOM 判断
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
        }

        // 2) 严格 UTF-8
        String text = tryDecodeStrict(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            // 3) GB18030（GBK 超集）
            text = tryDecodeStrict(bytes, Charset.forName("GB18030"));
        }
        if (text == null) {
            // 4) 兜底：ISO-8859-1 不会失败
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }

        // 去掉 UTF-8 BOM（EF BB BF），否则会变成正文第一个字符 \uFEFF
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * 严格解码：有非法字节就返回 null，不抛异常。
     *
     * @param bytes   原始字节
     * @param charset 目标字符集
     * @return 解码结果；解码失败返回 null
     */
    private String tryDecodeStrict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ==================== 路线 3：Tika（大栈线程）====================

    /**
     * 在大栈线程里执行 Tika 解析。
     *
     * <p>这个方法是从 {@code DocumentIngestionService} 原样搬过来的（2026-10-20 重构），
     * 逻辑没变，只是搬了个位置，方便三种抽取方式集中在一处。
     *
     * @param file 待解析文件
     * @return 解析出的文本
     * @throws Exception 解析异常；Error（如 StackOverflowError）会被包成 RuntimeException
     */
    private String readWithTika(File file) throws Exception {
        AtomicReference<String> holder = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread t = new Thread(null, () -> {
            try {
                List<org.springframework.ai.document.Document> docs =
                        new TikaDocumentReader(new FileSystemResource(file)).get();
                // Tika 极少数格式会返回多个 Document，这里直接拼起来
                StringBuilder sb = new StringBuilder();
                for (org.springframework.ai.document.Document d : docs) {
                    if (d.getText() != null) {
                        sb.append(d.getText()).append('\n');
                    }
                }
                holder.set(sb.toString());
            } catch (Throwable e) {
                // 子线程必须自己兜住，否则 Error 会直接打印栈、调用方只能拿到 null
                error.set(e);
            }
        }, "tika-reader", TIKA_STACK_SIZE);

        t.start();
        t.join();

        Throwable err = error.get();
        if (err != null) {
            if (err instanceof Exception ex) {
                throw ex;
            }
            // Error（含 StackOverflowError）包一层抛给上层，由 ingestFile 的 catch (Throwable) 记账
            throw new RuntimeException("Tika 解析失败: " + err, err);
        }
        return holder.get() == null ? "" : holder.get();
    }

    // ==================== 文本清理 ====================

    /**
     * 目录条目：编号 + 标题 + 制表符 + 页码，例如 {@code 1.1. 第三方接入与权限控制\t3}。
     */
    private static final Pattern TOC_TAB_ENTRY =
            Pattern.compile("^\\s*\\d+(\\.\\d+)*\\.?\\s*\\S.*\\t\\s*\\d{1,4}\\s*$");

    /**
     * 目录条目（PDF 版）：编号 + 标题 + 点线 + 页码，例如 {@code 1. 订单服务 ........ 3}。
     */
    private static final Pattern TOC_DOT_ENTRY =
            Pattern.compile("^\\s*\\d+(\\.\\d+)*\\.?\\s*\\S.*\\.{3,}\\s*\\d{1,4}\\s*$");

    /** 单独一行的"目录"标题 */
    private static final Pattern TOC_TITLE =
            Pattern.compile("^\\s*(目录|目\\s*录|Contents)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 清理正文里的目录（TOC）噪音。
     *
     * <p>为什么要清：Tika 对 .doc/.pdf 会把渲染后的目录条目当正文输出。目录行包含全文档
     * 所有章节标题，向量化后对"这文档讲了什么""某某功能怎么做"这类查询相似度都很高，
     * 是个"高相似度、零信息量"的强干扰——专门抢 top-k 但什么都不回答。
     *
     * <p><b>关键设计：必须连续 ≥ {@value #TOC_MIN_RUN} 行才删。</b>
     * 单行匹配很可能是正文表格行（Tika 输出的表格行也带制表符，形如 {@code 1\t100}），
     * 逐行删会误伤；而连续 3 行以上都是"编号+标题+tab+页码"的，基本只可能是目录。
     * 这一点和 tools/resume/harvest.py 的做法不同——那边是逐行删的，风险更高。
     *
     * <p>docx 走 POI 路线时本来就没有目录，这个方法对它是个无害的幂等操作。
     *
     * @param text 原始文本
     * @return 去掉目录块之后的文本
     */
    public static String stripTocNoise(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        boolean[] drop = new boolean[lines.length];

        int i = 0;
        while (i < lines.length) {
            // 1) 单独一行的"目录"标题
            if (TOC_TITLE.matcher(lines[i]).matches()) {
                drop[i] = true;
                i++;
                continue;
            }
            // 2) 连续目录条目：先往后数，够长才整块删
            if (TOC_TAB_ENTRY.matcher(lines[i]).matches() || TOC_DOT_ENTRY.matcher(lines[i]).matches()) {
                int j = i;
                while (j < lines.length
                        && (TOC_TAB_ENTRY.matcher(lines[j]).matches() || TOC_DOT_ENTRY.matcher(lines[j]).matches())) {
                    j++;
                }
                if (j - i >= TOC_MIN_RUN) {
                    for (int k = i; k < j; k++) {
                        drop[k] = true;
                    }
                }
                i = j;
                continue;
            }
            i++;
        }

        StringBuilder sb = new StringBuilder(text.length());
        int blankRun = 0;
        for (int k = 0; k < lines.length; k++) {
            if (drop[k]) {
                continue;
            }
            boolean blank = lines[k].isBlank();
            // 连续空行压成一个（删掉目录块后容易留下大片空白）
            if (blank) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            sb.append(lines[k]).append('\n');
        }
        return sb.toString();
    }

    /** 判定"连续目录条目"的最少行数，低于这个数不认为是目录 */
    private static final int TOC_MIN_RUN = 3;

    /** 取扩展名（不含点号），与 DocumentIngestionService 里的实现保持一致 */
    private static String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(lastDot + 1) : "";
    }
}
