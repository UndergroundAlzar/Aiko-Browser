package app.aikobrowser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URL;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;

import static app.aikobrowser.BrowserIcon.Kind.*;

public final class MainActivity extends Activity {
    static final int BG = 0xff151217, PANEL = 0xff211b23, FIELD = 0xff312632;
    static final int TEXT = 0xfffff4f6, MUTED = 0xffc5b3bd, RUBY = 0xffff587d;
    private static final int HOT_TABS = 5, MAX_TABS = 64, FILE_PICKER = 11;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Tab> tabs = new ArrayList<>();
    private final Runnable save = this::saveTabs;
    private final Runnable warm = this::warmWebView;
    private final ArrayDeque<String> timings = new ArrayDeque<>();
    private final ArrayDeque<Tab> closedTabs = new ArrayDeque<>();
    private BrowserLibrary library;
    private SiteFilter siteFilter;
    private final Runnable saveHistory = () -> library.flushHistory();
    private FrameLayout root, page;
    private LinearLayout content, addressRow;
    private EditText address, homeSearch;
    private View homeSpacer;
    private BrowserIcon backButton, tabButton, menuButton, siteButton, goButton;
    private ProgressBar loading;
    private Tab current;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private ValueCallback<Uri[]> picker;
    private boolean firstDraw;
    private boolean visible;
    private WebView spareWebView;
    private LinearLayout findBar;
    private WebView findTarget;

    private static final class Tab {
        String url, title;
        WebView view;
        Bundle navigation;
        final ScrollPositions positions = new ScrollPositions();
        String visibleUrl;
        int visibleIndex = ScrollPositions.URL_ONLY;
        volatile SiteFilter.Page filterPage;
        ScrollPositions.Position pendingScroll;
        boolean scrollQueued;
        android.graphics.Bitmap favicon;
        String faviconHost;
        String historyTarget;
        long used;
        long started, priorityTicket;
        int progress = 100;
        Tab(String url, String title) { this.url = url; this.title = title; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        library = new BrowserLibrary(getSharedPreferences("library", MODE_PRIVATE));
        DownloadService.PACER.setBrowserFirst(getPreferences(MODE_PRIVATE).getBoolean("browserFirst", true));
        buildChrome();
        restoreTabs(state == null ? incomingLink(getIntent()) : null);
        handler.postDelayed(() -> filter(), 600);
        if (state == null && getIntent().getBooleanExtra("downloads", false)) showDownloads();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent.getBooleanExtra("downloads", false)) showDownloads();
        String link = incomingLink(intent);
        if (link != null) addTab(link);
    }

    private String incomingLink(Intent intent) {
        String link = intent.getDataString();
        return Intent.ACTION_VIEW.equals(intent.getAction()) && http(link) ? link : null;
    }

    private void buildChrome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                content.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        page = new FrameLayout(this);
        page.setBackgroundColor(BG);
        content.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setProgressTintList(ColorStateList.valueOf(RUBY));
        loading.setProgressBackgroundTintList(ColorStateList.valueOf(BG));
        loading.setMax(100);
        loading.setVisibility(View.INVISIBLE);
        content.addView(loading, new LinearLayout.LayoutParams(-1, dp(2)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(PANEL);
        controls.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(controls, new LinearLayout.LayoutParams(-1, dp(56)));
        backButton = icon(BACK, "后退，长按前进", this::goBack);
        controls.addView(backButton, new LinearLayout.LayoutParams(dp(48), -1));
        backButton.setOnLongClickListener(v -> {
            if (!moveHistory(1)) toast("没有可前进的页面");
            return true;
        });

        addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setBackground(shape(FIELD, 16));
        controls.addView(addressRow, new LinearLayout.LayoutParams(0, -1, 1));
        siteButton = icon(SEARCH, "搜索", () -> {
            if (current.url == null) focusAddress(); else showSiteControls();
        });
        addressRow.addView(siteButton, new LinearLayout.LayoutParams(dp(42), -1));
        address = input("搜索或输入网址");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(0, 0, dp(10), 0);
        address.setTextSize(14);
        address.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addressRow.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        bindSubmission(address, EditorInfo.IME_ACTION_GO);
        goButton = icon(GO, "前往", () -> submitAddress(address));
        goButton.setVisibility(View.GONE);
        addressRow.addView(goButton, new LinearLayout.LayoutParams(dp(48), -1));
        homeSpacer = new View(this);
        homeSpacer.setVisibility(View.GONE);
        controls.addView(homeSpacer, new LinearLayout.LayoutParams(0, -1, 1));
        tabButton = icon(TABS, "标签页，长按新建", this::showTabs);
        tabButton.setOnLongClickListener(v -> { addTab(null); focusAddress(); return true; });
        controls.addView(tabButton, new LinearLayout.LayoutParams(dp(48), -1));
        menuButton = icon(MENU, "浏览工具，长按打开下载", this::showTools);
        menuButton.setOnLongClickListener(v -> { showDownloads(); return true; });
        controls.addView(menuButton, new LinearLayout.LayoutParams(dp(48), -1));
        address.setOnFocusChangeListener((field, editing) -> {
            if (current == null) return;
            int visibility = editing ? View.GONE : View.VISIBLE;
            backButton.setVisibility(editing || homeSearch != null ? View.GONE : View.VISIBLE);
            tabButton.setVisibility(visibility);
            menuButton.setVisibility(visibility);
            siteButton.setVisibility(visibility);
            goButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            address.setPadding(editing ? dp(14) : 0, 0, dp(10), 0);
            address.setEllipsize(editing ? null : android.text.TextUtils.TruncateAt.END);
            if (editing) {
                address.setText(current.url == null ? "" : current.url);
                address.selectAll();
            } else {
                updateAddress();
            }
        });
        findBar = new LinearLayout(this);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(PANEL);
        findBar.setVisibility(View.GONE);
        content.addView(findBar, new LinearLayout.LayoutParams(-1, dp(52)));
        setContentView(root);
        content.requestFocus();
        root.post(() -> {
            if (!firstDraw) {
                firstDraw = true;
                reportFullyDrawn();
            }
        });
    }

    private void restoreTabs(String incoming) {
        try {
            JSONArray stored = new JSONArray(getPreferences(MODE_PRIVATE).getString("tabs", "[]"));
            for (int i = 0; i < stored.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject item = stored.getJSONObject(i);
                String url = item.optString("url");
                Tab tab = new Tab(http(url) ? url : null, item.optString("title"));
                tab.positions.remember(ScrollPositions.URL_ONLY, tab.url,
                        item.optInt("scrollX"), item.optInt("scrollY"));
                tabs.add(tab);
            }
        } catch (Exception ignored) { }
        int index = getPreferences(MODE_PRIVATE).getInt("selected", 0);
        if (incoming != null && tabs.size() < MAX_TABS) {
            tabs.add(new Tab(incoming, incoming));
            index = tabs.size() - 1;
        }
        if (tabs.isEmpty()) tabs.add(new Tab(null, "新标签"));
        showTab(tabs.get(Math.min(Math.max(index, 0), tabs.size() - 1)));
    }

    private void saveTabs() {
        JSONArray list = new JSONArray();
        for (Tab t : tabs) {
            JSONObject item = new JSONObject();
            try {
                item.put("url", t.url == null ? "" : t.url);
                item.put("title", t.title == null ? "" : t.title);
                ScrollPositions.Position position = t.positions.get(ScrollPositions.URL_ONLY, t.url);
                if (position != null) {
                    item.put("scrollX", position.x);
                    item.put("scrollY", position.y);
                }
                list.put(item);
            } catch (Exception ignored) { }
        }
        getPreferences(MODE_PRIVATE).edit().putString("tabs", list.toString())
                .putInt("selected", tabs.indexOf(current)).apply();
    }

    private void scheduleSave() {
        handler.removeCallbacks(save);
        handler.postDelayed(save, 500);
    }

    private void addTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            toast("最多保留 " + MAX_TABS + " 个标签");
            return;
        }
        Tab tab = new Tab(url, url == null ? "新标签" : url);
        tabs.add(tab);
        showTab(tab);
    }

    private void showTab(Tab tab) {
        if (current != tab) closeFind();
        hideKeyboard();
        if (current != tab && current != null) {
            rememberScroll(current);
            DownloadService.PACER.release(current.priorityTicket);
            current.priorityTicket = 0;
            if (current.view != null) current.view.onPause();
        }
        current = tab;
        tab.used = SystemClock.uptimeMillis();
        if (tab.url == null && tab.view == null) {
            page.removeAllViews();
            page.addView(home(), new FrameLayout.LayoutParams(-1, -1));
            scheduleWarm();
        } else {
            if (tab.view == null) {
                beginNavigation(tab);
                tab.filterPage = filterPage(tab.url);
                setScrollTarget(tab, tab.positions.get(ScrollPositions.URL_ONLY, tab.url));
                tab.view = createWebView(tab);
                if (tab.navigation == null || tab.view.restoreState(tab.navigation) == null) tab.view.loadUrl(tab.url);
                tab.navigation = null;
            }
            attach(tab.view);
            tab.view.onResume();
        }
        updateChrome();
        trimTabs();
        scheduleSave();
    }

    private void attach(WebView web) {
        if (web.getParent() == page) return;
        homeSearch = null;
        page.removeAllViews();
        page.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    private void scheduleWarm() {
        handler.removeCallbacks(warm);
        if (visible && current != null && current.url == null && spareWebView == null) {
            handler.postDelayed(warm, 500);
        }
    }

    private void warmWebView() {
        if (!visible || !hasWindowFocus() || activeAddress().hasFocus() || current == null
                || current.url != null || spareWebView != null) return;
        for (Tab tab : tabs) if (tab.view != null) return;
        try { spareWebView = new ScrollWebView(this); } catch (RuntimeException ignored) { }
    }

    private void dropSpare() {
        handler.removeCallbacks(warm);
        if (spareWebView != null) {
            spareWebView.destroy();
            spareWebView = null;
        }
    }

    private void trimTabs() {
        for (;;) {
            int count = 0;
            Tab oldest = null;
            for (Tab t : tabs) {
                if (t.view == null) continue;
                count++;
                if (t != current && (oldest == null || oldest.used > t.used)) oldest = t;
            }
            if (count <= HOT_TABS || oldest == null) return;
            rememberScroll(oldest);
            oldest.navigation = new Bundle();
            oldest.view.saveState(oldest.navigation);
            destroyWeb(oldest);
        }
    }

    private void destroyWeb(Tab tab) {
        WebView web = tab.view;
        if (web == null) return;
        tab.view = null;
        setScrollTarget(tab, null);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
    }

    private View home() {
        FrameLayout home = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(520));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(shape(FIELD, 19));
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-1, dp(56), Gravity.CENTER);
        size.setMargins(dp(24), 0, dp(24), 0);
        home.addView(row, size);
        EditText box = input("搜索或输入网址");
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setBackgroundColor(Color.TRANSPARENT);
        box.setPadding(0, 0, 0, 0);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setTextSize(16);
        homeSearch = box;
        BrowserIcon search = icon(SEARCH, "聚焦搜索框", this::focusAddress);
        search.tint(MUTED);
        row.addView(search, new LinearLayout.LayoutParams(dp(48), -1));
        row.addView(box, new LinearLayout.LayoutParams(0, -1, 1));
        BrowserIcon go = icon(GO, "搜索或前往", () -> submitAddress(box));
        go.tint(RUBY);
        row.addView(go, new LinearLayout.LayoutParams(dp(48), -1));
        bindSubmission(box, EditorInfo.IME_ACTION_SEARCH);
        return home;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createWebView(Tab tab) {
        handler.removeCallbacks(warm);
        WebView web = spareWebView == null ? new ScrollWebView(this) : spareWebView;
        spareWebView = null;
        SiteFilter filter = filter();
        web.setBackgroundColor(Color.WHITE);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        if (Build.VERSION.SDK_INT >= 35) web.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                boolean allowed = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                if (allowed && tab == current && request.isForMainFrame() && !request.isRedirect()) {
                    if (!request.getUrl().toString().equals(tab.historyTarget)) {
                        prepareFreshNavigation(tab);
                    }
                    tab.filterPage = filterPage(request.getUrl().toString());
                    beginNavigation(tab);
                }
                return !allowed;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (tab.view != view) return;
                if (findTarget == view) closeFind();
                if (tab.started == 0) beginNavigation(tab);
                tab.progress = 1;
                if (http(url)) {
                    tab.url = url;
                    tab.filterPage = filterPage(url);
                }
                if (tab == current) updateChrome();
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return filter.blocks(tab.filterPage, request) ? SiteFilter.blocked() : null;
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                if (tab.view != view) return;
                tab.historyTarget = null;
                recordVisibleEntry(tab, view, url);
                restoreScroll(tab, view, url);
                filter.applyCss(view, url);
                finishNavigation(tab, url, "可绘制");
                if (http(url)) {
                    library.visit(url, view.getTitle());
                    scheduleHistory();
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (tab.view != view) return;
                recordVisibleEntry(tab, view, url);
                restoreScroll(tab, view, url);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (tab.view == view && request.isForMainFrame()) {
                    tab.historyTarget = null;
                    finishNavigation(tab, request.getUrl().toString(), "加载失败 " + error.getErrorCode());
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (tab.view != view) return;
                if (http(url)) tab.url = url;
                if (view.getProgress() >= 100) {
                    tab.filterPage = filterPage(url);
                    recordVisibleEntry(tab, view, url);
                    restoreScroll(tab, view, url);
                }
                if (tab == current) updateChrome();
                scheduleSave();
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (tab.view != view) return true;
                if (findTarget == view) resetFindBar();
                finishNavigation(tab, tab.url, "渲染进程退出");
                destroyWeb(tab);
                tab.navigation = null;
                if (tab == current) {
                    page.removeAllViews();
                    TextView retry = label("网页已停止运行 · 点此恢复", 17, TEXT);
                    retry.setGravity(Gravity.CENTER);
                    page.addView(retry, new FrameLayout.LayoutParams(-1, -1));
                    retry.setOnClickListener(v -> showTab(tab));
                    tab.progress = 100;
                    updateChrome();
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (tab.view != view) return;
                tab.progress = progress;
                if (tab == current) {
                    loading.setProgress(progress);
                    loading.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                if (tab.view != view) return;
                tab.title = title;
                library.updateVisitTitle(view.getUrl(), title);
                scheduleHistory();
                scheduleSave();
            }

            @Override public void onReceivedIcon(WebView view, android.graphics.Bitmap image) {
                if (tab.view == view && image != null
                        && image.getWidth() <= 128 && image.getHeight() <= 128) {
                    tab.favicon = image;
                    tab.faviconHost = siteFor(view.getUrl());
                }
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> result,
                                                     FileChooserParams params) {
                if (picker != null) picker.onReceiveValue(null);
                picker = result;
                try {
                    startActivityForResult(params.createIntent(), FILE_PICKER);
                    return true;
                } catch (RuntimeException e) {
                    picker.onReceiveValue(null);
                    picker = null;
                    toast("找不到可用的文件选择器");
                    return true;
                }
            }

            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreen = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                content.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (http(url)) showNewDownload(url, URLUtil.guessFileName(url, disposition, mime), ua);
            else toast("此链接不是可直接下载的 HTTP 或 HTTPS 文件");
        });
        web.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) setScrollTarget(tab, null);
            return false;
        });
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            String url = hit == null ? null : hit.getExtra();
            if (hit == null || hit.getType() != WebView.HitTestResult.SRC_ANCHOR_TYPE || !http(url)) return false;
            String[] choices = {"在后台标签打开", "复制链接", "分享链接"};
            new AlertDialog.Builder(this).setItems(choices, (dialog, which) -> {
                if (which == 0) queueTab(url);
                if (which == 1) {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("链接", url));
                    toast("已复制链接");
                }
                if (which == 2) shareLink(url);
            }).show();
            return true;
        });
        return web;
    }

    private void bindSubmission(EditText field, int imeAction) {
        field.setOnEditorActionListener((view, action, key) -> {
            if (action != imeAction && (key == null || key.getKeyCode() != KeyEvent.KEYCODE_ENTER
                    || key.getAction() != KeyEvent.ACTION_UP)) return false;
            submitAddress(field);
            return true;
        });
    }

    private void submitAddress(EditText field) {
        String raw = field.getText().toString().trim();
        if (raw.isEmpty()) return;
        String url = raw;
        if (!http(raw)) {
            if (!raw.contains(" ") && !raw.contains("://") && raw.split("[/?#]", 2)[0].contains(".")) {
                url = "https://" + raw;
            }
            if (!http(url)) url = "https://www.google.com/search?q=" + Uri.encode(raw);
        }
        navigate(url);
    }

    private void navigate(String url) {
        if (!http(url)) return;
        closeFind();
        hideKeyboard();
        prepareFreshNavigation(current);
        beginNavigation(current);
        current.url = url;
        current.filterPage = filterPage(url);
        if (current.view == null) {
            current.view = createWebView(current);
            attach(current.view);
        }
        current.view.loadUrl(url);
        updateChrome();
        trimTabs();
        scheduleSave();
    }

    private SiteFilter filter() {
        if (siteFilter == null) {
            siteFilter = new SiteFilter(this);
            siteFilter.load(this::applyCurrentFilter);
        }
        return siteFilter;
    }

    private void applyCurrentFilter() {
        if (!isDestroyed() && current != null && current.view != null)
            siteFilter.applyCss(current.view, current.view.getUrl());
    }

    private static SiteFilter.Page filterPage(String url) {
        return url == null ? null : new SiteFilter.Page(url);
    }

    private static String siteFor(String url) {
        return url == null ? null : SiteFilter.normal(Uri.parse(url).getHost());
    }

    private static void recordVisibleEntry(Tab tab, WebView view, String url) {
        if (!http(url) || !url.equals(view.getUrl())) return;
        tab.visibleUrl = url;
        tab.visibleIndex = view.copyBackForwardList().getCurrentIndex();
    }

    private static void rememberScroll(Tab tab) {
        WebView view = tab.view;
        if (view == null || tab.pendingScroll != null || tab.visibleUrl == null
                || !tab.visibleUrl.equals(view.getUrl())) return;
        tab.positions.remember(tab.visibleIndex, tab.visibleUrl, view.getScrollX(), view.getScrollY());
        tab.positions.remember(ScrollPositions.URL_ONLY, tab.visibleUrl, view.getScrollX(), view.getScrollY());
    }

    private static void prepareFreshNavigation(Tab tab) {
        tab.historyTarget = null;
        rememberScroll(tab);
        tab.positions.discardForward(tab.visibleIndex);
        setScrollTarget(tab, null);
    }

    private static void setScrollTarget(Tab tab, ScrollPositions.Position position) {
        tab.pendingScroll = position;
        tab.scrollQueued = false;
    }

    private void restoreScroll(Tab tab, WebView view, String url) {
        ScrollPositions.Position position = tab.pendingScroll;
        if (position == null || tab.scrollQueued || !position.url.equals(url) || !url.equals(view.getUrl())) return;
        tab.scrollQueued = true;
        restoreScrollStep(tab, (ScrollWebView) view, position, 0);
    }

    private void restoreScrollStep(Tab tab, ScrollWebView view, ScrollPositions.Position position, int attempt) {
        handler.postDelayed(() -> {
            if (tab.pendingScroll != position || tab.view != view) return;
            if (!position.url.equals(view.getUrl())) { setScrollTarget(tab, null); return; }
            int reachable = view.maxScrollY();
            if (reachable >= position.y || attempt == 4) {
                if (view.getScrollX() != position.x || view.getScrollY() != Math.min(reachable, position.y)) {
                    view.scrollTo(position.x, Math.min(reachable, position.y));
                }
                setScrollTarget(tab, null);
            } else {
                restoreScrollStep(tab, view, position, attempt + 1);
            }
        }, attempt == 0 ? 32 : 150L * attempt);
    }

    private static final class ScrollWebView extends WebView {
        ScrollWebView(android.content.Context context) { super(context); }
        int maxScrollY() { return Math.max(0, computeVerticalScrollRange() - computeVerticalScrollExtent()); }
    }

    private void beginNavigation(Tab tab) {
        tab.started = SystemClock.elapsedRealtime();
        if (tab == current && visible) tab.priorityTicket = DownloadService.PACER.protect(1350);
    }

    private void finishNavigation(Tab tab, String url, String outcome) {
        DownloadService.PACER.release(tab.priorityTicket);
        tab.priorityTicket = 0;
        if (tab.started == 0) return;
        long elapsed = SystemClock.elapsedRealtime() - tab.started;
        tab.started = 0;
        if (tab != current) return;
        String host = url == null ? null : Uri.parse(url).getHost();
        timings.addFirst(String.format(Locale.ROOT, "%s  ·  %d ms  ·  %s",
                host == null ? "网页" : host, elapsed, outcome));
        if (timings.size() > 12) timings.removeLast();
    }

    private void showPerformance() {
        android.content.pm.PackageInfo engine = WebView.getCurrentWebViewPackage();
        android.view.Display display = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
        String refresh = display == null ? "未知" : String.format(Locale.ROOT, "%.1f Hz", display.getRefreshRate());
        String stats = "系统 WebView：" + (engine == null ? "未知" : engine.versionName)
                + "\n当前屏幕刷新率：" + refresh
                + "\n\n导航到 WebView 可绘制，含网络等待。"
                + "\n\n" + (timings.isEmpty() ? "打开一个网页后查看记录。" : String.join("\n", timings));
        new AlertDialog.Builder(this).setTitle("性能记录").setMessage(stats)
                .setNeutralButton("复制", (d,w) -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("Aiko Browser 性能记录", stats)))
                .setPositiveButton("关闭", null).show();
    }

    void toggleTransferPriority() {
        boolean next = !DownloadService.PACER.browserFirst();
        DownloadService.PACER.setBrowserFirst(next);
        getPreferences(MODE_PRIVATE).edit().putBoolean("browserFirst", next).apply();
    }

    static boolean http(String url) {
        if (url == null) return false;
        try {
            RangeDownload.requireHttp(new URL(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateChrome() {
        boolean home = homeSearch != null;
        addressRow.setVisibility(home ? View.GONE : View.VISIBLE);
        homeSpacer.setVisibility(home ? View.VISIBLE : View.GONE);
        backButton.setVisibility(home || address.hasFocus() ? View.GONE : View.VISIBLE);
        updateAddress();
        tabButton.count(tabs.size());
        tabButton.setContentDescription("标签页，" + tabs.size() + " 个，长按新建");
        backButton.tint(current.view != null && current.view.canGoBack() ? TEXT : MUTED);
        loading.setProgress(current.progress);
        loading.setVisibility(current.progress < 100 ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateAddress() {
        if (address.hasFocus()) return;
        String url = current.url;
        String host = siteFor(url);
        Uri uri = url == null || host == null ? null : Uri.parse(url);
        boolean insecure = uri != null && "http".equalsIgnoreCase(uri.getScheme());
        int port = uri == null ? -1 : uri.getPort();
        String display = host == null ? "" : (insecure ? "http://" : "") + host + (port < 0 ? "" : ":" + port);
        if (!Objects.equals(address.getText().toString(), display)) address.setText(display);
        siteButton.symbol(host == null ? SEARCH : SHIELD);
        siteButton.tint(host != null && !insecure && filter().enabled(host) ? RUBY : MUTED);
        siteButton.setContentDescription(host == null ? "搜索或输入网址" : "站点控制：" + host);
    }

    private void focusAddress() {
        EditText target = activeAddress();
        if (target.requestFocus()) {
            target.postDelayed(() -> {
                if (target.hasFocus()) getSystemService(InputMethodManager.class)
                        .showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
            }, 80);
        }
    }

    private EditText activeAddress() { return homeSearch == null ? address : homeSearch; }

    private void showTabs() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, "标签页");
        LinearLayout quick = sheetRow(panel);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> { addTab(null); focusAddress(); });
        View recent = sheetAction(dialog, quick, HISTORY, "最近关闭", !closedTabs.isEmpty(), this::showClosedTabs);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        list.setPadding(0, dp(8), 0, 0);
        int visibleRows = Math.max(2, (getResources().getConfiguration().screenHeightDp - 160) / 66);
        int height = Math.min(Math.min(tabs.size(), visibleRows), 6) * 66 + 8;
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(height)));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return tabs.size(); }
            @Override public Object getItem(int index) { return tabs.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                TabRow row = recycled instanceof TabRow ? (TabRow) recycled : new TabRow();
                row.bind(tabs.get(index), () -> {
                    notifyDataSetChanged();
                    recent.setEnabled(!closedTabs.isEmpty());
                    recent.setFocusable(recent.isEnabled());
                    recent.setAlpha(recent.isEnabled() ? 1 : 0.38f);
                    list.getLayoutParams().height = dp(Math.min(Math.min(tabs.size(), visibleRows), 6) * 66 + 8);
                    list.requestLayout();
                }, dialog);
                return row;
            }
        });
        showSheet(dialog, panel);
        list.setSelection(tabs.indexOf(current));
    }

    private void closeTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index == -1) return;
        if (findTarget != null && findTarget == tab.view) closeFind();
        if (http(tab.url)) {
            rememberScroll(tab);
            Tab entry = new Tab(tab.url, tab.title);
            entry.favicon = tab.favicon;
            entry.faviconHost = tab.faviconHost;
            ScrollPositions.Position saved = tab.positions.get(ScrollPositions.URL_ONLY, tab.url);
            if (saved != null) entry.positions.remember(ScrollPositions.URL_ONLY, entry.url, saved.x, saved.y);
            closedTabs.addFirst(entry);
            if (closedTabs.size() > 12) closedTabs.removeLast();
        }
        destroyWeb(tab);
        tabs.remove(index);
        if (tabs.isEmpty()) tabs.add(new Tab(null, "新标签"));
        if (tab == current) showTab(tabs.get(Math.min(index, tabs.size() - 1)));
        updateChrome();
        scheduleSave();
    }

    private final class TabRow extends LinearLayout {
        private final LinearLayout link;
        private final ImageView image;
        private final TextView fallback, title, domain;
        private final BrowserIcon close;

        TabRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(3), dp(4), dp(3));
            setLayoutParams(new AbsListView.LayoutParams(-1, dp(66)));
            link = new LinearLayout(MainActivity.this);
            link.setGravity(Gravity.CENTER_VERTICAL);
            link.setPadding(dp(10), 0, 0, 0);
            addView(link, new LinearLayout.LayoutParams(0, -1, 1));
            FrameLayout tile = new FrameLayout(MainActivity.this);
            tile.setBackground(shape(FIELD, 10));
            link.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));
            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            fallback = label("", 15, RUBY);
            fallback.setGravity(Gravity.CENTER);
            tile.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setPadding(dp(10), 0, dp(4), 0);
            text.setOrientation(LinearLayout.VERTICAL);
            link.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            title = label("", 14, TEXT);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(title);
            domain = label("", 12, MUTED);
            domain.setSingleLine(true);
            domain.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(domain);
            close = icon(CLOSE, "关闭标签", () -> {});
            addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        void bind(Tab tab, Runnable onClose, Dialog dialog) {
            String host = siteFor(tab.url);
            String caption = tab.title;
            if (caption == null || caption.isEmpty() || caption.equals(tab.url)) caption = host == null ? "新标签" : host;
            title.setText(caption);
            title.setTextColor(tab == current ? RUBY : TEXT);
            domain.setText(tab == current ? host == null ? "当前标签" : host + "  ·  当前标签" : host);
            domain.setVisibility(host == null && tab != current ? View.GONE : View.VISIBLE);
            boolean useIcon = host != null && host.equals(tab.faviconHost) && tab.favicon != null;
            image.setImageBitmap(useIcon ? tab.favicon : null);
            fallback.setVisibility(useIcon ? View.GONE : View.VISIBLE);
            fallback.setText(host == null ? "A" : host.substring(0, 1).toUpperCase(Locale.ROOT));
            link.setBackground(tab == current ? shape(FIELD, 12) : null);
            link.setForeground(BrowserIcon.feedback(0x28ff587d, dp(12)));
            link.setOnClickListener(v -> { dialog.dismiss(); showTab(tab); });
            close.setContentDescription("关闭标签：" + caption);
            close.setOnClickListener(v -> { closeTab(tab); onClose.run(); });
        }
    }

    private void scheduleHistory() {
        handler.removeCallbacks(saveHistory);
        handler.postDelayed(saveHistory, 1500);
    }

    private void queueTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            toast("最多保留 " + MAX_TABS + " 个标签");
            return;
        }
        tabs.add(new Tab(url, url));
        updateChrome();
        scheduleSave();
        toast("已添加后台标签");
    }

    private void showTools() {
        String url = current.url;
        boolean pageOpen = current.view != null && http(url);
        Tab tab = current;
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, "浏览工具");
        ScrollView scroll = new ScrollView(this);
        int screen = getResources().getConfiguration().screenHeightDp;
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(Math.min(291, Math.max(160, screen - 132)))));
        LinearLayout actions = stack();
        scroll.addView(actions, new ScrollView.LayoutParams(-1, -2));
        groupLabel(actions, "浏览");
        LinearLayout quick = sheetRow(actions);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> { addTab(null); focusAddress(); });
        sheetAction(dialog, quick, DOWNLOAD, "下载", true, this::showDownloads);
        sheetAction(dialog, quick, PRIVATE, "无痕", true, () -> startActivity(new Intent(this, PrivateActivity.class)));
        groupLabel(actions, "当前页面");
        LinearLayout context = sheetRow(actions);
        sheetAction(dialog, context, STAR, pageOpen && library.isBookmarked(url) ? "取消收藏" : "收藏",
                pageOpen, () -> toggleBookmark(tab, url));
        sheetAction(dialog, context, FIND, "页内查找", pageOpen, () -> { if (tab.view != null) openFind(tab.view); });
        sheetAction(dialog, context, SHARE, "分享", pageOpen, () -> shareLink(url));
        sheetAction(dialog, context, REFRESH, "刷新", pageOpen, this::reloadPreservingScroll);
        groupLabel(actions, "记录与设置");
        LinearLayout saved = sheetRow(actions);
        sheetAction(dialog, saved, STAR, "书签", true, () -> showLibrary(true));
        sheetAction(dialog, saved, HISTORY, "历史", true, () -> showLibrary(false));
        sheetAction(dialog, saved, SHIELD, "过滤规则", true, this::showFilterRules);
        sheetAction(dialog, saved, SPEED, "性能记录", true, this::showPerformance);
        showSheet(dialog, panel);
    }

    private void toggleBookmark(Tab tab, String url) {
        if (library.isBookmarked(url)) {
            library.removeBookmark(url);
            toast("已取消收藏");
        } else if (library.bookmarksFull()) {
            toast("已达到书签上限");
        } else {
            library.addBookmark(url, tab.title);
            toast("已收藏");
        }
    }

    private void showSiteControls() {
        Tab tab = current;
        String url = tab.url, host = siteFor(url);
        if (host == null) return;
        boolean filtered = filter().enabled(host);
        boolean https = "https".equalsIgnoreCase(Uri.parse(url).getScheme());
        Dialog dialog = new Dialog(this);
        LinearLayout panel = sheet(dialog, "站点控制");
        TextView site = label(host, 17, TEXT);
        site.setSingleLine(true);
        site.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        site.setPadding(dp(8), dp(6), 0, 0);
        site.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(site);
        groupLabel(panel, (https ? "HTTPS" : "HTTP · 未加密") + "  ·  " + (filtered ? "过滤已开启" : "过滤已暂停"));
        LinearLayout row = sheetRow(panel);
        sheetAction(dialog, row, SHIELD, filtered ? "暂停过滤" : "恢复过滤", true, () -> {
            filter().setEnabled(host, !filtered);
            if (current == tab && current.view != null) reloadPreservingScroll();
            if (current != null) updateAddress();
        });
        sheetAction(dialog, row, REFRESH, "刷新", tab.view != null, this::reloadPreservingScroll);
        sheetAction(dialog, row, COPY, "复制网址", true, () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("网址", url));
            toast("网址已复制");
        });
        showSheet(dialog, panel);
    }

    private LinearLayout stack() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        return stack;
    }

    private LinearLayout sheet(Dialog dialog, String title) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = stack();
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(shape(PANEL, 24));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView name = label(title, 19, TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(dp(8), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
        header.addView(icon(CLOSE, "关闭" + title, dialog::dismiss), new LinearLayout.LayoutParams(dp(48), -1));
        return panel;
    }

    void showSheet(Dialog dialog, View panel) {
        FrameLayout inset = new FrameLayout(this);
        inset.setPadding(dp(8), 0, dp(8), dp(8));
        inset.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        dialog.setContentView(inset);
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(-1, -2);
        window.setGravity(Gravity.BOTTOM);
    }

    private void groupLabel(LinearLayout panel, String title) {
        TextView label = label(title, 12, MUTED);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(8), dp(5), 0, 0);
        panel.addView(label, new LinearLayout.LayoutParams(-1, dp(27)));
    }

    private LinearLayout sheetRow(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(70)));
        return row;
    }

    private View sheetAction(Dialog dialog, LinearLayout row, BrowserIcon.Kind kind, String text,
                             boolean available, Runnable command) {
        LinearLayout tile = stack();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(shape(FIELD, 13));
        tile.setForeground(BrowserIcon.feedback(0x35ff587d, dp(13)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(63), 1);
        size.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tile, size);
        BrowserIcon glyph = new BrowserIcon(this, kind);
        glyph.tint(RUBY);
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(28), dp(27)));
        TextView title = label(text, 12, TEXT);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(title, new LinearLayout.LayoutParams(-1, -2));
        tile.setContentDescription(text);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setOnClickListener(v -> { dialog.dismiss(); command.run(); });
        tile.setFocusable(available);
        tile.setEnabled(available);
        tile.setAlpha(available ? 1 : 0.38f);
        return tile;
    }

    private void showFilterRules() {
        SiteFilter filter = filter();
        FilterRules rules = filter.rules;
        String summary = rules == FilterRules.EMPTY ? "正在准备规则，基础过滤已启用。"
                : rules.networkCount + " 条请求规则，" + rules.cosmeticCount + " 条站点元素规则。"
                        + "\n跳过 " + rules.skipped + " 条不支持的规则。";
        new AlertDialog.Builder(this).setTitle("广告过滤规则")
                .setMessage("EasyList · EasyPrivacy · AdGuard 中文\n\n" + summary
                        + "\n\n可从底部菜单暂停当前站点的过滤。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("更新规则", (dialog, which) -> {
                    if (filter.update((count, applied) -> {
                        if (isDestroyed()) return;
                        if (applied) {
                            applyCurrentFilter();
                            toast("已更新 " + count + "/3 组规则。重新加载可完整应用。");
                        } else {
                            toast("未能更新，继续使用已有规则");
                        }
                    })) toast("正在后台更新规则");
                    else toast("规则正在更新");
                }).show();
    }

    private void shareLink(String url) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, "分享链接"));
    }

    private void showLibrary(boolean bookmarks) {
        java.util.List<BrowserLibrary.Entry> entries = library.pages(bookmarks);
        AlertDialog.Builder list = new AlertDialog.Builder(this)
                .setTitle(bookmarks ? "书签" : "浏览历史").setNegativeButton("关闭", null);
        if (entries.isEmpty()) {
            list.setMessage(bookmarks ? "还没有书签" : "还没有浏览历史").show();
            return;
        }
        ListView rows = new ListView(this);
        ArrayAdapter<String> rowsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        for (BrowserLibrary.Entry entry : entries) rowsAdapter.add(entry.title);
        rows.setAdapter(rowsAdapter);
        AlertDialog dialog = list.setView(rows).create();
        rows.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            navigate(entries.get(position).url);
        });
        if (bookmarks) {
            rows.setOnItemLongClickListener((parent, view, position, id) -> {
                BrowserLibrary.Entry entry = entries.get(position);
                new AlertDialog.Builder(this).setTitle("删除书签？").setMessage(entry.title)
                        .setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> {
                            library.removeBookmark(entry.url);
                            dialog.dismiss();
                            showLibrary(true);
                        }).show();
                return true;
            });
        } else {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "清除历史", (d, w) ->
                    new AlertDialog.Builder(this).setTitle("清除本应用的浏览历史？")
                            .setNegativeButton("保留", null).setPositiveButton("清除", (confirm, button) ->
                                    library.clearHistory()).show());
        }
        dialog.show();
    }

    private void showClosedTabs() {
        if (closedTabs.isEmpty()) { toast("本次会话没有已关闭的网页"); return; }
        ArrayList<Tab> choices = new ArrayList<>(closedTabs);
        String[] titles = new String[choices.size()];
        for (int i = 0; i < titles.length; i++) {
            Tab tab = choices.get(i);
            titles[i] = tab.title == null || tab.title.isEmpty() ? tab.url : tab.title;
        }
        new AlertDialog.Builder(this).setTitle("最近关闭的标签").setItems(titles, (dialog, position) -> {
            if (tabs.size() >= MAX_TABS) { toast("请先关闭一个标签"); return; }
            Tab tab = choices.get(position);
            closedTabs.remove(tab);
            tabs.add(tab);
            showTab(tab);
        }).show();
    }

    private void openFind(WebView target) {
        closeFind();
        findTarget = target;
        EditText search = input("在此页查找");
        TextView count = label("0 / 0", 12, MUTED);
        count.setGravity(Gravity.CENTER);
        findBar.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        findBar.addView(count, new LinearLayout.LayoutParams(dp(56), -1));
        target.setFindListener((index, matches, complete) -> {
            if (findTarget == target && complete) count.setText(matches == 0 ? "0 / 0" : (index + 1) + " / " + matches);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int length) {
                if (findTarget != target) return;
                if (s.length() == 0) {
                    target.clearMatches();
                    count.setText("0 / 0");
                } else {
                    target.findAllAsync(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        addFindControl("‹", "上一处匹配", () -> target.findNext(false));
        addFindControl("›", "下一处匹配", () -> target.findNext(true));
        addFindControl("×", "退出查找", this::closeFind);
        findBar.setVisibility(View.VISIBLE);
        search.requestFocus();
        search.post(() -> { if (findTarget == target) getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT); });
    }

    private void addFindControl(String text, String description, Runnable command) {
        TextView control = action(text, description);
        findBar.addView(control, new LinearLayout.LayoutParams(dp(40), -1));
        control.setOnClickListener(view -> command.run());
    }

    private void closeFind() {
        if (findTarget == null) return;
        findTarget.setFindListener(null);
        findTarget.clearMatches();
        resetFindBar();
    }

    private void resetFindBar() {
        findTarget = null;
        findBar.removeAllViews();
        findBar.setVisibility(View.GONE);
        hideKeyboard();
    }

    void showNewDownload(String url, String name, String ua) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), 0, dp(24), 0);
        EditText link = input("https:// 文件链接");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setText(url);
        fields.addView(link, new LinearLayout.LayoutParams(-1, dp(48)));
        link.setSelectAllOnFocus(true);
        EditText file = input("文件名");
        file.setText(name);
        fields.addView(file, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText count = input("并行路数，留空时按文件大小使用 1–8 路");
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        fields.addView(count, new LinearLayout.LayoutParams(-1, dp(48)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("下载文件").setView(fields)
                .setPositiveButton("下载", null).setNegativeButton("取消", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String chosen = link.getText().toString().trim();
                if (!http(chosen)) { link.setError("请输入 HTTP 或 HTTPS 文件链接"); return; }
                String requested = count.getText().toString().trim();
                int lanes = requested.isEmpty() ? 8 : Integer.parseInt(requested);
                if (lanes < 1 || lanes > RangeDownload.MAX_LANES) {
                    count.setError("输入 1–" + RangeDownload.MAX_LANES);
                    return;
                }
                String filename = file.getText().toString().trim();
                if (filename.isEmpty()) filename = URLUtil.guessFileName(chosen, null, null);
                DownloadService.start(this, chosen, filename, ua, lanes);
                d.dismiss();
                showDownloads();
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("notif", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("notif", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
                }
            } catch (NumberFormatException e) {
                count.setError("输入 1–" + RangeDownload.MAX_LANES);
            } catch (Exception e) {
                String message = e.getMessage();
                toast(message == null || message.contains("://") ? "无法启动下载" : message);
            }
        });
    }

    void showDownloads() { new DownloadPanel(this).show(); }

    @Override public void onBackPressed() {
        if (exitFullscreen()) return;
        if (findTarget != null) { closeFind(); return; }
        if (activeAddress().hasFocus()) { hideKeyboard(); updateChrome(); return; }
        if (!goBack()) super.onBackPressed();
    }

    private boolean goBack() {
        if (exitFullscreen()) return true;
        return moveHistory(-1);
    }

    private boolean moveHistory(int offset) {
        WebView view = current.view;
        if (view == null || !view.canGoBackOrForward(offset)) return false;
        closeFind();
        rememberScroll(current);
        WebBackForwardList history = view.copyBackForwardList();
        int destinationIndex = history.getCurrentIndex() + offset;
        WebHistoryItem target = history.getItemAtIndex(destinationIndex);
        current.historyTarget = target == null ? null : target.getUrl();
        setScrollTarget(current, target == null ? null
                : current.positions.get(destinationIndex, target.getUrl()));
        if (target != null) current.filterPage = filterPage(target.getUrl());
        beginNavigation(current);
        view.goBackOrForward(offset);
        return true;
    }

    private void reloadPreservingScroll() {
        rememberScroll(current);
        setScrollTarget(current, current.positions.get(ScrollPositions.URL_ONLY, current.url));
        beginNavigation(current);
        current.view.reload();
    }

    private boolean exitFullscreen() {
        if (fullscreen == null) return false;
        root.removeView(fullscreen);
        fullscreen = null;
        content.setVisibility(View.VISIBLE);
        fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            picker = null;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (current != null && current.view != null) {
            current.view.onResume();
            if (current.started != 0) current.priorityTicket = DownloadService.PACER.protect(1350);
        }
        scheduleWarm();
    }

    @Override protected void onStop() {
        visible = false;
        dropSpare();
        if (current != null) {
            rememberScroll(current);
            DownloadService.PACER.release(current.priorityTicket);
            current.priorityTicket = 0;
            if (current.view != null) current.view.onPause();
        }
        handler.removeCallbacks(save);
        handler.removeCallbacks(saveHistory);
        library.flushHistory();
        saveTabs();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (siteFilter != null) siteFilter.close();
        handler.removeCallbacksAndMessages(null);
        dropSpare();
        if (picker != null) picker.onReceiveValue(null);
        for (Tab t : tabs) destroyWeb(t);
        super.onDestroy();
    }

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        android.os.IBinder token = focused == null ? content.getWindowToken() : focused.getWindowToken();
        content.requestFocus();
        if (token != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(token, 0);
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radius));
        d.setColor(color);
        return d;
    }

    TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private EditText input(String hint) {
        EditText v = new EditText(this);
        v.setSingleLine(true);
        v.setTextSize(15);
        v.setTextColor(TEXT);
        v.setHintTextColor(MUTED);
        v.setHint(hint);
        return v;
    }

    private TextView action(String symbol, String desc) {
        TextView v = label(symbol, 24, TEXT);
        v.setContentDescription(desc);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    BrowserIcon icon(BrowserIcon.Kind kind, String text, Runnable click) {
        BrowserIcon v = new BrowserIcon(this, kind);
        v.setContentDescription(text);
        v.setForeground(BrowserIcon.feedback(0x2cff587d, dp(15)));
        v.setOnClickListener(w -> click.run());
        v.setFocusable(true);
        return v;
    }
}
