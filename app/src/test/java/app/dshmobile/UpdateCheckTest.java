package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「检查更新」（C1）的判据回归网（v1.3.9）。
 *
 * ── 为什么这几条必须有单测
 *   版本比较写错 = **该提示的更新不提示**（或反过来天天误报），而它只在"真有新版"那一天才暴露。
 *   其中最经典的一个坑：把版本当**字符串**比 —— `1.3.10` 会被判成比 `1.3.9` 旧。
 */
public class UpdateCheckTest {

    /* ───────── ① tag 归一化 ───────── */

    @Test
    public void tagIsNormalized() {
        assertEquals("1.3.9", UpdateCheck.normalizeTag("v1.3.9"));
        assertEquals("1.3.9", UpdateCheck.normalizeTag("V1.3.9"));
        assertEquals("1.3.9", UpdateCheck.normalizeTag("  1.3.9  "));
        assertEquals("", UpdateCheck.normalizeTag(null));
        assertEquals("", UpdateCheck.normalizeTag("v"));
    }

    /* ───────── ② 版本拆段 ───────── */

    @Test
    public void versionParsesIntoNumbers() {
        assertEquals(3, UpdateCheck.parseVersion("1.3.9").length);
        assertEquals(1, UpdateCheck.parseVersion("1.3.9")[0]);
        assertEquals(3, UpdateCheck.parseVersion("1.3.9")[1]);
        assertEquals(9, UpdateCheck.parseVersion("1.3.9")[2]);
        assertEquals("v 前缀不影响", 10, UpdateCheck.parseVersion("v1.3.10")[2]);
        assertEquals("预发布后缀被截断", 3, UpdateCheck.parseVersion("1.3.0-beta.2").length);
        assertEquals(0, UpdateCheck.parseVersion("").length);
        assertEquals(0, UpdateCheck.parseVersion(null).length);
        // 非法段（`1.x.3`）会在第一个非数字处截断 ⇒ 只剩 [1]；**不崩、也不瞎猜**
        assertEquals(1, UpdateCheck.parseVersion("1.x.3").length);
        assertEquals(1, UpdateCheck.parseVersion("1.x.3")[0]);
        assertEquals(2, UpdateCheck.parseVersion("1.2.x").length);
        assertEquals("带后缀的 tag 也照样能拆", 3, UpdateCheck.parseVersion("v1.3.10").length);
    }

    /* ───────── ③ 新旧比较（本文件的核心） ───────── */

    @Test
    public void numericComparisonNotStringComparison() {
        assertTrue("⭐ 字符串比会判错：'1.3.10' < '1.3.9'", UpdateCheck.isNewer("1.3.10", "1.3.9"));
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.9"));
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.99.99"));
        assertTrue(UpdateCheck.isNewer("1.3.9", "1.3.8"));
    }

    @Test
    public void sameOrOlderIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("1.3.9", "1.3.9"));
        assertFalse(UpdateCheck.isNewer("v1.3.9", "1.3.9"));
        assertFalse("远端比本机旧（本机是未发布的 1.3.9，远端最新是 v1.3.6）",
                UpdateCheck.isNewer("1.3.6", "1.3.9"));
    }

    @Test
    public void segmentCountDifferencesArePaddedWithZero() {
        assertTrue(UpdateCheck.isNewer("1.4", "1.3.9"));
        assertFalse("1.3.0 不比 1.3 新", UpdateCheck.isNewer("1.3.0", "1.3"));
        assertTrue(UpdateCheck.isNewer("1.3.0.1", "1.3"));
        assertFalse(UpdateCheck.isNewer("", "1.3.9"));
        assertTrue("本机版本读不到（空）时，远端的正式版本算新", UpdateCheck.isNewer("1.0.0", ""));
    }

    /* ───────── ④ 状态码分诊 ───────── */

    @Test
    public void httpCodesAreTriaged() {
        assertEquals(UpdateCheck.OK_LATEST, UpdateCheck.statusForHttp(200));
        assertEquals("403 = 多半限流", UpdateCheck.FAIL_RATE_LIMIT, UpdateCheck.statusForHttp(403));
        assertEquals(UpdateCheck.FAIL_RATE_LIMIT, UpdateCheck.statusForHttp(429));
        assertEquals(UpdateCheck.FAIL_HTTP, UpdateCheck.statusForHttp(404));
        assertEquals(UpdateCheck.FAIL_HTTP, UpdateCheck.statusForHttp(500));
        assertEquals(UpdateCheck.FAIL_HTTP, UpdateCheck.statusForHttp(-1));
    }

    @Test
    public void rateLimitNeedsTheHeaderNotJustA403() {
        // ⚠️ 403 不一定是限流（也可能是 GitHub 拒答：比如没带 User-Agent）
        assertTrue(UpdateCheck.isRateLimited(403, "0"));
        assertTrue(UpdateCheck.isRateLimited(429, " 0 "));
        assertFalse("还有额度就不是限流", UpdateCheck.isRateLimited(403, "17"));
        assertFalse(UpdateCheck.isRateLimited(403, null));
        assertFalse(UpdateCheck.isRateLimited(200, "0"));
    }

    /* ───────── ⑤ 发布说明摘要 ───────── */

    @Test
    public void notesSnippetStripsMarkdownNoise() {
        String body = "## 修复\n\n- 修了 A 问题\n- 修了 B 问题\n\n### 说明\n**重要**：请升级\n";
        String s = UpdateCheck.snippet(body, 200);
        assertFalse("标题符号要清掉", s.contains("##"));
        assertFalse(s.contains("**"));
        assertTrue(s.contains("修复"));
        assertTrue(s.contains("修了 A 问题"));
    }

    @Test
    public void notesSnippetTruncatesAndHandlesNull() {
        assertEquals("", UpdateCheck.snippet(null, 10));
        assertEquals("", UpdateCheck.snippet("", 10));
        String longNotes = new String(new char[500]).replace('\0', 'x');
        String s = UpdateCheck.snippet(longNotes, 50);
        assertEquals(51, s.length());                       // 50 + 省略号
        assertTrue(s.endsWith("…"));
    }

    @Test
    public void notesSnippetCollapsesConsecutiveBlankLinesIntoOne() {
        // 相邻两行不留空行；连续空行合并成**一个**空行（保留段落感，又不浪费小屏空间）
        assertEquals("A\nB", UpdateCheck.snippet("A\nB", 100));
        assertEquals("A\n\nB", UpdateCheck.snippet("A\n\n\n\n\nB", 100));
        assertEquals("开头与结尾的空行被去掉", "A\nB", UpdateCheck.snippet("\n\nA\nB\n\n\n", 100));
    }

    /* ───────── ⑥ 常量与口径 ───────── */

    @Test
    public void apiAndPageAreConsistent() {
        assertTrue("必须是 GitHub 的 latest 接口", UpdateCheck.API.endsWith("/releases/latest"));
        assertTrue(UpdateCheck.API.contains("LHN-xiao-hai-tun/dsh-mobile"));
        assertTrue(UpdateCheck.RELEASES_PAGE.endsWith("/releases"));
        assertEquals("⛔ 不带 UA 会被 GitHub 直接 403", "DSHMobile", UpdateCheck.USER_AGENT);
    }
}
