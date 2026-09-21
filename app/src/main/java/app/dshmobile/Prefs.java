package app.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本机偏好集中管理：服务器地址 / 连接历史 / 快捷指令模板。
 *
 * ⚠️ 隐私红线：这里所有数据**只存在本机 SharedPreferences**，
 * 不采集、不上传、不与任何第三方共享。新增字段时请保持这一条。
 */
final class Prefs {

    private static final String FILE = "dsh";
    private static final String KEY_URL = "url";
    private static final String KEY_HISTORY = "history";
    private static final String SEP = "\n";

    /** 连接历史最多保留条数 */
    static final int HISTORY_MAX = 5;

    /** 快捷指令默认模板（用户可编辑；仅存本机） */
    static final List<String> DEFAULT_TEMPLATES = Arrays.asList(
            "总结一下当前会话的要点",
            "继续上次未完成的任务",
            "把待办事项列成清单");

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

    static List<String> templates(Context c) {
        SharedPreferences sp = get(c);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < DEFAULT_TEMPLATES.size(); i++) {
            String v = sp.getString("tpl_" + i, DEFAULT_TEMPLATES.get(i));
            out.add(v == null ? "" : v);
        }
        return out;
    }

    static void setTemplate(Context c, int index, String text) {
        get(c).edit().putString("tpl_" + index, text == null ? "" : text.trim()).apply();
    }

    static void resetTemplates(Context c) {
        for (int i = 0; i < DEFAULT_TEMPLATES.size(); i++) {
            get(c).edit().remove("tpl_" + i).apply();
        }
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
