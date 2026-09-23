package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置导出 / 导入的文本格式（C2 · v1.3.9）。
 *
 * ── 为什么这几条必须有单测
 *   导入是**吃外部文件**的路径：解析器把一行读错、或者转义没做，轻则"名字串到别的地址上"，
 *   重则**整张表被撕开**（一个多行模板就能做到）。而这些错误在界面上只表现为"数据不对"，
 *   极难反查 ⇒ 判据全部固化成测试。
 */
public class ConfigPortableTest {

    private static ConfigPortable.Snapshot sample() {
        ConfigPortable.Snapshot s = new ConfigPortable.Snapshot();
        s.url = "http://192.168.10.44:3082";
        s.history.addAll(Arrays.asList("http://192.168.10.44:3082", "https://10.0.2.2:8443"));
        s.labels.put("http://192.168.10.44:3082", "家里电脑");
        s.templates.addAll(Arrays.asList("今天做了什么", "第二行\n带换行", "第三"));
        s.certs.put("10.0.2.2", "BF:D0:27:BF:87:66:DE:E9:F3:80:AF:6C:E3:15:96:E1:FA:4C:15:08:0C:BB:65:95:32:2E:9D:CB:B7:90:4C:9D");
        s.acks.add("192.168.10.44");
        return s;
    }

    /* ───────── ① 转义 ───────── */

    @Test
    public void escapeHandlesSeparatorsAndBackslash() {
        assertEquals("a\\tb", ConfigPortable.escape("a\tb"));
        assertEquals("a\\nb", ConfigPortable.escape("a\nb"));
        assertEquals("a\\\\b", ConfigPortable.escape("a\\b"));
        assertEquals("", ConfigPortable.escape(null));
        assertEquals("普通文本", ConfigPortable.escape("普通文本"));
    }

    @Test
    public void unescapeIsInverseOfEscape() {
        String[] cases = {"a\tb", "a\nb", "a\\b", "a\rb", "混合\t\n\\文本", "", "没有特殊字符"};
        for (String c : cases) {
            assertEquals(c, ConfigPortable.unescape(ConfigPortable.escape(c)));
        }
        assertEquals("", ConfigPortable.unescape(null));
    }

    @Test
    public void unescapeKeepsUnknownEscapesVerbatim() {
        // 宽松：认不出来的转义原样留着，别把用户内容吃掉
        assertEquals("\\q", ConfigPortable.unescape("\\q"));
        // 孤零零的结尾反斜杠也**留着**（宁可多留一个字符，也不静默丢内容）
        assertEquals("结尾的反斜杠\\", ConfigPortable.unescape("结尾的反斜杠\\"));
    }

    /* ───────── ② 往返（本文件的核心） ───────── */

    @Test
    public void roundTripKeepsEverything() {
        ConfigPortable.Snapshot a = sample();
        String text = ConfigPortable.serialize(a);
        ConfigPortable.Snapshot b = ConfigPortable.parse(text);

        assertTrue("文件头必须认出来", b.headerOk);
        assertEquals(a.url, b.url);
        assertEquals(a.history, b.history);
        assertEquals("家里电脑", b.labels.get("http://192.168.10.44:3082"));
        assertEquals(a.templates, b.templates);
        assertEquals(a.certs, b.certs);
        assertEquals(a.acks, b.acks);
        assertEquals("不该有跳过的行", 0, b.skipped);
    }

    @Test
    public void multiLineTemplateDoesNotBreakTheTable() {
        ConfigPortable.Snapshot a = new ConfigPortable.Snapshot();
        a.url = "http://h:3082";
        a.templates.add("第一行\n第二行\t带TAB");
        a.labels.put("http://h:3082", "带\n换行的名字");
        String text = ConfigPortable.serialize(a);

        // ⭐ 关键性质：模板里的换行/TAB 必须被转义掉 ⇒ 物理行数 = 表头 + 3 条（url/tpl/label）
        //    （split 带 -1 时末尾那个空串也算一个元素 ⇒ 5）
        assertEquals("转义没做好就会多出行来（整张表被撕开）", 5, text.split("\n", -1).length);
        ConfigPortable.Snapshot b = ConfigPortable.parse(text);
        assertEquals("第一行\n第二行\t带TAB", b.templates.get(0));
        assertEquals("带\n换行的名字", b.labels.get("http://h:3082"));
        assertEquals(0, b.skipped);
    }

    /* ───────── ③ 坏输入 ───────── */

    @Test
    public void nonOurFileIsRejectedByHeader() {
        assertFalse(ConfigPortable.parse("随便一份文本\nurl\thttp://x").headerOk);
        assertFalse(ConfigPortable.parse("").headerOk);
        assertFalse(ConfigPortable.parse(null).headerOk);
        assertTrue(ConfigPortable.parse(ConfigPortable.HEADER + "\n").headerOk);
    }

    @Test
    public void brokenLinesAreSkippedNotFatal() {
        String text = ConfigPortable.HEADER + "\n"
                + "url\thttp://good:3082\n"
                + "url\t\n"                       // 空值 ⇒ 跳过
                + "history\t\n"                   // 空值 ⇒ 跳过
                + "label\thttp://a\n"             // 少一列 ⇒ 跳过
                + "tpl\tx\t正文\n"                // 索引不是数字 ⇒ 跳过
                + "cert\thost\n"                  // 少一列 ⇒ 跳过
                + "ack\t\n"                       // 空值 ⇒ 跳过
                + "未来新键\t值\n"                 // 不认识的键 ⇒ 跳过（向前兼容）
                + "history\thttp://ok:3082\n";
        ConfigPortable.Snapshot s = ConfigPortable.parse(text);
        assertTrue(s.headerOk);
        assertEquals("http://good:3082", s.url);
        assertEquals(1, s.history.size());
        assertEquals("http://ok:3082", s.history.get(0));
        assertEquals(7, s.skipped);
    }

    @Test
    public void templateIndexGapsArePadded() {
        String text = ConfigPortable.HEADER + "\n"
                + "tpl\t0\tA\n"
                + "tpl\t2\tC\n";
        ConfigPortable.Snapshot s = ConfigPortable.parse(text);
        assertEquals(3, s.templates.size());
        assertEquals("A", s.templates.get(0));
        assertEquals("", s.templates.get(1));
        assertEquals("C", s.templates.get(2));
    }

    /* ───────── ④ 序列化细节 ───────── */

    @Test
    public void serializeAlwaysStartsWithTheHeader() {
        assertTrue(ConfigPortable.serialize(null).startsWith(ConfigPortable.HEADER));
        assertTrue(ConfigPortable.serialize(new ConfigPortable.Snapshot())
                .startsWith(ConfigPortable.HEADER));
    }

    @Test
    public void emptyValuesAreNotWritten() {
        ConfigPortable.Snapshot s = new ConfigPortable.Snapshot();
        String text = ConfigPortable.serialize(s);
        assertEquals(ConfigPortable.HEADER + "\n", text);
        assertTrue("空快照要被认成空（界面据此拒绝导入）", s.isEmpty());
        assertFalse(sample().isEmpty());
    }

    @Test
    public void suggestedNameCarriesTheDateAndIsASafeFilename() {
        assertEquals("dsh-config-20260923.txt", ConfigPortable.suggestedName("20260923"));
        assertEquals("dsh-config.txt", ConfigPortable.suggestedName(""));
        assertEquals("dsh-config.txt", ConfigPortable.suggestedName(null));
        String n = ConfigPortable.suggestedName("2026/09/23");
        assertFalse("不能带路径分隔符", n.contains("/"));
        assertFalse(n.contains("\\"));
    }

    /* ───────── ⑤ 与 Prefs 表的互操作 ───────── */

    @Test
    public void labelsMapRoundTripsThroughTheExportFormat() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("http://a", "名字 带空格");
        m.put("http://b", "另一个");
        ConfigPortable.Snapshot s = new ConfigPortable.Snapshot();
        s.labels.putAll(m);
        ConfigPortable.Snapshot back = ConfigPortable.parse(ConfigPortable.serialize(s));
        assertEquals(m, back.labels);
    }
}
