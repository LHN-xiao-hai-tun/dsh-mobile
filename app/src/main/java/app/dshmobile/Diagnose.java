package app.dshmobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 连接诊断（B1 · v1.3.9）—— **只读探针 + 人话结论**。
 *
 * ── 为什么要有它
 *   v1.3.9 之前，"连不上"只给一句提示，用户完全不知道该查什么。而分诊需要的维度**本来就都在**：
 *   手机有没有网 · 地址对不对 · 端口通不通 · 证书确认过没有 · 服务是不是 401 · 电脑 IP 是不是漂了。
 *   这个类把它们逐项探一遍，并给「下一步怎么办」。
 *
 * ── 三条硬约束（别改）
 *   ① **只读**：不写任何配置、不改信任表、不落盘、不向任何第三方发请求（只连用户自己填的那个地址）。
 *   ② **不因为诊断就放宽安全**：探证书时用的 TrustManager **读完指纹就抛异常**（见
 *      {@link #certFingerprintOf}）—— 它绝不"信任所有"，也不会真的建立一条被放行的连接。
 *      HTTP 探针复用 {@link Downloader} 的同一套信任口径（系统信任 → 失败才回落用户指纹）。
 *   ③ **判据是纯函数**（{@link #levelForHttp} / {@link #certVerdict} / {@link #adviceForPortClosed} /
 *      {@link #overallLevel}）—— 它们进单测（`DiagnoseTest`），Activity 只负责跑 I/O 和画界面。
 */
final class Diagnose {

    private Diagnose() {
    }

    /* ══════════════ 级别 ══════════════ */

    static final int OK = 0;
    static final int INFO = 1;
    static final int WARN = 2;
    static final int BAD = 3;

    /** 一组级别取最坏（INFO 不拉低结论） */
    static int overallLevel(int... levels) {
        boolean warn = false;
        for (int l : levels) {
            if (l == BAD) return BAD;
            if (l == WARN) warn = true;
        }
        return warn ? WARN : OK;
    }

    /* ══════════════ 纯判据（可单测） ══════════════ */

    /** HTTP 状态码 → 级别：2xx 正常；401/403 服务在但要登录；404 端口通了但不是 DSH；5xx 服务端报错 */
    static int levelForHttp(int code) {
        if (code >= 200 && code < 300) return OK;
        if (code == 401 || code == 403) return WARN;
        if (code >= 500) return BAD;
        return WARN;                     // 3xx / 404 / 其它 4xx
    }

    static final int CERT_OK = 0;
    static final int CERT_UNTRUSTED = 1;
    static final int CERT_CHANGED = 2;
    static final int CERT_NONE = 3;
    /** 连接成功、但靠的是**系统信任链**（正规 CA）⇒ 用户本来就不需要确认指纹 */
    static final int CERT_SYSTEM = 4;
    /** 这次连接压根没走到证书校验（比如端口就不通） */
    static final int CERT_SKIPPED = 5;

    /**
     * 证书三态（与下载侧**同口径**：比对用 {@link Downloader#fingerprintMatches}）。
     *
     * @param remembered 本机记过的指纹（未记过为 null/空）
     * @param actual     这次真的看到的指纹（读不到为空）
     */
    static int certVerdict(String remembered, String actual) {
        if (actual == null || actual.trim().isEmpty()) return CERT_NONE;
        if (remembered == null || remembered.trim().isEmpty()) return CERT_UNTRUSTED;
        return Downloader.fingerprintMatches(remembered, actual) ? CERT_OK : CERT_CHANGED;
    }

    /**
     * **探完之后**的证书结论 —— 这里比单看指纹多两个信息：这次连接到底成没成、失败是不是证书造成的。
     *
     * ⚠️ 为什么必须这么分：连接**成功**时说明证书已被接受（系统信任链，或用户确认过的指纹）；
     *    此时若本机没记过指纹，绝不能报"未确认"（那是正规 CA 站点的正常情况）；
     *    反过来连接**因证书失败**时，才该去比指纹、并区分"没确认过"与"指纹变了"。
     */
    static int certVerdictAfterProbe(boolean connected, boolean certFailed,
                                     String remembered, String actual) {
        if (connected) {
            boolean pinned = remembered != null && !remembered.trim().isEmpty();
            return (pinned && Downloader.fingerprintMatches(remembered, actual)) ? CERT_OK : CERT_SYSTEM;
        }
        if (!certFailed) return CERT_SKIPPED;
        return certVerdict(remembered, actual);
    }

    static int levelForCert(int verdict) {
        switch (verdict) {
            case CERT_OK:
            case CERT_SYSTEM:
                return OK;
            case CERT_UNTRUSTED:
                return WARN;                   // 首次连接本来就要确认一次，不算故障
            case CERT_CHANGED:
                return BAD;                    // 可能换证，也可能中间人
            default:
                return WARN;
        }
    }

    /**
     * 主端口连不上时的**主建议**：
     *   ① 配的端口本身就是常用端口（3082/3081/3080）、而同机另一个常用端口通着 ⇒ 多半**端口写错**；
     *   ② 否则按内外网分开查：
     *      内网 ⇒ "服务在跑吗 / 防火墙 / 同一网络吗"；公网 ⇒ "地址对不对 / 服务是否对外"。
     *
     * ⚠️ 为什么必须带上 {@code configuredPortIsCommon}：实测（模拟器 · 配 8097 而 3082/3080 通着）
     *    如果只看"另一个端口通"，会把「服务停了」误判成「端口写错」——**建议错了比不诊断更糟**。
     *    那种情况改为在下面用 {@link #softHintForOtherPort} 附一句"顺带"。
     */
    static int adviceForPortClosed(boolean isPrivateHost, boolean otherPortOpen, boolean configuredPortIsCommon) {
        if (otherPortOpen && configuredPortIsCommon) return R.string.diag_adv_other_port;
        return isPrivateHost ? R.string.diag_adv_private_closed : R.string.diag_adv_public_closed;
    }

    /** 附带软提示：配的端口不是常用端口、同机却有常用端口通着 ⇒ "DSH 要是在那上面就改过去"；无需提示返回 0 */
    static int softHintForOtherPort(boolean otherPortOpen, boolean configuredPortIsCommon) {
        return (otherPortOpen && !configuredPortIsCommon) ? R.string.diag_hint_other_port : 0;
    }

    /** 该端口是不是本 App 惯用的那几个（3082/3081/3080） */
    static boolean isCommonPort(int port) {
        for (int p : COMMON_PORTS) {
            if (p == port) return true;
        }
        return false;
    }

    /** 从「同机端口探测结果」里挑出第一个通的（给上面那条建议用）；没有则返回 -1 */
    static int firstOpenPort(int[] ports, boolean[] open) {
        if (ports == null || open == null) return -1;
        for (int i = 0; i < ports.length && i < open.length; i++) {
            if (open[i]) return ports[i];
        }
        return -1;
    }

    /* ══════════════ 探针（真 I/O） ══════════════ */

    /** 取 URL 的端口（缺省按 scheme 补 80/443） */
    static int portOf(String url) {
        try {
            URL u = new URL(url);
            int p = u.getPort();
            if (p > 0) return p;
            return "https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80;
        } catch (Exception e) {
            return -1;
        }
    }

    static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** 手机当前有没有可用网络（只读系统状态，不申请任何权限） */
    static boolean hasNetwork(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;                 // 拿不到就当有，别误报
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /** 网络类型名（WIFI / MOBILE…）用于展示；拿不到返回 "" */
    static String networkType(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || ni.getTypeName() == null) return "";
            return ni.getTypeName();
        } catch (Exception e) {
            return "";
        }
    }

    /** TCP 能不能连上（连上即断，不发任何应用层数据） */
    static boolean tcpOpen(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty() || port <= 0) return false;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
    }

    /** 一次 HTTP(S) 探测的结果 */
    static final class HttpProbe {
        int code = -1;                 // -1 = 没拿到响应
        String error = "";             // 失败原因（英文类名 + 消息，只进报告/日志）
        Throwable cause;               // 失败异常本体（界面文案用 Downloader.userMessageFor 翻人话）
        String fingerprint = "";       // 服务端证书指纹（拿得到才有）
        boolean certFailed = false;    // 失败是不是证书类
    }

    /**
     * 请求一次配置地址，拿状态码。**读到响应头就断开**（不下载正文，最多读 1 字节确认可读）。
     *
     * 信任口径与下载侧一致（系统信任 → 失败才回落用户已确认的指纹），所以它的结论
     * 和"App 真正连的时候"是一致的。
     */
    static HttpProbe httpStatus(Context ctx, String url, int timeoutMs) {
        HttpProbe out = new HttpProbe();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);         // 重定向本身就是要报告的事实
            c.setRequestProperty("User-Agent", "DSHMobile-diagnose");
            if (c instanceof HttpsURLConnection) {
                HttpsURLConnection h = (HttpsURLConnection) c;
                h.setSSLSocketFactory(Downloader.sslContextFor(ctx, hostOf(url)).getSocketFactory());
            }
            out.code = c.getResponseCode();
            out.fingerprint = peerFingerprint(c);
            try {                                            // 只读 1 字节：确认这条连接真能取数据
                InputStream in = (out.code >= 400) ? c.getErrorStream() : c.getInputStream();
                if (in != null) { in.read(); in.close(); }
            } catch (Exception ignored) { }
            return out;
        } catch (Exception e) {
            out.cause = e;
            out.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
            out.certFailed = Downloader.certCause(e) != null;
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从已建立的连接上取服务端证书指纹（拿不到返回 ""） */
    private static String peerFingerprint(HttpURLConnection c) {
        try {
            if (c instanceof HttpsURLConnection) {
                java.security.cert.Certificate[] chain = ((HttpsURLConnection) c).getServerCertificates();
                if (chain != null && chain.length > 0) {
                    return Downloader.fingerprintOf(chain[0].getEncoded());
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * **只读**地取一下该 host:port 的证书指纹 —— 用于"连接被证书挡下"时告诉用户**看到的到底是什么**。
     *
     * ⚠️ 这里的 TrustManager **读完指纹立刻抛异常**：它既不放行连接，也不是 trust-all
     *    （连上握手 → 记指纹 → 抛 ⇒ 不会有一条被"信任"的连接继续用于取数据）。
     *    ⇒ 拿不到指纹就返回 ""，界面上如实说"读不到证书"。
     */
    static String certFingerprintOf(String host, int port, int timeoutMs) {
        final String[] seen = new String[1];
        SSLSocket s = null;
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("diagnose: 不参与客户端证书");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain != null && chain.length > 0) {
                        seen[0] = Downloader.fingerprintOf(chain[0].getEncoded());
                    }
                    throw new CertificateException("diagnose: 只读指纹，不建立信任连接");
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            s = (SSLSocket) sc.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.startHandshake();
        } catch (Exception ignored) {
            // 预期就会抛（上面故意抛的）——指纹在抛之前已经记下来了
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
        return seen[0] == null ? "" : seen[0];
    }

    /** 本 App 惯用的三个端口（与 LanScan 同口径） */
    static final int[] COMMON_PORTS = {3082, 3081, 3080};

    /** 把 HTTP 状态码翻成一句话（资源 id + 参数由调用方按级别选择） */
    static int httpMessageRes(int code) {
        if (code >= 200 && code < 300) return R.string.diag_http_ok;
        if (code == 401 || code == 403) return R.string.diag_http_auth;
        if (code == 404) return R.string.diag_http_404;
        if (code >= 500) return R.string.diag_http_5xx;
        return R.string.diag_http_other;
    }

    /** 报告里的一行（供"复制诊断报告"用） */
    static String line(String label, String verdict, String advice) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("：").append(verdict);
        if (advice != null && !advice.isEmpty()) sb.append("  →  ").append(advice);
        return sb.toString();
    }

    /** 历史地址里除当前配置之外的（最多取几条，免得诊断太久）；`max <= 0` 就是"一条都不要" */
    static List<String> otherHistory(List<String> history, String current, int max) {
        List<String> out = new ArrayList<>();
        if (history == null || max <= 0) return out;
        String cur = current == null ? "" : current.trim();
        for (String h : history) {
            if (h == null) continue;
            String t = h.trim();
            if (t.isEmpty() || t.equalsIgnoreCase(cur)) continue;
            out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 地址太长时截断显示（诊断行里别把界面撑爆） */
    static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 端口列表转成展示串（3082/3081/3080 → "3082、3081、3080"） */
    static String portsLabel(int[] ports) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(ports[i]);
        }
        return sb.toString();
    }

    /** 小写化工具（避免各处写 Locale） */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
