package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 导出日志的**脱敏**回归网（v1.3.6 补）。
 *
 * ── 为什么先给这一块补测试
 *   脱敏是本项目**唯一出过两次真 bug 的地方**（见 LogExporter 的 v1/v2/v3 注释）：
 *     v1 误伤 Android 内部 `token=`；v2 以为"长度能区分"，被真机日志证伪（真值是 30 字符的对象引用）。
 *   两次都靠**用户导出的真机日志**才发现 ⇒ 说明当时**没有能自动重跑的判据**。
 *   这些用例把当时的真实样本固化成断言，以后改正则立刻能知道有没有回归。
 *
 * ⚠️ 覆盖边界（如实说）：本文件是 **JVM 单测**，跑在 Java 17 上 ——
 *   它**测不出**「用了高于 minSdk 的 API」这类问题（Java 17 里那个方法存在）。
 *   那类问题由 `lint` 闸门（`app/build.gradle` 里 NewApi 已提为 fatal）+ 真机/模拟器实测来管。
 */
public class LogExporterRedactTest {

    /* ───────── ① 不许误伤：Android 内部字段（v1 的真实教训） ───────── */

    @Test
    public void binderProxyTokenIsNotRedacted() {
        // 真机 logcat 原样样本：值是 Java 对象引用（30 字符），含 '.' 与 '@'
        String line = "ActivityRecord{de34fd token=android.os.BinderProxy@41fc4a7 t8 f}";
        assertEquals("Android 内部 token 不该被打码", line, LogExporter.redact(line));
    }

    @Test
    public void dottedAndAtValuesAreNotSecrets() {
        // 字符集判据：值里出现 '.' / '@' ⇒ 不是密钥
        assertFalse(LogExporter.redact("token=com.example.SomeClass@1a2b3c").contains("<已脱敏>"));
        assertFalse(LogExporter.redact("secret=android.os.BinderProxy@41fc4a7").contains("<已脱敏>"));
    }

    /* ───────── ② 必须脱敏：真密钥（且**不能把值留在结果里**） ───────── */

    @Test
    public void longTokenIsRedactedAndValueIsGone() {
        String secret = "KF_MWSHabcdefgh1234";
        String out = LogExporter.redact("token=" + secret);
        assertEquals("token=<已脱敏>", out);
        assertFalse("值绝不能留在结果里（v2 曾犯过这个错）", out.contains(secret));
    }

    @Test
    public void shortSecretsAreRedactedToo() {
        // pin / pwd / password 类：值只要 ≥2 位就脱敏（这类本来就短）
        assertEquals("pin=<已脱敏>", LogExporter.redact("pin=8899"));
        assertEquals("password=<已脱敏>", LogExporter.redact("password=abc"));
        assertEquals("sessdata=<已脱敏>", LogExporter.redact("sessdata=XY12z"));
    }

    @Test
    public void keyCaseIsIgnored() {
        assertEquals("TOKEN=<已脱敏>", LogExporter.redact("TOKEN=abcdefghijklmn"));
        assertEquals("Token=<已脱敏>", LogExporter.redact("Token=abcdefghijklmn"));
    }

    @Test
    public void apiKeyAndAccessKeyAreCovered() {
        assertTrue(LogExporter.redact("api_key=abcdefgh").contains("<已脱敏>"));
        assertTrue(LogExporter.redact("apiKey=abcdefgh").contains("<已脱敏>"));
        assertTrue(LogExporter.redact("access_key=abcdefgh").contains("<已脱敏>"));
    }

    /* ───────── ③ URL 尾巴（?token= / #pin=） ───────── */

    @Test
    public void urlTailTokenIsRedacted() {
        assertEquals("http://192.168.1.5:3082/?token=<已脱敏>",
                LogExporter.redact("http://192.168.1.5:3082/?token=abc12345"));
        assertTrue(LogExporter.redact("http://h/#pin=8899").contains("pin=<已脱敏>"));
    }

    /* ───────── ④ 保留排障必需的信息：IP 与端口 ───────── */

    @Test
    public void plainAddressIsUntouched() {
        String url = "http://192.168.10.44:3082";
        assertEquals("IP 与端口必须保留（排障必需）", url, LogExporter.redact(url));
        assertEquals(url, LogExporter.redactUrl(url));
    }

    /* ───────── ⑤ redactUrl：干掉 userinfo，别的都留 ───────── */

    @Test
    public void userInfoIsRedactedButHostPortKept() {
        assertEquals("http://<已脱敏>@192.168.1.5:3082/x",
                LogExporter.redactUrl("http://user:pass@192.168.1.5:3082/x"));
    }

    /* ───────── ⑥ 边界：null / 空 / 幂等 ───────── */

    @Test
    public void nullAndEmptyAreSafe() {
        assertNull(LogExporter.redact(null));
        assertEquals("", LogExporter.redact(""));
        assertNull(LogExporter.redactUrl(null));
        assertEquals("", LogExporter.redactUrl(""));
    }

    @Test
    public void redactIsIdempotent() {
        // build() 最后会对整段日志**再脱一次** ⇒ 二次脱敏不得改变结果（否则会越脱越花）
        String once = LogExporter.redact("token=KF_MWSHabcdefgh1234 与 pin=8899 以及 http://192.168.1.5:3082/?token=abc12345");
        String twice = LogExporter.redact(once);
        assertEquals("二次脱敏必须等于一次脱敏", once, twice);
        assertNotNull(once);
    }

    @Test
    public void chinesePlaceholderIsExactlyAsDocumented() {
        // 占位符文案是对用户可见的（导出文件里），钉住它，别在重构中被改掉
        assertTrue(LogExporter.redact("token=abcdefghijklmn").endsWith("<已脱敏>"));
    }
}
