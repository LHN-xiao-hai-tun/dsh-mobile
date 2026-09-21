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
        title.setText("关于 DSH Mobile");
        title.setTextSize(20);
        root.addView(title);

        // 版本（运行时读取）
        TextView ver = new TextView(this);
        ver.setTextSize(13);
        ver.setPadding(0, (int) (10 * d), 0, (int) (18 * d));
        ver.setText("版本 " + appVersion() + " · 约 3 MB · 不含 DSH 本体");
        root.addView(ver);

        // 定位
        TextView what = new TextView(this);
        what.setTextSize(14);
        what.setText("一个极简 WebView 容器：在你自己的手机 / 平板上，"
                + "像原生 App 一样访问你自己部署的 DeepSeek Harness。\n\n"
                + "不预置任何服务器地址，不采集、不上传任何数据，"
                + "无广告、无统计、无第三方 SDK。");
        root.addView(what);

        // 许可
        section(root, d, "开源许可");
        TextView lic = new TextView(this);
        lic.setTextSize(14);
        lic.setText("本项目采用 MIT 许可证：你可以自由使用、修改、分发，"
                + "甚至闭源商用，只需保留原作者的版权与许可声明。");
        root.addView(lic);
        root.addView(linkButton(d, "查看完整许可证（LICENSE）", LICENSE_URL));

        // 致谢
        section(root, d, "致谢");
        TextView thx = new TextView(this);
        thx.setTextSize(14);
        thx.setText("感谢 DeepSeek Harness —— 本 App 只是它的移动端外壳，"
                + "所有能力都来自这个开源项目。");
        root.addView(thx);
        root.addView(linkButton(d, "DeepSeek Harness 项目主页", DSH_URL));

        // 项目
        section(root, d, "项目");
        TextView repoDesc = new TextView(this);
        repoDesc.setTextSize(14);
        repoDesc.setText("源码、问题反馈、更新日志都在 GitHub 仓库。");
        root.addView(repoDesc);
        root.addView(linkButton(d, "打开 GitHub 仓库", REPO));

        // 检查更新
        Button update = new Button(this);
        update.setText("检查更新");
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
            return pi.versionName == null ? "未知" : pi.versionName;
        } catch (Exception e) {
            return "未知";
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
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}
