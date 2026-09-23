package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 网页下载（B2）里**安全相关判据**的回归网（v1.3.8 补）。
 *
 * ── 为什么只测这几个函数
 *   它们决定三件"判错就出事"的事：
 *     ① {@link Downloader#isAllowedScheme} —— 白名单之外一律不取（挡 `file:` / `javascript:` 这类伪协议）；
 *     ② {@link Downloader#shouldReplayCookie} —— **什么时候把 DSH 的登录凭据带出去**；
 *     ③ {@link Downloader#fingerprintMatches} —— 证书指纹认不认（下载侧与 WebView 侧必须同口径）。
 *   取文件的那段（网络 + SAF 写入）需要真机/模拟器，不在本文件覆盖范围内。
 *
 * ⚠️ ⛔ **本文件不测"信任所有证书"那种实现** —— 设计上就不存在这条路径
 *   （{@code Downloader.trustManager} 是"先系统信任，失败才回落用户指纹"）。
 */
public class DownloaderTest {

    /* ───────── ① scheme 白名单 ───────── */

    @Test
    public void onlyHttpAndHttpsAreAllowed() {
        assertTrue(Downloader.isAllowedScheme("http://192.168.1.5:3082/x.bin"));
        assertTrue(Downloader.isAllowedScheme("https://10.0.2.2:8443/x"));
        assertTrue("大小写不敏感", Downloader.isAllowedScheme("HTTP://host/x"));
        assertTrue("前后空白容忍", Downloader.isAllowedScheme("  https://host/x  "));
    }

    @Test
    public void pseudoSchemesAreRejected() {
        // 网页可以给下载链接挂任意 scheme —— 这些一律不许我们替它取
        assertFalse(Downloader.isAllowedScheme("file:///etc/hosts"));
        assertFalse(Downloader.isAllowedScheme("content://com.android.providers/x"));
        assertFalse(Downloader.isAllowedScheme("javascript:alert(1)"));
        assertFalse(Downloader.isAllowedScheme("data:text/html,<h1>x"));
        assertFalse(Downloader.isAllowedScheme("ftp://host/x"));
        assertFalse(Downloader.isAllowedScheme(null));
        assertFalse(Downloader.isAllowedScheme(""));
        assertFalse("只是把 http 当子串", Downloader.isAllowedScheme("nothttp://host"));
    }

    /* ───────── ② Cookie 回放（凭据外带） ───────── */

    @Test
    public void cookieIsReplayedOnlyForTheConfiguredHost() {
        String configured = "http://192.168.10.44:3082";
        assertTrue("同 host 才带凭据", Downloader.shouldReplayCookie(configured, "http://192.168.10.44:3082/a.bin"));
        assertTrue("端口不同也算同 host", Downloader.shouldReplayCookie(configured, "http://192.168.10.44:9999/a.bin"));
        assertTrue("协议不同也算同 host", Downloader.shouldReplayCookie(configured, "https://192.168.10.44/a.bin"));
    }

    @Test
    public void cookieIsNotReplayedForForeignHosts() {
        String configured = "http://192.168.10.44:3082";
        assertFalse("第三方 host 绝不能带", Downloader.shouldReplayCookie(configured, "http://evil.example.com/a.bin"));
        assertFalse(Downloader.shouldReplayCookie(configured, "https://192.168.10.45/a.bin"));
        assertFalse("相似前缀不算同 host", Downloader.shouldReplayCookie(configured, "http://192.168.10.44.evil.com/a"));
        assertFalse(Downloader.shouldReplayCookie(null, "http://192.168.10.44/a"));
        assertFalse(Downloader.shouldReplayCookie(configured, null));
        assertFalse("没有 scheme 取不到 host ⇒ 不带", Downloader.shouldReplayCookie("192.168.10.44:3082", "http://192.168.10.44/a"));
    }

    /* ───────── ③ 证书指纹比对 ───────── */

    @Test
    public void fingerprintMatchIsCaseInsensitiveAndExact() {
        String fp = "3C:28:9B:53:6A:28:19:91:40:3E:11:9F:90:12:CC:72:33:81:DB:D8:C8:45:9B:6D:D4:95:46:9F:5D:F6:3B:F2";
        assertTrue(Downloader.fingerprintMatches(fp, fp));
        assertTrue("大小写不敏感", Downloader.fingerprintMatches(fp, fp.toLowerCase()));
        assertFalse("少一段就不算一致", Downloader.fingerprintMatches(fp, fp.substring(0, fp.length() - 3)));
        assertFalse("换一张证书 ⇒ 必须不匹配", Downloader.fingerprintMatches(fp,
                "D4:87:C9:DE:E8:DE:E6:F4:C3:75:29:BF:F2:97:BB:47:C5:F1:D8:57:C2:C4:F9:40:12:48:C1:8D:B6:EF:75:18"));
    }

    @Test
    public void missingTrustedFingerprintIsNeverAMatch() {
        // ⛔ 关键安全性质：**没记录过指纹 ⇒ 一律不算匹配**（绝不能"空就等于放行"）
        assertFalse(Downloader.fingerprintMatches(null, "AA:BB"));
        assertFalse(Downloader.fingerprintMatches("", "AA:BB"));
        assertFalse(Downloader.fingerprintMatches("   ", "AA:BB"));
        assertFalse(Downloader.fingerprintMatches("AA:BB", null));
        assertFalse(Downloader.fingerprintMatches("AA:BB", ""));
    }

    /* ───────── ④ 文件名建议（别带路径分隔符） ───────── */

    @Test
    public void nameComesFromContentDispositionFirst() {
        assertEquals("report.pdf",
                Downloader.nameFrom("http://h/x.bin", "attachment; filename=\"report.pdf\""));
        assertEquals("data.csv",
                Downloader.nameFrom("http://h/x.bin", "attachment; filename=data.csv"));
    }

    @Test
    public void nameFallsBackToUrlPath() {
        assertEquals("file.bin", Downloader.nameFrom("http://h:3082/dir/file.bin", null));
        assertEquals("file.bin", Downloader.nameFrom("http://h/dir/file.bin?a=1&b=2", null));
        assertEquals("download.bin", Downloader.nameFrom("http://h/", null));
        assertEquals("download.bin", Downloader.nameFrom(null, null));
    }

    @Test
    public void nameNeverCarriesPathSeparators() {
        // 防"建议一个带 ../ 的名字"（SAF 会以它为默认值）
        String n = Downloader.nameFrom("http://h/x", "attachment; filename=\"../../etc/passwd\"");
        assertFalse("不许含 /", n.contains("/"));
        assertFalse("不许含 \\", n.contains("\\"));
        assertFalse("不许含 ..", n.contains(".."));
    }

    /* ───────── ⑤ hostOf（Cookie 判据的基础） ───────── */

    @Test
    public void hostOfHandlesCommonForms() {
        assertEquals("192.168.10.44", Downloader.hostOf("http://192.168.10.44:3082/a"));
        assertEquals("nas.local", Downloader.hostOf("https://nas.local/x"));
        assertEquals("example.com", Downloader.hostOf("http://user:pass@example.com:8080/x"));
        assertEquals("example.com", Downloader.hostOf("https://example.com"));
        assertEquals("fe80::1", Downloader.hostOf("http://[fe80::1]:8080/x"));
        assertEquals("", Downloader.hostOf("192.168.10.44:3082"));   // 无 scheme ⇒ 取不到
        assertEquals("", Downloader.hostOf(null));
    }

    /* ───────── ⑥ v1.3.9 补：证书错误拆链 + 人类可读大小（A2 实测逼出来的两条） ───────── */

    @Test
    public void certificateCauseIsUnwrappedFromHandshakeException() {
        // 实测复现的真实形状：TLS 握手把我们的 CertificateException 包进 SSLHandshakeException
        // ⇒ 不拆链的话，用户看到的是英文类名「下载失败：SSLHandshakeException」
        javax.net.ssl.SSLHandshakeException wrapped = new javax.net.ssl.SSLHandshakeException("握手失败");
        wrapped.initCause(new java.security.cert.CertificateException("这台服务器用的是系统不信任的证书"));
        assertEquals("这台服务器用的是系统不信任的证书",
                Downloader.certCause(wrapped).getMessage());
        assertEquals("本身就是证书异常也要认", "x",
                Downloader.certCause(new java.security.cert.CertificateException("x")).getMessage());
        // ⚠️ SSLHandshakeException 只有 (String) 构造器 ⇒ cause 只能事后 initCause 挂上
        javax.net.ssl.SSLHandshakeException middle = new javax.net.ssl.SSLHandshakeException("middle");
        middle.initCause(new java.security.cert.CertificateException("deep"));
        assertEquals("包两层也要拆到", "deep",
                Downloader.certCause(new RuntimeException(middle)).getMessage());
    }

    @Test
    public void certificateCauseIsNullForUnrelatedFailures() {
        assertNull("普通 IO 失败不该被当成证书问题", Downloader.certCause(new java.io.IOException("boom")));
        assertNull(Downloader.certCause(new RuntimeException("boom")));
        assertNull(Downloader.certCause(null));
    }

    @Test
    public void certificateCauseSurvivesCyclicCause() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);                       // 人为造环（Java 只禁自引用，不禁两节点环）
        assertNull("环形 cause 不能死循环", Downloader.certCause(a));
    }

    @Test
    public void humanSizeNeverSaysZeroForNonZeroBytes() {
        // 实测缺陷：95 B 的文件提示「已保存（0 KB）」
        assertEquals("95 B", Downloader.humanSize(95));
        assertEquals("1 B", Downloader.humanSize(1));
        assertEquals("1023 B", Downloader.humanSize(1023));
        assertEquals("1 KB", Downloader.humanSize(1024));
        assertEquals("1 KB", Downloader.humanSize(1536));
        assertEquals("200 MB", Downloader.humanSize(200L * 1024 * 1024));
        assertEquals("1 GB", Downloader.humanSize(1024L * 1024 * 1024));
        assertEquals("0 B", Downloader.humanSize(0));
        assertEquals("0 B", Downloader.humanSize(-5));
    }

    @Test
    public void userFacingMessageNeverLeaksJavaClassNames() {
        // 实测看到的两种泄漏：
        //   ① 证书原因被 TLS 包成 SSLHandshakeException ⇒ 界面上就是「下载失败：SSLHandshakeException」
        //   ② 主机名不匹配 ⇒ 「下载失败：SSLPeerUnverifiedException」
        assertEquals("主机名不匹配要说人话", R.string.dl_err_hostname,
                Downloader.userMessageFor(
                        new javax.net.ssl.SSLPeerUnverifiedException("Hostname 192.168.10.44 not verified")));
        assertEquals("TLS 层问题要说人话", R.string.dl_err_tls,
                Downloader.userMessageFor(new javax.net.ssl.SSLHandshakeException("handshake failed")));
        assertEquals(R.string.dl_err_unknown_host,
                Downloader.userMessageFor(new java.net.UnknownHostException("nope")));
        assertEquals(R.string.dl_err_timeout,
                Downloader.userMessageFor(new java.net.SocketTimeoutException("slow")));
        assertEquals("ConnectException 不能被笼统的 IOException 抢走", R.string.dl_err_connect,
                Downloader.userMessageFor(new java.net.ConnectException("refused")));
        assertEquals(R.string.dl_err_io, Downloader.userMessageFor(new java.io.IOException("io")));
        assertEquals(R.string.dl_err_generic, Downloader.userMessageFor(new RuntimeException("?")));
        assertEquals(R.string.dl_err_generic, Downloader.userMessageFor(null));
    }
}
