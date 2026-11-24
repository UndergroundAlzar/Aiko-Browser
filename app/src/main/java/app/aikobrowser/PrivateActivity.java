package app.aikobrowser;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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

    private static boolean initialized, closing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reapplyInsetsTask = this::applyPrivateInsets;
    private float density;
    private DisplayModeEngine displayMode;
    private SiteExtras siteExtras;
    private FrameLayout root, frame, privateDeck;
    private LinearLayout content;
    private EditText address;
    private GradientDrawable addressBg;
    private BrowserIcon backBtn, copyBtn;
    private TextView status, subtitle;
    private ProgressBar progress;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private SiteFilter filter;
    private WebView web;
    private volatile SiteFilter.Page page;
    private int insetLeft, insetTop, insetRight, insetBottom, imeBottom;
    private boolean legacyImeOpen;
    private boolean closed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!android.app.Application.getProcessName().equals(getPackageName() + ":private") || closing || initialized) {
            finish();
            return;
        }
        AikoTheme.applyTheme(this);
        density = getResources().getDisplayMetrics().density;
        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        win.setStatusBarColor(AikoTheme.BG);
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            win.setDecorFitsSystemWindows(false);
            win.setNavigationBarColor(Color.TRANSPARENT);
            win.setNavigationBarContrastEnforced(false);
        } else {
            win.setNavigationBarColor(AikoTheme.SURFACE);
        }

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
        root.setBackgroundColor(AikoTheme.BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(AikoTheme.BG);
        content.setFocusableInTouchMode(true);

        frame = new FrameLayout(this);
        frame.setBackgroundColor(AikoTheme.BG);
        content.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView badge = AikoTheme.badge(this, density, "PRIVATE",
                AikoTheme.ACCENT, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        badge.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.bottomMargin = dp(16);
        empty.addView(badge, badgeLp);

        status = new TextView(this);
        status.setText("正在准备无痕窗口");
        status.setTextSize(20);
        status.setTypeface(AikoTheme.MEDIUM);
        status.setTextColor(AikoTheme.TEXT);
        status.setGravity(Gravity.CENTER);
        empty.addView(status, new LinearLayout.LayoutParams(-2, -2));

        subtitle = new TextView(this);
        subtitle.setText("正在清理独立数据目录…");
        subtitle.setTextSize(13);
        subtitle.setTextColor(AikoTheme.TEXT_MUTED);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(8);
        empty.addView(subtitle, subLp);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        // Private bottom deck: sits strictly below WebView (weight 1 above), hides cleanly on WebView IME
        privateDeck = new FrameLayout(this);
        privateDeck.setBackgroundColor(AikoTheme.SURFACE);
        privateDeck.setElevation(dp(6));
        content.addView(privateDeck, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout deckStack = new LinearLayout(this);
        deckStack.setOrientation(LinearLayout.VERTICAL);
        privateDeck.addView(deckStack, new FrameLayout.LayoutParams(-1, -2));

        int hairlinePx = Math.max(1, Math.round(density));
        View hairline = new View(this);
        hairline.setBackgroundColor(AikoTheme.DIVIDER);
        deckStack.addView(hairline, new LinearLayout.LayoutParams(-1, hairlinePx));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        deckStack.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        backBtn = iconControl(bar, BrowserIcon.Kind.BACK, "后退", AikoTheme.TEXT_DIM, this::onBackPressed);

        address = new EditText(this);
        address.setHint("无痕搜索或输入网址");
        address.setSingleLine(true);
        address.setEnabled(false);
        address.setSaveEnabled(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setTextSize(14);
        address.setTypeface(AikoTheme.MEDIUM);
        address.setTextColor(AikoTheme.TEXT);
        address.setHintTextColor(AikoTheme.TEXT_DIM);
        addressBg = AikoTheme.capsule(density, dp(22));
        address.setBackground(addressBg);
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnFocusChangeListener((v, hasFocus) -> {
            addressBg.setStroke(hairlinePx, hasFocus ? AikoTheme.STROKE_ACCENT : AikoTheme.CAPSULE_STROKE);
            applyPrivateInsets();
        });
        address.setOnEditorActionListener((view, id, event) -> {
            if (id != EditorInfo.IME_ACTION_GO) return false;
            submit();
            return true;
        });
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        addrLp.leftMargin = dp(4);
        addrLp.rightMargin = dp(4);
        bar.addView(address, addrLp);

        copyBtn = iconControl(bar, BrowserIcon.Kind.COPY, "复制链接", AikoTheme.ACCENT_CYAN, this::copyCurrentUrl);
        copyBtn.setVisibility(View.GONE);
        iconControl(bar, BrowserIcon.Kind.GO, "打开网址", AikoTheme.ACCENT, this::submit);
        iconControl(bar, BrowserIcon.Kind.CLOSE, "关闭无痕窗口", AikoTheme.TEXT_MUTED, this::closeWindow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressDrawable(MainActivity.createLoadingDrawable(dp(2)));
        progress.setAlpha(0f);
        progress.setVisibility(View.INVISIBLE);
        int progressPx = Math.max(2, Math.round(2.5f * density));
        privateDeck.addView(progress, new FrameLayout.LayoutParams(-1, progressPx, Gravity.TOP));

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                insetLeft = bars.left;
                insetTop = bars.top;
                insetRight = bars.right;
                insetBottom = bars.bottom;
                imeBottom = ime.bottom;
                applyPrivateInsets();
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                WindowInsets rw = v.getRootWindowInsets();
                if (rw != null) {
                    @SuppressWarnings("deprecation")
                    boolean imeUp = (rw.getSystemWindowInsetBottom() - rw.getStableInsetBottom()) > dp(80);
                    if (imeUp != legacyImeOpen) {
                        legacyImeOpen = imeUp;
                        root.removeCallbacks(reapplyInsetsTask);
                        root.post(reapplyInsetsTask);
                    }
                }
            });
        }

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        applyPrivateInsets();
        content.requestFocus();
    }

    private void applyPrivateInsets() {
        if (content == null || privateDeck == null || isDestroyed()) return;
        if (fullscreenView != null) {
            if (content.getVisibility() != View.GONE) content.setVisibility(View.GONE);
            return;
        }
        if (content.getVisibility() != View.VISIBLE) content.setVisibility(View.VISIBLE);

        boolean imeOpen = imeBottom > insetBottom;
        boolean editingAddr = address != null && address.hasFocus();
        boolean webImeHidden = web != null && !editingAddr && (imeOpen || legacyImeOpen);

        if (webImeHidden) {
            if (privateDeck.getVisibility() != View.GONE) privateDeck.setVisibility(View.GONE);
            privateDeck.setPadding(0, 0, 0, 0);
            content.setPadding(insetLeft, insetTop, insetRight, imeOpen ? imeBottom : 0);
        } else {
            if (privateDeck.getVisibility() != View.VISIBLE) privateDeck.setVisibility(View.VISIBLE);
            if (editingAddr && imeOpen) {
                privateDeck.setPadding(0, 0, 0, 0);
                content.setPadding(insetLeft, insetTop, insetRight, imeBottom);
            } else {
                privateDeck.setPadding(0, 0, 0, insetBottom);
                content.setPadding(insetLeft, insetTop, insetRight, 0);
            }
        }
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
        content.requestFocus();
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
        view.setBackgroundColor(AikoTheme.BG);
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

        view.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && address != null && address.hasFocus()) {
                address.clearFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
            }
            return false;
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int newProgress) {
                updateProgressBar(newProgress);
            }

            @Override public void onShowCustomView(View custom, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = custom;
                fullscreenCallback = callback;
                applyPrivateInsets();
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
                syncBackTint();
            }

            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                if (HttpUrlUtil.isHttp(url)) {
                    page = new SiteFilter.Page(url);
                    if (!address.hasFocus()) address.setText(url);
                }
                syncBackTint();
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return filter != null && filter.blocks(page, request) ? SiteFilter.blocked() : null;
            }

            @Override public void onPageCommitVisible(WebView v, String url) {
                boolean dark = siteExtras != null && siteExtras.isGlobalDarkWeb();
                v.setBackgroundColor(dark ? AikoTheme.BG : Color.WHITE);
                if (filter != null) filter.applyCss(v, url);
                syncBackTint();
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (filter != null) filter.applyCss(v, url);
                syncBackTint();
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

    private void syncBackTint() {
        if (backBtn != null) {
            backBtn.tint(web != null && web.canGoBack() ? AikoTheme.TEXT : AikoTheme.TEXT_DIM);
        }
        if (copyBtn != null) {
            String url = web == null ? null : web.getUrl();
            copyBtn.setVisibility(HttpUrlUtil.isHttp(url) ? View.VISIBLE : View.GONE);
        }
    }

    private void copyCurrentUrl() {
        String url = web == null ? null : web.getUrl();
        if (!HttpUrlUtil.isHttp(url)) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("网址", url));
            Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateProgressBar(int newProgress) {
        if (progress == null) return;
        if (newProgress < 100) {
            progress.animate().setListener(null).cancel();
            progress.setLayerType(View.LAYER_TYPE_NONE, null);
            progress.setAlpha(1f);
            if (progress.getVisibility() != View.VISIBLE) progress.setVisibility(View.VISIBLE);
            progress.setProgress(Math.max(newProgress, 5), true);
        } else {
            progress.setProgress(100, true);
            if (progress.getVisibility() == View.VISIBLE && progress.getAlpha() > 0.05f) {
                progress.animate().setListener(null).cancel();
                progress.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(AikoTheme.FAST_OUT)
                        .withLayer()
                        .setListener(new AnimatorListenerAdapter() {
                            private boolean cancelled;
                            @Override public void onAnimationCancel(Animator a) {
                                cancelled = true;
                                progress.setLayerType(View.LAYER_TYPE_NONE, null);
                                progress.animate().setListener(null);
                            }
                            @Override public void onAnimationEnd(Animator a) {
                                progress.setLayerType(View.LAYER_TYPE_NONE, null);
                                progress.animate().setListener(null);
                                if (!cancelled) progress.setVisibility(View.INVISIBLE);
                            }
                        })
                        .start();
            }
        }
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
        applyPrivateInsets();
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

    private BrowserIcon iconControl(LinearLayout bar, BrowserIcon.Kind kind, String description,
                                    int tint, Runnable action) {
        BrowserIcon button = new BrowserIcon(this, kind);
        button.tint(tint);
        button.setContentDescription(description);
        button.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        button.setFocusable(true);
        AikoTheme.attachPressFeedback(button, 0.92f);
        button.setOnClickListener(view -> action.run());
        bar.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    private int dp(int n) { return Math.round(density * n); }

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
        density = getResources().getDisplayMetrics().density;
        if (displayMode != null) displayMode.apply();
        applyPrivateInsets();
    }

    @Override protected void onDestroy() {
        if (root != null) root.removeCallbacks(reapplyInsetsTask);
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
