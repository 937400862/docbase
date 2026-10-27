package com.zhu.docbase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 码值说明表的改写口径（2026-10-27 新增）。
 *
 * <p>为什么要有单测：这条改动在**所有 docx 的表格渲染**路径上，
 * 口径一宽就会把普通两列表格也改掉。所以断言的重点不是"能改"，而是**"不该改的一律不改"**。
 */
class DocumentTextExtractorTest {

    private static List<String[]> table(String[]... rows) {
        List<String[]> list = new ArrayList<>();
        for (String[] r : rows) {
            list.add(r);
        }
        return list;
    }

    @Test
    @DisplayName("典型码值表：编码并进名称，不再单独成列")
    void rewritesCodeTable() {
        String out = DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明"},
                new String[]{"0", "标准甲"},
                new String[]{"1", "标准乙"},
                new String[]{"2", "标准丙"}));

        assertTrue(out != null, "应该被识别为码值表");
        assertTrue(out.contains("- 标准甲（编码 0）"));
        assertTrue(out.contains("- 标准乙（编码 1）"));
        // 关键：不能出现"只有编码"的行
        assertTrue(!out.contains("| 0 |"));
    }

    @Test
    @DisplayName("英文表头也认（code / name）")
    void acceptsEnglishHeader() {
        String out = DocumentTextExtractor.renderCodeTable(table(
                new String[]{"Code", "Name"},
                new String[]{"0", "A"},
                new String[]{"1", "B"}));
        assertTrue(out != null && out.contains("A（编码 0）"));
    }

    @Test
    @DisplayName("表头不是「值/说明」类 → 原样不动（这是防误伤的主判据）")
    void keepsNormalTwoColumnTable() {
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"序号", "事项"},
                new String[]{"1", "申请"},
                new String[]{"2", "审核"})));
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"项目", "金额"},
                new String[]{"0", "100"},
                new String[]{"1", "200"})));
    }

    @Test
    @DisplayName("左列不是 0~99 的整数 → 原样不动")
    void keepsNonNumericCodeColumn() {
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明"},
                new String[]{"A", "甲"},
                new String[]{"B", "乙"})));
        // 3 位数字也不认（码值表不会到 100）
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明"},
                new String[]{"100", "甲"},
                new String[]{"200", "乙"})));
    }

    @Test
    @DisplayName("行数太少（表头 + 1 行）→ 不当码值表")
    void needsAtLeastTwoDataRows() {
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明"},
                new String[]{"0", "甲"})));
    }

    @Test
    @DisplayName("列数不是 2 → 原样不动")
    void keepsOtherColumnCounts() {
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明", "备注"},
                new String[]{"0", "甲", "x"},
                new String[]{"1", "乙", "y"})));
    }

    @Test
    @DisplayName("右列为空 → 原样不动（说明列空着的表多半不是码值表）")
    void keepsEmptyNameColumn() {
        assertNull(DocumentTextExtractor.renderCodeTable(table(
                new String[]{"值", "说明"},
                new String[]{"0", ""},
                new String[]{"1", "乙"})));
    }
}
