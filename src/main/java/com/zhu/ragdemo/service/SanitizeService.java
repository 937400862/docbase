package com.zhu.ragdemo.service;

import com.zhu.ragdemo.RagDemoApplication;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
// 【改动】引入 Ollama 的请求选项类，用于在单次请求上覆盖模型名
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏服务：提供正则脱敏 + AI 脱敏。
 * 只依赖 ChatClient，不依赖 Redis / VectorStore。
 *
 * <p>【改动】支持"双模型"：
 * AI 脱敏可以用一个单独的、更大的模型（配置项 {@code rag.sanitize.model}），
 * 与 RAG 问答用的模型（{@code spring.ai.ollama.chat.model}）解耦。
 * 这样问答保持 3B 的响应速度，脱敏用 7B 的准确率。
 *
 * @author ：zhuzj
 * @date ：2026/10/16 17:49
 */
@Slf4j
@Getter
@Service
public class SanitizeService {

    @Value("${rag.sanitize.ai-enabled:true}")
    private boolean aiSanitizeEnabled;

    /**
     * 【改动】脱敏专用模型名。
     *
     * <p>背景：原来只有 {@code spring.ai.ollama.chat.model} 一个模型（llama3.2-3b），
     * 3B 做"逐字替换、保持格式"这种任务不可靠，所以脱敏只能退化成纯正则。
     * 现在单独指定一个更大的模型（qwen2.5:7b）只干脱敏这一件事，
     * RAG 问答仍用 3B 保证响应速度，两个模型互不干扰。
     *
     * <p>留空（默认）时不做覆盖，自动沿用 {@code spring.ai.ollama.chat.model}。
     */
    @Value("${rag.sanitize.model:}")
    private String sanitizeModel;

    private final ChatClient sanitizeClient;

    public SanitizeService(ChatClient.Builder chatClientBuilder) {
        this.sanitizeClient = chatClientBuilder.build();
    }

// ==================== 脱敏规则 ====================
    /**
     * 脱敏规则映射：正则表达式 -> 替换文本。
     * 使用 LinkedHashMap 保证规则按插入顺序执行。
     */
    private static final Map<Pattern, String> SANITIZE_RULES = new LinkedHashMap<>();

    static {
        // ---------- 0. 银行卡 / 长账号（必须最先执行） ----------
        // 放到凭证规则之后会出问题："账号：6222 0212 3456 7890" 会被规则 1
        // 截成 "账号[CREDENTIAL] 0212 3456 7890"，后半段直接泄露。
        SANITIZE_RULES.put(
                Pattern.compile(
                        "(?i)(银行卡|卡号|账号|帐号|bank[_-]?card|card[_-]?no)" +
                                "\\s*[:：]?\\s*" +
                                "(\\d[\\d\\s\\-]{13,25}\\d)"
                ),
                "$1 [BANK_CARD]"
        );

        // ---------- 1.标签+值 模式（优先执行） ----------
        // 相比旧版三处改动：
        //   a) 分隔符支持中文动词"是 / 为"，修复 "密码是xxx" 整条漏网；
        //   b) 分隔符单独用 $2 捕获，替换时保留原文的 = 或 :，不破坏配置格式；
        //   c) 值字符集保持不变（含 @ # $ % 等），只补了 / 与反引号。
        SANITIZE_RULES.put(
                Pattern.compile(
                        "(?i)(账号|帐号|密码|口令|用户名|账户|密钥|令牌|token|secret|password)" +
                                "\\s*([:：=]|是|为)?\\s*" +
                                // (?!\\[) 防止本条规则把上一条产出的 [BANK_CARD] 占位符当成值再吃一遍
                                "(?!\\[)" +
                                "([A-Za-z0-9!@#$%^&*()_\\-+=.\\[\\]{}|;'\"<>?/~`]+)"
                ),
                "$1$2[CREDENTIAL]"
        );

        // 2. Oracle 导出/导入命令
//        SANITIZE_RULES.put(
//                Pattern.compile("(?i)(exp|expdp|imp|impdp|sqlplus)\\s+([^/\\s\"']+)/([^@\\s\"']+)(@\\S+)?"),
//                "$1 $2/[CREDENTIAL]$4"
//        );
        // 3. 带引号的 sysdba 模式
//        SANITIZE_RULES.put(
//                Pattern.compile("(?i)(exp|expdp|imp|impdp|sqlplus)\\s+[\"']([^/\"']+)/([^@\"'\\s]+)(\\s+as\\s+sysdba)?[\"']"),
//                "$1 \"$2/[CREDENTIAL]$4\""
//        );

        // ---------- 密钥类（优先处理，避免 PEM 块内的内容被其他规则误伤） ----------

        // 4. PEM 格式私钥：覆盖 RSA / EC / DSA / OPENSSH / PGP 等
        // 限制私钥最大长度 5000 字符，避免在超长文本中无限查找 END
        SANITIZE_RULES.put(
                Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]{0,5000}?-----END [A-Z ]*PRIVATE KEY-----"),
                "[PRIVATE_KEY]"
        );
        // 5. PEM 格式公钥
        // 限制私钥最大长度 5000 字符，避免在超长文本中无限查找 END
        SANITIZE_RULES.put(
                Pattern.compile("-----BEGIN [A-Z ]*PUBLIC KEY-----[\\s\\S]{0,5000}?-----END [A-Z ]*PUBLIC KEY-----"),
                "[PUBLIC_KEY]"
        );
        // 6. PEM 格式证书
        // 限制私钥最大长度 5000 字符，避免在超长文本中无限查找 END
        SANITIZE_RULES.put(
                Pattern.compile("-----BEGIN CERTIFICATE-----[\\s\\S]{0,5000}?-----END CERTIFICATE-----"),
                "[CERTIFICATE]"
        );
        // 7. SSH 公钥（ssh-rsa / ssh-ed25519 / ssh-dss / ssh-ecdsa 开头的一行）
        SANITIZE_RULES.put(
                Pattern.compile("ssh-(rsa|dss|ed25519|ecdsa)\\s+[A-Za-z0-9+/=]+(\\s+[\\w@.-]+)?"),
                "[SSH_KEY]"
        );
        // 8. JWT Token（三段 base64 用点分隔，以 eyJ 开头）
        SANITIZE_RULES.put(
                Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
                "[JWT]"
        );

        // ---------- 凭证类 ----------

        // 9. 密码/密钥/令牌赋值：password=xxx、api_key: yyy 等
        //    相比旧版：补了 username / user_name / db_user，值改成不吞分隔符
        //    （旧版 \S+ 会把后面的 & ; , 一起吃掉，破坏连接串结构）
        SANITIZE_RULES.put(
                Pattern.compile("(?i)(password|passwd|pwd|secret|token|密码|账号|公钥|私钥|用户名|" +
                        "api[_-]?key|access[_-]?key|private[_-]?key|username|user[_-]?name|db[_-]?user)" +
                        "\\s*[=:]\\s*[^\\s&;,]+"),
                "$1=***"
        );

        // 9b. 连接串里的 user 参数（单独处理，避免把代码中的 user = xxx 误伤）
        //     例：jdbc:mysql://host/db?user=root&password=xxx
        SANITIZE_RULES.put(
                Pattern.compile("(?i)([?&;])(user|username|uid)=([^&\\s;]+)"),
                "$1$2=***"
        );

        // ---------- 个人/组织标识 ----------

        // 10. IP 地址：不放在规则表里，改由 sanitizeIp() 处理。
        //     原因：\b(?:\d{1,3}\.){3}\d{1,3}\b 会把 1.2.3.4 这类版本号一起吃掉，
        //     纯正则无法区分，必须结合上下文判断。
        // 11. 身份证号（18 位，最后一位可能是 X）
        SANITIZE_RULES.put(
                Pattern.compile("\\b\\d{17}[\\dXx]\\b"),
                "[ID_CARD]"
        );
        // 12. 手机号（含 - 或空格分隔：13812345678 / 138-1234-5678 / 138 1234 5678）
        //     分隔符可选，因此这一条同时覆盖原来的纯 11 位写法
        SANITIZE_RULES.put(
                Pattern.compile("\\b1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}\\b"),
                "[PHONE]"
        );
        // 13. 邮箱
        SANITIZE_RULES.put(
                Pattern.compile("\\b[\\w.-]+@[\\w.-]+\\.\\w+\\b"),
                "[EMAIL]"
        );
        // 14. 统一社会信用代码（18 位）
        SANITIZE_RULES.put(
                Pattern.compile("\\b[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}\\b"),
                "[USCC]"
        );
    }

    /**
     * 正则脱敏：对文本执行所有正则规则替换。
     * 这是纯静态方法，不依赖 Spring 上下文，可以在任何地方调用。
     *
     * @param text 原始文本
     * @return 正则脱敏后的文本
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<Pattern, String> entry : SANITIZE_RULES.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        // IP 需要结合上下文判断是否为版本号，放在最后单独处理
        return sanitizeIp(result);
    }

    /** IP 地址粗匹配（四段点分十进制） */
    private static final Pattern IP_PATTERN =
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** 版本号前缀关键词：命中则把后面的四段数字判定为版本号 */
    private static final Pattern VERSION_PREFIX = Pattern.compile(
            "(?i)(版本|ver|version|release|build|jdk|java|spring|boot|(?<![A-Za-z])v)\\s*[:：]?\\s*$");

    /**
     * IP 脱敏：跳过疑似版本号，避免把 "Spring Boot 1.2.3.4" 里的版本号误删。
     *
     * <p>判定顺序（命中即返回）：
     * <ol>
     *   <li>私网 / 回环 / 链路本地段（10. / 192.168. / 172.16-31. / 127. / 169.254.）→ 一定是 IP，替换</li>
     *   <li>前面 24 个字符里出现"版本 / version / jdk / v"等关键词 → 判定为版本号，保留</li>
     *   <li>四段数值都 ≤ 20 → 判定为版本号，保留</li>
     *   <li>其余 → 按 IP 替换</li>
     * </ol>
     *
     * <p>已知取舍：像 8.8.8.8 这种"每段都很小"的公网 IP 会被当成版本号放过。
     * 对简历 / 技术文档场景来说，保住版本号比删掉公网 DNS 更重要。
     */
    private static String sanitizeIp(String text) {
        Matcher m = IP_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (m.find()) {
            found = true;
            String ip = m.group();
            String prefix = text.substring(Math.max(0, m.start() - 24), m.start());
            m.appendReplacement(sb, Matcher.quoteReplacement(looksLikeVersion(ip, prefix) ? ip : "[IP]"));
        }
        if (!found) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 判断一个"四段点分数字"更像版本号还是更像 IP。
     *
     * @param ip     匹配到的四段数字
     * @param prefix 该匹配之前最多 24 个字符，用于识别"版本 1.2.3.4"这类上下文
     * @return true 表示更像版本号（应当保留），false 表示按 IP 处理（应当替换）
     */
    private static boolean looksLikeVersion(String ip, String prefix) {
        String[] parts = ip.split("\\.");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Integer.parseInt(parts[i]);
            if (v[i] > 255) {
                // 超过 255 不可能是合法 IP，按可疑内容处理
                return false;
            }
        }
        // 私网 / 回环 / 链路本地：确定是 IP
        if (v[0] == 10) return false;
        if (v[0] == 192 && v[1] == 168) return false;
        if (v[0] == 172 && v[1] >= 16 && v[1] <= 31) return false;
        if (v[0] == 127) return false;
        if (v[0] == 169 && v[1] == 254) return false;
        // 上下文里带版本关键词（JDK 1.8.0.202 / Spring Boot 2.7.18）
        if (prefix != null && VERSION_PREFIX.matcher(prefix).find()) {
            return true;
        }
        // 四段都很小，按版本号处理（1.2.3.4 / 3.2.1.0 / 2.0.1.12）
        int max = 0;
        for (int x : v) {
            max = Math.max(max, x);
        }
        return max <= 20;
    }

    /**
     * AI 脱敏提示词。
     * 核心原则：只做替换，不改写、不总结、不添加任何解释。
     * 提示词越明确，LLM 越不容易"自作主张"。
     */
    private static final String AI_SANITIZE_PROMPT = """
        你是一个文本脱敏工具。请将下面文本中的敏感信息替换为对应的占位符，然后直接输出替换后的完整文本。

        敏感信息类型及替换规则：
        1. 人名：整个姓名替换为 [PERSON]。例如“张三”变成 [PERSON]。
        2. 公司、机构、单位名称：整个名称替换为 [ORG]。
        3. 项目内部代号：整个代号替换为 [PROJECT]。
        4. 地址：整个地址替换为 [ADDRESS]。
        5. 账号、密码、密钥、凭证等：
           - 如果前面有标签词（如“账号”、“密码”、“用户名”、“token”、“secret”等），保留标签词，只把后面的具体值替换为 [CREDENTIAL]。
             例如“账号demo_user”变成“账号[CREDENTIAL]”，“密码demo_pass”变成“密码[CREDENTIAL]”。
           - 如果没有标签词，整个值替换为 [CREDENTIAL]。
        6. 其他可能识别到具体个人或组织的信息：替换为 [SENSITIVE]。

        以下内容一律不是敏感信息，不要替换：
        - 系统路径（如 /etc/passwd、/data、/dev/mapper/...）
        - 命令和工具（如 cat、ll、mount、blkid、crontab、curl、sudo）
        - 软件和服务名（如 nginx、mysql、jdk、firewalld、iptables、lvm）
        - 脚本和文件名（如 start.sh、paycode.jar、paycode-web.zip）
        - 技术术语（如 UUID、LVM、xfs、defaults、databases）
        - 变量名、参数名、目录名

        严格要求：
        - 只输出替换后的文本，不要添加任何解释、说明、前言、后记、markdown 标记。
        - 保持原文的格式、换行、标点符号完全不变。
        - 如果文本中没有敏感信息，原样输出。

        现在开始处理以下文本：
        %s
        """;

    /**
     * AI 脱敏：调用本地 LLM，在正则脱敏的基础上再做一次语义层面的敏感信息识别。
     * 适用场景：正则覆盖不到的人名、公司名、内部代号等。
     *
     * 失败降级：如果 LLM 调用超时或报错，返回传入的文本（即正则脱敏的结果），
     * 不会中断整个摄入流程。
     *
     * @param text 已经过正则脱敏的文本
     * @return AI 脱敏后的文本，或失败时返回原文本
     */
    public String aiSanitize(String text) {
        if (!aiSanitizeEnabled || text == null || text.isBlank()) {
            return text;
        }
        try {
            String prompt = String.format(AI_SANITIZE_PROMPT, text);

            // 【改动】在本次请求上覆盖模型：配了 rag.sanitize.model 就用它（如 qwen2.5:7b），
            // 没配就沿用 spring.ai.ollama.chat.model。
            // 注意：Spring AI 2.0 的 options() 接收的是 Builder，不是 build() 之后的对象。
            ChatClient.ChatClientRequestSpec spec = sanitizeClient.prompt();
            if (sanitizeModel != null && !sanitizeModel.isBlank()) {
                spec = spec.options(OllamaChatOptions.builder().model(sanitizeModel));
            }

            String raw = spec.user(prompt)
                    .call()
                    .content();

            return postProcess(raw, text);
        } catch (Exception e) {
            log.error("AI 脱敏失败，降级为正则脱敏结果。原因: {}", e.getMessage());
            return text;
        }
    }

    /**
     * 完整脱敏流程：正则脱敏 → AI 脱敏。
     * 这是摄入流程中实际调用的方法。
     */
    public String fullSanitize(String text) {
        return aiSanitize(sanitize(text));
    }

    /**
     * 对 AI 返回的文本进行后处理：
     * 1. 去除首尾空白
     * 2. 如果输出以“脱敏后：”、“脱敏后的文本：”等开头，截取冒号后的内容
     * 3. 如果包含明显的解释性词语，降级返回原文（即正则脱敏结果）
     * 4. 长度异常膨胀时降级
     */
    private static String postProcess(String raw, String original) {
        if (raw == null || raw.isBlank()) {
            return original;
        }
        String result = raw.trim();

        // 去除常见的前缀
        String[] prefixes = {
                "脱敏后：", "脱敏后的文本：", "脱敏结果：", "处理后的文本：",
                "替换后：", "输出：", "结果："
        };
        for (String prefix : prefixes) {
            if (result.startsWith(prefix)) {
                result = result.substring(prefix.length()).trim();
                break;
            }
        }

        // 去除可能的 markdown 代码块标记
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.substring(3, result.length() - 3).trim();
        }

        // 检查是否包含解释性文本。
        // 旧版是全文 contains 扫描，导致原文里只要出现"以上""说明""注意"这类常用词，
        // 就会被误判成 AI 的废话而静默降级回正则结果（方向安全，但等于 AI 白跑）。
        // 现在改成：只在开头 80 字符内查"解释性开头"，另加几条 AI 专属口吻做全文兜底。
        String head = result.length() > 80 ? result.substring(0, 80) : result;
        String[] headMarkers = {
                "以下是", "注意：", "注意:", "说明：", "说明:",
                "解释：", "解释:", "处理结果：", "希望这"
        };
        for (String marker : headMarkers) {
            if (head.contains(marker)) {
                log.error("警告：AI 结果含解释性开头，降级为正则结果");
                return original;
            }
        }
        // AI 自述口吻：出现在任何位置都说明模型跑偏了
        String[] aiToneMarkers = {
                "我将按照", "我已将", "未发现敏感", "没有发现敏感",
                "无需脱敏", "原样返回", "以上是脱敏"
        };
        for (String marker : aiToneMarkers) {
            if (result.contains(marker)) {
                log.error("警告：AI 结果含模型自述语句，降级为正则结果");
                return original;
            }
        }

        // 长度异常检查：脱敏会把 2 个字的人名换成 8 个字符的占位符，
        // 原文人名多时长度翻倍属正常，阈值放宽到 3 倍 + 100，避免误判降级
        if (result.length() > original.length() * 3 + 100) {
            log.error("警告：AI 结果异常膨胀，降级为正则结果");
            return original;
        }

        return result;
    }

    /**
     * 独立版 AI 脱敏：直接用 Java HttpClient 调用 Ollama 的 /api/generate 接口。
     * 用于 main 方法测试，不需要启动 Spring 上下文。
     *
     * @param text       已经过正则脱敏的文本
     * @param ollamaBase Ollama 基础地址，如 http://localhost:11434
     * @param model      模型名称，如 llama3.2-3b-lowvram
     * @return 脱敏后的文本，失败时返回传入文本
     */
    public static String aiSanitizeStandalone(String text, String ollamaBase, String model) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", String.format(AI_SANITIZE_PROMPT, text));
            body.put("stream", false);
            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBase + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Ollama 返回非 200 状态: {}", response.statusCode());
                return text;
            }
            JsonNode root = mapper.readTree(response.body());
            String raw = root.path("response").asText("");
            log.info("[调试] AI 原始返回：\n{}", raw);

            return postProcess(raw, text);
        } catch (Exception e) {
            System.err.println("AI 脱敏失败: " + e.getMessage());
            return text;
        }
    }

    // ==================== 测试用 main 方法 ====================

    /**
     * 脱敏效果测试入口（不启动 Spring，不连接 Redis）。
     *
     * 用法：
     *   SanitizeService.main(new String[]{"samples/sample.txt"});
     *   或在 IDEA Program arguments 中传文件路径。
     *
     * 可选参数：
     *   --no-ai                    跳过 AI 脱敏，只做正则脱敏
     *   --ollama=http://host:port  指定 Ollama 地址（默认 http://localhost:11434）
     *   --model=模型名             指定模型（默认 llama3.2-3b-lowvram）
     */
    public static void main(String[] args) {
        String filePath = null;
        String ollamaBase = "http://localhost:11434";
        String model = "llama3.2-3b-lowvram";
        boolean aiEnabled = true;

        for (String arg : args) {
            if (arg.startsWith("--ollama=")) {
                ollamaBase = arg.substring("--ollama=".length());
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            } else if ("--no-ai".equals(arg)) {
                aiEnabled = false;
            } else if (!arg.startsWith("--")) {
                filePath = arg;
            }
        }
        if (filePath == null) {
            System.err.println("用法: SanitizeService <file> [--no-ai] [--ollama=http://host:port] [--model=name]");
            System.err.println("示例: SanitizeService samples/sample.txt --no-ai");
            return;
        }

        System.out.println("测试文件: " + filePath);
        System.out.println("Ollama:   " + ollamaBase);
        System.out.println("模型:     " + model);
        System.out.println("AI 脱敏:  " + (aiEnabled ? "启用" : "禁用"));
        System.out.println("==================== 脱敏测试开始 ====================");

        long totalStart = System.currentTimeMillis();

        try {
            TikaDocumentReader reader = new TikaDocumentReader(
                    new FileSystemResource(new File(filePath))
            );
            List<Document> documents = reader.get();

            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String original = doc.getText();

                // 正则脱敏计时
                long regexStart = System.currentTimeMillis();
                String regexSanitized = sanitize(original);
                long regexEnd = System.currentTimeMillis();

                // AI 脱敏计时
                String aiSanitized = regexSanitized;
                long aiStart = 0, aiEnd = 0;
                if (aiEnabled) {
                    aiStart = System.currentTimeMillis();
                    aiSanitized = aiSanitizeStandalone(regexSanitized, ollamaBase, model);
                    aiEnd = System.currentTimeMillis();
                }

                System.out.println("--- 文档片段 " + (i + 1) + " ---");
                System.out.println("【1. 原始内容】");
                System.out.println(original);
                System.out.println();
                System.out.println("【2. 正则脱敏后】");
                System.out.println(regexSanitized);
                System.out.println();
                System.out.println("【3. AI 脱敏后】");
                System.out.println(aiSanitized);
                System.out.println();

                // 输出耗时
                System.out.printf("耗时统计：正则脱敏 %d ms", (regexEnd - regexStart));
                if (aiEnabled) {
                    System.out.printf("，AI 脱敏 %d ms", (aiEnd - aiStart));
                }
                System.out.println();
                System.out.println();
            }

            long totalEnd = System.currentTimeMillis();
            System.out.println("==================== 脱敏测试结束 ====================");
            System.out.printf("总耗时：%d ms%n", (totalEnd - totalStart));

        } catch (Exception e) {
            System.err.println("测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
