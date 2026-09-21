package app.dshmobile;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描发现（v1.2.5）
 *
 * ⚠️ **为什么不用 mDNS**：mDNS / NSD 发现需要**服务端广播** `_dsh._tcp.local`，
 * 而 DSH 侧目前**不广播任何服务**（要改 DSH 源码或写插件才行）——
 * 所以纯 App 侧做 mDNS 会**扫不到任何东西**。改为**主动扫本机子网的常见端口**：
 * 同样是一键发现，但**不需要动 DSH 一行代码**。
 *
 * 隐私口径（与本 App 其它部分一致）：
 *   · 只在本机所在子网内做 **TCP 连接探测**（连上即断，**不发送任何应用层数据**）
 *   · 结果只留在内存里交给调用方，**不上传、不落盘、无第三方**
 *
 * 性能：254 主机 × 2 端口 = 508 次探测，48 线程 / 单次 350ms 超时 → 约 3~5 秒扫完。
 */
final class LanScan {

    /** 候选端口：3081 = dsh-pocket 局域网（推荐）· 3080 = Web UI */
    private static final int[] PORTS = {3081, 3080};
    /** 分级结果（v1.2.6）：无 / 高置信 DSH / 仅泛词命中（候选） */
    private static final int TIER_NONE = 0;
    private static final int TIER_DSH = 1;
    private static final int TIER_MAYBE = 2;
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int THREADS = 48;
    private static final int TOTAL_WAIT_SECONDS = 30;
    /** HTTP 验证阶段：读超时 / 读取字节上限（只看特征，读一点点就够） */
    private static final int HTTP_READ_TIMEOUT_MS = 900;
    private static final int PROBE_BYTES = 4096;

    interface Callback {
        /**
         * 每发现一台就回调一次（**主线程**）。
         *
         * @param strong true = 命中**强特征**（高置信，直接当 DSH）；false = 仅命中泛词（候选，需人工确认）
         */
        void onFound(String url, boolean strong);

        /**
         * 全部扫完（**主线程**）。
         *
         * @param strong 高置信台数 · @param weak 仅泛词命中的候选台数（两者都 0 = 没找到）
         */
        void onDone(int strong, int weak);
    }

    private LanScan() {
    }

    static void start(final Activity a, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final String prefix = subnetPrefix();
            if (prefix == null) {
                // 没连 WiFi / 拿不到子网 —— 直接告知"没找到"，不抛异常
                ui.post(() -> cb.onDone(0, 0));
                return;
            }
            final AtomicInteger strongCount = new AtomicInteger(0);
            final AtomicInteger weakCount = new AtomicInteger(0);
            final AtomicInteger remaining = new AtomicInteger(254 * PORTS.length);
            final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                for (final int port : PORTS) {
                    pool.execute(() -> {
                        try {
                            int tier = classify(host, port);
                            if (tier != TIER_NONE) {
                                final String url = "http://" + host + ":" + port;
                                final boolean strong = (tier == TIER_DSH);
                                if (strong) strongCount.incrementAndGet();
                                else weakCount.incrementAndGet();
                                ui.post(() -> cb.onFound(url, strong));
                            }
                        } finally {
                            if (remaining.decrementAndGet() == 0) {
                                ui.post(() -> cb.onDone(strongCount.get(), weakCount.get()));
                            }
                        }
                    });
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(TOTAL_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // 兜底：万一有线程卡死没把 remaining 减到 0，也要给调用方一个结束信号
            if (remaining.get() > 0) {
                ui.post(() -> cb.onDone(strongCount.get(), weakCount.get()));
            }
        }, "lan-scan").start();
    }

    /**
     * 取本机**无线/有线网卡**的 IPv4 子网前缀（形如 `192.168.1.`）；取不到返回 null。
     * 用 {@link NetworkInterface} 而非 WifiManager —— 后者在 API 31+ 要定位/近场权限，前者不要。
     */
    private static String subnetPrefix() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (!(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap"))) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        int dot = ip.lastIndexOf('.');
                        if (dot > 0) return ip.substring(0, dot + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 两段式判定：**先 TCP 连通快筛，再 HTTP 特征分级**。
     *
     * 为什么不能只看 TCP：子网里任何占用 3081/3080 的设备（路由器管理页 / 打印机 /
     * 别的开发服务）都会被当成候选 —— 这是 2026-09-22 真机测试暴露的缺陷。
     *
     * ⭐ v1.2.6 **分级**（据 2026-09-22 误报实测，见
     * `建议\DSH文档\评价\dsh-mobile_HTTP误报率实测与v1.2.6清单_2026-09-22.md`）：
     * 旧的单一布尔 `dsh || harness || deepseek` **三个都是泛词** → 实测
     * `https://www.deepseek.com/` 会被误认成 DSH。改为两级：
     *   · **强特征**（`dsh pocket` / `dsh web authentication` / `deepseek harness` / 标题含 DSH）
     *     → 高置信，直接当 DSH；
     *   · **泛词**（单独出现 `deepseek` / `harness` / `dsh`）→ 候选，交人工确认。
     *
     * 隐私：HTTP 探测读到的正文**只在内存里做特征匹配后丢弃**，不落盘、不外传。
     */
    private static int classify(String host, int port) {
        if (!tcpOpen(host, port)) return TIER_NONE;
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            URL u = new URL("http", host, port, "/");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "text/html");
            int code = c.getResponseCode();
            if (code <= 0) return TIER_NONE;
            // 4xx（开了访问密码的 DSH）正文在 errorStream 里
            in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            if (in == null) return TIER_NONE;
            byte[] buf = new byte[PROBE_BYTES];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String body = new String(buf, 0, Math.max(0, n), "UTF-8").toLowerCase();
            String title = titleOf(body);

            // ── 级 A：强特征（DSH 专有短语 / 标题含 DSH）──
            if (body.contains("dsh pocket")
                    || body.contains("dsh web authentication")
                    || body.contains("deepseek harness")
                    || body.contains("dsh 本地构建")
                    || title.contains("dsh")
                    || title.contains("harness")) {
                return TIER_DSH;
            }
            // ── 级 B：仅泛词 ──
            if (body.contains("deepseek") || body.contains("harness") || body.contains("dsh")) {
                return TIER_MAYBE;
            }
            return TIER_NONE;
        } catch (Exception e) {
            return TIER_NONE;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /** 取 `<title>…</title>` 的内容（取不到返回空串）；用于强特征判定 */
    private static String titleOf(String lowerBody) {
        int open = lowerBody.indexOf("<title>");
        if (open < 0) return "";
        int close = lowerBody.indexOf("</title>", open);
        if (close < 0) return lowerBody.substring(open + 7, Math.min(lowerBody.length(), open + 207));
        return lowerBody.substring(open + 7, close);
    }

    /** 纯 TCP 连通探测（**不发送任何数据**），作为 HTTP 验证的前置快筛 */
    private static boolean tcpOpen(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
