package com.zhu.docbase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正则脱敏规则的单元测试。
 *
 * <p>这些用例全部针对 {@link SanitizeService#sanitize(String)}，它是纯静态方法，
 * 不启动 Spring 上下文、不连接 Redis / Ollama，所以可以在 CI 里直接跑。
 *
 * <p>断言方式刻意写成「原值消失 + 占位符出现」两条同时成立：
 * 只断言占位符存在，无法发现"值没被吃掉但旁边多贴了个占位符"这类漏网。
 */
class SanitizeServiceTest {

    @Test
    @DisplayName("手机号被替换为 [PHONE]")
    void phoneIsMasked() {
        String out = SanitizeService.sanitize("联系电话 13812345678 请勿外传");
        assertFalse(out.contains("13812345678"), "原始手机号仍然存在：" + out);
        assertTrue(out.contains("[PHONE]"), "未出现 [PHONE] 占位符：" + out);
    }

    @Test
    @DisplayName("邮箱被替换为 [EMAIL]")
    void emailIsMasked() {
        String out = SanitizeService.sanitize("邮箱 zhangsan@example.com");
        assertFalse(out.contains("zhangsan@example.com"), "原始邮箱仍然存在：" + out);
        assertTrue(out.contains("[EMAIL]"), "未出现 [EMAIL] 占位符：" + out);
    }

    @Test
    @DisplayName("「密码是 xxx」这种中文动词句式也能命中")
    void credentialWithChineseVerbIsMasked() {
        // 这是历史 bug 的回归用例：旧版分隔符只认 : 和 =，"密码是 xxx" 会整条漏网
        String out = SanitizeService.sanitize("数据库密码是 Test@2024");
        assertFalse(out.contains("Test@2024"), "密码值仍然存在：" + out);
        assertTrue(out.contains("[CREDENTIAL]"), "未出现 [CREDENTIAL] 占位符：" + out);
    }

    @Test
    @DisplayName("连接串里的 user / password 参数被替换为 ***")
    void jdbcCredentialIsMasked() {
        String out = SanitizeService.sanitize("jdbc:mysql://host:3306/db?user=root&password=abc123");
        assertFalse(out.contains("root"), "用户名仍然存在：" + out);
        assertFalse(out.contains("abc123"), "密码仍然存在：" + out);
        assertTrue(out.contains("user=***"), "user 参数未替换：" + out);
    }

    @Test
    @DisplayName("裸 JWT 被替换为 [JWT]")
    void jwtIsMasked() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl";
        String out = SanitizeService.sanitize("凭证 " + jwt);
        assertFalse(out.contains(jwt), "JWT 仍然存在：" + out);
        assertTrue(out.contains("[JWT]"), "未出现 [JWT] 占位符：" + out);
    }

    @Test
    @DisplayName("PEM 私钥整块被替换为 [PRIVATE_KEY]")
    void privateKeyIsMasked() {
        String pem = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA1234567890abcdef
                -----END RSA PRIVATE KEY-----""";
        String out = SanitizeService.sanitize(pem);
        assertFalse(out.contains("MIIEowIBAAKCAQEA"), "私钥正文仍然存在：" + out);
        assertTrue(out.contains("[PRIVATE_KEY]"), "未出现 [PRIVATE_KEY] 占位符：" + out);
    }

    @Test
    @DisplayName("身份证号被替换为 [ID_CARD]")
    void idCardIsMasked() {
        String out = SanitizeService.sanitize("身份证 450102199001011234");
        assertFalse(out.contains("450102199001011234"), "身份证号仍然存在：" + out);
        assertTrue(out.contains("[ID_CARD]"), "未出现 [ID_CARD] 占位符：" + out);
    }

    @Test
    @DisplayName("私网 IP 被替换为 [IP]")
    void privateIpIsMasked() {
        String out = SanitizeService.sanitize("系统部署在 192.168.1.65 这台服务器上");
        assertFalse(out.contains("192.168.1.65"), "IP 仍然存在：" + out);
        assertTrue(out.contains("[IP]"), "未出现 [IP] 占位符：" + out);
    }

    @Test
    @DisplayName("版本号不会被误判成 IP")
    void versionNumberIsNotTreatedAsIp() {
        // sanitizeIp 的核心设计：四段点分十进制既可能是 IP 也可能是版本号，
        // 靠前缀关键词区分。"Spring Boot 1.2.3.4" 里的 1.2.3.4 必须保留。
        String out = SanitizeService.sanitize("Spring Boot 1.2.3.4");
        assertTrue(out.contains("1.2.3.4"), "版本号被误删：" + out);
    }

    @Test
    @DisplayName("普通中文文本不受影响")
    void plainTextIsUntouched() {
        String text = "这是一段普通的中文说明文字，不含任何敏感信息。";
        assertEquals(text, SanitizeService.sanitize(text));
    }
}
