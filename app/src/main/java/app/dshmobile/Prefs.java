package app.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机偏好集中管理：服务器地址 / 连接历史 / 快捷指令模板。
 *
 * ⚠️ 隐私红线：这里所有数据**只存在本机 SharedPreferences**，
 * 不采集、不上传、不与任何第三方共享。新增字段时请保持这一条。
 *
 * ⚠️ 文案约定：默认模板文字取自 strings.xml（需 Context 才能取，故由
 *    static final 常量改为 defaultTemplates(Context) 方法）。
 */
final class Prefs {

    private static final String FILE = "dsh";
    private static final String KEY_URL = "url";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_FAB_X = "fab_x";
    private static final String KEY_FAB_Y = "fab_y";
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

    // ---------- 服务器地址 ----------

    static String url(Context c) {
        String u = get(c).getString(KEY_URL, "");
        return u == null ? "" : u.trim();
    }

    static void setUrl(Context c, String url) {
        get(c).edit().putString(KEY_URL, url).apply();
    }

    static void clearUrl(Context c) {
        get(c).edit().remove(KEY_URL).apply();
    }

    // ---------- 连接历史 ----------

    /** 最近连接在前 */
    static List<String> history(Context c) {
        List<String> out = new ArrayList<>();
        String raw = get(c).getString(KEY_HISTORY, "");
        if (raw == null || raw.isEmpty()) {
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
        get(c).edit().putString(KEY_HISTORY, join(list)).apply();
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
        SharedPreferences sp = get(c);
        List<String> defs = defaultTemplates(c);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            String v = sp.getString("tpl_" + i, defs.get(i));
            out.add(v == null ? "" : v);
        }
        return out;
    }

    static void setTemplate(Context c, int index, String text) {
        get(c).edit().putString("tpl_" + index, text == null ? "" : text.trim()).apply();
    }

    static void resetTemplates(Context c) {
        for (int i = 0; i < TEMPLATE_COUNT; i++) {
            get(c).edit().remove("tpl_" + i).apply();
        }
    }

    // ---------- 浮动按钮位置 ----------

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
