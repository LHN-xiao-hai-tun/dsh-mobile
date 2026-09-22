package app.dshmobile;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网发现（v1.2.5 子网扫描 · **v1.2.7 加 mDNS/NSD 快路径**）
 *
 * 两条路径**并集去重**：
 *   ① **mDNS/NSD 快路径**——发现 `_dsh._tcp.` 服务（DSH 端广播，端口由 SRV 记录给出）。
 *      服务类型本身就是 DSH 自报 → 命中按**高置信（strong=true）**回调，
 *      且**命中后提前收摊子网扫描**（用户感知"秒出"）。
 *   ② **子网扫描兜底**——254 主机 × 3 端口，TCP 快筛 + HTTP 特征分级。
 *      ⚠️ **必须保留**：mDNS 会被 AP 隔离 / 路由器 IGMP snooping 挡掉，不能变成单点。
 *
 * 隐私口径（与本 App 其它部分一致）：
 *   · 只在局域网内探测（NSD 被动监听组播；子网扫描连上即断，不发送应用层数据）
 *   · 结果只留在内存里交给调用方，**不上传、不落盘、无第三方**
 *
 * 性能：NSD 命中通常 **≤1 秒**；子网扫描 254 主机 × 2 端口 = 508 次探测，
 * 48 线程 / 单次 350ms 超时 → 约 3~5 秒（NSD 命中后立即收摊）。
 */
final class LanScan {

    /**
     * 候选端口（**2026-09-22 修：补 3082**）。
     *
     * 为什么必须补 3082 —— 实测证据链：
     *   · 电脑侧 `dsh-pocket/settings.json` 的 `proxyPort` = **3082**（Pocket 的真实监听端口），
     *     而 3081 上**没有任何服务在听**；
     *   · 于是旧清单 `{3081, 3080}` 在真机上**必然扫不到**电脑：
     *     `adb shell curl http://<电脑IP>:3082/` = 200，而 3081 = 连不上、3080 只绑 loopback（超时）；
     *   · Pocket 首页标题是 `DSH Pocket · 正在进入` ⇒ 含 `dsh pocket`，
     *     命中 `classify()` 的**强特征** ⇒ 加进清单后能判为**高置信**（不是"候选需确认"）。
     * ⇒ 顺序把 3082 放最前（最常见命中先探，且 254×3 时早命中早收摊）。
     */
    private static final int[] PORTS = {3082, 3081, 3080};
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

    /* ---------- v1.2.7 · mDNS/NSD 快路径 ---------- */
    /** 服务类型（与 DSH 端广播契约一致；注意结尾的点） */
    private static final String NSD_SERVICE_TYPE = "_dsh._tcp.";
    /** NSD 发现总时长上限：到点收摊，不拖住 {@link Callback#onDone} */
    private static final long NSD_TIMEOUT_MS = 8000;
    /** 首次命中后的宽限：留一点时间收其余实例，然后收摊 */
    private static final long NSD_GRACE_MS = 1200;

    /** 结果出口（两条路径共用一个出口 → 去重 + 计数 + 提前收摊信号都在这里收口） */
    private interface Reporter {
        void report(String url, boolean strong);

        /** 请求子网扫描提前收摊（mDNS 已命中） */
        void stopSubnetScan();
    }

    /** 可重复调用的收摊动作（停发现 + 释放 MulticastLock + 结束本路径） */
    private interface Session {
        void stop();
    }

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
        final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger strongCount = new AtomicInteger(0);
        final AtomicInteger weakCount = new AtomicInteger(0);
        final AtomicInteger pendingPaths = new AtomicInteger(0);
        final AtomicBoolean doneSent = new AtomicBoolean(false);
        final AtomicBoolean stopSubnet = new AtomicBoolean(false);
        // NSD 收摊动作要在回调里用，但回调异步晚于赋值 → 用单元素持有器打破循环引用
        final Session[] nsdSession = new Session[1];

        final Reporter reporter = new Reporter() {
            @Override
            public void report(String url, boolean strong) {
                if (url == null || !seen.add(url)) return;   // 并集去重：同一 host:port 只报一次
                if (strong) strongCount.incrementAndGet();
                else weakCount.incrementAndGet();
                ui.post(() -> cb.onFound(url, strong));
                if (strong) {
                    stopSubnetScan();                        // mDNS 命中 → 子网扫描提前收摊
                    final Session s = nsdSession[0];
                    if (s != null) ui.postDelayed(s::stop, NSD_GRACE_MS);
                }
            }

            @Override
            public void stopSubnetScan() {
                stopSubnet.set(true);
            }
        };

        final Runnable finishPath = () -> {
            if (pendingPaths.decrementAndGet() == 0 && doneSent.compareAndSet(false, true)) {
                ui.post(() -> cb.onDone(strongCount.get(), weakCount.get()));
            }
        };

        final String prefix = subnetPrefix();
        // ⚠️ 先定总路径数再启动：否则任一路径**同步降级**会提前把 onDone 发出去，后续结果就成了"事后到货"
        pendingPaths.set(prefix == null ? 1 : 2);

        // ① 快路径：mDNS/NSD（拿不到 NsdManager / 组播被挡 → 静默降级）
        nsdSession[0] = startNsd(a, ui, reporter, finishPath);

        // ② 兜底路径：子网扫描（必须保留 —— mDNS 被 AP 隔离时不能变成单点）
        if (prefix == null) return;
        new Thread(() -> runSubnetScan(prefix, reporter, stopSubnet, finishPath), "lan-scan").start();
    }

    /**
     * 子网扫描（兜底路径）：TCP 快筛 + HTTP 特征分级。
     * `stop` 置位后后续任务立即返回 → 整条路径快速收摊，但**不影响** mDNS 已拿到的结果。
     */
    private static void runSubnetScan(final String prefix, final Reporter reporter,
                                      final AtomicBoolean stop, final Runnable onPathDone) {
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                for (final int port : PORTS) {
                    pool.execute(() -> {
                        if (stop.get()) return;
                        int tier = classify(host, port);
                        if (tier != TIER_NONE) {
                            reporter.report("http://" + host + ":" + port, tier == TIER_DSH);
                        }
                    });
                }
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(TOTAL_WAIT_SECONDS, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } finally {
            onPathDone.run();
        }
    }

    /**
     * mDNS/NSD 快路径：发现 `_dsh._tcp.` 并解析出 host/port。
     *
     * 服务类型是 DSH 端**自报**的 → 命中即按**高置信**回调（强于 HTTP 特征猜测）。
     * 任何失败/不可用一律**静默降级**：不抛异常、不阻塞、不影响子网扫描。
     */
    @SuppressWarnings("deprecation")
    private static Session startNsd(final Activity a, final Handler ui,
                                    final Reporter reporter, final Runnable onPathDone) {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final NsdManager[] mgr = new NsdManager[1];
        final NsdManager.DiscoveryListener[] listener = new NsdManager.DiscoveryListener[1];
        final WifiManager.MulticastLock[] lockRef = new WifiManager.MulticastLock[1];

        final Session session = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                if (mgr[0] != null && listener[0] != null) mgr[0].stopServiceDiscovery(listener[0]);
            } catch (Exception ignored) { }
            try {
                if (lockRef[0] != null && lockRef[0].isHeld()) lockRef[0].release();
            } catch (Exception ignored) { }
            onPathDone.run();
        };

        try {
            Context ctx = a.getApplicationContext();
            if (ctx == null) {
                session.stop();
                return session;
            }
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                session.stop();
                return session;
            }
            mgr[0] = nsd;

            // 组播锁：部分机型不持锁收不到 mDNS 应答；拿不到就继续（不致命）
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiManager.MulticastLock lock = wm.createMulticastLock("dsh-lan-scan");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                    lockRef[0] = lock;
                }
            } catch (Exception ignored) { }

            listener[0] = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String type) { }

                @Override
                public void onStartDiscoveryFailed(String type, int code) {
                    session.stop();   // 起不来 → 立刻收摊，交给子网扫描兜底
                }

                @Override
                public void onDiscoveryStopped(String type) { }

                @Override
                public void onStopDiscoveryFailed(String type, int code) { }

                @Override
                public void onServiceLost(NsdServiceInfo info) { }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    try {
                        mgr[0].resolveService(info, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo i, int code) {
                                // 解析失败就放弃这一个实例（子网扫描仍会兜底）
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo resolved) {
                                int port = resolved.getPort();
                                if (port <= 0) return;
                                String host = hostOf(resolved);
                                if (host == null) return;
                                reporter.report("http://" + host + ":" + port, true);
                            }
                        });
                    } catch (Exception ignored) { }
                }
            };
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener[0]);
            ui.postDelayed(session::stop, NSD_TIMEOUT_MS);   // 到时收摊
        } catch (Exception ignored) {
            session.stop();
        }
        return session;
    }

    /**
     * 取解析后的主机地址（只收 IPv4：地址栏与 HTTP 都走 IPv4）。
     * ⚠️ `getHost()` 在 API 34 已 deprecated，但 minSdk 26 只能用它——
     * `getHostAddresses()` 需要 API 34，为它抬 minSdk 不值得。
     */
    private static String hostOf(NsdServiceInfo info) {
        try {
            InetAddress addr = info.getHost();
            if (addr == null) return null;
            String host = addr.getHostAddress();
            if (host == null || host.isEmpty()) return null;
            if (host.indexOf(':') >= 0) return null;   // 排除 IPv6
            return host;
        } catch (Exception e) {
            return null;
        }
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
