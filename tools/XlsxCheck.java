import com.zhu.docbase.service.XlsxTextExtractor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * xlsx 结构化抽取的验收工具（2026-10-23 新增，配合「阶段 1：结构修复」）。
 *
 * <p>用途有三个：
 * <ol>
 *   <li><b>硬验收</b>：回填后"合并导致的空值占比"必须 &lt; 1%，达不到不许进下一步；</li>
 *   <li>看每个文件的 sheet / 行 / 列 / 合并格数，确认表头定位和裁列没把表搞坏；</li>
 *   <li>{@code --dump <关键字>} 把某个文件渲染后的正文打出来，人工核对格式对不对。</li>
 * </ol>
 *
 * <p>为什么单独写工具而不是塞进单测：验收要跑全库 30+ 个真实文件（含 4,923 行的大表），
 * 单测里放不下这些样本；而且这些文件在个人目录下，不适合进仓库。
 *
 * <p>用法：
 * <pre>
 * CP="target/classes;$(cat cp.txt)"
 * java -cp "$CP" tools/XlsxCheck.java <语料目录>
 * java -cp "$CP" tools/XlsxCheck.java <语料目录> --dump 网络策略表
 * </pre>
 */
public class XlsxCheck {

    /** 与 DocumentIngestionService.shouldIngest 保持一致的跳过规则（只保留与本工具相关的） */
    private static boolean accept(Path p) {
        String name = p.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("~$")) {
            return false;
        }
        String lower = name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            return false;
        }
        String s = p.toString().replace("\\", "/");
        if (s.contains("/.git/") || s.contains("/node_modules/") || s.contains("/target/")) {
            return false;
        }
        try {
            return Files.size(p) <= 10L * 1024 * 1024;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: XlsxCheck <目录|清单文件> [--list] [--dump 文件名关键字]");
            return;
        }
        String root = args[0];
        String dumpKey = null;
        boolean listMode = false;
        boolean headersOnly = false;
        for (int i = 1; i < args.length; i++) {
            if ("--dump".equals(args[i]) && i + 1 < args.length) {
                dumpKey = args[i + 1];
            }
            if ("--list".equals(args[i])) {
                listMode = true;
            }
            if ("--headers".equals(args[i])) {
                headersOnly = true;
            }
        }

        List<Path> files = new ArrayList<>();
        if (listMode) {
            // 清单模式：一行一个绝对路径。用于只验收"真正入库的那批文件"，
            // 而不是整个磁盘目录——语料根目录下还有一份 4,800 多个文件的备份副本，
            // 全目录跑出来的数字不能代表库里的内容。
            for (String line : Files.readAllLines(Paths.get(root))) {
                if (!line.isBlank()) {
                    files.add(Paths.get(line.strip()));
                }
            }
        } else {
            try (Stream<Path> s = Files.walk(Paths.get(root))) {
                s.filter(Files::isRegularFile).filter(XlsxCheck::accept).sorted().forEach(files::add);
            }
        }

        XlsxTextExtractor extractor = new XlsxTextExtractor();
        XlsxTextExtractor.Stats sum = XlsxTextExtractor.Stats.empty();
        long chars = 0;
        int failed = 0;

        System.out.printf("%-46s %5s %7s %5s %8s %10s%n",
                "文件", "sheet", "数据行", "列", "合并格", "正文字符");
        System.out.println("-".repeat(92));

        for (Path p : files) {
            try {
                XlsxTextExtractor.Result r = extractor.extract(p.toFile());
                XlsxTextExtractor.Stats st = r.stats();
                sum = sum.plus(st);
                chars += r.text().length();
                System.out.printf("%-46s %5d %7d %5d %8d %10d%n",
                        cut(p.getFileName().toString(), 46), st.sheets(), st.dataRows(),
                        st.cols(), st.mergedCells(), r.text().length());

                if (dumpKey != null && p.getFileName().toString().contains(dumpKey)) {
                    System.out.println("\n===== 渲染样例：" + p.getFileName() + " =====");
                    String[] lines = r.text().split("\n", -1);
                    for (int i = 0; i < Math.min(lines.length, 60); i++) {
                        System.out.println(lines[i]);
                    }
                    System.out.println("… 共 " + lines.length + " 行 =====\n");
                }

                // 【2026-10-23 加】只打表头，用来一次性复核所有文件的表头定位对不对
                if (headersOnly) {
                    System.out.println("■ " + cut(p.getFileName().toString(), 40));
                    for (String line : r.text().split("\n")) {
                        if (line.startsWith("【表】") || line.startsWith("表头: ")) {
                            System.out.println("   " + cut(line, 150));
                        }
                    }
                }
            } catch (Throwable t) {
                failed++;
                System.out.printf("%-46s  解析失败: %s%n", cut(p.getFileName().toString(), 46), t);
            }
        }

        System.out.println("-".repeat(92));
        System.out.printf("文件 %d 个（失败 %d）｜有效 sheet %d 个｜数据行 %d｜列 %d（各 sheet 累加）%n",
                files.size(), failed, sum.sheets(), sum.dataRows(), sum.cols());
        System.out.printf("正文字符 %d｜非空格子 %d｜空格子 %d（数据区空值率 %.1f%%）%n",
                chars, sum.nonEmptyCells(), sum.emptyCells(), sum.emptyRatio() * 100);
        System.out.printf("有数据的合并区域覆盖 %d 格（不含锚点）｜回填后仍为空 %d 格%n",
                sum.mergedCells(), sum.mergedStillEmpty());
        System.out.printf("锚点本身为空的合并格 %d 个（版面留白，不参与验收）%n", sum.mergedEmptyAnchor());
        if (sum.transposedSheets() > 0) {
            System.out.printf("转置表 %d 个 sheet｜输出记录 %d 条（已改走一条记录一行的键值渲染）%n",
                    sum.transposedSheets(), sum.transposedRecords());
        }
        double ratio = sum.mergedEmptyRatio();
        System.out.printf("★ 硬验收：合并导致的空值占比 = %.3f%%  →  %s（门槛 < 1%%）%n",
                ratio * 100, ratio < 0.01 ? "PASS" : "FAIL");
        if (sum.formulaNoCache() > 0) {
            System.out.printf("⚠ 公式取不到缓存值的格子：%d 个%n", sum.formulaNoCache());
        }
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    static {
        // 控制台中文输出（Windows 默认 GBK，这里强制 UTF-8 由 -Dstdout.encoding 负责）
        System.setProperty("file.encoding", StandardCharsets.UTF_8.name());
    }
}
