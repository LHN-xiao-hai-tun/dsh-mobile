package app.dshmobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 局域网扫描「HTTP 特征分级」的**误报回归网**（v1.3.7 补）。
 *
 * ── 为什么补这一块
 *   v1.2.6 把判据从「单一布尔」改成「A 强特征 / B 泛词」两级，但 **A 级里留了裸 `harness`** ——
 *   而 `harness` 是 **CI / 测试领域的通用词**。实测合成页 `<title>Test Harness</title>`（Jenkins 一类）
 *   被判成 **A 级 = 直接列为 DSH** ⇒ **真误报**。
 *   ⇒ 本文件把「当时实测的合成用例」固化成断言：**误报用例必须落到 B（候选）或 C（无关）**，
 *      真阳性必须留在 A。
 *
 * ⚠️ 被测的是**纯函数** {@link LanScan#classifyBody}（无网络、无 Android 依赖）。
 *   取正文那一段（TCP + HTTP 探测）不在本文件覆盖范围内 —— 那需要真机/局域网条件。
 */
public class LanScanClassifyTest {

    /** 用一段最小 HTML 表达「正文 + 标题」，与 classify() 里 body/title 的取法一致（正文已小写） */
    private static int judge(String html) {
        String lower = html.toLowerCase();
        int open = lower.indexOf("<title>");
        String title = "";
        if (open >= 0) {
            int close = lower.indexOf("</title>", open);
            title = (close < 0)
                    ? lower.substring(open + 7, Math.min(lower.length(), open + 207))
                    : lower.substring(open + 7, close);
        }
        return LanScan.classifyBody(lower, title);
    }

    /* ───────── ① 合成「假 DSH」页 4 例：都不该判成 A ───────── */

    @Test
    public void testHarnessTitleIsNotTierA() {
        // 🔴 本文件存在的理由：CI/Jenkins 类页面，标题里只有通用词 harness
        String html = "<html><head><title>Test Harness</title></head>"
                + "<body><h1>Build #128 passed</h1></body></html>";
        assertEquals("裸 harness 的标题不得再判 A（真误报）", LanScan.TIER_MAYBE, judge(html));
    }

    @Test
    public void deepseekDocsCenterIsNotTierA() {
        String html = "<html><head><title>DeepSeek 文档中心</title></head>"
                + "<body>欢迎来到 DeepSeek 开放平台文档</body></html>";
        assertEquals("只含泛词 deepseek ⇒ B（候选）", LanScan.TIER_MAYBE, judge(html));
    }

    @Test
    public void techBlogMentioningDshInBodyIsNotTierA() {
        // ⚠️ 关键是「dsh 只出现在正文里」——若出现在标题里，按既定口径仍算 A（标题是更强的信号）
        String html = "<html><head><title>我的技术博客</title></head>"
                + "<body>今天聊聊 dsh 与各类自建服务</body></html>";
        assertEquals("正文提到 dsh、标题没有 ⇒ B（候选）", LanScan.TIER_MAYBE, judge(html));
    }

    @Test
    public void printerPanelIsTierC() {
        String html = "<html><head><title>打印机面板</title></head>"
                + "<body>HP LaserJet 状态：就绪</body></html>";
        assertEquals("一个特征词都没有 ⇒ C（无关）", LanScan.TIER_NONE, judge(html));
    }

    /* ───────── ② 真阳性 2 例：必须留在 A ───────── */

    @Test
    public void dshPocketHomeIsTierA() {
        // 电脑侧 dsh-pocket 的首页标题（真机实测过：3082 端口）
        String html = "<html><head><title>DSH Pocket · 正在进入</title></head>"
                + "<body>正在进入…</body></html>";
        assertEquals("dsh pocket 短语 ⇒ A", LanScan.TIER_DSH, judge(html));
    }

    @Test
    public void dshWebAuthenticationPageIsTierA() {
        // 3080 的访问密码页（HTTP 401，正文在 errorStream 里 —— 这里只验判据）
        String html = "<html><head><title>DSH Web Authentication</title></head>"
                + "<body>请输入访问密码</body></html>";
        assertEquals("dsh web authentication 短语 ⇒ A", LanScan.TIER_DSH, judge(html));
    }

    /* ───────── ③ 边界 ───────── */

    @Test
    public void dshInTitleIsStillTierA() {
        // 收紧后 A 级标题判据只剩 dsh 一条 —— 钉住它，别在后续重构里被误删
        assertEquals(LanScan.TIER_DSH, judge("<html><head><title>DSH 本地构建</title></head><body>x</body></html>"));
    }

    @Test
    public void deepseekHarnessPhraseIsStillTierA() {
        // 短语级强信号（不是单个通用词）⇒ 保留在 A
        assertEquals(LanScan.TIER_DSH, judge("<body>DeepSeek Harness 控制台</body>"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals(LanScan.TIER_NONE, LanScan.classifyBody(null, null));
        assertEquals(LanScan.TIER_NONE, LanScan.classifyBody("", ""));
    }
}
