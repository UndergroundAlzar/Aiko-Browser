package app.aikobrowser;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;

/** Short-lived, single-tab browsing in its own Android process and WebView directory. */
public final class PrivateActivity extends Activity {
    private static final String SUFFIX = "aiko_private";
    private static final int CANVAS = 0xFF0B0D12;
    private static final int SURFACE = 0xFF131722;
    private static final int ELEVATED = 0xFF1B2130;
    private static final int STROKE = 0x24FFFFFF;
    private static final int TEXT = 0xFFF3F6FC;
    private static final int MUTED = 0xFF9AA5BD;
    private static final int ACCENT = 0xFF7C9BFF;

    private static boolean initialized, closing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DisplayModeEngine displayMode;
    private SiteExtras siteExtras;
    private FrameLayout root, frame;
    private EditText address;
    private TextView status, subtitle;
    private ProgressBar progress;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private SiteFilter filter;
    private WebView web;
    private volatile SiteFilter.Page page;
    private boolean closed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!android.app.Application.getProcessName().equals(getPackageName() + ":private") || closing || initialized) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(CANVAS);
        getWindow().setNavigationBarColor(SURFACE);
        displayMode = new DisplayModeEngine(this);
        siteExtras = new SiteExtras(this);
        siteExtras.preloadAsync();
        buildUi();
        new Thread(() -> {
            try {
                cleanupPrivatePaths(getDataDir(), getCacheDir());
                main.post(() -> {
                    if (closed || isDestroyed()) return;
                    try {
                        WebView.setDataDirectorySuffix(SUFFIX);
                        initialized = true;
                        filter = new SiteFilter(this);
                        filter.load(() -> {
                            if (!closed && web != null) filter.applyCss(web, web.getUrl());
                        });
                        status.setText("独立无痕窗口");
                        subtitle.setText("独立进程与目录 · 关闭窗口立即清除痕迹");
                        address.setEnabled(true);
                    } catch (RuntimeException error) {
                        failure();
                    }
                });
            } catch (IOException | RuntimeException error) {
                main.post(this::failure);
            }
        }, "aiko-private-prepare").start();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(CANVAS);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(CANVAS);
        content.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bar = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bar.left, bar.top, bar.right, Math.max(bar.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            content.setFitsSystemWindows(true);
        }

        frame = new FrameLayout(this);
        frame.setBackgroundColor(CANVAS);
        content.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView badge = new TextView(this);
        badge.setText("PRIVATE");
        badge.setTextSize(11);
        badge.setTextColor(ACCENT);
        badge.setLetterSpacing(0.14f);
        badge.setPadding(dp(12), dp(4), dp(12), dp(4));
        badge.setBackground(pillDrawable(ELEVATED, STROKE, dp(12)));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.bottomMargin = dp(14);
        empty.addView(badge, badgeLp);

        status = new TextView(this);
        status.setText("正在准备无痕窗口");
        status.setTextSize(20);
        status.setTextColor(TEXT);
        status.setGravity(Gravity.CENTER);
        empty.addView(status, new LinearLayout.LayoutParams(-2, -2));

        subtitle = new TextView(this);
        subtitle.setText("正在清理独立数据目录…");
        subtitle.setTextSize(13);
        subtitle.setTextColor(MUTED);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(8);
        empty.addView(subtitle, subLp);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        View hairline = new View(this);
        hairline.setBackgroundColor(STROKE);
        content.addView(hairline, new LinearLayout.LayoutParams(-1, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(SURFACE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        control(bar, "‹", "后退", this::onBackPressed);

        address = new EditText(this);
        address.setHint("无痕搜索或输入网址");
        address.setSingleLine(true);
        address.setEnabled(false);
        address.setSaveEnabled(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setTextSize(14);
        address.setTextColor(TEXT);
        address.setHintTextColor(MUTED);
        address.setBackground(pillDrawable(ELEVATED, STROKE, dp(20)));
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener((view, id, event) -> {
            if (id != EditorInfo.IME_ACTION_GO) return false;
            submit();
            return true;
        });
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        addrLp.leftMargin = dp(4);
        addrLp.rightMargin = dp(4);
        bar.addView(address, addrLp);

        control(bar, "›", "打开网址", this::submit);
        control(bar, "×", "关闭无痕窗口", this::closeWindow);

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        content.requestFocus();
    }

    private GradientDrawable pillDrawable(int fill, int stroke, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(1, stroke);
        return gd;
    }

    private void submit() {
        if (!initialized || closed || !address.isEnabled()) return;
        String input = address.getText().toString().trim();
        if (input.isEmpty()) return;
        String url = SearchEngines.resolveInput(this, input);
        if (!HttpUrlUtil.isHttp(url)) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        try {
            if (web == null) createWebView();
            page = new SiteFilter.Page(url);
            web.loadUrl(url);
        } catch (RuntimeException error) {
            failure();
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void createWebView() {
        WebView view = new WebView(this);
        web = view;
        view.setBackgroundColor(CANVAS);
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        // Same-session cache lives strictly inside the isolated :private dir wiped on entry/exit.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);

        WebViewPerf.configureWebView(view, true, WebViewPerf.isLowRam(this));
        WebViewPerf.setActiveRenderer(view, true);
        WebViewPerf.setAlgorithmicDarkening(view, siteExtras != null && siteExtras.isGlobalDarkWeb());
        if (displayMode != null) displayMode.attachView(view);

        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int newProgress) {
                if (progress == null) return;
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    if (progress.getVisibility() != View.VISIBLE) progress.setVisibility(View.VISIBLE);
                    progress.setProgress(Math.max(newProgress, 5), true);
                }
            }

            @Override public void onShowCustomView(View custom, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = custom;
                fullscreenCallback = callback;
                custom.setBackgroundColor(0xFF000000);
                root.addView(custom, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setSystemBarsHidden(true);
                if (displayMode != null) displayMode.setFullscreenVideo(true);
            }

            @Override public void onHideCustomView() {
                exitFullscreenVideo();
            }
        });

        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !HttpUrlUtil.isHttp(request.getUrl().toString());
            }

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (HttpUrlUtil.isHttp(url)) {
                    page = new SiteFilter.Page(url);
                    if (!address.hasFocus()) address.setText(url);
                }
            }

            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                if (HttpUrlUtil.isHttp(url)) {
                    page = new SiteFilter.Page(url);
                    if (!address.hasFocus()) address.setText(url);
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return filter != null && filter.blocks(page, request) ? SiteFilter.blocked() : null;
            }

            @Override public void onPageCommitVisible(WebView v, String url) {
                if (filter != null) filter.applyCss(v, url);
            }

            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                frame.removeView(v);
                v.destroy();
                web = null;
                closeWindow();
                return true;
            }
        });
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private void exitFullscreenVideo() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) {
            try { fullscreenCallback.onCustomViewHidden(); } catch (RuntimeException ignored) { }
            fullscreenCallback = null;
        }
        setSystemBarsHidden(false);
        if (displayMode != null) displayMode.setFullscreenVideo(false);
    }

    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hide) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                if (hide) {
                    ctrl.hide(WindowInsets.Type.systemBars());
                    ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    ctrl.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            int flags = hide
                    ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    : View.SYSTEM_UI_FLAG_VISIBLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void failure() {
        if (closed || isDestroyed()) return;
        status.setText("无法准备隔离的无痕数据目录");
        subtitle.setText("请关闭窗口后重试");
        address.setEnabled(false);
    }

    private void control(LinearLayout bar, String text, String description, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(24);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(view -> action.run());
        bar.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    @Override public void onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreenVideo();
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else closeWindow();
    }

    private void closeWindow() {
        exitFullscreenVideo();
        finish();
        endSession();
    }

    @Override protected void onStart() {
        super.onStart();
        if (displayMode != null) displayMode.start();
        if (web != null) {
            web.onResume();
            WebViewPerf.setActiveRenderer(web, true);
        }
    }

    @Override protected void onStop() {
        if (displayMode != null) displayMode.stop();
        if (web != null) {
            WebViewPerf.setActiveRenderer(web, false);
            web.onPause();
        }
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && displayMode != null) displayMode.apply();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (displayMode != null) displayMode.apply();
    }

    @Override protected void onDestroy() {
        exitFullscreenVideo();
        if (!closing && android.app.Application.getProcessName().equals(getPackageName() + ":private")) {
            endSession();
        }
        super.onDestroy();
    }

    private void endSession() {
        if (closed || closing) return;
        closed = true;
        closing = true;
        if (filter != null) filter.close();
        if (web == null) {
            main.postDelayed(PrivateActivity::endPrivateProcess, 180);
            return;
        }
        try {
            web.stopLoading();
            web.clearCache(true);
            web.clearHistory();
            frame.removeView(web);
            web.destroy();
            web = null;
            WebStorage.getInstance().deleteAllData();
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(ignored ->
                    new Thread(cookies::flush, "aiko-private-cookie-cleanup").start());
        } finally {
            main.postDelayed(PrivateActivity::endPrivateProcess, 900);
        }
    }

    private static void endPrivateProcess() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    static void cleanupPrivatePaths(File data, File cache) throws IOException {
        deleteIsolatedTree(data.toPath().resolve("app_webview_" + SUFFIX));
        deleteIsolatedTree(cache.toPath().resolve("webview_" + SUFFIX));
    }

    private static void deleteIsolatedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> items = paths.sorted(Comparator.reverseOrder()).iterator();
            while (items.hasNext()) Files.deleteIfExists(items.next());
        }
    }
}
