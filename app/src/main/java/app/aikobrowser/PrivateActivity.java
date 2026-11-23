package app.aikobrowser;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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
    private FrameLayout frame;
    private EditText address;
    private TextView status;
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
        getWindow().setStatusBarColor(MainActivity.BG);
        getWindow().setNavigationBarColor(MainActivity.BG);
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(MainActivity.BG);
        content.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bar = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bar.left, bar.top, bar.right, Math.max(bar.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            content.setFitsSystemWindows(true);
        }
        frame = new FrameLayout(this);
        content.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this);
        status.setText("正在准备无痕窗口");
        status.setTextSize(20);
        status.setTextColor(MainActivity.MUTED);
        status.setGravity(Gravity.CENTER);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(MainActivity.PANEL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(55)));
        control(bar, "‹", "后退", this::onBackPressed);
        address = new EditText(this);
        address.setHint("无痕搜索或输入网址");
        address.setSingleLine(true);
        address.setEnabled(false);
        address.setSaveEnabled(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setTextSize(14);
        address.setTextColor(MainActivity.TEXT);
        address.setHintTextColor(MainActivity.MUTED);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener((view, id, event) -> {
            if (id != EditorInfo.IME_ACTION_GO) return false;
            submit();
            return true;
        });
        bar.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        control(bar, "›", "打开网址", this::submit);
        control(bar, "×", "关闭无痕窗口", this::closeWindow);
        setContentView(content);
        content.requestFocus();
    }

    private void submit() {
        if (!initialized || closed || !address.isEnabled()) return;
        String input = address.getText().toString().trim();
        if (input.isEmpty()) return;
        String url = input;
        if (!MainActivity.http(url) && !input.contains(" ") && !input.contains("://") && input.contains("."))
            url = "https://" + input;
        if (!MainActivity.http(url)) url = "https://www.google.com/search?q=" + Uri.encode(input);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
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
        view.setBackgroundColor(Color.WHITE);
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !MainActivity.http(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (MainActivity.http(url)) {
                    page = new SiteFilter.Page(url);
                    if (!address.hasFocus()) address.setText(url);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return filter.blocks(page, request) ? SiteFilter.blocked() : null;
            }
            @Override public void onPageCommitVisible(WebView v, String url) { filter.applyCss(v, url); }
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

    private void failure() {
        if (closed || isDestroyed()) return;
        status.setText("无法准备隔离的无痕数据目录");
        address.setEnabled(false);
    }

    private void control(LinearLayout bar, String text, String description, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(MainActivity.TEXT);
        button.setTextSize(25);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(view -> action.run());
        bar.addView(button, new LinearLayout.LayoutParams(dp(44), -1));
    }

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else closeWindow();
    }

    private void closeWindow() {
        finish();
        endSession();
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        if (!closing && android.app.Application.getProcessName().equals(getPackageName() + ":private")) endSession();
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
            cookies.removeAllCookies(ignored -> {
                new Thread(cookies::flush, "aiko-private-cookie-cleanup").start();
            });
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
