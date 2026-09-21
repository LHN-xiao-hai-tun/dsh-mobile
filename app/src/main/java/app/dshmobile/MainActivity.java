package app.dshmobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.splashscreen.SplashScreen;

/**
 * DSH Mobile · 轻量 WebView 容器
 *
 * 连接你自己部署的 DeepSeek Harness 服务。
 * 本 App 不包含 DSH 本体，也不内置任何服务器地址。
 * 首次启动会引导填写地址；之后可点右上角齿轮随时切换。
 */
public class MainActivity extends Activity {

    private WebView web;
    private ProgressBar bar;
    private TextView settingsBtn;
    private long lastBack = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动页：必须在 super.onCreate 之前安装（Android 官方 SplashScreen API）
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (2 * dp)));

        settingsBtn = new TextView(this);
        settingsBtn.setText("\u2699");
        settingsBtn.setTextSize(17);
        settingsBtn.setGravity(Gravity.CENTER);
        settingsBtn.setTextColor(0x99FFFFFF);
        settingsBtn.setBackgroundColor(0x33000000);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                (int) (38 * dp), (int) (38 * dp));
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = (int) (8 * dp);
        bp.rightMargin = (int) (8 * dp);
        settingsBtn.setLayoutParams(bp);
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settingsBtn);

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.startsWith("http")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
                    toast("\u8fde\u4e0d\u4e0a\u670d\u52a1\u5668\uff0c\u70b9\u53f3\u4e0a\u89d2\u9f7f\u8f6e\u68c0\u67e5\u5730\u5740");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                bar.setProgress(p);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                toast("\u5f53\u524d\u7248\u672c\u4e0d\u652f\u6301\u7f51\u9875\u4e0a\u4f20\u6587\u4ef6");
                return false;
            }
        });

        startIfConfigured();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 有地址就加载；否则引导去设置页 */
    private void startIfConfigured() {
        SharedPreferences sp = getSharedPreferences("dsh", MODE_PRIVATE);
        String url = sp.getString("url", "").trim();
        if (url.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        bar.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences("dsh", MODE_PRIVATE);
        String url = sp.getString("url", "").trim();
        if (url.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (web.getUrl() == null) {
            web.loadUrl(url);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                toast("\u518d\u6309\u4e00\u6b21\u9000\u51fa");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
