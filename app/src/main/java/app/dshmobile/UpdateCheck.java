package app.dshmobile;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 「检查更新」（C1 · v1.3.9）。
 *
 * ── 以前是什么样
 *   点「检查更新」只是**用浏览器打开 Releases 页**（`AboutActivity` 里那句注释写着"本页不含任何网络请求"），
 *   所以 App 自己**根本不知道有没有新版**。C1 把它变成真检查。
 *
 * ── 三条口径（别改）
 *   ① **绝不静默外联**：只有用户点「检查更新」时才发这一次请求（本类不注册任何定时/后台任务）。
 *   ② **绝不自动下载**：发现有新版只给一个「打开下载页」按钮（交给系统浏览器），
 *      App 自己不取 APK、更不会去装。
 *   ③ **失败不崩**：无网 / 超时 / 限流 / 非 200 / 返回内容看不懂 —— 全部翻译成人话，界面照常用。
 *
 * 判据（版本比较、状态码分诊、说明摘要）都是纯函数 ⇒ 进单测（`UpdateCheckTest`）。
 */
final class UpdateCheck {

    private UpdateCheck() {
    }

    /** GitHub「最新正式发布」接口（未认证：60 次/小时/IP） */
    static final String API =
            "https://api.github.com/repos/LHN-xiao-hai-tun/dsh-mobile/releases/latest";
    /** 发布页（失败时的兜底出口） */
    static final String RELEASES_PAGE = "https://github.com/LHN-xiao-hai-tun/dsh-mobile/releases";
    /** ⚠️ GitHub API **强制要求**带 User-Agent，不带会直接 403（不是限流，是拒答） */
    static final String USER_AGENT = "DSHMobile";

    static final int OK_LATEST = 0;         // 已是最新
    static final int OK_NEWER = 1;          // 有新版本
    static final int FAIL_RATE_LIMIT = 2;   // 被限流
    static final int FAIL_NETWORK = 3;      // 无网 / 超时 / IO 失败
    static final int FAIL_HTTP = 4;         // 其它 HTTP 状态码
    static final int FAIL_PARSE = 5;        // 200 但内容读不懂

    /** 一次检查的结果 */
    static final class Result {
        int status = FAIL_NETWORK;
        int httpCode = -1;
        String remoteTag = "";
        String remoteVersion = "";
        String pageUrl = "";
        String notes = "";
        String detail = "";                 // 失败原因（进日志/详情，不进主文案）
    }

    /* ══════════════ 纯判据 ══════════════ */

    /** 去掉 tag 的 `v` / `V` 前缀与空白（`v1.3.10` → `1.3.10`） */
    static String normalizeTag(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.length() > 0 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            t = t.substring(1).trim();
        }
        return t;
    }

    /**
     * 版本拆成数字段：`1.3.10` → [1,3,10]；非数字后缀（`-beta.1`）**截断丢弃**。
     * 段数不同由 {@link #isNewer} 按 0 补齐比较。
     */
    static int[] parseVersion(String v) {
        String s = normalizeTag(v);
        if (s.isEmpty()) return new int[0];
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) {
                cut = i;                     // 遇到 `-beta`、`+build` 这类后缀就截断
                break;
            }
        }
        s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;                  // 非法段当作 0，不让它把整次比较搞崩
            }
        }
        return out;
    }

    /**
     * `remote` 是否比 `current` 新（逐段比较，段数不同按 0 补）。
     *
     * ⚠️ 必须是**逐段数字**比较：字符串比较会把 `1.3.10` 判成比 `1.3.9` **旧**（'1' < '9'）。
     */
    static boolean isNewer(String remote, String current) {
        int[] r = parseVersion(remote);
        int[] c = parseVersion(current);
        int n = Math.max(r.length, c.length);
        for (int i = 0; i < n; i++) {
            int a = i < r.length ? r[i] : 0;
            int b = i < c.length ? c[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /** HTTP 状态码 → 结论（200 交给 {@link #isNewer} 再细分） */
    static int statusForHttp(int code) {
        if (code == 200) return OK_LATEST;
        if (code == 403 || code == 429) return FAIL_RATE_LIMIT;
        return FAIL_HTTP;
    }

    /** 403 到底是不是限流：GitHub 会带 `X-RateLimit-Remaining: 0`（没有这个头就别乱说是限流） */
    static boolean isRateLimited(int httpCode, String rateLimitRemaining) {
        return (httpCode == 403 || httpCode == 429) && "0".equals(rateLimitRemaining == null ? "" : rateLimitRemaining.trim());
    }

    /**
     * 把发布说明压成一段适合小屏看的摘要：
     * 去掉 markdown 的 `#`/`*`/`>`/反引号、合并空行、压掉多余空格，最后按字数截断。
     */
    static String snippet(String body, int max) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean pendingBlank = false;
        for (String rawLine : body.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            while (line.startsWith("#") || line.startsWith("*") || line.startsWith(">")
                    || line.startsWith("-") || line.startsWith("`")) {
                line = line.substring(1).trim();
            }
            line = line.replace("**", "").replace("`", "").replace("\u00a0", " ").trim();
            if (line.isEmpty()) {
                // ⚠️ 空行**不能当场写进结果**（否则连续空行会攒出一串换行）；
                //    改成"欠着"，下一个非空行来了才补 —— 效果 = **连续空行合并成一个空行**（保留段落感）。
                if (sb.length() > 0) pendingBlank = true;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                if (pendingBlank) sb.append('\n');
            }
            sb.append(line);
            pendingBlank = false;
        }
        String s = sb.toString().trim();
        if (max > 0 && s.length() > max) {
            s = s.substring(0, max) + "…";
        }
        return s;
    }

    /** 小写化（版本比较不区分大小写） */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /* ══════════════ 真发一次请求（只在用户点按钮时调用） ══════════════ */

    /**
     * GET 一次 GitHub 的 latest 接口。
     *
     * ⚠️ 这里用**系统默认信任链**（不套 {@link Downloader#sslContextFor} 那套 TOFU 指纹回落）：
     *    api.github.com 是正规 CA 签发的站点，拿自签那套逻辑来反而是滥用。
     */
    static Result fetch(String apiUrl, int connectMs, int readMs) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apiUrl).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            r.httpCode = c.getResponseCode();
            if (r.httpCode != 200) {
                String remaining = c.getHeaderField("X-RateLimit-Remaining");
                r.status = isRateLimited(r.httpCode, remaining)
                        ? FAIL_RATE_LIMIT : statusForHttp(r.httpCode);
                r.detail = "HTTP " + r.httpCode
                        + (remaining == null ? "" : (" (X-RateLimit-Remaining: " + remaining + ")"));
                return r;
            }
            String body = readAll(c.getInputStream());
            org.json.JSONObject o = new org.json.JSONObject(body);
            r.remoteTag = o.optString("tag_name", "");
            r.remoteVersion = normalizeTag(r.remoteTag);
            r.pageUrl = o.optString("html_url", "");
            r.notes = snippet(o.optString("body", ""), 400);
            if (r.remoteVersion.isEmpty()) {
                r.status = FAIL_PARSE;
                r.detail = "响应里没有 tag_name";
                return r;
            }
            r.status = OK_LATEST;            // 由调用方按 isNewer 细分
            return r;
        } catch (org.json.JSONException e) {
            r.status = FAIL_PARSE;
            r.detail = "JSON: " + e.getMessage();
            return r;
        } catch (Exception e) {
            r.status = FAIL_NETWORK;
            r.detail = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : (": " + e.getMessage()));
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > 512 * 1024) break;      // 发布说明再长也不该超过半兆，防被塞爆
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
