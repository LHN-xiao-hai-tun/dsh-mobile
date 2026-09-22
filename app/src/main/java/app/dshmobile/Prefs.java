package app.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本机偏好集中管理：服务器地址 / 连接历史 / 快捷指令模板。
 *
 * ⚠️ 隐私红线：这里所有数据**只存在本机 SharedPreferences**，
 * 不采集、不上传、不与任何第三方共享。新增字段时请保持这一条。
 *
 * ⚠️ 文案约定：默认模板文字取自 strings.xml（需 Context 才能取，故由
 *    static final 常量改为 defaultTemplates(Context) 方法）。
 *
 * ── v1.3.0：敏感值**本机加密**
 *    地址 / 连接历史 / 快捷指令模板 → 经 {@link SecretStore}（AES-256-GCM · 密钥在 Keystore）
 *    以 `enc1:` 前缀落盘；**旧版明文值读得出来、下次写入自动变密文**（惰性迁移，地址不丢）。
 *    浮动按钮位置（`fab_x` / `fab_y`）是纯 UI 比例，**不加密** —— 加密它没有安全收益，只是成本。
 *
 * ── v1.3.0：证书信任（TOFU）与明文风险确认
 *    `trust` = 每行 `host=指纹`；`ack` = 已确认过明文风险的主机。
 */
final class Prefs {

    private static final String FILE = "dsh";
    private static final String KEY_URL = "url";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_FAB_X = "fab_x";
    private static final String KEY_FAB_Y = "fab_y";
    private static final String KEY_TRUST = "trust";
    private static final String KEY_ACK = "ack";
    private static final String SEP = "\n";

    /** 连接历史最多保留条数 */
    static final int HISTORY_MAX = 5;

    /** 快捷指令模板条数（与 defaultTemplates 的取值个数、strings.xml 的 tpl_default_N 必须一致） */
    static final int TEMPLATE_COUNT = 3;

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 加密读写底座（v1.3.0） ----------

    /**
     * 读一个「可能已加密」的串。
     * ① 密文 → 解密；解密失败（密钥丢了 / 数据坏了）→ 返回 ""，**绝不崩**；
     * ② 旧版明文 → 原样返回（零成本迁移）。
     */
    private static String readSecret(Context c, String key, String def) {
        String raw = get(c).getString(key, null);
        if (raw == null) {
            return def;
        }
        String v = SecretStore.decrypt(raw);
        return v == null ? "" : v;
    }

    /**
     * 写一个「应当加密」的串。
     * 加密失败时**退回明文写入** —— 宁可少一层保护，也不能让用户存不上地址。
     */
    private static void writeSecret(Context c, String key, String value) {
        String enc = SecretStore.encrypt(value);
        get(c).edit().putString(key, enc != null ? enc : value).apply();
    }

    // ---------- 服务器地址 ----------

    static String url(Context c) {
        String u = readSecret(c, KEY_URL, "");
        return u.trim();
    }

    static void setUrl(Context c, String url) {
        writeSecret(c, KEY_URL, url == null ? "" : url);
    }

    static void clearUrl(Context c) {
        get(c).edit().remove(KEY_URL).apply();
    }

    // ---------- 连接历史 ----------

    /** 最近连接在前 */
    static List<String> history(Context c) {
        List<String> out = new ArrayList<>();
        String raw = readSecret(c, KEY_HISTORY, "");
        if (raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(SEP)) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 记录一次成功连接：去重（已有则提到最前）+ 超限截断 */
    static void pushHistory(Context c, String url) {
        String u = normalize(url);
        if (u.isEmpty()) {
            return;
        }
        List<String> list = history(c);
        list.remove(u);
        list.add(0, u);
        while (list.size() > HISTORY_MAX) {
            list.remove(list.size() - 1);
        }
        writeSecret(c, KEY_HISTORY, join(list));
    }

    // ---------- 快捷指令模板 ----------

    /** 快捷指令默认模板（用户可编辑；仅存本机）。文案见 strings.xml */
    static List<String> defaultTemplates(Context c) {
        List<String> d = new ArrayList<>(TEMPLATE_COUNT);
        d.add(c.getString(R.string.tpl_default_1));
        d.add(c.getString(R.string.tpl_default_2));
        d.add(c.getString(R.string.tpl_default_3));
        return d;
    }

    static List<String> templates(Context c) {
        List<String> defs = defaultTemplates(c);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            out.add(readSecret(c, "tpl_" + i, defs.get(i)));
        }
        return out;
    }

    static void setTemplate(Context c, int index, String text) {
        writeSecret(c, "tpl_" + index, text == null ? "" : text.trim());
    }

    static void resetTemplates(Context c) {
        for (int i = 0; i < TEMPLATE_COUNT; i++) {
            get(c).edit().remove("tpl_" + i).apply();
        }
    }

    // ---------- 浮动按钮位置（非敏感 · 不加密） ----------

    /**
     * 返回 {fx, fy}，为「可移动范围的比例」(0~1)；无记录时默认 {1, 1} → 右下角。
     * 存比例而非像素：旋转屏幕 / 换密度后位置依然合理。
     */
    static float[] fab(Context c) {
        SharedPreferences sp = get(c);
        return new float[]{clamp01(sp.getFloat(KEY_FAB_X, 1f)),
                clamp01(sp.getFloat(KEY_FAB_Y, 1f))};
    }

    static void setFab(Context c, float fx, float fy) {
        get(c).edit()
                .putFloat(KEY_FAB_X, clamp01(fx))
                .putFloat(KEY_FAB_Y, clamp01(fy))
                .apply();
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, v));
    }

    // ---------- 证书信任（TOFU）· v1.3.0 ----------

    /** 已信任的 host → 证书 SHA-256 指纹 */
    static Map<String, String> trustedCerts(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = readSecret(c, KEY_TRUST, "");
        if (raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split(SEP)) {
            int i = line.indexOf('=');
            if (i > 0) {
                out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return out;
    }

    /**
     * @return 该 host 已信任的指纹；**没信任过返回 null**
     *         （null = "首次见到" ≠ "" = "有记录但为空"，两者处理不同）
     */
    static String trustedFingerprint(Context c, String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        return trustedCerts(c).get(host.toLowerCase(Locale.ROOT));
    }

    /** 记住 / 覆盖某 host 的证书指纹（指纹变化时，由用户二次确认后才走到这里） */
    static void trustCert(Context c, String host, String fingerprint) {
        if (host == null || host.isEmpty() || fingerprint == null || fingerprint.isEmpty()) {
            return;
        }
        Map<String, String> m = trustedCerts(c);
        m.put(host.toLowerCase(Locale.ROOT), fingerprint);
        writeSecret(c, KEY_TRUST, joinTrust(m));
    }

    /** 清除某 host 的信任（下次连接重新走「首次信任」确认） */
    static void forgetCert(Context c, String host) {
        Map<String, String> m = trustedCerts(c);
        m.remove(host.toLowerCase(Locale.ROOT));
        writeSecret(c, KEY_TRUST, joinTrust(m));
    }

    static void clearAllCerts(Context c) {
        get(c).edit().remove(KEY_TRUST).apply();
    }

    private static String joinTrust(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ---------- 明文风险确认（首次连接提示）· v1.3.0 ----------

    static Set<String> ackedHosts(Context c) {
        Set<String> out = new LinkedHashSet<>();
        String raw = readSecret(c, KEY_ACK, "");
        if (raw.isEmpty()) {
            return out;
        }
        for (String s : raw.split(SEP)) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    static boolean isAcked(Context c, String host) {
        return host != null && ackedHosts(c).contains(host.toLowerCase(Locale.ROOT));
    }

    static void ackHost(Context c, String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        Set<String> s = ackedHosts(c);
        s.add(host.toLowerCase(Locale.ROOT));
        writeSecret(c, KEY_ACK, String.join(SEP, s));
    }

    static void clearAcked(Context c) {
        get(c).edit().remove(KEY_ACK).apply();
    }

    // ---------- 工具 ----------

    /** 去空白、去换行（换行是历史记录的分隔符），补全 scheme */
    static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String u = url.replace("\n", "").replace("\r", "").trim();
        if (u.isEmpty()) {
            return "";
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        return u;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
