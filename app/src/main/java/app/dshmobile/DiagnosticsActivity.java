package app.dshmobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接诊断页（B1 · v1.3.9）。
 *
 * 从「设置 → 连接诊断」进。逐项探一遍**并把「下一步怎么办」写在每一项下面**：
 *   手机网络 → 配置地址 → 端口可达 → 同机其它端口 → 服务器证书 → 服务响应 → 最近的其它地址
 *
 * ── 三条口径（与 {@link Diagnose} 注释一致）
 *   ① 只读：不改配置、不改信任表、不落盘；只连**你自己填过**的那些地址（含最近连接里的历史）。
 *   ② 不为诊断放宽安全：证书探针读完指纹就抛（不是 trust-all）；HTTP 探针复用下载侧同一套信任口径。
 *   ③ 用户可见文字一律取自 strings.xml。
 */
public class DiagnosticsActivity extends Activity {

    private LinearLayout list;
    private TextView summary;
    private Button rerun;
    private boolean running = false;

    /** 一行诊断结果（跑完探针后填充） */
    private static final class Item {
        final String label;
        final int level;
        final String verdict;
        final String advice;
        final String detail;

        Item(String label, int level, String verdict, String advice, String detail) {
            this.label = label;
            this.level = level;
            this.verdict = verdict;
            this.advice = advice;
            this.detail = detail;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.diag_title);
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setAlpha(0.7f);
        hint.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        hint.setText(R.string.diag_hint);
        root.addView(hint);

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        summary.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        root.addView(summary);

        rerun = new Button(this);
        rerun.setText(R.string.diag_run);
        rerun.setAllCaps(false);
        rerun.setOnClickListener(v -> runDiagnostics());
        root.addView(rerun);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (8 * d);
        list.setLayoutParams(llp);
        root.addView(list);

        Button copy = new Button(this);
        copy.setText(R.string.diag_copy);
        copy.setAllCaps(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (16 * d);
        copy.setLayoutParams(clp);
        final List<Item> shown = new ArrayList<>();
        this.lastItems = shown;
        copy.setOnClickListener(v -> {
            if (shown.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.diag_report_title)).append('\n');
            for (Item it : shown) {
                sb.append(Diagnose.line(it.label, it.verdict, it.advice)).append('\n');
                if (!it.detail.isEmpty()) sb.append("    ").append(it.detail).append('\n');
            }
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.diag_report_title), sb.toString()));
                    Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception ignored) { }
            Toast.makeText(this, R.string.diag_copy_fail, Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        setContentView(scroll);
        runDiagnostics();
    }

    /** 上一次跑出来的行（「复制报告」用） */
    private List<Item> lastItems = new ArrayList<>();

    private int colorOf(int level) {
        switch (level) {
            case Diagnose.OK: return 0xFF34C759;
            case Diagnose.WARN: return 0xFFFF9F0A;
            case Diagnose.BAD: return 0xFFFF3B30;
            default: return 0xFF8E8E93;
        }
    }

    private void runDiagnostics() {
        if (running) return;
        running = true;
        rerun.setEnabled(false);
        rerun.setText(R.string.diag_running);
        summary.setText(R.string.diag_running);
        summary.setTextColor(0xFF8E8E93);
        list.removeAllViews();

        new Thread(() -> {
            final List<Item> items = new ArrayList<>();
            final String url = Prefs.url(this);
            final String host = Diagnose.hostOf(url);
            final int port = Diagnose.portOf(url);
            final boolean https = Diagnose.lower(url).startsWith("https://");
            final int t = 2500;

            // ① 手机网络
            boolean net = Diagnose.hasNetwork(this);
            items.add(new Item(getString(R.string.diag_item_network),
                    net ? Diagnose.OK : Diagnose.BAD,
                    net ? getString(R.string.diag_net_ok, Diagnose.networkType(this))
                        : getString(R.string.diag_net_none),
                    net ? "" : getString(R.string.diag_adv_net_none), ""));

            if (url.isEmpty()) {
                items.add(new Item(getString(R.string.diag_item_url), Diagnose.BAD,
                        getString(R.string.diag_url_none),
                        getString(R.string.diag_adv_url_none), ""));
                finishRun(items, Diagnose.BAD);
                return;
            }

            // ② 配置地址
            NetPolicy.Verdict npv = NetPolicy.verdict(url);
            int urlLevel = (npv == NetPolicy.Verdict.BLOCK_STRONG) ? Diagnose.WARN : Diagnose.OK;
            items.add(new Item(getString(R.string.diag_item_url), urlLevel,
                    getString(R.string.diag_url_ok,
                            Diagnose.shorten(url, 60),
                            getString(https ? R.string.diag_scheme_tls : R.string.diag_scheme_cleartext)),
                    (npv == NetPolicy.Verdict.BLOCK_STRONG)
                            ? getString(R.string.diag_adv_public_cleartext) : "",
                    ""));

            // ③ 配置端口可达 + ⑤ 同机其它端口（一次探完，省时间）
            boolean open = Diagnose.tcpOpen(host, port, t);
            boolean[] otherOpen = new boolean[Diagnose.COMMON_PORTS.length];
            for (int i = 0; i < Diagnose.COMMON_PORTS.length; i++) {
                int p = Diagnose.COMMON_PORTS[i];
                otherOpen[i] = (p == port) ? open : Diagnose.tcpOpen(host, p, t);
            }
            int otherPort = Diagnose.firstOpenPort(Diagnose.COMMON_PORTS, otherOpen);

            if (open) {
                items.add(new Item(getString(R.string.diag_item_port), Diagnose.OK,
                        getString(R.string.diag_port_open, host, port), "", ""));
            } else {
                boolean common = Diagnose.isCommonPort(port);
                int advRes = Diagnose.adviceForPortClosed(NetPolicy.isPrivateHost(host), otherPort > 0, common);
                String advice = (otherPort > 0 && common)
                        ? getString(advRes, otherPort) : getString(advRes);
                int hintRes = Diagnose.softHintForOtherPort(otherPort > 0, common);
                if (hintRes != 0) advice = advice + "\n" + getString(hintRes, otherPort);
                items.add(new Item(getString(R.string.diag_item_port), Diagnose.BAD,
                        getString(R.string.diag_port_closed, host, port), advice, ""));
            }

            StringBuilder openList = new StringBuilder();
            for (int i = 0; i < Diagnose.COMMON_PORTS.length; i++) {
                if (otherOpen[i]) {
                    if (openList.length() > 0) openList.append(" / ");
                    openList.append(Diagnose.COMMON_PORTS[i]);
                }
            }
            items.add(new Item(getString(R.string.diag_item_ports), Diagnose.INFO,
                    (openList.length() > 0)
                            ? getString(R.string.diag_ports_hint, openList.toString())
                            : getString(R.string.diag_ports_none),
                    "", getString(R.string.diag_ports_all, Diagnose.portsLabel(Diagnose.COMMON_PORTS))));

            // ④ 服务响应 + 证书（一次请求同时得出）
            Diagnose.HttpProbe probe = Diagnose.httpStatus(this, url, 4000);
            boolean connected = probe.code > 0;

            // 证书那行要给出"你看到的到底是哪张证书" ⇒ 连接被证书挡下时**只读地**取一次指纹
            String actual = probe.fingerprint;
            if (actual.isEmpty() && probe.certFailed) {
                actual = Diagnose.certFingerprintOf(host, port, 4000);
            }
            String remembered = Prefs.trustedFingerprint(this, host);
            int cv = Diagnose.certVerdictAfterProbe(connected, probe.certFailed, remembered, actual);
            int clv = Diagnose.levelForCert(cv);
            String cverdict;
            String cadvice = "";
            switch (cv) {
                case Diagnose.CERT_OK:
                    cverdict = getString(R.string.diag_cert_ok);
                    break;
                case Diagnose.CERT_SYSTEM:
                    cverdict = getString(R.string.diag_cert_system);
                    break;
                case Diagnose.CERT_CHANGED:
                    cverdict = getString(R.string.diag_cert_changed);
                    cadvice = getString(R.string.diag_adv_cert_changed);
                    break;
                case Diagnose.CERT_UNTRUSTED:
                    cverdict = getString(R.string.diag_cert_untrusted);
                    cadvice = getString(R.string.diag_adv_cert_untrusted);
                    break;
                case Diagnose.CERT_SKIPPED:
                    cverdict = getString(R.string.diag_cert_skipped);
                    break;
                default:
                    cverdict = getString(R.string.diag_cert_none);
            }
            if (https) {
                items.add(new Item(getString(R.string.diag_item_cert), clv, cverdict, cadvice,
                        actual.isEmpty() ? "" : getString(R.string.diag_cert_fp, actual)));
            }

            if (connected) {
                int lv = Diagnose.levelForHttp(probe.code);
                int msgRes = Diagnose.httpMessageRes(probe.code);
                String advice = "";
                if (probe.code == 401 || probe.code == 403) advice = getString(R.string.diag_adv_http_auth);
                else if (probe.code >= 500) advice = getString(R.string.diag_adv_http_5xx);
                else if (probe.code == 404) advice = getString(R.string.diag_adv_http_404);
                items.add(new Item(getString(R.string.diag_item_http), lv,
                        getString(msgRes, probe.code), advice, ""));
            } else {
                // 证书类失败要给出**与下载侧同一句人话**（那条消息本身就是最准的指引）
                java.security.cert.CertificateException ce = Downloader.certCause(probe.cause);
                String human;
                if (ce != null && ce.getMessage() != null && !ce.getMessage().trim().isEmpty()) {
                    human = ce.getMessage();
                } else if (probe.cause != null) {
                    human = getString(Downloader.userMessageFor(probe.cause));
                } else {
                    human = getString(R.string.diag_http_failed);
                }
                items.add(new Item(getString(R.string.diag_item_http), Diagnose.BAD, human,
                        getString(R.string.diag_adv_http_failed),
                        probe.error.isEmpty() ? ""
                                : getString(R.string.diag_http_raw, Diagnose.shorten(probe.error, 100))));
            }

            // ⑥ 最近的其它地址（帮用户发现"电脑 IP 漂了"）
            List<String> others = Diagnose.otherHistory(Prefs.history(this), url, 3);
            if (!others.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                boolean anyReachable = false;
                for (String o : others) {
                    boolean ok = Diagnose.tcpOpen(Diagnose.hostOf(o), Diagnose.portOf(o), 1500);
                    anyReachable = anyReachable || ok;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(getString(ok ? R.string.diag_hist_ok : R.string.diag_hist_bad,
                            Diagnose.shorten(o, 48)));
                }
                items.add(new Item(getString(R.string.diag_item_history), Diagnose.INFO,
                        sb.toString(),
                        (anyReachable && !open) ? getString(R.string.diag_adv_hist) : "", ""));
            }

            int overall = Diagnose.overallLevel(levelsOf(items));
            finishRun(items, overall);
        }, "dsh-diagnose").start();
    }

    private static int[] levelsOf(List<Item> items) {
        int[] lv = new int[items.size()];
        for (int i = 0; i < items.size(); i++) lv[i] = items.get(i).level;
        return lv;
    }

    /** 回到主线程渲染 */
    private void finishRun(final List<Item> items, final int overall) {
        runOnUiThread(() -> {
            running = false;
            rerun.setEnabled(true);
            rerun.setText(R.string.diag_run);

            summary.setText(overall == Diagnose.BAD ? R.string.diag_sum_bad
                    : (overall == Diagnose.WARN ? R.string.diag_sum_warn : R.string.diag_sum_ok));
            summary.setTextColor(colorOf(overall));

            lastItems.clear();
            lastItems.addAll(items);

            float d = getResources().getDisplayMetrics().density;
            for (Item it : items) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                int p = (int) (8 * d);
                box.setPadding(p, p, p, p);

                TextView head = new TextView(this);
                head.setTextSize(15);
                head.setTypeface(Typeface.DEFAULT_BOLD);
                head.setTextColor(colorOf(it.level));
                head.setText(it.label + " — " + it.verdict);
                box.addView(head);

                if (!it.advice.isEmpty()) {
                    TextView adv = new TextView(this);
                    adv.setTextSize(13);
                    adv.setAlpha(0.85f);
                    adv.setPadding(0, (int) (4 * d), 0, 0);
                    adv.setText("→ " + it.advice);
                    box.addView(adv);
                }
                if (!it.detail.isEmpty()) {
                    TextView det = new TextView(this);
                    det.setTextSize(11);
                    det.setAlpha(0.6f);
                    det.setTypeface(Typeface.MONOSPACE);
                    det.setPadding(0, (int) (4 * d), 0, 0);
                    det.setText(it.detail);
                    box.addView(det);
                }
                list.addView(box);
            }
        });
    }
}
