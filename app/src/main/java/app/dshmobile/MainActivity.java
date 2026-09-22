package app.dshmobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.splashscreen.SplashScreen;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * DSH Mobile · 轻量 WebView 容器
 *
 * 连接你自己部署的 DeepSeek Harness 服务。
 * 本 App 不包含 DSH 本体，也不内置任何服务器地址。
 * 首次启动会引导填写地址；之后可点浮动齿轮按钮（可拖动）随时切换。
 */
public class MainActivity extends Activity {

    /** 浮动按钮边长 / 间距 / 吸附边缘留白（dp） */
    private static final int FAB_SIZE_DP = 44;
    private static final int FAB_GAP_DP = 4;
    private static final int FAB_MARGIN_DP = 8;

    private WebView web;
    private ProgressBar bar;
    private FrameLayout root;
    private LinearLayout fabStack;
    private View fabSettings;
    private long lastBack = 0;

    // ---------- 断线自动重连（v1.2.5）----------
    private TextView offlineBanner;
    private int retryStep = 0;
    private boolean retryPending = false;
    private ConnectivityManager.NetworkCallback netCallback;
    /** 指数退避：1s → 2s → 4s → 8s → 15s → 30s → 60s（封顶） */
    private static final int[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000, 15000, 30000, 60000};

    private float density;
    private int touchSlop;

    // 拖拽状态
    private float dragDownRawX, dragDownRawY;
    private int dragStartLeft, dragStartTop;
    private boolean dragging;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动页：必须在 super.onCreate 之前安装（Android 官方 SplashScreen API）
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        float dp = getResources().getDisplayMetrics().density;
        density = dp;
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (2 * dp)));

        // ⚙ 设置 + ⌘ 快捷指令：合成一个「可拖拽的竖向小栈」，默认停在右下角。
        // 目的：不再压住 DSH 官方 Web UI 自己的右上角控件，且位置会被记住。
        fabStack = new LinearLayout(this);
        fabStack.setOrientation(LinearLayout.VERTICAL);

        fabSettings = floatButton(dp, "\u2699", getString(R.string.fab_settings),
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        View fabCommands = floatButton(dp, "\u2318", getString(R.string.fab_commands),
                v -> QuickCommands.show(MainActivity.this, web));

        LinearLayout.LayoutParams lpSettings = new LinearLayout.LayoutParams(
                (int) (FAB_SIZE_DP * dp), (int) (FAB_SIZE_DP * dp));
        lpSettings.bottomMargin = (int) (FAB_GAP_DP * dp);
        fabStack.addView(fabSettings, lpSettings);
        fabStack.addView(fabCommands, new LinearLayout.LayoutParams(
                (int) (FAB_SIZE_DP * dp), (int) (FAB_SIZE_DP * dp)));

        // ⚠️ 必须显式给 LayoutParams：FrameLayout 的默认值是 MATCH_PARENT，
        //    会让小栈铺满全屏 → getWidth() 等于屏宽 → maxFabLeft() 恒为 0（拖不动），
        //    子按钮也被顶到左上角。出厂位置直接给「右下 + 边距」，即使还原逻辑出问题
        //    也不会掉到左上角。
        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        stackLp.rightMargin = (int) (FAB_MARGIN_DP * dp);
        stackLp.bottomMargin = (int) (FAB_MARGIN_DP * dp);
        root.addView(fabStack, stackLp);
        // 提升层级：即使与 WebView 区域重叠，也保证浮层先拿到触摸
        fabStack.setElevation(Math.max(1f, dp));
        fabStack.setClickable(true);

        setContentView(root);
        // ⚠️ 不能用 root.post()：该回调只保证「已 attach + 主线程空闲」，不保证已 measure/layout
        //    —— 那一刻 root.getWidth() 与 fabStack.getWidth() 都是 0，比例相乘得 0 →
        //    按钮被压到左上角（真机实测 bounds [0,165][100,265]）。改为「测量完成后再还原」。
        restoreFabWhenMeasured();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        // 保留 false：DSH 侧有 TTS / 语音播报，收紧成 true 会变成"必须手动点一下才响"（回归）
        s.setMediaPlaybackRequiresUserGesture(false);
        // v1.3.0 加固：原为 MIXED_CONTENT_ALWAYS_ALLOW（见 v1.2.2 黑屏排查）。
        // 实测本 App 的页本身就是 http（明文页），混合内容模式对它**不生效**，
        // 收紧成 NEVER_ALLOW 不影响局域网用法，却能挡住"https 页里偷偷加载 http 资源"。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // ---------- v1.3.0 · WebView 加固 ----------
        // 页面是远程 DSH，不需要任何本地文件能力；关掉即缩小攻击面（不影响 Web 功能）。
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSavePassword(false);                 // 凭据交给 DSH 自己管，不落 WebView 的密码库
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        // ⚠️ 不调用 addJavascriptInterface —— 一旦注入 JS 桥，WebView 里的任何内容都能调原生代码。

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
                // 加载成功 → 复位退避计数并收起离线遮罩（v1.2.5）
                onPageLoaded();
                // ⚠️ 页面加载完成后 WebView 可能重新占据层级 → 把浮层再提到最前，
                //    否则重叠区域的触摸会被 WebView 抢走（真机实测：点在浮层内却打开了 DSH 侧边栏）。
                if (fabStack != null) fabStack.bringToFront();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
                    // v1.2.5：不再只弹一次 toast —— 进入「离线遮罩 + 指数退避自动重连」。
                    // 只对**主框架**生效：子资源（图片/接口）失败不该把整页判死。
                    scheduleReconnect();
                }
            }

            /**
             * v1.3.0 · 证书校验（TOFU = Trust On First Use）
             *
             * ⚠️ 铁律：**绝不无条件 `handler.proceed()`**。三条分支各有明确出口：
             *   ① 首次见到该 host 的证书 → 亮出指纹让用户自己核对，确认后才记住并放行；
             *   ② 指纹与记住的一致     → 放行（自签证书的正常复连路径）；
             *   ③ 指纹变了            → **强警告**（可能换了证书/中间人），默认取消，要再点一次才信任。
             * 拿不到指纹（极少数机型）→ 一律取消，绝不放行。
             *
             * 注：本方法只在**系统信任链已失败**时才被调用；证书正常的 https 不会走到这里。
             */
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                final String host = NetPolicy.hostOf(error.getUrl());
                final String fp = fingerprintOf(error.getCertificate());
                if (fp.isEmpty()) {
                    handler.cancel();
                    toast(getString(R.string.ssl_unknown_cert));
                    return;
                }
                String remembered = Prefs.trustedFingerprint(MainActivity.this, host);
                if (fp.equals(remembered)) {
                    handler.proceed();                                   // ② 已信任且未变
                } else if (remembered == null) {
                    askTrustFirstTime(host, fp, error, handler);          // ① 首次
                } else {
                    askTrustChanged(host, fp, remembered, handler);       // ③ 变更
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
                // v1.3.0：原来是无条件 grant(全部资源)。现在只对**你配置的那个主机**放行
                // 麦克风/摄像头 —— 万一页面被换成别家站点，不再自动把设备权限交出去。
                String originHost = request.getOrigin() == null ? "" : request.getOrigin().getHost();
                String allowedHost = NetPolicy.hostOf(Prefs.url(MainActivity.this));
                if (originHost.isEmpty() || !originHost.equalsIgnoreCase(allowedHost)) {
                    runOnUiThread(request::deny);
                    return;
                }
                java.util.List<String> grant = new java.util.ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                        grant.add(r);   // 其余（如受保护媒体）一律不给
                    }
                }
                if (grant.isEmpty()) {
                    runOnUiThread(request::deny);
                    return;
                }
                final String[] g = grant.toArray(new String[0]);
                runOnUiThread(() -> request.grant(g));
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                toast("\u5f53\u524d\u7248\u672c\u4e0d\u652f\u6301\u7f51\u9875\u4e0a\u4f20\u6587\u4ef6");
                return false;
            }
        });

        installReconnect();
        startIfConfigured();
    }

    /**
     * 浮动小按钮：**可拖拽 + 松手吸附最近左右边缘**，点击仍走 listener。
     *
     * 触摸策略（刻意保守，避免抢 WebView 手势）：
     *  - 只在本按钮自身区域内接管触摸（不用全屏 overlay）
     *  - ACTION_DOWN 即消费（return true），但**位移未超过 touchSlop 时不算拖拽**，仍按点击处理
     *  - 拖拽中松手 → 吸附边缘并记住位置；未拖拽 → performClick()
     */
    private TextView floatButton(float dp, String glyph, String desc,
                                 View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(glyph);
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(0xCCFFFFFF);
        btn.setBackgroundColor(0x4D000000);
        btn.setContentDescription(desc);
        btn.setOnClickListener(listener);
        btn.setClickable(true);
        btn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragDownRawX = e.getRawX();
                    dragDownRawY = e.getRawY();
                    dragStartLeft = fabLeft();
                    dragStartTop = fabTop();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - dragDownRawX;
                    float dy = e.getRawY() - dragDownRawY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        moveFab(dragStartLeft + (int) dx, dragStartTop + (int) dy);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        snapFabToEdge();
                    } else {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        snapFabToEdge();
                    }
                    return true;
                default:
                    return false;
            }
        });
        return btn;
    }

    // ---------- 浮动按钮位置：拖拽 / 吸附 / 记忆 ----------

    private FrameLayout.LayoutParams fabLp() {
        return (FrameLayout.LayoutParams) fabStack.getLayoutParams();
    }

    private int fabLeft() {
        return fabLp().leftMargin;
    }

    private int fabTop() {
        return fabLp().topMargin;
    }

    /** 状态栏下沿（避免拖到状态栏里） */
    private int statusBarBottom() {
        Rect r = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
        return Math.max(0, r.top);
    }

    private int edgeMargin() {
        return (int) (FAB_MARGIN_DP * density);
    }

    // ⚠️ 以下三个尺寸取值一律带「回退链」，**绝不返回 0**：
    //    0 会让「比例 × 最大值」恒为 0，把浮层压到左上角（v1.2.2 真机缺陷的形态之一）。
    private int rootWidth() {
        if (root != null && root.getWidth() > 0) return root.getWidth();
        if (root != null && root.getMeasuredWidth() > 0) return root.getMeasuredWidth();
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int rootHeight() {
        if (root != null && root.getHeight() > 0) return root.getHeight();
        if (root != null && root.getMeasuredHeight() > 0) return root.getMeasuredHeight();
        return getResources().getDisplayMetrics().heightPixels;
    }

    /** 小栈尺寸（2 个按钮 + 1 个间距）；未测量时按 dp 估算 */
    private int fabWidth() {
        if (fabStack != null && fabStack.getWidth() > 0) return fabStack.getWidth();
        if (fabStack != null && fabStack.getMeasuredWidth() > 0) return fabStack.getMeasuredWidth();
        return (int) (FAB_SIZE_DP * density);
    }

    private int fabHeight() {
        if (fabStack != null && fabStack.getHeight() > 0) return fabStack.getHeight();
        if (fabStack != null && fabStack.getMeasuredHeight() > 0) return fabStack.getMeasuredHeight();
        return (int) ((FAB_SIZE_DP * 2 + FAB_GAP_DP) * density);
    }

    private int maxFabLeft() {
        return Math.max(0, rootWidth() - fabWidth());
    }

    private int maxFabTop() {
        return Math.max(0, rootHeight() - fabHeight() - edgeMargin());
    }

    private int minFabTop() {
        return statusBarBottom() + (int) (2 * density);
    }

    private int clampFabLeft(int v) {
        int lo = Math.min(edgeMargin(), maxFabLeft());
        int hi = Math.max(lo, maxFabLeft() - edgeMargin());
        return Math.max(lo, Math.min(v, hi));
    }

    private int clampFabTop(int v) {
        int lo = minFabTop();
        int hi = Math.max(lo, maxFabTop());
        return Math.max(lo, Math.min(v, hi));
    }

    private void moveFab(int left, int top) {
        FrameLayout.LayoutParams lp = fabLp();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = clampFabLeft(left);
        lp.topMargin = clampFabTop(top);
        fabStack.setLayoutParams(lp);
    }

    /** 松手：吸附到最近的左右边缘（小按钮不占中间挡住内容），并记住位置。 */
    private void snapFabToEdge() {
        FrameLayout.LayoutParams lp = fabLp();
        int center = lp.leftMargin + fabWidth() / 2;
        lp.leftMargin = center < rootWidth() / 2
                ? edgeMargin()
                : Math.max(edgeMargin(), maxFabLeft() - edgeMargin());
        lp.topMargin = clampFabTop(lp.topMargin);
        lp.gravity = Gravity.TOP | Gravity.START;
        fabStack.setLayoutParams(lp);
        saveFabPosition();
    }

    /** 位置以「可移动范围的比例」存储 → 旋转/换密度后仍合理 */
    private void saveFabPosition() {
        int maxL = maxFabLeft();
        int maxT = maxFabTop();
        float fx = maxL <= 0 ? 1f : (float) fabLeft() / maxL;
        float fy = maxT <= 0 ? 1f : (float) fabTop() / maxT;
        Prefs.setFab(this, fx, fy);
    }

    /**
     * ⭐ 等「容器与浮层都真正测量完成」后再按比例还原位置。
     *
     * 为什么不能用 root.post()：post 只保证「已 attach + 主线程空闲」，不保证已 measure/layout
     * —— 那时 root.getWidth() 与 fabStack.getWidth() 都是 0，比例相乘得 0，按钮被压到左上角。
     * 这里用 OnGlobalLayoutListener：尺寸仍为 0 就等下一轮回调，成功后立刻移除监听（防重复）。
     */
    private void restoreFabWhenMeasured() {
        if (root == null || fabStack == null) return;
        final ViewTreeObserver.OnGlobalLayoutListener[] holder =
                new ViewTreeObserver.OnGlobalLayoutListener[1];
        holder[0] = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (root.getWidth() <= 0 || fabStack.getWidth() <= 0) {
                    return; // 还没测量好，等下一次布局回调
                }
                ViewTreeObserver vto = root.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(holder[0]);
                }
                restoreFabPosition();
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(holder[0]);
    }

    /** 无记录时默认右下角（1,1） */
    private void restoreFabPosition() {
        if (fabStack == null) return;
        float[] p = Prefs.fab(this);
        FrameLayout.LayoutParams lp = fabLp();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = clampFabLeft(Math.round(p[0] * maxFabLeft()));
        lp.topMargin = clampFabTop(Math.round(p[1] * maxFabTop()));
        fabStack.setLayoutParams(lp);
    }

    private void reclampFab() {
        if (fabStack == null) return;
        moveFab(fabLeft(), fabTop());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 清单里声明了 configChanges（旋转不重建 Activity）→ 需手动把按钮拉回可视区
        if (fabStack != null) {
            fabStack.post(() -> {
                // 同样要等测量完成：尺寸还是 0 就别 clamp（否则会把浮层推到左上角）
                if (root.getWidth() > 0 && fabStack.getWidth() > 0) {
                    reclampFab();
                } else {
                    restoreFabWhenMeasured();
                }
            });
        }
    }

    // ---------- 断线自动重连（v1.2.5）----------

    /**
     * 装「离线遮罩 + 网络变化监听」。
     *
     * 遮罩 = 贴在**顶部**的一条横幅（不挡内容，可点即立即重试），断线时出现、连上后消失。
     * 另注册默认网络回调：网络一恢复就**立刻**重试，不必等下一次退避到点。
     */
    private void installReconnect() {
        offlineBanner = new TextView(this);
        offlineBanner.setText(R.string.reconnect_offline);
        offlineBanner.setTextSize(13);
        offlineBanner.setGravity(Gravity.CENTER);
        offlineBanner.setTextColor(0xFFFFFFFF);
        offlineBanner.setBackgroundColor(0xE6B3261E);   // 深红：明显但不刺眼
        offlineBanner.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
        offlineBanner.setVisibility(View.GONE);
        offlineBanner.setClickable(true);
        offlineBanner.setOnClickListener(v -> {
            retryStep = 0;
            retryPending = false;
            hideOffline();
            loadCurrent();
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(offlineBanner, blp);
        offlineBanner.setElevation(Math.max(2f, density));

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                netCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        runOnUiThread(() -> {
                            if (offlineBanner != null
                                    && offlineBanner.getVisibility() == View.VISIBLE) {
                                retryStep = 0;
                                retryPending = false;
                                loadCurrent();
                            }
                        });
                    }
                };
                cm.registerDefaultNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {
            // 拿不到 ConnectivityManager 也不该让 App 起不来 —— 只是少了「网络恢复即重试」这一层
        }
    }

    /** 主框架加载失败 → 显示遮罩 + 指数退避重试（loadUrl 不会丢 Cookie / LocalStorage） */
    private void scheduleReconnect() {
        if (retryPending) return;
        retryPending = true;
        showOffline();
        int delay = RETRY_DELAYS_MS[Math.min(retryStep, RETRY_DELAYS_MS.length - 1)];
        retryStep++;
        if (bar != null) bar.setVisibility(View.GONE);
        root.postDelayed(() -> {
            retryPending = false;
            loadCurrent();
        }, delay);
    }

    /** 页面加载完成 → 复位退避计数并收起遮罩 */
    private void onPageLoaded() {
        retryStep = 0;
        retryPending = false;
        hideOffline();
    }

    /** 用「已配置地址」重载；没有则收起遮罩（交给 onResume 去引导设置页） */
    private void loadCurrent() {
        String u = Prefs.url(this);
        if (u.isEmpty()) { hideOffline(); return; }
        loadUrlWithPolicy(u);
    }

    private void showOffline() {
        if (offlineBanner == null) return;
        int n = retryStep + 1;
        offlineBanner.setText(n <= 1
                ? getString(R.string.reconnect_offline)
                : getString(R.string.reconnect_retrying, n));
        offlineBanner.setVisibility(View.VISIBLE);
        offlineBanner.bringToFront();
    }

    private void hideOffline() {
        if (offlineBanner != null) offlineBanner.setVisibility(View.GONE);
    }

    // ---------- v1.3.0 · 连接前安全策略 ----------

    /** 策略对话框是否正在显示（防止退避重连 / onResume 反复弹） */
    private boolean policyDialogShowing = false;

    /**
     * **所有**"要加载地址"的入口都走这里，统一过一遍安全策略。
     *
     * 判定见 {@link NetPolicy#verdict}：
     *   OK           —— https，或已确认过 → 直接放行，不打扰；
     *   CONFIRM_ONCE —— 私有网段明文（本 App 的典型用法）：告知是明文，**首次确认一次**；
     *   BLOCK_STRONG —— 公网 + 明文：**默认拦下**，要用户明确接受风险才继续。
     *
     * ⚠️ 注意：这里拦不住"用户在网页里自己跳到公网明文站点"（那是 WebView 内部导航），
     *    只拦 App 侧的入口地址。
     */
    private void loadUrlWithPolicy(String url) {
        final String u = Prefs.normalize(url);
        if (u.isEmpty()) {
            hideOffline();
            return;
        }
        final String host = NetPolicy.hostOf(u);
        NetPolicy.Verdict v = NetPolicy.verdict(u);

        if (v == NetPolicy.Verdict.OK || Prefs.isAcked(this, host)) {
            reallyLoad(u);
            return;
        }
        if (policyDialogShowing || isFinishing()) {
            return;
        }
        policyDialogShowing = true;

        boolean strong = (v == NetPolicy.Verdict.BLOCK_STRONG);
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(strong ? R.string.sec_public_title : R.string.sec_first_title)
                .setMessage(strong ? getString(R.string.sec_public_msg, u, host)
                        : getString(R.string.sec_first_msg, u, host))
                .setPositiveButton(strong ? R.string.sec_public_continue : R.string.sec_continue,
                        (d, w) -> {
                            policyDialogShowing = false;
                            Prefs.ackHost(this, host);
                            reallyLoad(u);
                        })
                .setNegativeButton(R.string.qc_cancel, (d, w) -> {
                    policyDialogShowing = false;
                    hideOffline();
                })
                .setOnCancelListener(d -> {
                    policyDialogShowing = false;
                    hideOffline();
                })
                .show();
    }

    private void reallyLoad(String url) {
        lastRequestedUrl = url;      // v1.3.1：记下"已下发"，供 onResume 判断地址是否被改过
        if (bar != null) bar.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    // ---------- v1.3.0 · 证书指纹 ----------

    /**
     * 证书 SHA-256 指纹（`AA:BB:…`）。
     *
     * `SslCertificate` 不直接给 X509，但 {@link SslCertificate#saveState} 的 Bundle 里
     * 有 DER 编码的证书（键 `x509-certificate`）—— 对它做 SHA-256 即为常见意义上的
     * "证书指纹"（与浏览器/openssl 显示的同一个值）。
     */
    private static String fingerprintOf(SslCertificate cert) {
        if (cert == null) {
            return "";
        }
        try {
            Bundle st = SslCertificate.saveState(cert);
            if (st == null) {
                return "";
            }
            byte[] der = st.getByteArray("x509-certificate");
            if (der == null || der.length == 0) {
                return "";
            }
            byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder(h.length * 3);
            for (int i = 0; i < h.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02X", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String issuedTo(SslCertificate c) {
        if (c == null) {
            return "-";
        }
        SslCertificate.DName d = c.getIssuedTo();
        return (d == null || d.getDName() == null) ? "-" : d.getDName();
    }

    /** ① 首次见到该主机的证书：亮指纹，让用户自己核对后决定 */
    private void askTrustFirstTime(final String host, final String fp, SslError error,
                                   final SslErrorHandler handler) {
        if (isFinishing()) {
            handler.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.ssl_first_title)
                .setMessage(getString(R.string.ssl_first_msg, host, issuedTo(error.getCertificate()), fp))
                .setPositiveButton(R.string.ssl_trust, (d, w) -> {
                    Prefs.trustCert(this, host, fp);
                    handler.proceed();
                })
                .setNegativeButton(R.string.ssl_cancel, (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    /** ③ 指纹变了：这是最危险的一支 —— 默认取消，要**再点一次**才信任 */
    private void askTrustChanged(final String host, final String fp, final String old,
                                 final SslErrorHandler handler) {
        if (isFinishing()) {
            handler.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.ssl_changed_title)
                .setMessage(getString(R.string.ssl_changed_msg, host, old, fp))
                .setPositiveButton(R.string.ssl_changed_trust, (d, w) ->
                        new AlertDialog.Builder(this)
                                .setCancelable(false)
                                .setTitle(R.string.ssl_changed_confirm_title)
                                .setMessage(getString(R.string.ssl_changed_confirm_msg, host))
                                .setPositiveButton(R.string.ssl_changed_confirm_yes, (d2, w2) -> {
                                    Prefs.trustCert(this, host, fp);
                                    handler.proceed();
                                })
                                .setNegativeButton(R.string.ssl_cancel, (d2, w2) -> handler.cancel())
                                .setOnCancelListener(d2 -> handler.cancel())
                                .show())
                .setNegativeButton(R.string.ssl_cancel, (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * 上一次**已下发**给 WebView 的地址。
     * v1.3.1：用来判断「你在设置页把地址改了」，从而决定要不要重载。
     */
    private String lastRequestedUrl = null;

    /** 有地址就加载；没地址则在**启动时**引导一次去设置页 */
    private void startIfConfigured() {
        String url = Prefs.url(this);
        if (url.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        loadUrlWithPolicy(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String url = Prefs.url(this);
        if (url.isEmpty()) {
            // ⚠️ 刻意**不**在这里再开设置页。
            // 老代码是无条件 `startActivity(SettingsActivity)` —— 于是「清空地址 → 按返回键」
            // 会被反复弹回设置页，用户被关在里面出不来（v1.3.0 真机复现：每次 onResume
            // 的 topResumedActivity 都是 SettingsActivity）。
            // 引导只放在**启动时**做一次（startIfConfigured）；之后想改地址点 ⚙ 即可。
            return;
        }

        // v1.3.1：**地址被改过就必须重载**。
        // 旧判据只有 `web.getUrl() == null` —— WebView 已经加载过页面时它永远不成立，
        // 表现就是「在设置页改完地址毫无反应，要杀掉 App 重开才生效」。
        //
        // 这里比的是「你配置的地址」vs「上次下发过的地址」，**与 WebView 自己跳到哪儿无关**，
        // 所以不会被 DSH 的登录跳转 / 补尾斜杠之类的情况误触发重载。
        if (!url.equals(lastRequestedUrl) || web.getUrl() == null) {
            loadUrlWithPolicy(url);
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
        // 注销网络回调（v1.2.5）—— 不注销会随 Activity 泄漏
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) { }
            netCallback = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
