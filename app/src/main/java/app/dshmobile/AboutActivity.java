package app.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「关于」页。
 *
 * 展示：版本号（运行时读取，不写死）、许可、致谢、仓库入口、检查更新。
 *
 * ── 检查更新（v1.3.9 · C1）
 *   以前点它只是**用浏览器打开 Releases 页** ⇒ App 根本不知道有没有新版。
 *   现在会 GET 一次 GitHub 的 `releases/latest` 与本机版本比对：
 *   **只有用户点它才联网**（不静默外联）· 有新版只给「打开下载页」（**不自动下载**）·
 *   无网/限流/失败一律给人话。
 *
 * ⚠️ 文案约定：所有「用户可见」文字一律取自 strings.xml，代码里不写中文字面量
 *    （集中管理 = 防乱码 + 为多语言留口）。
 */
public class AboutActivity extends Activity {

    static final String REPO = "https://github.com/LHN-xiao-hai-tun/dsh-mobile";
    private static final String LICENSE_URL = REPO + "/blob/master/LICENSE";
    private static final String DSH_URL = "https://github.com/deepseek-ai/deepseek-harness";

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

        // 标题
        TextView title = new TextView(this);
        title.setText(R.string.about_heading);
        title.setTextSize(20);
        root.addView(title);

        // 版本（运行时读取）
        TextView ver = new TextView(this);
        ver.setTextSize(13);
        ver.setPadding(0, (int) (10 * d), 0, (int) (18 * d));
        ver.setText(getString(R.string.about_version_fmt, appVersion()));
        root.addView(ver);

        // 定位
        TextView what = new TextView(this);
        what.setTextSize(14);
        what.setText(R.string.about_intro);
        root.addView(what);

        // 许可
        section(root, d, getString(R.string.about_section_license));
        TextView lic = new TextView(this);
        lic.setTextSize(14);
        lic.setText(R.string.about_license_text);
        root.addView(lic);
        root.addView(linkButton(d, getString(R.string.about_license_btn), LICENSE_URL));

        // 致谢
        section(root, d, getString(R.string.about_section_thanks));
        TextView thx = new TextView(this);
        thx.setTextSize(14);
        thx.setText(R.string.about_thanks_text);
        root.addView(thx);
        root.addView(linkButton(d, getString(R.string.about_dsh_btn), DSH_URL));

        // 项目
        section(root, d, getString(R.string.about_section_repo));
        TextView repoDesc = new TextView(this);
        repoDesc.setTextSize(14);
        repoDesc.setText(R.string.about_repo_text);
        root.addView(repoDesc);
        root.addView(linkButton(d, getString(R.string.about_repo_btn), REPO));

        // 检查更新（v1.3.9 · C1：变成**真检查**）
        //   ⚠️ 三条口径：只有你点它才联网查（不静默外联）· 发现有新版只给「打开下载页」（不自动下载）
        //      · 无网/限流/查询失败一律给人话，界面照常可用。
        Button update = new Button(this);
        update.setText(R.string.about_check_update);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = (int) (8 * d);
        update.setLayoutParams(ulp);
        update.setOnClickListener(v -> checkUpdate(update));
        root.addView(update);

        TextView updateNote = new TextView(this);
        updateNote.setTextSize(11f);
        updateNote.setAlpha(0.6f);
        updateNote.setPadding(0, (int) (4 * d), 0, 0);
        updateNote.setText(getString(R.string.about_update_note, UpdateCheck.RELEASES_PAGE));
        root.addView(updateNote);

        updateResult = new TextView(this);
        updateResult.setTextSize(14);
        updateResult.setPadding(0, (int) (10 * d), 0, 0);
        root.addView(updateResult);

        openPage = linkButton(d, getString(R.string.about_update_open), UpdateCheck.RELEASES_PAGE);
        openPage.setVisibility(View.GONE);
        root.addView(openPage);

        setContentView(scroll);
    }

    /** 检查更新的结果文本（初次为空） */
    private TextView updateResult;
    /** 「打开下载页」按钮（只在真有新版 / 失败兜底时显示） */
    private Button openPage;
    /** 正在查询 —— 防连点 */
    private boolean checking;

    /**
     * 真检查一次。
     *
     * ⛔ 不要把这个调用挪到 `onCreate` / `onResume` / 任何定时器里 —— 那会变成**静默外联**。
     *    它只能由用户点「检查更新」触发。
     */
    private void checkUpdate(final Button btn) {
        if (checking) return;
        checking = true;
        btn.setEnabled(false);
        btn.setText(R.string.about_checking);
        updateResult.setText(R.string.about_checking);
        updateResult.setTextColor(0xFF8E8E93);
        openPage.setVisibility(View.GONE);

        new Thread(() -> {
            final UpdateCheck.Result r = UpdateCheck.fetch(UpdateCheck.API, 5000, 8000);
            final String mine = appVersion();
            if (r.status == UpdateCheck.OK_LATEST) {
                r.status = UpdateCheck.isNewer(r.remoteVersion, mine)
                        ? UpdateCheck.OK_NEWER : UpdateCheck.OK_LATEST;
            }
            runOnUiThread(() -> {
                checking = false;
                btn.setEnabled(true);
                btn.setText(R.string.about_check_update);
                if (isFinishing()) return;

                switch (r.status) {
                    case UpdateCheck.OK_NEWER:
                        updateResult.setTextColor(0xFF34C759);
                        updateResult.setText(getString(R.string.about_update_newer_fmt,
                                r.remoteVersion, mine)
                                + (r.notes.isEmpty() ? ""
                                    : "\n\n" + getString(R.string.about_update_notes) + "\n" + r.notes));
                        // 只给"打开下载页"（⛔ 绝不自动下载、更不会去装）
                        openPage.setVisibility(View.VISIBLE);
                        if (!r.pageUrl.isEmpty()) {
                            openPage.setOnClickListener(v -> open(r.pageUrl));
                        }
                        break;
                    case UpdateCheck.OK_LATEST:
                        updateResult.setTextColor(0xFF34C759);
                        updateResult.setText(getString(R.string.about_update_latest_fmt, mine));
                        break;
                    case UpdateCheck.FAIL_RATE_LIMIT:
                        updateResult.setTextColor(0xFFFF9F0A);
                        updateResult.setText(R.string.about_update_fail_rate);
                        openPage.setVisibility(View.VISIBLE);
                        break;
                    case UpdateCheck.FAIL_PARSE:
                        updateResult.setTextColor(0xFFFF9F0A);
                        updateResult.setText(R.string.about_update_fail_parse);
                        openPage.setVisibility(View.VISIBLE);
                        break;
                    case UpdateCheck.FAIL_NETWORK:
                        updateResult.setTextColor(0xFFFF9F0A);
                        updateResult.setText(R.string.about_update_fail_net);
                        openPage.setVisibility(View.VISIBLE);
                        break;
                    default:
                        updateResult.setTextColor(0xFFFF9F0A);
                        updateResult.setText(getString(R.string.about_update_fail_http_fmt, r.httpCode));
                        openPage.setVisibility(View.VISIBLE);
                        break;
                }
                if (!r.detail.isEmpty()) {
                    Log.w("DSHMobile", "检查更新：" + r.status + " " + r.detail);
                }
            });
        }, "dsh-update-check").start();
    }

    /** 运行时读取版本号，避免在代码里写死后与 build.gradle 脱节。 */
    private String appVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? getString(R.string.about_unknown) : pi.versionName;
        } catch (Exception e) {
            return getString(R.string.about_unknown);
        }
    }

    private void section(LinearLayout root, float d, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(0, (int) (22 * d), 0, (int) (6 * d));
        tv.setAlpha(0.6f);
        root.addView(tv);
    }

    private Button linkButton(float d, String text, String url) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * d);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> open(url));
        return btn;
    }

    /** 一律交给系统浏览器，App 自身不发起任何网络请求。 */
    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
