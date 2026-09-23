package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 连接诊断（B1）里**判据**的回归网（v1.3.9）。
 *
 * ── 为什么只测这些
 *   探针（TCP / TLS / HTTP）要真机才行；但"探到什么 ⇒ 结论是什么 ⇒ 下一步怎么建议"
 *   是**纯逻辑**，判错会让用户按错的方向去修（比不诊断更糟）⇒ 全部进单测。
 */
public class DiagnoseTest {

    /* ───────── ① 总体结论取最坏 ───────── */

    @Test
    public void overallLevelTakesTheWorst() {
        assertEquals(Diagnose.OK, Diagnose.overallLevel(Diagnose.OK, Diagnose.OK));
        assertEquals("INFO 不该拉低结论", Diagnose.OK,
                Diagnose.overallLevel(Diagnose.OK, Diagnose.INFO, Diagnose.INFO));
        assertEquals(Diagnose.WARN, Diagnose.overallLevel(Diagnose.OK, Diagnose.WARN, Diagnose.INFO));
        assertEquals("有 BAD 就是 BAD（不管还有多少 WARN）", Diagnose.BAD,
                Diagnose.overallLevel(Diagnose.WARN, Diagnose.BAD, Diagnose.WARN));
        assertEquals(Diagnose.OK, Diagnose.overallLevel());
    }

    /* ───────── ② HTTP 状态码分诊 ───────── */

    @Test
    public void httpCodeIsTriaged() {
        assertEquals("200 正常", Diagnose.OK, Diagnose.levelForHttp(200));
        assertEquals(Diagnose.OK, Diagnose.levelForHttp(204));
        assertEquals("401/403 = 服务在，但要登录", Diagnose.WARN, Diagnose.levelForHttp(401));
        assertEquals(Diagnose.WARN, Diagnose.levelForHttp(403));
        assertEquals("404 = 端口通了但不是 DSH", Diagnose.WARN, Diagnose.levelForHttp(404));
        assertEquals("重定向也要提醒", Diagnose.WARN, Diagnose.levelForHttp(302));
        assertEquals("5xx = 服务端自己坏了", Diagnose.BAD, Diagnose.levelForHttp(500));
        assertEquals(Diagnose.BAD, Diagnose.levelForHttp(503));
    }

    @Test
    public void httpCodeMapsToItsOwnMessage() {
        assertEquals(R.string.diag_http_ok, Diagnose.httpMessageRes(200));
        assertEquals(R.string.diag_http_auth, Diagnose.httpMessageRes(401));
        assertEquals(R.string.diag_http_404, Diagnose.httpMessageRes(404));
        assertEquals(R.string.diag_http_5xx, Diagnose.httpMessageRes(502));
        assertEquals(R.string.diag_http_other, Diagnose.httpMessageRes(418));
    }

    /* ───────── ③ 证书结论（含"连接成功"与"没走到校验证书"两种情形） ───────── */

    @Test
    public void certVerdictComparesWithSameRuleAsDownload() {
        String fp = "BF:D0:27:BF:87:66:DE:E9:F3:80:AF:6C:E3:15:96:E1:FA:4C:15:08:0C:BB:65:95:32:2E:9D:CB:B7:90:4C:9D";
        assertEquals(Diagnose.CERT_OK, Diagnose.certVerdict(fp, fp));
        assertEquals("大小写不敏感（与下载侧同口径）", Diagnose.CERT_OK,
                Diagnose.certVerdict(fp, fp.toLowerCase()));
        assertEquals(Diagnose.CERT_UNTRUSTED, Diagnose.certVerdict(null, fp));
        assertEquals(Diagnose.CERT_UNTRUSTED, Diagnose.certVerdict("", fp));
        assertEquals("换证必须报", Diagnose.CERT_CHANGED, Diagnose.certVerdict(fp,
                "3C:28:9B:53:6A:28:19:91:40:3E:11:9F:90:12:CC:72:33:81:DB:D8:C8:45:9B:6D:D4:95:46:9F:5D:F6:3B:F2"));
        assertEquals(Diagnose.CERT_NONE, Diagnose.certVerdict(fp, ""));
        assertEquals(Diagnose.CERT_NONE, Diagnose.certVerdict(fp, null));
    }

    @Test
    public void certVerdictAfterProbeDistinguishesConnectedFromBlocked() {
        String fp = "BF:D0:27:BF:87:66:DE:E9:F3:80:AF:6C:E3:15:96:E1:FA:4C:15:08:0C:BB:65:95:32:2E:9D:CB:B7:90:4C:9D";

        // 连上了、且命中已确认的指纹 ⇒ OK
        assertEquals(Diagnose.CERT_OK, Diagnose.certVerdictAfterProbe(true, false, fp, fp));

        // ⭐ 连上了、但本机没记过指纹 ⇒ 那是**系统信任链**放行的正规 CA，绝不能报"未确认"
        assertEquals(Diagnose.CERT_SYSTEM, Diagnose.certVerdictAfterProbe(true, false, null, fp));
        assertEquals(Diagnose.CERT_SYSTEM, Diagnose.certVerdictAfterProbe(true, false, "", fp));

        // 没连上、但不是证书的锅 ⇒ 说明这次压根没走到证书校验（别误报成证书问题）
        assertEquals(Diagnose.CERT_SKIPPED, Diagnose.certVerdictAfterProbe(false, false, null, ""));

        // 没连上、且是证书挡下的 ⇒ 这才去比指纹
        assertEquals(Diagnose.CERT_UNTRUSTED, Diagnose.certVerdictAfterProbe(false, true, null, fp));
        assertEquals(Diagnose.CERT_CHANGED, Diagnose.certVerdictAfterProbe(false, true, fp,
                "3C:28:9B:53:6A:28:19:91:40:3E:11:9F:90:12:CC:72:33:81:DB:D8:C8:45:9B:6D:D4:95:46:9F:5D:F6:3B:F2"));
        assertEquals("证书失败但读不到指纹 ⇒ 如实说读不到", Diagnose.CERT_NONE,
                Diagnose.certVerdictAfterProbe(false, true, fp, ""));
    }

    @Test
    public void certLevelIsSane() {
        assertEquals(Diagnose.OK, Diagnose.levelForCert(Diagnose.CERT_OK));
        assertEquals(Diagnose.OK, Diagnose.levelForCert(Diagnose.CERT_SYSTEM));
        assertEquals("首次要确认一次，不算故障", Diagnose.WARN, Diagnose.levelForCert(Diagnose.CERT_UNTRUSTED));
        assertEquals("换证最危险", Diagnose.BAD, Diagnose.levelForCert(Diagnose.CERT_CHANGED));
        assertEquals(Diagnose.WARN, Diagnose.levelForCert(Diagnose.CERT_NONE));
    }

    /* ───────── ④ 「端口连不上」的下一步（别只说连不上） ───────── */

    @Test
    public void portClosedAdvicePrefersWrongPortOnlyWhenConfiguredPortIsCommon() {
        assertEquals("配的就是常用端口、另一个常用端口通着 ⇒ 先怀疑端口写错",
                R.string.diag_adv_other_port, Diagnose.adviceForPortClosed(true, true, true));
        assertEquals(R.string.diag_adv_other_port, Diagnose.adviceForPortClosed(false, true, true));
        // ⭐ 实测踩到的误判：配 8097（非常用端口）而 3082/3080 通着 —— 那是「服务停了」，不是「端口写错」
        assertEquals("非常用端口不该被判成端口写错",
                R.string.diag_adv_private_closed, Diagnose.adviceForPortClosed(true, true, false));
        assertEquals(R.string.diag_adv_public_closed, Diagnose.adviceForPortClosed(false, true, false));
    }

    @Test
    public void portClosedAdviceSplitsPrivateAndPublic() {
        assertEquals("内网 ⇒ 查服务/防火墙/同网络",
                R.string.diag_adv_private_closed, Diagnose.adviceForPortClosed(true, false, true));
        assertEquals("公网 ⇒ 查地址与对外可达",
                R.string.diag_adv_public_closed, Diagnose.adviceForPortClosed(false, false, true));
    }

    @Test
    public void otherPortBecomesASoftHintWhenConfiguredPortIsNotCommon() {
        assertEquals(R.string.diag_hint_other_port, Diagnose.softHintForOtherPort(true, false));
        assertEquals("常用端口 ⇒ 已经有主建议了，不再重复",
                0, Diagnose.softHintForOtherPort(true, true));
        assertEquals("没有别的端口通 ⇒ 不提", 0, Diagnose.softHintForOtherPort(false, false));
    }

    @Test
    public void commonPortRecognition() {
        assertTrue(Diagnose.isCommonPort(3082));
        assertTrue(Diagnose.isCommonPort(3081));
        assertTrue(Diagnose.isCommonPort(3080));
        assertFalse(Diagnose.isCommonPort(8097));
        assertFalse(Diagnose.isCommonPort(0));
    }

    @Test
    public void firstOpenPortPicksTheFirstOneThatAnswers() {
        int[] ports = Diagnose.COMMON_PORTS;
        assertEquals(3081, Diagnose.firstOpenPort(ports, new boolean[]{false, true, true}));
        assertEquals(3082, Diagnose.firstOpenPort(ports, new boolean[]{true, true, true}));
        assertEquals("一个都不通 ⇒ -1（不要瞎给建议）", -1,
                Diagnose.firstOpenPort(ports, new boolean[]{false, false, false}));
        assertEquals(-1, Diagnose.firstOpenPort(null, null));
    }

    /* ───────── ⑤ 地址/端口解析与展示 ───────── */

    @Test
    public void portDefaultsByScheme() {
        assertEquals(3082, Diagnose.portOf("http://192.168.10.44:3082"));
        assertEquals("https 缺省 443", 443, Diagnose.portOf("https://nas.local"));
        assertEquals("http 缺省 80", 80, Diagnose.portOf("http://nas.local/"));
        assertEquals("解析不了给 -1", -1, Diagnose.portOf("不是地址"));
    }

    @Test
    public void hostParsingAndShortening() {
        assertEquals("192.168.10.44", Diagnose.hostOf("http://192.168.10.44:3082/x"));
        assertEquals("", Diagnose.hostOf("不是地址"));
        assertEquals("abc", Diagnose.shorten("abc", 5));
        assertEquals("abcde…", Diagnose.shorten("abcdef", 5));
        assertEquals("", Diagnose.shorten(null, 5));
    }

    @Test
    public void otherHistorySkipsCurrentAndCapsCount() {
        List<String> h = new ArrayList<>(Arrays.asList(
                "http://192.168.10.44:3082", "http://192.168.10.41:3082",
                "http://192.168.10.9:3082", "http://192.168.10.7:3082"));
        List<String> others = Diagnose.otherHistory(h, "http://192.168.10.44:3082", 2);
        assertEquals(2, others.size());
        assertFalse("当前配置那条不该出现在'其它地址'里",
                others.contains("http://192.168.10.44:3082"));
        assertTrue(others.contains("http://192.168.10.41:3082"));
        assertEquals(0, Diagnose.otherHistory(null, "x", 3).size());
        assertEquals(0, Diagnose.otherHistory(h, "http://192.168.10.44:3082", 0).size());
    }

    @Test
    public void portsLabelIsReadable() {
        assertEquals("3082 / 3081 / 3080", Diagnose.portsLabel(Diagnose.COMMON_PORTS));
        assertEquals("3082", Diagnose.portsLabel(new int[]{3082}));
    }
}
