package app.dshmobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 网页下载的「应用内落盘」（v1.3.8 · 对应设计文档 §4.3 B2）。
 *
 * ── 三条口径（都是被真问题逼出来的，别改）
 *  ① **不申请任何权限**：走 SAF（用户选落点）→ 仍维持「零运行时权限」。
 *     ⛔ 不用 DownloadManager 写公共目录（API 26–28 要 `WRITE_EXTERNAL_STORAGE`）。
 *  ② **证书只认用户确认过的**：先试**系统信任**（正规 CA），失败才回落到
 *     `Prefs.trustedFingerprint()` 里那个**用户亲手核对过的指纹**。
 *     ⚠️ 不是"只认指纹" —— 那会把 CA 签发的正常 https 也拒掉（TOFU 只在系统信任失败时才记录）；
 *     更不是"信任所有"。拿不到指纹就**中止**。
 *  ③ **只写用户当次选定的那个 URI**，流式写入（不整个读进内存）、有大小上限、可取消、
 *     **绝不自动打开**（自动打开下载来的 APK 就是"下载即安装"的跳板）。
 *
 * 安全相关的判据都抽成了**纯函数**（见 {@link #isAllowedScheme} / {@link #shouldReplayCookie} /
 * {@link #fingerprintMatches}）—— 这样它们能进单测（`DownloaderTest`）。
 */
final class Downloader {

    private static final String TAG = "DSHMobile";
    /** 单文件大小上限（超过即中止，避免把磁盘/流量吃满） */
    static final long MAX_BYTES = 200L * 1024 * 1024;
    /** 最多跟随几次重定向；每一跳都重新校验 scheme / host / 证书 */
    static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private Downloader() {
    }

    /** 进度回调（都在后台线程触发，调用方自己切主线程） */
    interface Progress {
        void onProgress(long read, long total);   // total <= 0 表示服务端没给 Content-Length
        void onDone(long bytes);
        void onError(String message);
    }

    /* ══════════════ 纯函数（可单测） ══════════════ */

    /**
     * 只允许 http / https。⛔ 显式挡掉 `file:` / `content:` / `javascript:` 这类伪协议 ——
     * 网页可以给下载链接挂任意 scheme，我们**只替用户取网络资源**。
     */
    static boolean isAllowedScheme(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /**
     * 要不要给这个下载请求带上 Cookie？
     *
     * 判据：**下载地址与用户配置的 DSH 地址是同一个 host** 才带。
     * 理由：Cookie 里可能有 DSH 的登录态；页面若链到第三方 host，**不该把凭据带过去**。
     * （WebView 的 Cookie 本就是按域存的，这里是**第二道**防线。）
     */
    static boolean shouldReplayCookie(String configuredUrl, String downloadUrl) {
        if (configuredUrl == null || downloadUrl == null) return false;
        String a = hostOf(configuredUrl);
        String b = hostOf(downloadUrl);
        return !a.isEmpty() && a.equals(b);
    }

    /** 证书指纹是否与用户已确认过的一致（大小写不敏感；空值一律视为不匹配） */
    static boolean fingerprintMatches(String trusted, String actual) {
        if (trusted == null || actual == null) return false;
        String t = trusted.trim();
        String a = actual.trim();
        return !t.isEmpty() && t.equalsIgnoreCase(a);
    }

    /** 从 URL / Content-Disposition 推一个建议文件名（SAF 只把它当默认值，用户可改） */
    static String nameFrom(String url, String contentDisposition) {
        String name = null;
        if (contentDisposition != null) {
            for (String part : contentDisposition.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("filename=")) {
                    name = p.substring("filename=".length()).trim().replace("\"", "");
                }
            }
        }
        if (name == null || name.isEmpty()) {
            if (url != null) {
                int q = url.indexOf('?');
                String path = q >= 0 ? url.substring(0, q) : url;
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < path.length()) name = path.substring(slash + 1);
            }
        }
        if (name == null || name.isEmpty()) name = "download.bin";
        // ⛔ 别把路径分隔符带进来（防目录穿越式的落点建议）
        name = name.replace('/', '_').replace('\\', '_').replace("..", "_");
        if (name.length() > 100) name = name.substring(0, 100);
        return name;
    }

    /** 取 host（与 NetPolicy.hostOf 同口径的轻量版；仅用于「同 host 才回放 Cookie」这一判断） */
    static String hostOf(String url) {
        if (url == null) return "";
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return "";
        s = s.substring(scheme + 3);
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        s = s.substring(0, end);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        if (s.startsWith("[")) {                      // IPv6 字面量
            int close = s.indexOf(']');
            return close > 0 ? s.substring(1, close).toLowerCase(Locale.ROOT) : "";
        }
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.toLowerCase(Locale.ROOT);
    }

    /** 证书 SHA-256 指纹，格式与 `MainActivity.fingerprintOf` / `openssl` 一致（大写、冒号分隔） */
    static String fingerprintOf(byte[] der) {
        if (der == null || der.length == 0) return "";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder(h.length * 3);
            for (int i = 0; i < h.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02X", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ══════════════ SAF 落点 ══════════════ */

    /** 让用户选保存位置（SAF：不需要任何权限） */
    static Intent saveIntent(String suggestedName) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        return i;
    }

    /* ══════════════ 取文件 ══════════════ */

    /**
     * 把 {@code url} 的内容流式写入 {@code target}（用户刚选定的 URI）。**在后台线程跑**。
     *
     * 每一跳都重新校验：scheme 白名单 · host 变了就不带 Cookie · https 走 {@link #trustManager}。
     *
     * @param cancel 用户点「取消」时置 true ⇒ 循环里检查并中止（每读一块查一次）
     */
    static void fetch(Context ctx, String url, Uri target, Progress cb,
                      java.util.concurrent.atomic.AtomicBoolean cancel) {
        new Thread(() -> {
            OutputStream out = null;
            HttpURLConnection c = null;
            try {
                if (!isAllowedScheme(url)) { cb.onError("只支持 http/https 链接"); return; }
                String configured = Prefs.url(ctx);
                String current = url;
                int redirects = 0;
                while (true) {
                    if (cancel.get()) { cb.onError("已取消"); return; }
                    c = open(ctx, current, shouldReplayCookie(configured, current));
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        c = null;
                        if (loc == null || ++redirects > MAX_REDIRECTS) { cb.onError("重定向过多或缺少 Location"); return; }
                        current = new URL(new URL(current), loc).toString();
                        if (!isAllowedScheme(current)) { cb.onError("重定向到了不支持的协议"); return; }
                        continue;
                    }
                    if (code < 200 || code >= 300) { cb.onError("服务器返回 HTTP " + code); return; }

                    long total = c.getContentLengthLong();
                    if (total > MAX_BYTES) { cb.onError("文件太大（超过 " + (MAX_BYTES / 1024 / 1024) + " MB）"); return; }

                    out = ctx.getContentResolver().openOutputStream(target, "w");
                    if (out == null) { cb.onError("打不开你选的位置"); return; }

                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[16 * 1024];
                    long read = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel.get()) { cb.onError("已取消"); return; }
                        read += n;
                        if (read > MAX_BYTES) { cb.onError("文件超过上限，已中止"); return; }
                        out.write(buf, 0, n);
                        cb.onProgress(read, total);
                    }
                    out.flush();
                    cb.onDone(read);
                    return;
                }
            } catch (CertificateException ce) {
                // 证书问题要**说人话**（这是最可能被用户遇到的失败）
                Log.w(TAG, "下载中止（证书）：" + ce.getMessage());
                cb.onError(ce.getMessage() == null ? "证书校验未通过，已中止" : ce.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "下载失败：" + e);
                cb.onError("下载失败：" + e.getClass().getSimpleName());
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) { }
                if (c != null) c.disconnect();
            }
        }, "dsh-download").start();
    }

    private static HttpURLConnection open(Context ctx, String url, boolean withCookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);          // 自己管重定向：每一跳都要重新校验
        c.setRequestProperty("User-Agent", "DSHMobile");
        if (withCookie) {
            try {
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            } catch (Exception ignored) { }
        }
        if (c instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) c;
            h.setSSLSocketFactory(sslContext(ctx, hostOf(url)).getSocketFactory());
            // ⚠️ 刻意**不**自定义 HostnameVerifier —— 主机名校验保持系统默认（不能放）
        }
        return c;
    }

    private static SSLContext sslContext(Context ctx, String host) throws Exception {
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{ trustManager(ctx, host) }, new java.security.SecureRandom());
        return sc;
    }

    /**
     * **先系统信任，失败才认「用户确认过的指纹」**。
     *
     * ⚠️ 为什么不能只认指纹：TOFU 只在**系统信任失败**时才会记录指纹
     * （`onReceivedSslError` 只在系统校验不通过时触发）⇒ 一个用正规 CA 证书的 https 站点
     * 在 `trustedFingerprint()` 里**根本没有记录**。只认指纹会把这种正常站点拒掉。
     *
     * ⛔ 更不能 trust-all —— 那等于把 v1.3.0 的 TOFU 白做。
     */
    private static X509TrustManager trustManager(Context ctx, String host) throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);      // 系统 CA
        X509TrustManager system = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) { system = (X509TrustManager) tm; break; }
        }
        final X509TrustManager sys = system;
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("客户端证书不参与本流程");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                // ① 系统信任（正规 CA）→ 放行
                if (sys != null) {
                    try { sys.checkServerTrusted(chain, authType); return; }
                    catch (CertificateException ignored) { /* 落到 ② */ }
                }
                // ② 回落到「用户亲手核对过的指纹」
                String actual = (chain != null && chain.length > 0) ? fingerprintOf(chain[0].getEncoded()) : "";
                String trusted = Prefs.trustedFingerprint(ctx, host);
                if (fingerprintMatches(trusted, actual)) return;
                if (trusted == null || trusted.isEmpty()) {
                    throw new CertificateException("这台服务器用的是系统不信任的证书，且你还确认过它的指纹。"
                            + "请先用本 App 打开一次该地址并核对指纹，再下载。");
                }
                throw new CertificateException("证书指纹与已记录的不一致（可能是服务端换证，也可能是中间人），已中止下载。");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
