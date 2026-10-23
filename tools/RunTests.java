import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 不依赖 mvn / junit-platform 的测试运行器。
 *
 * 为什么需要：本机 mvn 用不了（离线库缺 spring-boot-starter-parent，联网又 PKIX 证书失败），
 * 而 junit-platform-launcher 只有 1.12.2（配 JUnit 5），与项目里的 Jupiter 6.0.3 不兼容。
 * 这个运行器只做一件事：反射调用带 @Test 的方法，@TempDir 参数自动塞一个临时目录。
 */
public class RunTests {
    public static void main(String[] a) throws Exception {
        Class<?> cls = Class.forName(a[0]);
        int pass = 0, fail = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Test.class)) continue;
            m.setAccessible(true);
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object inst = ctor.newInstance();
            Object[] args = new Object[m.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                if (m.getParameterTypes()[i] == Path.class) {
                    args[i] = Files.createTempDirectory("xt");
                }
            }
            try {
                m.invoke(inst, args);
                System.out.println("  PASS  " + m.getName());
                pass++;
            } catch (Throwable t) {
                Throwable c = t.getCause() == null ? t : t.getCause();
                System.out.println("  FAIL  " + m.getName() + "  →  " + c);
                fail++;
            }
        }
        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        System.exit(fail > 0 ? 1 : 0);
    }
}
