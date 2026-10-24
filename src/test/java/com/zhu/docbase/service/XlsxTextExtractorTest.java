package com.zhu.docbase.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xlsx 结构化抽取的单元测试（2026-10-23 新增，对应「阶段 1：结构修复」）。
 *
 * <p>用 POI 现场造一张小表来测，不依赖个人目录里的真实语料，所以能在 CI 里跑。
 * 表里刻意放齐了四种坑：
 * <ol>
 *   <li>表头不在第 1 行（第 1 行是标题）—— 实测 19/31 个真实文件都是这样</li>
 *   <li>两级表头（「调整前」横跨 3 列）—— 回填后会出现 3 个重复列名</li>
 *   <li>纵向合并（「分组一」跨 2 行）—— 不回填下面那行就变空</li>
 *   <li>一个整列全空 —— 要裁掉</li>
 * </ol>
 */
class XlsxTextExtractorTest {

    /**
     * 造一张带"标题行 + 两级表头 + 纵向合并 + 全空列"的小表。
     *
     * @param dir 临时目录
     * @return 生成的文件路径
     */
    private Path buildSample(Path dir) throws Exception {
        Path file = dir.resolve("样例表.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet("Sheet1");

            // 第 0 行：合并的大标题（表头不在第 1 行）
            Row r0 = sh.createRow(0);
            r0.createCell(0).setCellValue("示例汇总表");
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            // 第 1 行：父表头「调整前」横跨 3 列
            Row r1 = sh.createRow(1);
            r1.createCell(0).setCellValue("序号");
            r1.createCell(1).setCellValue("功能");
            r1.createCell(2).setCellValue("调整前");
            sh.addMergedRegion(new CellRangeAddress(1, 1, 2, 4));
            r1.createCell(5).setCellValue("备注");

            // 第 2 行：子表头（真列名）
            Row r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("序号");
            r2.createCell(1).setCellValue("功能名称");
            r2.createCell(2).setCellValue("工作量（人月）");
            r2.createCell(3).setCellValue("人员类型");
            r2.createCell(4).setCellValue("单价（万元/人月）");
            r2.createCell(5).setCellValue("备注");

            // 第 3~4 行：数据，第 0 列纵向合并（分类字段只写在第一行）
            Row r3 = sh.createRow(3);
            r3.createCell(0).setCellValue("分组一");
            r3.createCell(1).setCellValue("模块甲");
            r3.createCell(2).setCellValue("3.5");
            r3.createCell(3).setCellValue("软件开发人员");
            r3.createCell(4).setCellValue("1.70");
            r3.createCell(5).setCellValue("备注一");
            sh.addMergedRegion(new CellRangeAddress(3, 4, 0, 0));

            Row r4 = sh.createRow(4);
            // 第 0 列留空（纵向合并的续行），第 1 列必须有值，否则整行会被当空行丢掉
            r4.createCell(1).setCellValue("模块乙");
            r4.createCell(2).setCellValue("2.1");
            r4.createCell(3).setCellValue("测试人员");
            r4.createCell(4).setCellValue("1.60");
            r4.createCell(5).setCellValue("备注二");

            // 第 5 行：故意留一列（第 6 列）全空，验证会被裁掉
            Row r5 = sh.createRow(5);
            r5.createCell(0).setCellValue("分组三");
            r5.createCell(1).setCellValue("对接模块");
            r5.createCell(2).setCellValue("1.2");
            r5.createCell(3).setCellValue("实施人员");
            r5.createCell(4).setCellValue("1.40");
            r5.createCell(5).setCellValue("备注三");

            wb.write(out);
        }
        return file;
    }

    @Test
    @DisplayName("两级表头被拼成「调整前/工作量（人月）」")
    void multiLevelHeaderIsJoined(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildSample(dir).toFile());
        assertTrue(r.text().contains("调整前/工作量（人月）"),
                "两级表头没有拼接，实际正文：\n" + r.text());
        assertTrue(r.text().contains("调整前/单价（万元/人月）"), "第二个子列也没拼上");
        // 没拼的话会出现 3 个都叫「调整前」的列，模型无法区分
        assertFalse(r.text().contains("表头: 序号 | 功能 | 调整前 | 调整前"),
                "出现了重复列名「调整前」");
    }

    @Test
    @DisplayName("纵向合并的分类字段被回填到下一行")
    void verticalMergeIsBackfilled(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildSample(dir).toFile());
        // 「业务功能升级改造」只在第 1 行有值，回填后第 2 行也应该带上
        long count = r.text().lines().filter(l -> l.equals("分组一")).count();
        assertEquals(2, count, "纵向合并只回填了 1 行，实际正文：\n" + r.text());
    }

    @Test
    @DisplayName("合并导致的空值占比为 0，且统计能自证")
    void mergeRatioIsZero(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Stats st = new XlsxTextExtractor().extract(buildSample(dir).toFile()).stats();
        assertTrue(st.mergedCells() > 0, "没有统计到任何合并格，回填可能根本没跑");
        assertEquals(0, st.mergedStillEmpty(), "回填后仍有合并格为空");
        assertEquals(0.0, st.mergedEmptyRatio(), 1e-9, "合并导致的空值占比应为 0");
    }

    @Test
    @DisplayName("标题行被当前序保留，不是被丢掉")
    void preHeaderRowsAreKept(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildSample(dir).toFile());
        assertTrue(r.text().contains("前序: 示例汇总表"),
                "表头之上的标题行被整行丢弃了，实际正文：\n" + r.text());
    }

    @Test
    @DisplayName("每块带元信息行：文件名 + sheet + 列名清单")
    void metaLineIsPresent(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildSample(dir).toFile());
        assertTrue(r.text().startsWith("【表】样例表.xlsx｜sheet: Sheet1｜列: "),
                "元信息行缺失或格式不对，实际开头：" + r.text().substring(0, Math.min(120, r.text().length())));
        assertTrue(r.text().contains("表头: "), "缺少表头行");
    }

    @Test
    @DisplayName("单元格内换行被压平，一行一记录不会被拆散")
    void cellNewlinesAreFlattened(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("换行样例.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet("Sheet1");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("序号");
            h.createCell(1).setCellValue("源地址");
            for (int i = 1; i <= 3; i++) {
                Row r = sh.createRow(i);
                r.createCell(0).setCellValue(i);
                r.createCell(1).setCellValue("10.0.0." + i + "\n10.0.0." + (i + 10) + "\n10.0.0." + (i + 20));
            }
            wb.write(out);
        }
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(file.toFile());
        assertTrue(r.text().contains("10.0.0.1 10.0.0.11 10.0.0.21"),
                "单元格内换行没压平，一行一记录会被拆散，实际正文：\n" + r.text());
        // 一条记录只能占一行
        long lines = r.text().lines().filter(l -> l.startsWith("10.0.0.1 ")).count();
        assertEquals(1, lines, "同一条记录被拆成了多行");
    }

    @Test
    @DisplayName("全空列被裁掉")
    void emptyColumnsAreTrimmed(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildSample(dir).toFile());
        // 样例表有 6 个有内容的列（0~5），第 6 列全空
        String header = r.text().lines().filter(l -> l.startsWith("表头: ")).findFirst().orElse("");
        assertEquals(6, header.split("\\|").length, "列数不对，全空列没裁干净：" + header);
    }

    @Test
    @DisplayName("公式单元格不会把公式文本当正文")
    void formulaUsesCachedValue(@TempDir Path dir) throws Exception {        // 说明：POI 写出的公式**自带一个 0.0 的缓存结果**，所以这里测不到"缓存缺失"的分支，
        // 只验证最关键的一条：公式文本（A2*2）不能出现在正文里。
        // 真实缓存值的读取靠 tools/XlsxCheck.java 在语料上验收（那批文件是 Excel 存的，带真缓存值）。
        Path file = dir.resolve("公式样例.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet("Sheet1");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("序号");
            h.createCell(1).setCellValue("金额");
            for (int i = 1; i <= 3; i++) {
                Row r = sh.createRow(i);
                r.createCell(0).setCellValue(i);
                Cell c = r.createCell(1);
                c.setCellFormula("A" + (i + 1) + "*2");
            }
            wb.write(out);
        }
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(file.toFile());
        assertFalse(r.text().contains("A2*2"), "公式文本被当成正文输出了：" + r.text());
        assertFalse(r.text().contains("*2"), "公式文本被当成正文输出了：" + r.text());
    }

    // ==================== 转置表（字段名在行、记录在列） ====================

    /**
     * 造一张转置表：左边两列是字段名（一级 + 二级），右边每个数字列是一条记录。
     *
     * <p>刻意放齐三种情况：
     * <ol>
     *   <li>标题横向合并（回填后每列都"非空"，所以裁空列裁不掉空记录列）；</li>
     *   <li>一级字段名纵向合并（续行没有值，回填后才有）；</li>
     *   <li>第 4 条记录只有记录号、整列没有数据 —— 必须被跳过。</li>
     * </ol>
     *
     * @param dir 临时目录
     * @return 生成的文件路径
     */
    private Path buildTransposed(Path dir) throws Exception {
        Path file = dir.resolve("转置样例.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet("填写表");

            Row r0 = sh.createRow(0);
            r0.createCell(0).setCellValue("示例填报说明表");
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

            Row r1 = sh.createRow(1);
            r1.createCell(0).setCellValue("序号");
            for (int i = 0; i < 4; i++) {
                r1.createCell(2 + i).setCellValue(i + 1);
            }

            Row r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("字段甲");
            r2.createCell(1).setCellValue("子项一");
            r2.createCell(2).setCellValue("值甲一");
            r2.createCell(3).setCellValue("值甲二");
            r2.createCell(4).setCellValue("值甲三");
            sh.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));

            Row r3 = sh.createRow(3);
            r3.createCell(1).setCellValue("子项二");
            r3.createCell(2).setCellValue("值乙一");
            r3.createCell(3).setCellValue("值乙二");
            r3.createCell(4).setCellValue("值乙三");

            Row r4 = sh.createRow(4);
            r4.createCell(0).setCellValue("字段乙");
            r4.createCell(2).setCellValue("值丙一");
            r4.createCell(3).setCellValue("值丙二");
            r4.createCell(4).setCellValue("值丙三");

            wb.write(out);
        }
        return file;
    }

    @Test
    @DisplayName("转置表被认出来，并按一条记录一行渲染")
    void transposedTableIsRenderedPerRecord(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildTransposed(dir).toFile());
        String text = r.text();

        assertTrue(text.contains("转置: 是"), "没有识别成转置表，实际正文：\n" + text);
        // 字段名在行上，两列标签要拼起来，否则「子项一」和「子项二」分不清
        assertTrue(text.contains("字段甲/子项一: 值甲一"),
                "字段名没有和值成对出现，实际正文：\n" + text);
        assertTrue(text.contains("字段乙: 值丙三"), "第二个一级字段没渲染出来：\n" + text);
        // 一条记录必须只占一行
        assertEquals(3, text.lines().filter(l -> l.startsWith("序号: ")).count(),
                "记录条数不对，实际正文：\n" + text);
        assertTrue(text.contains("前序: 示例填报说明表"), "标题行被丢了：\n" + text);
        assertEquals(1, r.stats().transposedSheets(), "转置 sheet 计数不对");
        assertEquals(3, r.stats().transposedRecords(), "转置记录计数不对");
    }

    @Test
    @DisplayName("只有记录号、整列无数据的记录列被跳过")
    void emptyRecordColumnIsSkipped(@TempDir Path dir) throws Exception {
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(buildTransposed(dir).toFile());
        // 第 4 条记录只有序号 4，横向合并的标题把这一列撑成"非空"，裁空列裁不掉，只能在渲染时挡掉
        assertFalse(r.text().contains("序号: 4"), "空记录列没有被跳过：\n" + r.text());
    }

    @Test
    @DisplayName("普通宽表不会被误判成转置表")
    void wideTableIsNotTransposed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("宽表样例.xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sh = wb.createSheet("Sheet1");
            Row h = sh.createRow(0);
            String[] cols = {"资源分类", "资源名称", "资源代码", "提供方", "提供方代码",
                    "资源摘要", "资源格式", "信息项名称", "数据类型", "共享类型",
                    "共享条件", "共享方式", "开放条件", "更新周期", "发布日期", "关联代码", "备注"};
            for (int c = 0; c < cols.length; c++) {
                h.createCell(c).setCellValue(cols[c]);
            }
            // 数据行：首列是短文本分类值（会被回填成重复值），列数远多于行数
            for (int i = 1; i <= 5; i++) {
                Row r = sh.createRow(i);
                r.createCell(0).setCellValue("主题资源");
                r.createCell(1).setCellValue("业务报文" + i);
                r.createCell(2).setCellValue("A0" + i);
                r.createCell(3).setCellValue("某单位");
                r.createCell(5).setCellValue("业务流水号、单位编码、单位名称等字段的说明文字");
                r.createCell(6).setCellValue("接口");
                r.createCell(7).setCellValue("信息项" + i);
                r.createCell(8).setCellValue("String");
                r.createCell(9).setCellValue("有条件共享");
                r.createCell(10).setCellValue("依申请共享");
                r.createCell(12).setCellValue("无");
                r.createCell(13).setCellValue("实时");
            }
            wb.write(out);
        }
        XlsxTextExtractor.Result r = new XlsxTextExtractor().extract(file.toFile());
        assertEquals(0, r.stats().transposedSheets(), "宽表被误判成转置表了：\n" + r.text());
        assertTrue(r.text().contains("表头: "), "宽表没有走原来的单元格墙渲染：\n" + r.text());
    }
}
