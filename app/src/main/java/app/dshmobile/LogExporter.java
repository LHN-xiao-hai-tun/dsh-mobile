package app.dshmobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 崩溃与日志 · 导出（v1.3.4 · 工单批 3-5）
 *
 * 三条硬口径：
 *   ① **默认不上传** —— 本 App 从来没有、也不会有任何自动上报；本类只做「用户点一下 → 生成文件 → 交给分享面板」。
 *      生成的日志**只在本地**，去向由用户决定（分享给谁是他自己的选择）。
 *   ② **提供「导出日志」** —— 设置 → 安全 → 导出日志（一键）。
 *   ③ **导出前脱敏** —— 地址里的 **token/PIN**、URL 上的敏感参数、以及**自定义快捷指令模板**（可能含私人内容）
 *      一律替换为占位符；IP/端口保留（那是排障必需，且属内网信息）。
 *
 * 为什么用 FileProvider + 分享而不是让用户自己去翻文件：
 *   Android 10+ 的应用私有目录用户根本翻不到；`FileProvider` 把文件**只读**授权给用户选中的那个 App，
 *   不需要任何存储权限（本 App 至今**零运行时权限**，这条不能破）。
 */
final class LogExporter {

    private static final String TAG = "DSH-LogExport";
    /** 与 manifest 里 <provider android:authorities> 必须一致 */
    static final String AUTHORITY = "app.dshmobile.files";
    /** 与 res/xml/file_paths.xml 里的 <cache-path name> 必须一致 */
    private static final String PROVIDER_PATH = "exports";

    /**
     * 匹配"看起来像密钥"的赋值。
     *
     * 🔴 两轮真机实测修正（2026-09-23，靠用户导出的真实日志发现）：
     *   v1 版：`(key)=([^&\s"'<>]{2,})` —— 会把 logcat 里**与密钥无关**的 Android 内部字段一并打码。
     *   v2 版（误判为"长度能区分"）：给 token 类加"≥12 位" —— **仍然误伤**，因为真实日志里
     *        `ActivityRecord{de34fd token=android.os.BinderProxy@41fc4a7 {…}}`
     *        的值是 **30 个字符**（Java 对象引用），长度根本区分不了。
     *   ✅ v3（本版）：改用**字符集**判据 —— 真实密钥（DSH token 等）只由
     *        `A-Za-z0-9 _ - + / =` 组成；而 Java 对象引用含 `.` 与 `@`
     *        ⇒ 值里出现 `.`/`@` 就**不是密钥，不脱敏**。长度仍要求 ≥12（token 类）以进一步收窄。
     */
    private static final Pattern TOKENISH = Pattern.compile(
            "((?:token|access_token|refresh_token|session|sid)=)([A-Za-z0-9_\\-+/=]{12,})(?![A-Za-z0-9_\\-+/=])(?![.@])"
                    + "|((?:pin|pwd|password|passwd|secret|apikey|api_key|access_key|sessdata)=)([A-Za-z0-9_\\-+/=]{2,})(?![A-Za-z0-9_\\-+/=])(?![.@])",
            Pattern.CASE_INSENSITIVE);
    /** 日志行里形如 `?token=xxx` 或 `#xxx` 的尾巴 */
    private static final Pattern URL_TAIL = Pattern.compile("([?&#])(token|pin)=([^&\\s\"'<>]{2,})");

    private LogExporter() {
    }

    /* ─────────────── 脱敏 ─────────────── */

    /** 对**任意文本**做脱敏（地址 / PIN / token / 密钥类查询参数）。保留 IP 与端口。 */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        // 🔴 这里**不能**写 `TOKENISH.matcher(text).replaceAll(m -> …)`（lambda 重载）——
        //    那个重载是 **Java 9 / Android 14（API 34）** 才有的方法，而本 App 的 minSdk 是 **26**
        //    ⇒ Android 8~13 上一点「导出日志」就 **NoSuchMethodError 崩掉**（本 App 没开脱糖）。
        //    实测经过（2026-09-23）：v1.3.4/v1.3.5 两版带着它发出去了 —— 本地 `assembleRelease`
        //    全程绿灯（lintVital 默认只拦 Fatal，而 NewApi 是 Error），直到 CI 的完整
        //    `lintRelease` 才照出来；已在本机 Android 8 模拟器上复现崩溃。
        //    ⇒ 改用 **Java 8 就有的** appendReplacement/appendTail 循环，输出与原先逐字节一致。
        //    另：`app/build.gradle` 已把 NewApi 提为 fatal，同类问题以后**本地出包就会拦下**。
        Matcher m = TOKENISH.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (m.find()) {
            // group1/3 = "键="；group2/4 = 值。**只保留键，值整个丢掉**
            String key = m.group(1) != null ? m.group(1) : m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(key + "<已脱敏>"));
        }
        m.appendTail(sb);
        String out = sb.toString();
        out = URL_TAIL.matcher(out).replaceAll("$1$2=<已脱敏>");
        return out;
    }

    /** URL 专用脱敏：把 userinfo / 查询串里的敏感项替换掉，保留 scheme://host:port/path */
    static String redactUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String out = redact(url);
        // 干掉 userinfo（user:pass@host）
        Matcher m = Pattern.compile("^(\\w+://)([^/@\\s]+)@").matcher(out);
        if (m.find()) out = m.replaceFirst("$1<已脱敏>@");
        return out;
    }

    /* ─────────────── 收集 ─────────────── */

    /** 组装日志正文（**全部经脱敏**）。 */
    static String build(Context c) {
        StringBuilder sb = new StringBuilder(8192);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sb.append("DSH Mobile 诊断日志（本机生成，未上传）\n");
        sb.append("生成时间: ").append(fmt.format(new Date())).append('\n');
        sb.append("包名: ").append(c.getPackageName()).append('\n');
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            sb.append("版本: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Exception ignored) {
        }
        sb.append("设备: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");

        sb.append("\n── 配置（已脱敏）──\n");
        String url = Prefs.url(c);
        sb.append("地址: ").append(url == null || url.isEmpty() ? "(未设置)" : redactUrl(url)).append('\n');
        try {
            Map<String, String> certs = Prefs.trustedCerts(c);
            sb.append("已信任证书数: ").append(certs == null ? 0 : certs.size()).append('\n');
        } catch (Exception ignored) {
            sb.append("已信任证书数: (读取失败)\n");
        }
        sb.append("快捷指令模板: (已省略 —— 可能含私人内容；条数=")
                .append(safeSize(Prefs.templates(c))).append(")\n");

        sb.append("\n── 运行日志（logcat · 仅本应用，已脱敏）──\n");
        sb.append(readLogcat(c.getPackageName()));

        // ⚠️ 最后再兜一次：任何一行漏网的密钥串都拦掉
        return redact(sb.toString());
    }

    private static int safeSize(java.util.List<String> l) {
        try {
            return l == null ? 0 : l.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读本应用的 logcat（最多 1500 行；失败返回原因，不抛） */
    private static String readLogcat(String pkg) {
        StringBuilder sb = new StringBuilder(16384);
        Process p = null;
        try {
            // 只取本进程相关行：先 -d 全量再过滤（避免 -s 的 tag 过滤漏掉崩溃栈）
            p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int kept = 0;
                while ((line = r.readLine()) != null) {
                    if (kept > 1500) break;
                    if (line.contains(pkg) || line.contains("AndroidRuntime")
                            || line.contains("FATAL") || line.contains("ANR")) {
                        sb.append(line).append('\n');
                        kept++;
                    }
                }
                if (kept == 0) sb.append("(logcat 里没有与 ").append(pkg).append(" 相关的行)\n");
            }
        } catch (Exception e) {
            sb.append("(读取 logcat 失败: ").append(redact(String.valueOf(e.getMessage()))).append(")\n");
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    /* ─────────────── 落盘 + 分享 ─────────────── */

    /** 生成文件到 cacheDir/exports/，返回 File（失败返回 null） */
    static File write(Context c) {
        try {
            File dir = new File(c.getCacheDir(), PROVIDER_PATH);
            if (!dir.exists() && !dir.mkdirs()) return null;
            // 清掉旧导出，避免堆积
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) { try { f.delete(); } catch (Exception ignored) { } }

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "dsh-mobile-log-" + stamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(build(c).getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "写日志文件失败: " + e.getMessage());
            return null;
        }
    }

    /** 生成并拉起系统分享面板；返回 null 表示成功，否则返回给用户看的原因 */
    static String exportAndShare(Context c) {
        File f = write(c);
        if (f == null) return "生成日志文件失败";
        try {
            Uri uri = android.net.Uri.parse("content://" + AUTHORITY + "/" + f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "DSH Mobile 诊断日志");
            i.putExtra(Intent.EXTRA_TEXT,
                    "DSH Mobile 诊断日志（已脱敏：不含地址里的 token/PIN，也不含快捷指令内容）。"
                            + "本文件由手机本机生成，App 不会自动上传。");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(i, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        } catch (Exception e) {
            // 极端情况（无任何可分享 App）→ 退回告知文件路径
            return "已生成到应用缓存，但无法拉起分享：" + redact(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 用系统文件选择器**存成真实文件**（用户自己挑位置，通常存到「下载」）。
     *
     * ⚠️ 为什么不能只靠「分享」：实测（2026-09-23）在荣耀机型上，分享面板里选到
     *    只吃文本的目标（备忘录 / 微信收藏）时，**附件不会被带走** —— 用户只收到那段说明文字，
     *    拿不到日志文件本身。所以主路径改为 SAF（ACTION_CREATE_DOCUMENT）：
     *    不依赖任何存储权限，落点由用户指定，文件是**真·文件**，之后想再分享/传电脑都行。
     */
    static Intent saveIntent(String fileName) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        return i;
    }

    /** 建议的文件名（带时间戳） */
    static String suggestedName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "dsh-mobile-log-" + stamp + ".txt";
    }

    /** 把日志正文写进用户选定的 Uri；返回 null = 成功 */
    static String writeTo(Context c, Uri target) {
        try (java.io.OutputStream os = c.getContentResolver().openOutputStream(target)) {
            if (os == null) return "无法写入所选位置";
            os.write(build(c).getBytes(StandardCharsets.UTF_8));
            os.flush();
            return null;
        } catch (Exception e) {
            return "写入失败：" + redact(String.valueOf(e.getMessage()));
        }
    }
}