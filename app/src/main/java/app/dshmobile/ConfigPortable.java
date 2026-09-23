package app.dshmobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置导出 / 导入的**文本格式**（C2 · v1.3.9）。
 *
 * ── 为什么要有它
 *   地址 / 历史 / 名字 / 快捷指令模板 / 证书信任**都加密存在 Keystore 里**，密钥不随备份走
 *   （`allowBackup=false` 就是为此）⇒ **换机时这些全都得重填**。导出成一份用户自选落点的文件，
 *   换机导入即可回来（**含证书信任** —— 否则自签 https 到了新机又得重新核对指纹）。
 *
 * ── 格式（行式文本 · 人可读 · 每行 `键\t值[\t值]`）
 *   ```
 *   DSHMOBILE-CONFIG v1
 *   url<TAB>http://192.168.10.44:3082
 *   history<TAB>http://192.168.10.44:3082
 *   label<TAB>http://192.168.10.44:3082<TAB>家里电脑
 *   tpl<TAB>0<TAB>今天做了什么
 *   cert<TAB>192.168.10.44<TAB>BF:D0:…
 *   ack<TAB>192.168.10.44
 *   ```
 *   ⚠️ 值里的 `\` / TAB / 换行会被转义（`\\` `\t` `\n`）—— 否则一个多行模板就能把整张表撕开。
 *
 * ── 三条口径
 *   ① **只搬用户自己的东西**：地址、历史、名字、模板、证书指纹、明文确认记录。
 *      ⛔ 不含任何密钥 / PIN / Cookie（那些本来就不存本机）。
 *   ② **导入前必须让用户确认**（界面层做）：文件是外部输入，**不能默默覆盖**当前配置。
 *   ③ **坏行只跳过、不中断**（`skipped` 计数在界面上如实说），绝不因为一行烂数据把能用的部分也扔掉。
 *
 * 全部是纯函数 ⇒ 进单测（`ConfigPortableTest`）。
 */
final class ConfigPortable {

    private ConfigPortable() {
    }

    static final String HEADER = "DSHMOBILE-CONFIG v1";

    static final String K_URL = "url";
    static final String K_HISTORY = "history";
    static final String K_LABEL = "label";
    static final String K_TPL = "tpl";
    static final String K_CERT = "cert";
    static final String K_ACK = "ack";

    /** 一份可搬走的配置快照 */
    static final class Snapshot {
        String url = "";
        List<String> history = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> templates = new ArrayList<>();
        Map<String, String> certs = new LinkedHashMap<>();
        Set<String> acks = new LinkedHashSet<>();
        /** 解析时跳过的坏行数（>0 就在界面上如实说） */
        int skipped = 0;
        /** 文件头对不对（导入前的第一道闸） */
        boolean headerOk = false;

        boolean isEmpty() {
            return url.isEmpty() && history.isEmpty() && labels.isEmpty()
                    && templates.isEmpty() && certs.isEmpty() && acks.isEmpty();
        }
    }

    /* ══════════════ 转义（纯） ══════════════ */

    /** 把值里的 `\` / TAB / CR / LF 转义掉（否则会撕开行结构） */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@link #escape} 的逆运算；遇到不认识的转义就原样保留那个字符（宽松，不抛） */
    static String unescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '\\': sb.append('\\'); break;
                case 't': sb.append('\t'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                default: sb.append('\\').append(n);      // 不认识的转义：原样保留
            }
        }
        return sb.toString();
    }

    /* ══════════════ 组装 / 拆解（纯） ══════════════ */

    /** 拼一行（每个值先转义，再用 TAB 连接） */
    static String line(String key, String... values) {
        StringBuilder sb = new StringBuilder(key == null ? "" : key);
        if (values != null) {
            for (String v : values) {
                sb.append('\t').append(escape(v));
            }
        }
        return sb.toString();
    }

    /** 拆一行（按 TAB 切，再逐个反转义）；返回 null 表示这行是空的 */
    static String[] splitLine(String rawLine) {
        if (rawLine == null) return null;
        String s = rawLine.replace("\r", "");
        if (s.trim().isEmpty()) return null;
        String[] parts = s.split("\t", -1);
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = unescape(parts[i]);
        }
        return out;
    }

    /* ══════════════ 序列化（纯） ══════════════ */

    static String serialize(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        if (s == null) return sb.toString();
        if (!s.url.isEmpty()) sb.append(line(K_URL, s.url)).append('\n');
        for (String h : s.history) sb.append(line(K_HISTORY, h)).append('\n');
        for (Map.Entry<String, String> e : s.labels.entrySet()) {
            sb.append(line(K_LABEL, e.getKey(), e.getValue())).append('\n');
        }
        for (int i = 0; i < s.templates.size(); i++) {
            sb.append(line(K_TPL, String.valueOf(i), s.templates.get(i))).append('\n');
        }
        for (Map.Entry<String, String> e : s.certs.entrySet()) {
            sb.append(line(K_CERT, e.getKey(), e.getValue())).append('\n');
        }
        for (String a : s.acks) sb.append(line(K_ACK, a)).append('\n');
        return sb.toString();
    }

    /* ══════════════ 解析（纯 · 坏行只跳过） ══════════════ */

    /**
     * 解析导入文件。
     * 头部不对 ⇒ `headerOk=false`（界面据此拒绝导入 —— 别把随便一份文本吃进来）。
     */
    static Snapshot parse(String raw) {
        Snapshot s = new Snapshot();
        if (raw == null || raw.trim().isEmpty()) return s;
        String[] lines = raw.replace("\r", "").split("\n");
        if (lines.length == 0) return s;
        s.headerOk = HEADER.equals(lines[0].trim());
        for (int i = 1; i < lines.length; i++) {
            String[] f = splitLine(lines[i]);
            if (f == null) continue;
            String k = f[0] == null ? "" : f[0].trim();
            try {
                if (K_URL.equals(k)) {
                    if (f.length >= 2 && !f[1].isEmpty()) s.url = f[1];
                    else s.skipped++;
                } else if (K_HISTORY.equals(k)) {
                    if (f.length >= 2 && !f[1].isEmpty()) s.history.add(f[1]);
                    else s.skipped++;
                } else if (K_LABEL.equals(k)) {
                    if (f.length >= 3 && !f[1].isEmpty() && !f[2].isEmpty()) s.labels.put(f[1], f[2]);
                    else s.skipped++;
                } else if (K_TPL.equals(k)) {
                    if (f.length >= 3) {
                        int idx;
                        try {
                            idx = Integer.parseInt(f[1].trim());
                        } catch (NumberFormatException e) {
                            idx = -1;
                        }
                        if (idx >= 0) {
                            while (s.templates.size() <= idx) s.templates.add("");
                            s.templates.set(idx, f[2]);
                        } else {
                            s.skipped++;
                        }
                    } else {
                        s.skipped++;
                    }
                } else if (K_CERT.equals(k)) {
                    if (f.length >= 3 && !f[1].isEmpty() && !f[2].isEmpty()) s.certs.put(f[1], f[2]);
                    else s.skipped++;
                } else if (K_ACK.equals(k)) {
                    if (f.length >= 2 && !f[1].isEmpty()) s.acks.add(f[1]);
                    else s.skipped++;
                } else {
                    s.skipped++;               // 不认识的键：跳过（向前兼容将来新增的键）
                }
            } catch (Exception e) {
                s.skipped++;
            }
        }
        return s;
    }

    /**
     * 建议文件名（不含目录；SAF 会把它当默认值，用户可改）。
     *
     * ⚠️ dateStamp 会被**消毒**：只留字母数字与 `-` —— 万一将来有人传进来一个
     * `2026/09/23` 这种带分隔符的日期，也不至于建议出一个带路径的名字。
     */
    static String suggestedName(String dateStamp) {
        StringBuilder d = new StringBuilder();
        if (dateStamp != null) {
            for (int i = 0; i < dateStamp.length(); i++) {
                char c = dateStamp.charAt(i);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-') {
                    d.append(c);
                }
            }
        }
        return "dsh-config" + (d.length() == 0 ? "" : ("-" + d)) + ".txt";
    }
}
