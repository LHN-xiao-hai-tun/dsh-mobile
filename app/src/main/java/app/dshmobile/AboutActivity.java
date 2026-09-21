package app.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
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
 * 本页不含任何网络请求 —— 「检查更新」只是用系统浏览器打开 Releases 页。
 *
 * ⚠️ 文案约定：所有「用户可见」文字一律取自 strings.xml，代码里不写中文字面量
 *    （集中管理 = 防乱码 + 为多语言留口）。
 */
public class AboutActivity extends Activity {

    static final String REPO = "https://github.com/LHN-xiao-hai-tun/dsh-mobile";
    private static final String LICENSE_URL = REPO + "/blob/master/LICENSE";
    private static final String RELEASES_URL = REPO + "/releases";
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

        // 检查更新
        Button update = new Button(this);
        update.setText(R.string.about_check_update);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = (int) (8 * d);
        update.setLayoutParams(ulp);
        update.setOnClickListener(v -> open(RELEASES_URL));
        root.addView(update);

        setContentView(scroll);
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
