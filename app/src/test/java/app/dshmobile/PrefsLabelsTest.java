package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地址命名表的序列化（v1.3.9 · B2 多地址管理）。
 *
 * ── 为什么要单测这两条纯函数
 *   它们读写的是**加密落盘的同一个串**（`labels`）——解析写错就"名字串行/丢名字"，
 *   而且用户看不见这个串，出问题极难查。存法是每行 `地址\t名字`（TAB 分隔，名字里可以有空格）。
 */
public class PrefsLabelsTest {

    @Test
    public void parsesTabSeparatedLines() {
        Map<String, String> m = Prefs.parseLabels(
                "http://192.168.10.44:3082\t家里电脑\nhttp://10.0.2.2:8443\t公司");
        assertEquals(2, m.size());
        assertEquals("家里电脑", m.get("http://192.168.10.44:3082"));
        assertEquals("公司", m.get("http://10.0.2.2:8443"));
    }

    @Test
    public void ignoresBrokenLinesInsteadOfThrowing() {
        // 没 TAB / TAB 在开头 / TAB 在结尾 / 空行 / 只有空白 —— 一律跳过，不能崩
        Map<String, String> m = Prefs.parseLabels(
                "\nhttp://a\t\n\t名字\nhttp://b\n   \nhttp://c\t有名字\t多余的TAB后面的都算名字");
        assertEquals(1, m.size());
        assertEquals("有名字\t多余的TAB后面的都算名字", m.get("http://c"));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertTrue(Prefs.parseLabels(null).isEmpty());
        assertTrue(Prefs.parseLabels("").isEmpty());
        assertTrue(Prefs.parseLabels("\n\n").isEmpty());
        assertEquals("", Prefs.joinLabels(null));
        assertTrue(Prefs.joinLabels(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    public void roundTripIsStable() {
        String raw = "http://192.168.10.44:3082\t家里电脑\nhttps://nas.local\tNAS";
        assertEquals(raw, Prefs.joinLabels(Prefs.parseLabels(raw)));
    }

    @Test
    public void joinSkipsEntriesWithoutNameOrWithoutUrl() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("http://a", "A");
        m.put("http://no-name", "");
        m.put("http://no-name2", "   ");
        m.put("", "空地址");
        m.put("   ", "全空白地址");
        m.put("http://b", "B");
        assertEquals("http://a\tA\nhttp://b\tB", Prefs.joinLabels(m));
    }

    @Test
    public void namesWithSpacesSurviveTheRoundTrip() {
        // 名字里带空格是常态（"家里的那台电脑" / "Office WiFi"）⇒ 分隔符必须是 TAB 而不是空格
        Map<String, String> m = new LinkedHashMap<>();
        m.put("http://a", "家里的那台电脑 Office WiFi");
        Map<String, String> back = Prefs.parseLabels(Prefs.joinLabels(m));
        assertEquals("家里的那台电脑 Office WiFi", back.get("http://a"));
    }

    @Test
    public void newlineInNameIsFlattenedBySetLabelContract() {
        // setLabel 会把换行/TAB 压成空格（换行是记录分隔符，混进去会毁掉整张表）
        // 这里验证"压平后"的形态能被正常解析
        Map<String, String> m = new LinkedHashMap<>();
        m.put("http://a", "第一行 第二行");
        String joined = Prefs.joinLabels(m);
        assertFalse("序列化结果里只应有一个换行（行分隔）", joined.substring(0, joined.length()).contains("\n\n"));
        assertEquals(1, Prefs.parseLabels(joined).size());
    }
}
