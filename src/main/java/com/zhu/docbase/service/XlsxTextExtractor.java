package com.zhu.docbase.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * xlsx / xls 结构化抽取器（2026-10-23 新增，对应「阶段 1：结构修复」）。
 *
 * <p><b>为什么要有这个类</b>：原来 xlsx 走 Tika，Tika 把每个单元格渲染成独立一行，
 * 行与列的关系、表头、合并单元格全部丢失。实测全库 33 个 xlsx / 39 个有效 sheet 里：
 * <ul>
 *   <li><b>19 / 31 个文件的表头不在第 1 行</b>（有几个在第 8 行），拿第 1 行当表头必错；</li>
 *   <li><b>35 个 sheet 有合并单元格</b>，横向 126 处、纵向 37 处，合计 1,569 格只有左上角有值。
 *       不回填的话，纵向合并的分类字段在下面各行直接变空串——<b>这是数据错误，不是效果问题</b>；</li>
 *   <li>多级表头回填后会变成多个同名列（如 4 个「阶段一」），不拼接就无法区分；</li>
 *   <li>大量全空列（某表 33 列里大半是空的），裁掉能省 token 也能降噪。</li>
 * </ul>
 *
 * <p><b>改造要点四条</b>：xlsx 不再走 Tika 改走 POI、合并单元格必须回填、
 * 表头定位是前置条件、每块写元信息行。本类就是这四条的实现。
 *
 * <p><b>渲染格式</b>：保持「单元格墙」（每格一行）+ 顶部两行元信息。故意不做行级键值化
 * （那是阶段 2，前置条件未满足），也不做 Markdown 表格（实测更差：top-5 命中 6/8 vs 7/8）。
 * 与实验脚本 {@code 评测/测xlsx格式.py} 的 A2 臂保持一致，这样实验结论能直接对上生产。
 *
 * <pre>
 * 【表】某表.xlsx｜sheet: 主机层面｜列: 问题编号、安全要求、安全层面、…
 * 表头: 问题编号 | 安全要求 | 安全层面 | …
 * T01
 * 通用
 * 安全管理制度
 * …
 * </pre>
 *
 * <p><b>一个已知局限</b>：元信息行和表头行只在 sheet 的第一块里出现。
 * 切分器（TokenTextSplitter）按 token 预算切，跨块之后就看不到表头了——
 * 这要靠阶段 2「按行切、一行不跨块」解决，本阶段不做。
 *
 * @author ：zhuzj（2026-10-23 新增）
 */
@Slf4j
@Component
public class XlsxTextExtractor {

    /**
     * 表头识别关键词。
     *
     * <p>与 {@code 评测/测xlsx格式.py} 的 {@code HEADER_WORDS} 逐字一致——
     * 两边不一致的话，实验测出来的收益没法直接搬到生产。
     */
    private static final List<String> HEADER_WORDS = List.of(
            "序号", "编号", "名称", "类型", "时间", "日期", "说明", "备注", "单位",
            "数量", "金额", "状态", "地址", "端口", "用途", "责任人", "项目", "内容",
            "结果", "部门", "负责人", "电话", "邮箱", "模块", "功能", "需求", "问题",
            "建议", "等级", "整改", "措施", "系统", "服务", "版本", "路径", "账号");

    /**
     * 表头只在前 N 行里找。
     *
     * <p>为什么是 12：实测有文件的真表头在第 8 行（上面是标题、填报说明、"可自行增减列"之类的提示行）。
     * 12 行能覆盖全部样本，再往后就是数据行了，误判风险上升。
     */
    private static final int HEADER_SCAN_ROWS = 12;

    /** 表头单元格的"短文本"上限（字数）。超过这个长度的基本是说明文字，不是列名 */
    private static final int HEADER_CELL_MAX_LEN = 14;

    /**
     * 子表头单元格的"短文本"上限（字数）。
     *
     * <p>比表头宽 6 个字：实测子表头里有「人工综合单价（万元/人月）」这种 12 字的，
     * 卡 10 字会漏判。真正的保护是"父表头必须有重复列名"这个前置条件——
     * 只有横向合并的父表头才会产生重复，普通数据行不会。
     */
    private static final int SUBHEADER_CELL_MAX_LEN = 20;

    /** 表头行里"短文本"单元格的最低占比。低于这个比例说明是数据行不是表头 */
    private static final double HEADER_SHORT_RATIO = 0.6;

    /** 一个 sheet 至少要有这么多非空行才处理，否则当空表跳过（与实验脚本一致） */
    private static final int MIN_DATA_ROWS = 3;

    /** 纯数字/小数/负号/日期分隔符组成的单元格，不算"表头词" */
    private static final Pattern NUMERIC_LIKE = Pattern.compile("[\\d.\\-/:\\s]*");

    /**
     * 单元格内连续空白（含换行）压成一个空格。
     *
     * <p>必须显式带上 {@code \u00A0}（不间断空格）和 {@code \u3000}（全角空格）：
     * Java 的 {@code \s} 默认**不匹配**它们，实测表格里大量"看起来是空"的格子其实是 NBSP，
     * 不处理就会在单元格墙里渲染出一堆隐形空行。
     */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000\\u200B]+");

    /**
     * 抽取结果。
     *
     * @param text  渲染后的正文（未脱敏、未切分）
     * @param stats 统计口径，用于验收「合并导致的空值占比 &lt; 1%」
     */
    public record Result(String text, Stats stats) {
    }

    /**
     * 抽取统计。
     *
     * <p>这几个数的用途只有一个：证明合并回填真的做了，而不是"看起来做了"。
     *
     * @param sheets           有效 sheet 数（非空行 ≥ {@value #MIN_DATA_ROWS}）
     * @param dataRows         数据行数（不含表头及表头以上的标题行）
     * @param cols             裁掉全空列之后的列数（各 sheet 累加）
     * @param mergedCells      合并区域覆盖的格子数（**不含**左上角锚点格，即"本来会变空"的那些）
     * @param mergedStillEmpty 回填后仍为空的合并格数（**独立复核**得出）。正常必须是 0
     * @param mergedEmptyAnchor 锚点本身为空的合并格数——版面合并（留白），不参与验收
     * @param nonEmptyCells    实际渲染进正文的格子数
     * @param emptyCells       保留下来的格子里的空值数（含表头行以外的数据区）
     * @param formulaNoCache   公式单元格取不到缓存值的个数（需要 WARN）
     */
    public record Stats(int sheets, int dataRows, int cols, long mergedCells,
                        long mergedStillEmpty, long mergedEmptyAnchor,
                        long nonEmptyCells, long emptyCells, int formulaNoCache) {

        /** 合并导致的空值占比：分母是"本来会被合并吃掉的格子"，分子是回填后仍为空的 */
        public double mergedEmptyRatio() {
            return mergedCells == 0 ? 0.0 : (double) mergedStillEmpty / mergedCells;
        }

        /** 数据区整体的空值占比（含本来就空的格子，仅作参考，不是验收项） */
        public double emptyRatio() {
            long all = nonEmptyCells + emptyCells;
            return all == 0 ? 0.0 : (double) emptyCells / all;
        }

        /** 把多个文件的统计累加成一份（批量验收用） */
        public Stats plus(Stats o) {
            return new Stats(sheets + o.sheets, dataRows + o.dataRows, cols + o.cols,
                    mergedCells + o.mergedCells, mergedStillEmpty + o.mergedStillEmpty,
                    mergedEmptyAnchor + o.mergedEmptyAnchor,
                    nonEmptyCells + o.nonEmptyCells, emptyCells + o.emptyCells,
                    formulaNoCache + o.formulaNoCache);
        }

        public static Stats empty() {
            return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 抽取 xlsx / xls 的结构化正文。
     *
     * <p>用 {@link WorkbookFactory} 而不是直接 new XSSFWorkbook：语料里还有老式 {@code .xls}，
     * WorkbookFactory 能同时吃 HSSF 和 XSSF，而 XSSF 只认 .xlsx。
     *
     * @param file 表格文件
     * @return 渲染结果（正文 + 统计）；解析失败时抛异常，由调用方退回 Tika
     * @throws Exception 文件读取 / 解析异常
     */
    public Result extract(File file) throws Exception {
        StringBuilder sb = new StringBuilder(64 * 1024);
        // DataFormatter 不带 evaluator：公式单元格直接取文件里存的**缓存值**，
        // 不重新计算。理由是重算要跑整个依赖图，慢且可能抛异常；
        // 而 Excel/WPS 保存时一定写过缓存值，取它才是"用户当时看到的结果"。
        DataFormatter fmt = new DataFormatter();

        int sheets = 0, dataRows = 0, cols = 0, formulaNoCache = 0;
        long mergedCells = 0, mergedStillEmpty = 0, mergedEmptyAnchor = 0;
        long nonEmptyCells = 0, emptyCells = 0;

        try (InputStream in = Files.newInputStream(file.toPath());
             Workbook wb = WorkbookFactory.create(in)) {

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);

                // ---------- 1. 读成二维字符串网格（顺手压平单元格内换行） ----------
                List<List<String>> grid = new ArrayList<>();
                int maxWidth = 0;
                int[] noCache = new int[1];

                for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        grid.add(new ArrayList<>());
                        continue;
                    }
                    int width = Math.max(row.getLastCellNum(), 0);
                    List<String> cells = new ArrayList<>(width);
                    for (int c = 0; c < width; c++) {
                        cells.add(cellText(row.getCell(c), fmt, noCache));
                    }
                    maxWidth = Math.max(maxWidth, width);
                    grid.add(cells);
                }
                formulaNoCache += noCache[0];

                if (grid.isEmpty() || maxWidth == 0) {
                    continue;
                }
                // 补齐成矩形，后面按列索引访问不会越界
                for (List<String> row : grid) {
                    while (row.size() < maxWidth) {
                        row.add("");
                    }
                }

                // ---------- 2. 合并单元格回填（横补右、纵补下） ----------
                // 这是本类存在的核心理由：不回填 = 静默丢数据。
                //
                // 统计口径（2026-10-23 修正，第一版把口径搞错了）：
                //   只统计**锚点非空**的合并区域。锚点本身为空的是版面合并（排版时为了留白
                //   把一片空格子合并掉，实测全库有 8,908 格），它们本来就没有数据，
                //   算进分母会把"合并导致的空值占比"从 0% 抬到 10%，把真问题淹没掉。
                //   这类区域单独记 mergedEmptyAnchor，不藏起来。
                List<CellRangeAddress> ranges = sheet.getMergedRegions();
                List<CellRangeAddress> dataRanges = new ArrayList<>();
                for (CellRangeAddress range : ranges) {
                    int r0 = range.getFirstRow(), r1 = range.getLastRow();
                    int c0 = range.getFirstColumn(), c1 = range.getLastColumn();
                    if (r0 < 0 || c0 < 0 || r0 >= grid.size() || c0 >= maxWidth) {
                        continue;
                    }
                    String value = grid.get(r0).get(c0);
                    if (value.isEmpty()) {
                        // 版面合并：没有数据可补。按**格子数**记账（和 mergedCells 同口径），
                        // 不是按区域数——按区域数会让人以为问题很小。
                        for (int r = r0; r <= Math.min(r1, grid.size() - 1); r++) {
                            for (int c = c0; c <= Math.min(c1, maxWidth - 1); c++) {
                                if (r != r0 || c != c0) {
                                    mergedEmptyAnchor++;
                                }
                            }
                        }
                        continue;
                    }
                    dataRanges.add(range);
                    for (int r = r0; r <= Math.min(r1, grid.size() - 1); r++) {
                        for (int c = c0; c <= Math.min(c1, maxWidth - 1); c++) {
                            if (r == r0 && c == c0) {
                                continue;   // 锚点格不参与统计
                            }
                            mergedCells++;
                            grid.get(r).set(c, value);
                        }
                    }
                }
                // 复核：独立于上面的回填循环再数一遍"仍然为空"的位置。
                // 写成两遍不是浪费——只有独立复核才能抓出"回填循环漏了某个区域/越界跳过"这种 bug，
                // 在同一个循环里顺手 +1 是自证，永远 0。
                for (CellRangeAddress range : dataRanges) {
                    int r1 = Math.min(range.getLastRow(), grid.size() - 1);
                    int c1 = Math.min(range.getLastColumn(), maxWidth - 1);
                    for (int r = range.getFirstRow(); r <= r1; r++) {
                        for (int c = range.getFirstColumn(); c <= c1; c++) {
                            if (r == range.getFirstRow() && c == range.getFirstColumn()) {
                                continue;
                            }
                            if (grid.get(r).get(c).isEmpty()) {
                                mergedStillEmpty++;
                            }
                        }
                    }
                }

                // ---------- 3. 丢掉全空行 ----------
                List<List<String>> rows = new ArrayList<>(grid.size());
                for (List<String> row : grid) {
                    if (row.stream().anyMatch(s -> !s.isEmpty())) {
                        rows.add(row);
                    }
                }
                if (rows.size() < MIN_DATA_ROWS) {
                    continue;   // 空表 / 只有标题的说明页
                }

                // ---------- 4. 裁掉全空列 ----------
                // 不裁的话单元格墙里会混进大量空行，既费 token 又稀释语义。
                // 实测某表 33 列里大半是空列。
                List<Integer> usedCols = new ArrayList<>();
                for (int c = 0; c < maxWidth; c++) {
                    final int cc = c;
                    if (rows.stream().anyMatch(row -> !row.get(cc).isEmpty())) {
                        usedCols.add(c);
                    }
                }
                if (usedCols.isEmpty()) {
                    continue;
                }
                if (usedCols.size() < maxWidth) {
                    List<List<String>> trimmed = new ArrayList<>(rows.size());
                    for (List<String> row : rows) {
                        List<String> nr = new ArrayList<>(usedCols.size());
                        for (int c : usedCols) {
                            nr.add(row.get(c));
                        }
                        trimmed.add(nr);
                    }
                    rows = trimmed;
                    maxWidth = usedCols.size();
                }

                // ---------- 5. 表头定位 + 多级表头拼接 ----------
                int headerIdx = pickHeader(rows);
                List<String> header;
                int bodyStart;
                if (headerIdx < 0) {
                    // 没有明确表头（纯数据表 / 转置表）：按列号命名，所有行都当数据
                    header = new ArrayList<>(maxWidth);
                    for (int c = 0; c < maxWidth; c++) {
                        header.add("列" + (c + 1));
                    }
                    bodyStart = 0;
                } else {
                    header = new ArrayList<>(rows.get(headerIdx));
                    bodyStart = headerIdx + 1;

                    // 多级表头拼接：`父/子`（如 `阶段一/工作量（人月）`）。
                    //
                    // 【2026-10-23 改】先试"和**上一行**拼"，再试"和下一行拼"。
                    // 为什么顺序重要：改成"取命中关键词最多的行"之后，通常选中的是**子表头**
                    // （子表头列名更多、命中更多），父表头（阶段一/阶段二）就落到了它上面一行。
                    // 只往下一行找的话，父表头会被当成"前序"丢掉，
                    // 结果两列都叫「工作量（人月）」，分不清哪个属于阶段一、哪个属于阶段二。
                    //
                    // 判定"上一行是父表头"靠两条：①有重复的非空值（横向合并回填的特征）
                    // ②是短文本行。普通标题行（如「表名：… | 单位：万元」）没有重复值，不会被误拼。
                    boolean joined = false;
                    if (headerIdx > 0) {
                        List<String> prev = rows.get(headerIdx - 1);
                        if (hasDuplicateNonBlank(prev) && isShortTextRow(prev) && isShortTextRow(header)) {
                            header = joinHeader(prev, header, maxWidth);
                            headerIdx = headerIdx - 1;   // 父表头也算表头，不能再进"前序"
                            bodyStart = headerIdx + 2;
                            joined = true;
                        }
                    }
                    if (!joined && headerIdx + 1 < rows.size()) {
                        List<String> next = rows.get(headerIdx + 1);
                        if (hasDuplicateNonBlank(header) && isShortTextRow(next)) {
                            header = joinHeader(header, next, maxWidth);
                            bodyStart = headerIdx + 2;
                        }
                    }
                    // 表头全是数字（说明定位错了）→ 退回列号
                    if (header.stream().allMatch(h -> h.isEmpty() || NUMERIC_LIKE.matcher(h).matches())) {
                        header = new ArrayList<>(maxWidth);
                        for (int c = 0; c < maxWidth; c++) {
                            header.add("列" + (c + 1));
                        }
                        bodyStart = 0;
                    }
                }

                // ---------- 6. 渲染 ----------
                List<List<String>> body = rows.subList(Math.min(bodyStart, rows.size()), rows.size());
                if (body.isEmpty()) {
                    continue;
                }
                sheets++;
                dataRows += body.size();
                cols += maxWidth;

                // 元信息行：文件名 + sheet + 列名清单（成本 30~60 token）
                sb.append("【表】").append(file.getName())
                        .append("｜sheet: ").append(sheet.getSheetName())
                        .append("｜列: ").append(String.join("、", header.stream().filter(h -> !h.isEmpty()).toList()))
                        .append('\n');

                // 【2026-10-23 修】表头之上的行不能直接丢。
                // 实测踩到的坑：某张申请表第 2 行（第 39 列）塞着一段 4,162 字的 JSON 配置
                // （字段定义 key/name/required），它在表头之上，
                // 按"表头之前都是标题"处理会把它整段丢掉 —— 字数比直接从 1.0 掉到 0.12。
                // 所以表头之前的非空行统一按"前序"输出，一行一条，单元格用 | 分隔。
                for (int r = 0; r < Math.min(headerIdx < 0 ? 0 : headerIdx, rows.size()); r++) {
                    List<String> row = rows.get(r);
                    // 横向合并回填后，一行里会出现几十个相同值（标题横跨 38 列就重复 38 遍），
                    // 渲染时把**相邻重复**压掉，只留一次。注意只在"前序行"这么做：
                    // 表头行不能压，多级表头拼接正是靠"有重复列名"来判定的。
                    List<String> cells = new ArrayList<>();
                    String prev = null;
                    for (String cell : row) {
                        if (cell.isEmpty() || cell.equals(prev)) {
                            continue;
                        }
                        cells.add(cell);
                        prev = cell;
                    }
                    if (!cells.isEmpty()) {
                        sb.append("前序: ").append(String.join(" | ", cells)).append('\n');
                    }
                }

                // 表头行：单元格墙本身不带列名，没有这行模型无法把值对应到列
                sb.append("表头: ").append(String.join(" | ", header)).append('\n');

                for (List<String> row : body) {
                    for (String cell : row) {
                        if (!cell.isEmpty()) {
                            sb.append(cell).append('\n');
                            nonEmptyCells++;
                        } else {
                            emptyCells++;
                        }
                    }
                }
                sb.append('\n');
            }
        }

        Stats stats = new Stats(sheets, dataRows, cols, mergedCells, mergedStillEmpty,
                mergedEmptyAnchor, nonEmptyCells, emptyCells, formulaNoCache);
        if (formulaNoCache > 0) {
            log.warn("{} 个公式单元格取不到缓存值（可能从未在 Excel 里保存过计算结果）: {}",
                    formulaNoCache, file.getName());
        }
        return new Result(sb.toString(), stats);
    }

    /**
     * 取单元格的显示文本。
     *
     * <p>三个细节：
     * <ol>
     *   <li><b>公式取缓存值</b>：DataFormatter 不带 evaluator，直接读文件里存的缓存结果。</li>
     *   <li><b>压平换行</b>：实测某"源地址"单元格塞了 6 个 IP、用换行分隔。
     *       不压平的话，"一行一记录"的序列化会一条记录拆成 7 行——这是 2026-10-23 查 P9 时发现的真 bug。</li>
     *   <li><b>公式没缓存值</b>时记一笔，交给上层 WARN。</li>
     * </ol>
     *
     * @param cell    单元格（可为 null）
     * @param fmt     格式化器
     * @param noCache 公式无缓存值的计数器（长度 1 的数组，充当 out 参数）
     * @return 压平空白后的文本，空单元格返回空串
     */
    private String cellText(Cell cell, DataFormatter fmt, int[] noCache) {
        if (cell == null) {
            return "";
        }
        String raw;
        try {
            // 【2026-10-23 修】公式单元格必须单独处理。
            // DataFormatter.formatCellValue(cell) 在不传 evaluator 时返回的是**公式文本**
            // （实测 "D6*F6"、"SUM(D9:D70)"），不是缓存值 —— 全库 1,181 个公式格会全部变成公式字符串，
            // 金额/工作量这些计算结果直接丢失（覆盖率校验掉到 87% 就是这批）。
            // 这里按"缓存值类型 + 单元格格式"自己格式化：既取到缓存值，又保留显示格式。
            if (cell.getCellType() == CellType.FORMULA) {
                raw = cachedFormulaText(cell, fmt);
                if (raw == null || raw.isEmpty()) {
                    noCache[0]++;
                    return "";
                }
            } else {
                raw = fmt.formatCellValue(cell);
            }
        } catch (RuntimeException e) {
            // 少数畸形单元格会让 DataFormatter 抛异常（日期格式串非法等），不能因此丢掉整张表
            log.debug("单元格格式化失败，按空处理: {}", e.toString());
            return "";
        }
        if (raw == null) {
            return "";
        }
        raw = WHITESPACE.matcher(raw).replaceAll(" ").strip();
        return raw;   // 纯空白（含 NBSP）的格子在这里变成空串
    }

    /**
     * 取公式单元格的**缓存结果**（不重算）。
     *
     * <p>为什么不重算：重算要跑整条依赖图，慢，而且跨表引用/不支持的函数会抛异常；
     * 而 Excel/WPS 保存时一定写过缓存值，取它才是"用户当时看到的结果"。
     *
     * @param cell 公式单元格
     * @param fmt  格式化器
     * @return 显示文本；缓存值缺失时返回空串
     */
    private String cachedFormulaText(Cell cell, DataFormatter fmt) {
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> {
                CellStyle style = cell.getCellStyle();
                yield fmt.formatRawCellContents(cell.getNumericCellValue(),
                        style.getDataFormat(), style.getDataFormatString());
            }
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";      // ERROR / BLANK：没有可用的缓存值
        };
    }

    /**
     * 在前 {@value #HEADER_SCAN_ROWS} 行里找表头行。
     *
     * <p>启发式（与实验脚本一致）：该行命中至少 1 个常见列名词，且 ≥60% 的单元格是短文本。
     * 找不到返回 -1，表示这张表没有明确表头。
     *
     * <p>为什么不直接拿第 1 行：实测 <b>19/31 个文件的表头不在第 1 行</b>，
     * 上面是标题、填报说明、"可自行增减列"之类。拿第 1 行当表头会得到一行全是标题的"列名"。
     *
     * @param rows 已回填、已裁列的网格
     * @return 表头行下标；找不到返回 -1
     */
    private int pickHeader(List<List<String>> rows) {
        int limit = Math.min(HEADER_SCAN_ROWS, rows.size());
        int best = -1;
        int bestHits = 0;
        long bestShort = -1;
        for (int i = 0; i < limit; i++) {
            List<String> cells = rows.get(i).stream().filter(s -> !s.isEmpty()).toList();
            if (cells.size() < 2) {
                continue;
            }
            // 横向合并的标题行：去重后不足 2 个不同值，直接排除
            if (new HashSet<>(cells).size() < 2) {
                continue;
            }
            // 数据行的特征：夹着 40 字以上的长描述（问题描述、整改建议、测评项）。
            // 真表头的列名最长也就 20 多字（「源服务器IP与浮动IP（要访问的IP）」）。
            // 实测某张三 sheet 的表里，真表头和数据行的命中关键词数打平，靠这条才分开。
            if (cells.stream().anyMatch(c -> c.length() > 40)) {
                continue;
            }
            long shortCells = cells.stream()
                    .filter(c -> c.length() <= HEADER_CELL_MAX_LEN && !NUMERIC_LIKE.matcher(c).matches())
                    .count();
            if (shortCells < cells.size() * HEADER_SHORT_RATIO) {
                continue;
            }
            // 【2026-10-23 改】取"命中关键词最多"的那一行，而不是"第一个碰巧命中的"。
            // 实测踩到的坑：某表标题行「表1： | 配置清单 ×6」因为含"项目"二字命中 1 个词，
            // 排在第 1 行，按"第一个命中"就会把标题当表头；
            // 而真表头（序号/名称/单价/数量/单位/总价/说明）命中 6 个词。
            // 改成打分取最大之后，全部文件都定位到真表头。
            // 注意：这里数的是"命中了几个**不同的**关键词"，不是"多少个单元格命中"。
            // 数单元格会被合并回填带偏：标题「项目软硬件配置总清单」回填成 7 个相同值后，
            // 每个都含"项目"，7 个单元格 = 7 分，反而压过真表头的 5 分（序号/名称/数量/单位/说明）。
            // 关键词匹配前去掉单元格里的空白：实测有表的列名被排成「信 息 项 名 称」，
            // 带空格就匹配不到"名称"，整张表会退化成"没有表头"。
            Set<String> matched = new HashSet<>();
            for (String c : cells) {
                String compact = WHITESPACE.matcher(c).replaceAll("");
                for (String w : HEADER_WORDS) {
                    if (compact.contains(w)) {
                        matched.add(w);
                    }
                }
            }
            int hits = matched.size();
            // 同分时取"短文本更多"的那行：真表头每格都是短列名，数据行常夹着长描述
            // （实测某张表的真表头和数据行都命中 2 个词，靠这条才选对）
            if (hits > bestHits || (hits == bestHits && hits > 0 && shortCells > bestShort)) {
                bestHits = hits;
                bestShort = shortCells;
                best = i;
            }
        }
        return best;
    }

    /**
     * 把父表头和子表头拼成 `父/子`。
     *
     * <p>父格为空时取子格；两者相同（父表头下只有一个子列）时只留一个，避免出现 `阶段一/阶段一`。
     *
     * @param parent 父表头行
     * @param child  子表头行
     * @param width  列数
     * @return 拼好的列名
     */
    private List<String> joinHeader(List<String> parent, List<String> child, int width) {
        List<String> joined = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            String a = parent.get(c), b = child.get(c);
            joined.add(!a.isEmpty() && !b.isEmpty() && !a.equals(b) ? a + "/" + b
                    : (a.isEmpty() ? b : a));
        }
        return joined;
    }

    /** 非空单元格里是否存在重复值（父表头横向合并的特征） */
    private boolean hasDuplicateNonBlank(List<String> row) {
        Set<String> seen = new HashSet<>();
        for (String s : row) {
            if (!s.isEmpty() && !seen.add(s)) {
                return true;
            }
        }
        return false;
    }

    /** 是否是"短文本行"（子表头的特征）：至少 2 个非空格，且每个都不超过 {@value #SUBHEADER_CELL_MAX_LEN} 字 */
    private boolean isShortTextRow(List<String> row) {
        List<String> cells = row.stream().filter(s -> !s.isEmpty()).toList();
        return cells.size() >= 2 && cells.stream().allMatch(c -> c.length() <= SUBHEADER_CELL_MAX_LEN);
    }
}
