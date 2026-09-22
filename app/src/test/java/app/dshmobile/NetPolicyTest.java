package app.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 连接安全策略的**分级判据**回归网（v1.3.6 补）。
 *
 * ── 为什么这块值得测
 *   `isPrivateHost` 决定「明文 http 是**放行（确认一次）**还是**默认拦下**」——
 *   判错的方向只有两种：把公网当内网（**放松了防护**），或把内网当公网（**天天弹窗**）。
 *   而它的实现是一串 CIDR 边界比较（10/8、172.16/12、192.168/16、127/8、169.254/16），
 *   这类代码**最容易在边界上写错**（例如 172.15 与 172.32 都该是"公网"）。
 *   纯逻辑、无 Android 依赖 ⇒ 正好用 JVM 单测钉住。
 *
 * ⚠️ 覆盖边界：这里只测**不碰 Android API** 的部分。
 *   `hostOf()` 走 `android.net.Uri.parse`，`verdict()` 对**明文**地址会调它
 *   ⇒ 那两条路径需要真机/模拟器（或 Robolectric）才能测，本文件不假装测过。
 *   （`verdict()` 对非明文地址在调 hostOf **之前**就返回 OK，所以那条分支可以测。）
 */
public class NetPolicyTest {

    /* ───────── isCleartext ───────── */

    @Test
    public void cleartextIsCaseInsensitiveAndSchemeOnly() {
        assertTrue(NetPolicy.isCleartext("http://192.168.1.5:3082"));
        assertTrue("大写也要认", NetPolicy.isCleartext("HTTP://192.168.1.5:3082"));
        assertFalse(NetPolicy.isCleartext("https://example.com"));
        assertFalse(NetPolicy.isCleartext("httpx://example.com"));
        assertFalse(NetPolicy.isCleartext(null));
        assertFalse(NetPolicy.isCleartext(""));
        assertFalse("只是把 http 当子串，不算明文地址", NetPolicy.isCleartext("ftp://http.example.com"));
    }

    /* ───────── isPrivateHost：IPv4 私有段与边界 ───────── */

    @Test
    public void ipv4PrivateRangesArePrivate() {
        assertTrue(NetPolicy.isPrivateHost("10.0.0.1"));
        assertTrue(NetPolicy.isPrivateHost("10.255.255.255"));
        assertTrue(NetPolicy.isPrivateHost("172.16.0.1"));
        assertTrue(NetPolicy.isPrivateHost("172.31.255.255"));
        assertTrue(NetPolicy.isPrivateHost("192.168.0.1"));
        assertTrue(NetPolicy.isPrivateHost("192.168.10.44"));  // 本机电脑当前地址
        assertTrue(NetPolicy.isPrivateHost("127.0.0.1"));
        assertTrue(NetPolicy.isPrivateHost("169.254.1.1"));    // 链路本地（无 DHCP 时的 169.254.x.x）
        assertTrue(NetPolicy.isPrivateHost("0.0.0.0"));
    }

    @Test
    public void ipv4BoundariesAreStrict() {
        // 🔴 172.16/12 的两个边界外侧 —— 写错一位就会把公网当内网
        assertFalse("172.15.x 不在 172.16/12 内", NetPolicy.isPrivateHost("172.15.255.255"));
        assertFalse("172.32.x 不在 172.16/12 内", NetPolicy.isPrivateHost("172.32.0.0"));
        assertFalse(NetPolicy.isPrivateHost("192.169.0.1"));
        assertFalse(NetPolicy.isPrivateHost("11.0.0.1"));
        assertFalse(NetPolicy.isPrivateHost("8.8.8.8"));
    }

    @Test
    public void invalidIPv4IsNotPrivate() {
        assertFalse("超出 0~255", NetPolicy.isPrivateHost("999.1.1.1"));
        assertFalse("段数不对", NetPolicy.isPrivateHost("1.2.3"));
        assertFalse("段数不对", NetPolicy.isPrivateHost("1.2.3.4.5"));
        assertFalse(NetPolicy.isPrivateHost("192.168.1.-1"));
    }

    /* ───────── 主机名 / 环回 / IPv6 ───────── */

    @Test
    public void localNamesArePrivate() {
        assertTrue(NetPolicy.isPrivateHost("localhost"));
        assertTrue("单标签主机名（局域网才解析得出）", NetPolicy.isPrivateHost("nas"));
        assertTrue(NetPolicy.isPrivateHost("dsh.local"));
        assertTrue(NetPolicy.isPrivateHost("nas.lan"));
        assertTrue(NetPolicy.isPrivateHost("router.home"));
    }

    @Test
    public void publicDomainsAreNotPrivate() {
        assertFalse(NetPolicy.isPrivateHost("example.com"));
        assertFalse(NetPolicy.isPrivateHost("deepseek.com"));
        assertFalse("从严：带点的域名一律按公网处理", NetPolicy.isPrivateHost("myrouter.local.example.com"));
    }

    @Test
    public void ipv6LoopbackAndUlaArePrivate() {
        assertTrue(NetPolicy.isPrivateHost("::1"));
        assertTrue("ULA fc00::/7", NetPolicy.isPrivateHost("fd00::1"));
        assertTrue(NetPolicy.isPrivateHost("fc00::1"));
        assertFalse(NetPolicy.isPrivateHost("2001:db8::1"));
    }

    @Test
    public void nullAndEmptyAreNotPrivate() {
        assertFalse(NetPolicy.isPrivateHost(null));
        assertFalse(NetPolicy.isPrivateHost(""));
    }

    /* ───────── verdict：只测不碰 Android API 的那条分支 ───────── */

    @Test
    public void httpsIsAlwaysOkAndDoesNotTouchHostParsing() {
        // 非明文 ⇒ 在调 hostOf 之前就返回 OK（所以这条分支可以在 JVM 上安全地测）
        assertEquals(NetPolicy.Verdict.OK, NetPolicy.verdict("https://example.com"));
        assertEquals(NetPolicy.Verdict.OK, NetPolicy.verdict("https://192.168.1.5:8443"));
        assertEquals(NetPolicy.Verdict.OK, NetPolicy.verdict(""));
        assertEquals(NetPolicy.Verdict.OK, NetPolicy.verdict(null));
    }
}
