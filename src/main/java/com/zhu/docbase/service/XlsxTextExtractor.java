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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>渲染格式：行级键值</b>。一条记录一行，形如 {@code 列名: 值 | 列名: 值}。
 * 依据是 43 题四臂对照：行级键值 60.4%、单元格墙+回填 43.8%，同题配对 t=−2.5；
 * 代价是 chunk 约 1.7 倍（1,382 → 约 2,300）。与实验脚本 {@code 评测/测xlsx格式-RRF.py}
 * 的 B 臂逐字对应，实验结论可以直接对上生产。
 *
 * <p>之前用「单元格墙」（每格一行）：墙把行内关系拍平，模型拿不到"哪个值属于哪一列"。
 * 单记录表还勉强能用，多记录表上直接散架 —— 这是这次换渲染格式的唯一理由。
 *
 * <p><b>唯一的例外是转置表</b>（字段名在行、记录在列）：这类表套单元格墙或行级键值都会散架，
 * 所以先认出来，改走"一条记录一行"的键值渲染，见 {@link #renderTransposed}。
 * 全库实测只有 1 张这样的表，判据刻意做窄，认不出来就退回行级键值，不会比原来更差。
 *
 * <pre>
 * 【表】某表.xlsx｜sheet: 工作表1｜列: 编号、类别、名称、…
 * 表头: 编号 | 类别 | 名称 | …
 * 编号: A01 | 类别: 甲类 | 名称: 示例条目一
 * 编号: A02 | 类别: 乙类 | 名称: 示例条目二
 * </pre>
 *
 * <p><b>一个已知局限</b>：元信息行和表头行只在 sheet 的第一块里出现，切块之后看不到。
 * 记录本身自带键名，所以"这条记录是什么"不受影响，受影响的是"这张表有哪些列"这类问题。
 * 离线实验的每个块都带一行表头，生产没有 —— 生产实测可能低于离线臂的 65.5%，差的这部分就是它。
 *
 * <p><b>超长记录</b>：单条记录超过 {@link #RECORD_SOFT_LIMIT} 时改成"一个字段一行"。
 * 实测 13,231 条记录里只有 53 条（0.40%）超线，最大 4,291 字；不处理的话切块器会把它拦腰切断，
 * 续块开头没有键名。
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
     * <p>比表头宽 6 个字：实测子表头列名最长 12 个字，卡 10 字会漏判。真正的保护是"父表头必须有重复列名"这个前置条件——
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
     * 转置表识别的扫描行数上限。
     *
     * <p>序号行只会出现在表头位置，比表头定位的 12 行更靠前，8 行足够。
     */
    private static final int TRANSPOSE_SCAN_ROWS = 8;

    /** 序号行里至少要数出这么多条记录，才值得转置（少于 3 条没必要） */
    private static final int TRANSPOSE_MIN_RECORDS = 3;

    /** 序号行里"纯数字单元格"的最低占比。实测命中样本是 0.86（6 个数字 / 7 个非空） */
    private static final double TRANSPOSE_NUM_RATIO = 0.5;

    /** 第一列"字段名得分"的下限。低于它说明第一列是数据（如分类值），不是字段名 */
    private static final double TRANSPOSE_FIELD_RATIO = 0.6;

    /** 字段名的字数上限。与 {@link #HEADER_CELL_MAX_LEN} 同口径，超过的基本是说明文字 */
    private static final int FIELD_NAME_MAX_LEN = 14;

    /** 单条记录的字符数告警线。超过它就要担心切块时一条记录被拆到两个块里 */
    private static final int TRANSPOSE_RECORD_WARN_LEN = 400;

    /**
     * 单条记录的字符数软上限。超过就改成「一个字段一行」。
     *
     * <p>为什么是 700：xlsx 走 600 token 的切块预算，实测 1 token ≈ 1.21 字符
     * （生产 xlsx 块平均 727 字，对应 600 token），700 字已经贴着预算上限。
     * 一条记录超过它就会被切块器拦腰切断，而续块开头没有键名；
     * 改成按字段换行后，切点落在字段之间，每段仍自带键名。
     *
     * <p>实测分布：中位 148 字、p90 176、p99 494，超线只有 53 条（0.40%）。
     * 所以这条分支是兜底，不是主路径。
     */
    private static final int RECORD_SOFT_LIMIT = 700;

    /** 列取值摘要：只收"取值数不多、值又短"的列，这类列才是"有哪些 X"问题的答案所在 */
    private static final int SUMMARY_MIN_DISTINCT = 2;

    /** 超过这么多不同取值就不全量列举了，改走"出现最多的 N 个"（见 SUMMARY_TOPN） */
    private static final int SUMMARY_MAX_DISTINCT = 20;

    /** 平均长度超过这个数，多半是描述性字段，摘出来只是噪音 */
    private static final int SUMMARY_MAX_AVG_LEN = 20;

    /** 单个值超过这么长就不当类别值（描述、整段说明、合并单元格的页脚都靠这条挡掉） */
    private static final int SUMMARY_MAX_VALUE_LEN = 24;

    /** 高基数列只列出现最多的前 N 个 */
    private static final int SUMMARY_TOPN = 10;

    /** 高基数列至少要有这么多非空值，样本太少谈"最多"没意义 */
    private static final int SUMMARY_TOPN_MIN_ROWS = 30;

    /** 前 N 个合计占不到这个比例，说明取值太散（编号、IP），不值得摘 */
    private static final double SUMMARY_TOPN_MIN_COVER = 0.35;

    /** 单个 sheet 最多摘几列，防止宽表把第一块撑爆 */
    private static final int SUMMARY_MAX_COLUMNS = 6;

    /** 单个 sheet 的摘要总字数上限（摘要和【表】行同在块 0，块只有约 800 字预算） */
    private static final int SUMMARY_MAX_CHARS = 600;

    /** 渲染器给没有表头的列起的占位名（列7、列8…），摘它们没有意义 */
    private static final Pattern PLACEHOLDER_COL = Pattern.compile("列\\d+");

    /** 序号/编号/流水号这类列，取值再多也不是"类别" */
    private static final Pattern ID_COL = Pattern.compile(".*(序号|编号|流水号|ID|id)");

    /**
     * 纯整数（序号）。带小数点的不算——序号列不会写成 {@code 1.0}。
     *
     * <p>长度上限 6 位：超过 6 位的多半是编码/日期，不是序号，参与递增判断会误判。
     */
    private static final Pattern PURE_INT = Pattern.compile("\\d+");

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
     * @param transposedSheets 判为转置表（字段名在行、记录在列）并改走转置渲染的 sheet 数
     * @param transposedRecords 转置渲染输出的记录条数（空记录列不计）
     *
     * <p>注意：转置渲染的 sheet <b>不参与</b> {@code nonEmptyCells} / {@code emptyCells} 计数
     * （那两个数只统计单元格墙路径）。{@code emptyRatio()} 本来就只作参考、不是验收项，
     * 少一张表的格子不影响任何门槛判断。
     */
    public record Stats(int sheets, int dataRows, int cols, long mergedCells,
                        long mergedStillEmpty, long mergedEmptyAnchor,
                        long nonEmptyCells, long emptyCells, int formulaNoCache,
                        int transposedSheets, int transposedRecords) {

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
                    formulaNoCache + o.formulaNoCache,
                    transposedSheets + o.transposedSheets, transposedRecords + o.transposedRecords);
        }

        public static Stats empty() {
            return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
        int transposedSheets = 0, transposedRecords = 0;
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

                // ---------- 4.5 转置表：字段名在行、记录在列 ----------
                // 这类表拿"一行一记录"套上去，字段名会被当成第一列的数据，
                // 值又挂在错位的键下面 —— 语义直接散掉。所以先认出来，改走"一条记录一行"的转置渲染。
                //
                // 判据故意做窄（序号行三条同时满足）：宁可漏判退回原来的单元格墙（不会更差），
                // 也不要误判 —— 宽表被转置会把列名和值拆开。
                int seqRow = findSeqRow(rows);
                if (seqRow >= 0) {
                    TransposedRender tr = renderTransposed(sb, file.getName(), sheet.getSheetName(), rows, seqRow);
                    if (tr != null) {
                        sheets++;
                        // 数据行按**原表物理行数**计，不按记录条数计，与"没有表头"的老分支同口径。
                        // 注意这会让本表的行数变化：老逻辑把第 3 行当成表头，只数了 11 行；
                        // 现在序号行才是表头，上面是标题、下面是数据，共 14 行。
                        // 全库因此 13,192 → 13,195（只此一张表，其余 38 个 sheet 不变）。
                        dataRows += rows.size();
                        cols += maxWidth;
                        transposedSheets++;
                        transposedRecords += tr.records();
                        log.debug("转置渲染 {} / {}：{} 条记录、{} 个字段、跳过 {} 个空记录列",
                                file.getName(), sheet.getSheetName(),
                                tr.records(), tr.fields(), tr.emptyColumns());
                        if (tr.maxLineLen() > TRANSPOSE_RECORD_WARN_LEN) {
                            log.warn("{} / {} 有一条转置记录长达 {} 字，切块时可能被拆开",
                                    file.getName(), sheet.getSheetName(), tr.maxLineLen());
                        }
                        sb.append('\n');
                        continue;
                    }
                } else if (looksLikeFieldColumn(rows) && maxWidth > rows.size() * 2) {
                    // 软信号：列远多于行、第一列又像字段名，但没找到序号行。
                    // 不改变渲染，只留一条日志 —— 换语料后能一眼看出判据是不是该放宽。
                    log.info("疑似转置但未达判据：{} / {}（{} 行 x {} 列）",
                            file.getName(), sheet.getSheetName(), rows.size(), maxWidth);
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

                // 列名清洗：空列名补成 列N，重名加 #2/#3。
                // 键值渲染里列名就是键，空键会渲染出 ": 14" 这种没有语义的片段；
                // 重名则让两个不同的值顶着同一个键（实测某表是左右并排两张表，
                // 表头各有一组同名参数列），模型无法区分是左边那张还是右边那张。
                header = normalizeHeader(header, maxWidth);

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

                // 列取值摘要：回答"这一列有哪些取值"这类问题。
                //
                // 起因：有一类问题问的是某列的取值集合（"这张表分哪几类""还有哪些类型"）
                // "哪两个对象之间有关联"）。答案分散在几十条记录里，而检索只取 top-5 块，
                // 实测这类题的 gold 常常落在第 8~20 名，靠调 topK 也救不回来
                // （top-10 会让单题上下文从 3 千涨到 7 千 token，还会稀释其他题）。
                //
                // 放在这里而不是末尾：紧跟【表】行，保证它落在该 sheet 的第一块里，
                // 和表名/列名同块，问"这张表有哪些 X"时能一起被召回。
                appendColumnValueSummary(sb, file.getName(), sheet.getSheetName(), header, body, maxWidth);

                // 【2026-10-23 修】表头之上的行不能直接丢。
                // 实测踩到的坑：某张申请表第 2 行（第 39 列）塞着一段 4,162 字的 JSON 配置
                // （字段定义 key/name/required），它在表头之上，
                // 按"表头之前都是标题"处理会把它整段丢掉 —— 字数比直接从 1.0 掉到 0.12。
                // 所以表头之前的非空行统一按"前序"输出，一行一条，单元格用 | 分隔。
                for (int r = 0; r < Math.min(headerIdx < 0 ? 0 : headerIdx, rows.size()); r++) {
                    appendPreamble(sb, rows.get(r));
                }

                // 表头行：留一行列名清单，便于回答"这张表有哪些列"这类问题。
                // 每条记录自带键名，所以它不是记录可读的前提，只是补一个表级视图。
                sb.append("表头: ").append(String.join(" | ", header)).append('\n');

                for (List<String> row : body) {
                    // 行级键值：一条记录一行，字段之间用 | 分隔。
                    //
                    // 空值直接跳过、不写占位：键值化的目的是让"哪个值属于哪一列"显式可读，
                    // 空列本来就没有值可归属，写 "-" 只是浪费 token。
                    // （列式渲染必须占位，因为那边靠位置对齐；这边靠键名，不需要。）
                    List<String> parts = new ArrayList<>(maxWidth);
                    for (int c = 0; c < maxWidth; c++) {
                        String cell = c < row.size() ? row.get(c) : "";
                        if (cell.isEmpty()) {
                            emptyCells++;
                            continue;
                        }
                        nonEmptyCells++;
                        parts.add(header.get(c) + ": " + cell);
                    }
                    if (parts.isEmpty()) {
                        continue;
                    }
                    String record = String.join(" | ", parts);
                    if (record.length() <= RECORD_SOFT_LIMIT) {
                        sb.append(record).append('\n');
                    } else {
                        // 超长记录：按字段拆行，让切块落在字段边界上
                        for (String part : parts) {
                            sb.append(part).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
        }

        Stats stats = new Stats(sheets, dataRows, cols, mergedCells, mergedStillEmpty,
                mergedEmptyAnchor, nonEmptyCells, emptyCells, formulaNoCache,
                transposedSheets, transposedRecords);
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
    /**
     * 列名清洗：空列名补成 {@code 列N}，重名追加 {@code #2}、{@code #3}。
     *
     * <p>为什么必须有这一步：单元格墙时代列名只是装饰，空着也不影响读；
     * 换成键值渲染后列名就是键，空键会产出 {@code ": 14"} 这种没有语义的片段，
     * 重名则让两个不同的值顶着同一个键。
     *
     * <p>重名的典型来源是**一个 sheet 里并排放了两张表**（实测某接口比对表左右各一组
     * "序号/参数名称/参数说明/数据类型/是否必填"）。这类表本该拆成两块，但拆表要先识别
     * 空列分界，属于另一个改动；当前先靠 {@code #2} 保证键唯一，至少不会张冠李戴。
     *
     * @param header 原表头（可能含空串与重复值）
     * @param width  列数
     * @return 长度等于 width、无空值、无重复的列名
     */
    private List<String> normalizeHeader(List<String> header, int width) {
        List<String> out = new ArrayList<>(width);
        Map<String, Integer> seen = new HashMap<>();
        for (int c = 0; c < width; c++) {
            String name = c < header.size() ? header.get(c) : "";
            if (name.isEmpty()) {
                name = "列" + (c + 1);
            }
            int n = seen.merge(name, 1, Integer::sum);
            out.add(n == 1 ? name : name + "#" + n);
        }
        return out;
    }

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
     * 列取值摘要：把"类别列"的取值集合写成几行，跟【表】行放在同一块里。
     *
     * <p>两种写法，按列的基数自动选：
     * <ol>
     *   <li><b>低基数列</b>（不同取值 2~20 个）：全量列举 —— 回答"有哪些 X"</li>
     *   <li><b>高基数列</b>（取值多但有明显头部）：列出现最多的前 10 个 —— 回答"最多的几件事"</li>
     * </ol>
     *
     * <p>为什么要第二种：实测某工作记录表"事项"列有 <b>370 种</b>取值（1,182 次），
     * 前 5 个占 61%。全量列举根本写不下，但"最多的三件事"正是那道题要的答案。
     *
     * <p>挡噪音的四道闸：单值超过 {@value #SUMMARY_MAX_VALUE_LEN} 字的不算类别值
     * （描述、整段说明、合并单元格的页脚都靠这条）、纯数字列跳过（序号/金额）、
     * 几乎每行都不同的列跳过（编号/IP）、每 sheet 最多 {@value #SUMMARY_MAX_COLUMNS} 列
     * 且总字数不超过 {@value #SUMMARY_MAX_CHARS}。
     *
     * <p>只对普通表格做；转置表（字段名在行、记录在列）走自己的渲染，这里跳过。
     */
    private void appendColumnValueSummary(StringBuilder sb, String fileName, String sheetName,
                                          List<String> header, List<List<String>> body, int maxWidth) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (int c = 0; c < maxWidth && lines.size() < SUMMARY_MAX_COLUMNS; c++) {
            String col = header.get(c);
            if (col.isEmpty() || PLACEHOLDER_COL.matcher(col).matches() || ID_COL.matcher(col).matches()) {
                continue;
            }
            Map<String, Integer> freq = new LinkedHashMap<>();
            int nonEmpty = 0;
            int numeric = 0;
            long totalLen = 0;
            for (List<String> row : body) {
                String cell = c < row.size() ? row.get(c) : "";
                if (cell.isEmpty() || cell.length() > SUMMARY_MAX_VALUE_LEN) {
                    continue;
                }
                nonEmpty++;
                totalLen += cell.length();
                if (isNumericCell(cell)) {
                    numeric++;
                }
                freq.merge(cell, 1, Integer::sum);
            }
            // 以数字为主的列（序号、金额、端口）不是"取值集合"类问题要的答案
            if (nonEmpty == 0 || numeric > nonEmpty * 0.8) {
                continue;
            }

            String line;
            if (freq.size() <= SUMMARY_MAX_DISTINCT) {
                if (freq.size() < SUMMARY_MIN_DISTINCT) {
                    continue;
                }
                if ((double) totalLen / nonEmpty > SUMMARY_MAX_AVG_LEN) {
                    continue;
                }
                // 几乎每行都不一样的列（编号、流水号）不是类别列；表很小的时候不这么判
                if (nonEmpty > 8 && freq.size() > nonEmpty * 0.9) {
                    continue;
                }
                StringBuilder b = new StringBuilder(col).append(": ");
                int shown = 0;
                for (String v : freq.keySet()) {
                    if (shown++ > 0) {
                        b.append('、');
                    }
                    b.append(v);
                }
                line = b.append("（共 ").append(freq.size()).append(" 种）").toString();
            } else {
                if (nonEmpty < SUMMARY_TOPN_MIN_ROWS) {
                    continue;
                }
                List<Map.Entry<String, Integer>> top = freq.entrySet().stream()
                        .sorted((x, y) -> y.getValue() - x.getValue())
                        .limit(SUMMARY_TOPN)
                        .toList();
                int covered = top.stream().mapToInt(Map.Entry::getValue).sum();
                if ((double) covered / nonEmpty < SUMMARY_TOPN_MIN_COVER) {
                    continue;
                }
                StringBuilder b = new StringBuilder(col).append("（出现最多）: ");
                for (int i = 0; i < top.size(); i++) {
                    if (i > 0) {
                        b.append('、');
                    }
                    b.append(top.get(i).getKey()).append('×').append(top.get(i).getValue());
                }
                line = b.append("（共 ").append(freq.size()).append(" 种）").toString();
            }

            if (used + line.length() > SUMMARY_MAX_CHARS) {
                break;
            }
            used += line.length();
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("【列取值】").append(fileName).append("｜sheet: ").append(sheetName).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }

    /** 纯数字（含千分位、百分号、正负号）。序号/金额列靠这条挡掉 */
    private boolean isNumericCell(String v) {
        String s = v.replace(",", "").replace("%", "").trim();
        if (s.isEmpty()) {
            return false;
        }
        return s.matches("[-+]?\\d+(\\.\\d+)?");
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
            // 数据行的特征：夹着 40 字以上的长描述（问题描述、处理建议、说明项）。
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

    // ==================== 转置表（字段名在行、记录在列） ====================

    /**
     * 转置渲染的结果，只用于统计和告警。
     *
     * @param records     输出了几条记录
     * @param fields      字段（键）的个数
     * @param emptyColumns 有记录号但整列没有数据的记录列数（被跳过）
     * @param maxLineLen  最长一条记录的字符数
     */
    private record TransposedRender(int records, int fields, int emptyColumns, int maxLineLen) {
    }

    /**
     * 找"序号行"——转置表的标志。
     *
     * <p>为什么不做成"列数远多于行数"：那是宽表的常态。实测 40 个 sheet 里，按列数/行数比判定会
     * 把两张普通宽表（6 行 17 列、6 行 39 列）也判成转置，假阳性 2/2；而按下面的序号行判定，
     * 命中 1 张、误报 0。
     *
     * <p>四条同时满足才算：非空单元格 ≥ {@value #TRANSPOSE_MIN_RECORDS}、纯数字占比
     * ≥ {@value #TRANSPOSE_NUM_RATIO}、数字严格递增、第一列像字段名。
     * 数字递增是关键的一条——统计行（如「1 2 2 3」）过不了。
     *
     * @param rows 已回填、已裁列的网格
     * @return 序号行的下标；不是转置表返回 -1
     */
    private int findSeqRow(List<List<String>> rows) {
        if (!looksLikeFieldColumn(rows)) {
            return -1;
        }
        int limit = Math.min(TRANSPOSE_SCAN_ROWS, rows.size());
        for (int i = 0; i < limit; i++) {
            int nonEmpty = 0;
            List<Long> nums = new ArrayList<>();
            for (String cell : rows.get(i)) {
                if (cell.isEmpty()) {
                    continue;
                }
                nonEmpty++;
                if (isSeqNumber(cell)) {
                    nums.add(Long.parseLong(cell));
                }
            }
            if (nonEmpty == 0 || nums.size() < TRANSPOSE_MIN_RECORDS) {
                continue;
            }
            if ((double) nums.size() / nonEmpty < TRANSPOSE_NUM_RATIO) {
                continue;
            }
            boolean increasing = true;
            for (int k = 1; k < nums.size(); k++) {
                if (nums.get(k) <= nums.get(k - 1)) {
                    increasing = false;
                    break;
                }
            }
            if (increasing) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 第一列是否像"字段名"：非空、不超 {@value #FIELD_NAME_MAX_LEN} 字、不是纯数字。
     *
     * <p>转置表的字段名在这一列，普通表这一列是数据（分类值、编号、备注），后者通常更短更杂。
     * 分母只数非空格——空列不参与，否则稀疏表会被稀释成"不像"。
     *
     * @param rows 已回填、已裁列的网格
     * @return 像字段名列返回 true
     */
    private boolean looksLikeFieldColumn(List<List<String>> rows) {
        int total = 0, field = 0;
        for (List<String> row : rows) {
            String cell = row.get(0);
            if (cell.isEmpty()) {
                continue;
            }
            total++;
            if (cell.length() <= FIELD_NAME_MAX_LEN && !PURE_INT.matcher(cell).matches()) {
                field++;
            }
        }
        return total > 0 && (double) field / total >= TRANSPOSE_FIELD_RATIO;
    }

    /** 是否是序号格：纯整数且不超过 6 位（更长的多半是编码/日期，参与递增判断会误判） */
    private boolean isSeqNumber(String s) {
        return s.length() <= 6 && PURE_INT.matcher(s).matches();
    }

    /**
     * 把转置表渲染成"一条记录一行"的键值文本。
     *
     * <p>结构：序号行左边是标签列（字段名），右边每个数字列是一条记录。
     * 标签列可能有 2 列（一级分组 + 二级字段，如「分成规则 / 省级」），用 {@code /} 拼接，
     * 与多级表头的 {@code 父/子} 保持同一套写法。
     *
     * <p>三条必须跳过的：
     * <ol>
     *   <li>标签全空的行——没有键，值无处挂；</li>
     *   <li>某条记录里空值的格——不输出 {@code 键: }；</li>
     *   <li>除记录号外整列无数据的记录列——横向合并的标题会把空列"填满"，
     *       导致裁空列失效，这类假记录必须在这里挡掉。</li>
     * </ol>
     *
     * @param sb        正文缓冲
     * @param fileName  文件名（写进元信息行）
     * @param sheetName sheet 名
     * @param rows      已回填、已裁列的网格
     * @param seqRow    序号行下标
     * @return 渲染结果；无法渲染（没有标签列 / 一条记录都没有）返回 null，由调用方退回普通渲染
     */
    private TransposedRender renderTransposed(StringBuilder sb, String fileName, String sheetName,
                                              List<List<String>> rows, int seqRow) {
        List<String> seq = rows.get(seqRow);
        int firstNumCol = -1;
        for (int c = 0; c < seq.size(); c++) {
            if (isSeqNumber(seq.get(c))) {
                firstNumCol = c;
                break;
            }
        }
        if (firstNumCol <= 0) {
            return null;   // 左边没有标签列，转置出来也没有键
        }

        List<Integer> recCols = new ArrayList<>();
        for (int c = firstNumCol; c < seq.size(); c++) {
            if (isSeqNumber(seq.get(c))) {
                recCols.add(c);
            }
        }

        // 字段名：标签列非空值用 / 拼接。标签全空、或所有记录列都没值的行跳过
        // （后者是"标准上限/标准下限"这类整行留空的占位字段，进字段清单只会添噪）。
        List<Integer> labelRows = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int r = seqRow + 1; r < rows.size(); r++) {
            String key = joinLabel(rows.get(r), firstNumCol);
            if (key.isEmpty() || keys.contains(key)) {
                continue;
            }
            boolean hasValue = false;
            for (int c : recCols) {
                if (!rows.get(r).get(c).isEmpty()) {
                    hasValue = true;
                    break;
                }
            }
            if (!hasValue) {
                continue;
            }
            labelRows.add(r);
            keys.add(key);
        }
        if (keys.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        int emptyColumns = 0, maxLen = 0;
        for (int c : recCols) {
            String no = seq.get(c);
            List<String> parts = new ArrayList<>();
            parts.add("序号: " + no);
            for (int i = 0; i < labelRows.size(); i++) {
                String value = rows.get(labelRows.get(i)).get(c);
                if (!value.isEmpty()) {
                    parts.add(keys.get(i) + ": " + value);
                }
            }
            if (parts.size() == 1) {
                emptyColumns++;
                continue;   // 只有记录号，整列没有数据
            }
            String line = String.join(" | ", parts);
            lines.add(line);
            maxLen = Math.max(maxLen, line.length());
        }
        if (lines.isEmpty()) {
            return null;
        }

        sb.append("【表】").append(fileName)
                .append("｜sheet: ").append(sheetName)
                .append("｜转置: 是｜记录: ").append(lines.size())
                .append("｜字段: ").append(String.join("、", keys))
                .append('\n');
        for (int r = 0; r < seqRow; r++) {
            appendPreamble(sb, rows.get(r));
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return new TransposedRender(lines.size(), keys.size(), emptyColumns, maxLen);
    }

    /** 把一行里前 {@code labelCols} 列的非空值用 / 拼起来（转置表的字段名） */
    private String joinLabel(List<String> row, int labelCols) {
        StringBuilder b = new StringBuilder();
        String prev = null;
        for (int c = 0; c < labelCols && c < row.size(); c++) {
            String v = row.get(c);
            // 一级字段名横向合并（A:B）时，回填会让两列变成同一个值，这里不能重复拼
            if (v.isEmpty() || v.equals(prev)) {
                continue;
            }
            if (b.length() > 0) {
                b.append('/');
            }
            b.append(v);
            prev = v;
        }
        return b.toString();
    }

    /**
     * 输出一行"前序"（表头之上的标题/说明）。
     *
     * <p>横向合并回填后，一行里会出现几十个相同值（标题横跨 38 列就重复 38 遍），
     * 渲染时把<b>相邻重复</b>压掉，只留一次。只在"前序行"这么做：
     * 表头行不能压，多级表头拼接正是靠"有重复列名"来判定的。
     *
     * @param sb  正文缓冲
     * @param row 一行
     */
    private void appendPreamble(StringBuilder sb, List<String> row) {
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
}
