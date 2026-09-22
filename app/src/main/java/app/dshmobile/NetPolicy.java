package app.dshmobile;

import java.util.Locale;

/**
 * 连接安全策略（纯逻辑，无 Android 依赖，便于审查）。
 *
 * ── 为什么明文策略必须在**代码层**做，而不是 network_security_config.xml
 *    Android 的 `<domain>` **只接受域名**，不接受 IP，也不支持 CIDR 网段。
 *    而本 App 的典型地址恰恰是 `http://192.168.x.x:3081` —— 用 XML 根本表达不出
 *    「只允许私有网段明文」。网上流传的那种 `<domain>192.168.0.0</domain>` 写法是**无效的**
 *    （它被当成一个名叫 "192.168.0.0" 的域名，永远匹配不上）。
 *    ⇒ XML 只负责"全局是否允许明文 + 信任哪些 CA"，**按地址的放行判断在这里**。
 */
final class NetPolicy {

    private NetPolicy() {
    }

    /** 明文 http（大小写不敏感） */
    static boolean isCleartext(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 是否「私有 / 本机」地址 —— 明文只在这类地址上放行。
     *
     * 覆盖：IPv4 私有段（10/8、172.16/12、192.168/16）、环回（127/8、::1）、
     * 链路本地（169.254/16）、IPv6 ULA（fc00::/7）、以及**不带点的单标签主机名**
     * （如 `dsh.local`、`nas`：局域网内才解析得出来的名字）。
     */
    static boolean isPrivateHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);

        if (h.equals("localhost") || h.equals("::1") || h.startsWith("[::1]")) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7（以 fc / fd 开头）
        if (h.contains(":") && (h.startsWith("fc") || h.startsWith("fd"))) {
            return true;
        }
        // 单标签主机名 / .local / .lan / .home —— 公网 DNS 解析不出来的名字
        if (!h.contains(".") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) {
            return true;
        }
        String[] p = h.split("\\.");
        if (p.length != 4) {
            return false;   // 域名（非 IP 字面量）→ 当公网处理，从严
        }
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
            for (String s : p) {
                int v = Integer.parseInt(s);
                if (v < 0 || v > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return a == 10                                  // 10.0.0.0/8
                || (a == 172 && b >= 16 && b <= 31)      // 172.16.0.0/12
                || (a == 192 && b == 168)                // 192.168.0.0/16
                || a == 127                             // 环回
                || (a == 169 && b == 254)                // 链路本地（含 Windows 无 DHCP 时的 169.254.x.x）
                || a == 0;
    }

    /**
     * 判定结果。
     * OK           —— 放行，不打扰
     * CONFIRM_ONCE —— 私有网段明文：合理用法，但**明确告知是明文**，首次确认一次
     * BLOCK_STRONG —— 公网 + 明文：默认拦下，需要用户明确接受风险才继续
     */
    enum Verdict { OK, CONFIRM_ONCE, BLOCK_STRONG }

    static Verdict verdict(String url) {
        if (!isCleartext(url)) {
            return Verdict.OK;               // https：不打扰
        }
        return isPrivateHost(hostOf(url)) ? Verdict.CONFIRM_ONCE : Verdict.BLOCK_STRONG;
    }
}
