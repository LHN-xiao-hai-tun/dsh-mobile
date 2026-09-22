package app.dshmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 服务器地址设置页。
 *
 * 只存用户自己填的地址，不预置任何服务器。
 * 下方列出「最近连接」历史（最多 5 条，纯本机），点一下即可填入。
 *
 * ⚠️ 文案约定：所有「用户可见」文字一律取自 strings.xml，代码里不写中文字面量。
 */
public class SettingsActivity extends Activity {

    /** 导出日志：SAF 文件选择器的请求码（批 3-5） */
    private static final int REQ_SAVE_LOG = 0x5A01;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_LOG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            String err = LogExporter.writeTo(this, data.getData());
            String msg = (err == null) ? getString(R.string.settings_log_saved) : err;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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
        title.setText(R.string.settings_conn_title);
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        hint.setText(R.string.settings_conn_hint);
        root.addView(hint);

        EditText et = new EditText(this);
        et.setHint(R.string.settings_url_hint);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        et.setText(Prefs.url(this));
        root.addView(et);

        // 扫描局域网（v1.2.5）：主动探测本子网的 DSH 端口 → 一键填入，
        // 免去"自己查电脑 IP"这一步（mDNS 需要 DSH 侧广播，故改用扫描，见 LanScan 注释）
        Button scan = new Button(this);
        scan.setText(R.string.settings_scan);
        scan.setAllCaps(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (8 * d);
        scan.setLayoutParams(slp);
        scan.setOnClickListener(v -> startScan(scan, et));
        root.addView(scan);

        // 最近连接：点一下填入（只读本机记录，不联网）
        List<String> history = Prefs.history(this);
        if (!history.isEmpty()) {
            TextView label = new TextView(this);
            label.setTextSize(13);
            label.setAlpha(0.6f);
            label.setPadding(0, (int) (18 * d), 0, (int) (4 * d));
            label.setText(R.string.settings_history_label);
            root.addView(label);

            for (final String h : history) {
                TextView row = new TextView(this);
                row.setText(h);
                row.setTextSize(14);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
                row.setOnClickListener(v -> {
                    et.setText(h);
                    et.setSelection(et.getText().length());
                });
                root.addView(row);
            }
        }

        Button save = new Button(this);
        save.setText(R.string.settings_save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        save.setLayoutParams(lp);
        save.setOnClickListener(v -> {
            String u = Prefs.normalize(et.getText().toString());
            if (u.isEmpty()) {
                Toast.makeText(this, R.string.settings_toast_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.setUrl(this, u);
            Prefs.pushHistory(this, u);
            Toast.makeText(this, R.string.settings_toast_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        Button clear = new Button(this);
        clear.setText(R.string.settings_clear);
        clear.setOnClickListener(v -> {
            Prefs.clearUrl(this);
            et.setText("");
            Toast.makeText(this, R.string.settings_toast_cleared, Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        // ---- v1.3.0 · 安全小节：本机加密说明 + 已信任证书（可逐条清除 / 一键重置） ----
        TextView secTitle = new TextView(this);
        secTitle.setText(R.string.settings_sec_title);
        secTitle.setTextSize(18);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = (int) (28 * d);
        secTitle.setLayoutParams(stlp);
        root.addView(secTitle);

        TextView crypto = new TextView(this);
        crypto.setTextSize(13);
        crypto.setAlpha(0.75f);
        crypto.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        crypto.setText(R.string.settings_crypto_note);
        root.addView(crypto);

        TextView certLabel = new TextView(this);
        certLabel.setTextSize(13);
        certLabel.setAlpha(0.6f);
        certLabel.setPadding(0, (int) (6 * d), 0, (int) (4 * d));
        certLabel.setText(R.string.settings_cert_label);
        root.addView(certLabel);

        java.util.Map<String, String> certs = Prefs.trustedCerts(this);
        if (certs.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextSize(13);
            none.setAlpha(0.6f);
            none.setText(R.string.settings_cert_none);
            root.addView(none);
        } else {
            for (final java.util.Map.Entry<String, String> en : certs.entrySet()) {
                TextView row = new TextView(this);
                row.setText(en.getKey() + "\n" + en.getValue());
                row.setTextSize(12);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
                row.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle(en.getKey())
                        .setMessage(getString(R.string.settings_cert_forget_msg, en.getKey()))
                        .setPositiveButton(R.string.settings_cert_forget, (dd, w) -> {
                            Prefs.forgetCert(this, en.getKey());
                            recreate();
                        })
                        .setNegativeButton(R.string.qc_cancel, null)
                        .show());
                root.addView(row);
            }
        }

        Button reset = new Button(this);
        reset.setText(R.string.settings_cert_clear);
        reset.setAllCaps(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (8 * d);
        reset.setLayoutParams(rlp);
        reset.setOnClickListener(v -> {
            Prefs.clearAllCerts(this);
            Prefs.clearAcked(this);
            Toast.makeText(this, R.string.settings_cert_cleared, Toast.LENGTH_SHORT).show();
            recreate();
        });
        root.addView(reset);

        // 导出诊断日志（批 3-5）：本机生成 + 脱敏 + 交给分享面板；App 从不自动上传
        TextView logNote = new TextView(this);
        logNote.setText(R.string.settings_log_note);
        logNote.setTextSize(12f);
        LinearLayout.LayoutParams lnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lnlp.topMargin = (int) (14 * d);
        logNote.setLayoutParams(lnlp);
        root.addView(logNote);

        Button exportLog = new Button(this);
        exportLog.setText(R.string.settings_log_export);
        exportLog.setAllCaps(false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = (int) (8 * d);
        exportLog.setLayoutParams(elp);
        exportLog.setOnClickListener(v -> {
            // 主路径：系统文件选择器 → 存成真实文件（用户可存到「下载」，之后随便传）
            try {
                startActivityForResult(LogExporter.saveIntent(LogExporter.suggestedName()), REQ_SAVE_LOG);
            } catch (Exception e) {
                Toast.makeText(this, R.string.settings_log_fail, Toast.LENGTH_LONG).show();
            }
        });
        root.addView(exportLog);

        // 「关于」入口：版本 / 许可 / 致谢 / 检查更新
        Button about = new Button(this);
        about.setText(R.string.about_heading);
        about.setAllCaps(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) (28 * d);
        about.setLayoutParams(alp);
        about.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        root.addView(about);

        setContentView(scroll);
    }

    /**
     * 扫描局域网并把结果交给用户选（v1.2.5 · **v1.2.6 加分级**）。
     * 高置信唯一 → 直接填入；高置信与候选混在一起 → 高置信在前、候选带「（需确认）」后缀弹列表；
     * 全 0 → 提示排查方向。
     *
     * ⭐ 为什么要分级：旧判据 `dsh || harness || deepseek` 三个都是泛词 → 实测
     * `https://www.deepseek.com/` 会被误认成 DSH（误报实证见
     * `建议\DSH文档\评价\dsh-mobile_HTTP误报率实测与v1.2.6清单_2026-09-22.md`）。
     */
    private void startScan(final Button scan, final EditText et) {
        scan.setEnabled(false);
        scan.setText(R.string.settings_scan_discovering);   // v1.2.7：先试 mDNS 快路径（命中通常 ≤1 秒）
        final List<String> strong = new java.util.ArrayList<>();
        final List<String> weak = new java.util.ArrayList<>();
        LanScan.start(this, new LanScan.Callback() {
            @Override
            public void onFound(String url, boolean isStrong) {
                if (isStrong) {
                    strong.add(url);
                    scan.setText(getString(R.string.settings_scan_strong_hit, strong.size()));
                } else {
                    weak.add(url);
                    scan.setText(getString(R.string.settings_scan_found, strong.size() + weak.size()));
                }
            }

            @Override
            public void onDone(int strongTotal, int weakTotal) {
                scan.setEnabled(true);
                scan.setText(R.string.settings_scan);

                if (strongTotal == 0 && weakTotal == 0) {
                    Toast.makeText(SettingsActivity.this, R.string.settings_scan_none,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // 唯一高置信 → 直接填入（v1.2.5 行为，保留）
                if (strongTotal == 1) {
                    et.setText(strong.get(0));
                    et.setSelection(et.getText().length());
                    return;
                }
                // 多个高置信 / 只有弱候选 → 弹列表：高置信在前，弱候选带后缀
                final List<String> labels = new java.util.ArrayList<>(strong);
                final List<String> urls = new java.util.ArrayList<>(strong);
                for (final String w : weak) {
                    labels.add(w + getString(R.string.settings_scan_weak_suffix));
                    urls.add(w);
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.settings_scan_pick)
                        .setItems(labels.toArray(new String[0]), (dd, which) -> {
                            et.setText(urls.get(which));
                            et.setSelection(et.getText().length());
                        })
                        .setNegativeButton(R.string.qc_cancel, null)
                        .show();
            }
        });
    }
}
