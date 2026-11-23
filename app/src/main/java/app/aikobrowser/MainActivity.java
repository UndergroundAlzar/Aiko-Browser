package app.aikobrowser;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
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
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.aikobrowser.BrowserIcon.Kind.*;

public final class MainActivity extends Activity {
    // Preserved static palette aliases mapped to Obsidian Flagship tokens for peer compatibility.
    static final int BG = AikoTheme.BG;
    static final int PANEL = AikoTheme.SURFACE;
    static final int FIELD = AikoTheme.PILL;
    static final int TEXT = AikoTheme.TEXT;
    static final int MUTED = AikoTheme.TEXT_MUTED;
    static final int RUBY = AikoTheme.ACCENT;

    private static final int MAX_TABS = 64;
    private static final int FILE_PICKER = 11;
    private static final int BOOKMARKS_EXPORT = 13;
    private static final int BOOKMARKS_IMPORT = 14;
    private static final int STATE_SAVE_MAX_BYTES = 256 * 1024;
    private static final Pattern URL_EXTRACT = Pattern.compile("https?://[^\\s<>\"'\\)\\]]+");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "aiko-io");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ArrayList<Tab> tabs = new ArrayList<>();
    private final ArrayDeque<String> timings = new ArrayDeque<>();
    private final ArrayDeque<Tab> closedTabs = new ArrayDeque<>();
    private final Runnable saveTask = this::saveTabsAsync;
    private final Runnable saveHistoryTask = this::flushHistorySafe;
    private final Runnable findDebounceTask = this::runDebouncedFind;

    private float density;
    private boolean isLowRam;
    private int maxHotTabs = 5;

    private DisplayModeEngine displayMode;
    private BrowserLibrary library;
    private SharedPreferences mainPrefs;
    private SharedPreferences siteProfiles;
    private SiteExtras siteExtras;
    private ReadingShelf readingShelf;
    private SiteFilter siteFilter;
    private UserScripts userScripts;
    private ScriptTools scriptTools;
    private String desktopAgent;

    // Chrome view hierarchy
    private FrameLayout root, viewport, page;
    private BottomDeckLayout bottomDeck;
    private LinearLayout controls, addressRow, findBar;
    private View homeSpacer, deckDivider;
    private EditText address, homeSearch, findInput;
    private BrowserIcon backButton, tabButton, menuButton, siteButton, goButton, actionButton;
    private ProgressBar loading;

    // Reusable Home view & dynamic sections
    private ScrollView homeView;
    private LinearLayout homeSpeedGrid, homeShelfSection, homeShelfList;
    private TextView homeEngineBadge, homeStatusPill;
    private boolean onHome;

    // Lazy focus-only Omnibox suggestions overlay
    private LinearLayout omniboxOverlay, omniboxList;
    private ScrollView omniboxScroll;
    private boolean editingOmnibox;
    private String lastSuggestionQuery;

    // Opt-in 185Hz Live Frame-Pacing HUD pill (0% overhead when off)
    private TextView hudPill;
    private boolean hudEnabled;

    // Gesture & on-demand overlays
    private GestureChrome gestures;
    private ElementPicker elementPicker;
    private View crashOverlay;
    private boolean cachedAutoHide = true;
    private boolean cachedEdgeGestures = true;

    // Window insets state (prevents WebView reflow while editing URL bar)
    private int insetLeft, insetTop, insetRight, insetBottom, imeBottom;

    // Active tab & lifecycle state
    private Tab current;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private ValueCallback<Uri[]> picker;
    private WebView findTarget;
    private boolean firstDraw;
    private boolean visible;
    private boolean engineReady;
    private boolean filterReady;

    private static final class Tab {
        String url, title, cachedHost;
        WebView view;
        Bundle navigation;
        final ScrollPositions positions = new ScrollPositions();
        String visibleUrl;
        int visibleIndex = ScrollPositions.URL_ONLY;
        volatile SiteFilter.Page filterPage;
        boolean filterPagePrepared, desktopApplied;
        boolean muted;
        boolean canGoBack, canGoForward;
        int baseTextZoom = SiteProfile.DEFAULT_ZOOM;
        ScrollPositions.Position pendingScroll;
        boolean scrollQueued;
        Bitmap favicon;
        String faviconHost;
        String historyTarget;
        long used;
        long started, priorityTicket;
        long lastCommitMs, lastFinishMs;
        boolean lastNavBfCache;
        int progress = 100;

        Tab(String url, String title) {
            this.url = url;
            this.title = title;
            this.cachedHost = url == null ? null : normalizeHostFast(url);
        }

        void setUrl(String next) {
            this.url = next;
            this.cachedHost = next == null ? null : normalizeHostFast(next);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        ActivityManager am = getSystemService(ActivityManager.class);
        isLowRam = am != null && am.isLowRamDevice();
        maxHotTabs = WebViewPerf.recommendedHotTabs(this);

        // Configure window colors & edge-to-edge immediately before first frame
        Window win = getWindow();
        win.setStatusBarColor(AikoTheme.BG);
        win.setNavigationBarColor(AikoTheme.BG);
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) win.setDecorFitsSystemWindows(false);

        // Unlock flagship 120-185Hz display mode from frame 1
        displayMode = new DisplayModeEngine(this);
        displayMode.apply();

        // Kick off ALL disk/engine warmup asynchronously BEFORE touching WebView
        mainPrefs = getPreferences(MODE_PRIVATE);
        siteProfiles = getSharedPreferences("site_profiles", MODE_PRIVATE);
        library = new BrowserLibrary(getSharedPreferences("library", MODE_PRIVATE));
        siteExtras = new SiteExtras(this);
        readingShelf = new ReadingShelf(this);
        userScripts = new UserScripts(this);
        scriptTools = new ScriptTools(this, userScripts);

        library.preloadAsync();
        siteExtras.preloadAsync();
        readingShelf.preloadAsync();
        userScripts.preloadAsync();
        filter(); // Start background adblock compile immediately (no 600ms lag!)
        WebViewPerf.startUpAsync(getApplicationContext(), this::onWebViewEngineReady);

        // Read minimal startup prefs (browserFirst + tabs) & paint chrome on frame 1 (zero sync extras/filter disk reads!)
        DownloadService.PACER.setBrowserFirst(mainPrefs.getBoolean("browserFirst", true));
        buildChrome();
        restoreTabs(state == null ? incomingLink(getIntent()) : null);
        setupHudIfEnabled();
        if (state == null && getIntent().getBooleanExtra("downloads", false)) showDownloads();

        // Sync preloaded SiteExtras gesture flags off frame 1 without blocking UI thread
        ioExecutor.execute(() -> {
            boolean ah = siteExtras.isAutoHideBar();
            boolean eg = siteExtras.isEdgeGestures();
            handler.post(() -> {
                if (!isDestroyed()) applyGestureValues(ah, eg);
            });
        });

        // Refresh Home cards once async preloads settle
        handler.postDelayed(() -> {
            if (!isDestroyed() && onHome) refreshHomeContent();
        }, 120);
    }

    private void onWebViewEngineReady() {
        if (isDestroyed() || isFinishing()) return;
        engineReady = true;
        if (current != null && current.url != null && current.view == null) {
            ensureWebViewAndLoad(current);
        }
        // Subtle home preconnect to configured search engine once engine is warm
        if (onHome) {
            String origin = SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this));
            if (origin != null && !origin.isEmpty()) WebViewPerf.preconnect(origin, siteFilter);
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        density = getResources().getDisplayMetrics().density;
        if (displayMode != null) displayMode.apply();
        if (gestures != null && current != null) {
            gestures.updateExclusionRects(page, current.canGoBack, current.canGoForward);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null && fullscreen == null && findTarget == null
                && (elementPicker == null || !elementPicker.isActive())) {
            gestures.observeEdgeTouch(root, ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("downloads", false)) showDownloads();
        String link = incomingLink(intent);
        if (link != null) addTab(link, false);
    }

    private String incomingLink(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            String link = intent.getDataString();
            return HttpUrlUtil.isHttp(link) ? link : null;
        }
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared == null) return null;
            String trimmed = shared.trim();
            if (trimmed.isEmpty()) return null;
            Matcher m = URL_EXTRACT.matcher(trimmed);
            if (m.find()) {
                String candidate = m.group();
                while (candidate.length() > 8 && ".,;:!?)".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                    candidate = candidate.substring(0, candidate.length() - 1);
                }
                if (HttpUrlUtil.isHttp(candidate)) return candidate;
            }
            return SearchEngines.resolveInput(this, trimmed);
        }
        return null;
    }

    private void buildChrome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(AikoTheme.BG);

        viewport = new FrameLayout(this);
        viewport.setFocusableInTouchMode(true);
        root.addView(viewport, new FrameLayout.LayoutParams(-1, -1));

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
                applyInsetsLayout();
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        page = new FrameLayout(this);
        page.setBackgroundColor(AikoTheme.BG);
        viewport.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Bottom deck: hairline progress bar + 1dp divider + obsidian controls + find bar
        bottomDeck = new BottomDeckLayout();
        bottomDeck.setOrientation(LinearLayout.VERTICAL);
        bottomDeck.setBackgroundColor(AikoTheme.SURFACE);
        bottomDeck.setElevation(dp(8));
        viewport.addView(bottomDeck, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setProgressTintList(ColorStateList.valueOf(AikoTheme.ACCENT));
        loading.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        loading.setMax(100);
        loading.setVisibility(View.INVISIBLE);
        bottomDeck.addView(loading, new LinearLayout.LayoutParams(-1, dp(2)));

        deckDivider = new View(this);
        deckDivider.setBackgroundColor(AikoTheme.DIVIDER);
        bottomDeck.addView(deckDivider, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(density))));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(4), dp(6), dp(4), dp(6));
        bottomDeck.addView(controls, new LinearLayout.LayoutParams(-1, dp(56)));

        backButton = icon(BACK, "后退，长按前进", this::goBack);
        controls.addView(backButton, new LinearLayout.LayoutParams(dp(46), dp(44)));
        backButton.setOnLongClickListener(v -> {
            if (!moveHistory(1)) toast("没有可前进的页面");
            return true;
        });

        addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(18)));
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(0, dp(42), 1);
        arlp.leftMargin = dp(2);
        arlp.rightMargin = dp(2);
        controls.addView(addressRow, arlp);

        siteButton = icon(SEARCH, "搜索或输入网址", () -> {
            if (current == null || current.url == null) focusAddress();
            else showSiteControls();
        });
        addressRow.addView(siteButton, new LinearLayout.LayoutParams(dp(40), -1));

        address = input("搜索或输入网址");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(0, 0, dp(4), 0);
        address.setTextSize(14);
        address.setTypeface(AikoTheme.MEDIUM);
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setEllipsize(TextUtils.TruncateAt.END);
        addressRow.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        bindSubmission(address, EditorInfo.IME_ACTION_GO);

        actionButton = icon(REFRESH, "刷新", () -> {
            if (current == null || current.view == null) return;
            if (current.progress < 100) current.view.stopLoading();
            else reloadPreservingScroll();
        });
        actionButton.tint(AikoTheme.TEXT_MUTED);
        addressRow.addView(actionButton, new LinearLayout.LayoutParams(dp(38), -1));

        goButton = icon(GO, "前往", () -> submitAddress(address));
        goButton.tint(AikoTheme.ACCENT);
        goButton.setVisibility(View.GONE);
        addressRow.addView(goButton, new LinearLayout.LayoutParams(dp(44), -1));

        homeSpacer = new View(this);
        homeSpacer.setVisibility(View.GONE);
        controls.addView(homeSpacer, new LinearLayout.LayoutParams(0, -1, 1));

        tabButton = icon(TABS, "标签页，长按新建", this::showTabs);
        tabButton.setOnLongClickListener(v -> { addTab(null, true); return true; });
        controls.addView(tabButton, new LinearLayout.LayoutParams(dp(46), dp(44)));

        menuButton = icon(MENU, "浏览工具，长按打开下载", this::showTools);
        menuButton.setOnLongClickListener(v -> { showDownloads(); return true; });
        controls.addView(menuButton, new LinearLayout.LayoutParams(dp(46), dp(44)));

        address.setOnFocusChangeListener((field, editing) -> {
            if (current == null) return;
            editingOmnibox = editing;
            if (editing) {
                if (gestures != null) gestures.showBar(false);
                String origin = SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this));
                if (origin != null && !origin.isEmpty()) WebViewPerf.preconnect(origin, siteFilter);
                addressRow.setVisibility(View.VISIBLE);
                homeSpacer.setVisibility(View.GONE);
            } else {
                addressRow.setVisibility(onHome ? View.GONE : View.VISIBLE);
                homeSpacer.setVisibility(onHome ? View.VISIBLE : View.GONE);
            }
            int vis = editing ? View.GONE : View.VISIBLE;
            backButton.setVisibility(editing || onHome ? View.GONE : View.VISIBLE);
            tabButton.setVisibility(vis);
            menuButton.setVisibility(vis);
            siteButton.setVisibility(vis);
            actionButton.setVisibility(editing ? View.GONE : View.VISIBLE);
            goButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            address.setPadding(editing ? dp(14) : 0, 0, dp(6), 0);
            address.setEllipsize(editing ? null : TextUtils.TruncateAt.END);
            if (editing) {
                address.setText(current.url == null ? "" : current.url);
                address.selectAll();
                showOmniboxOverlay(address.getText());
            } else {
                hideOmniboxOverlay();
                updateAddress();
            }
            applyInsetsLayout();
        });
        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (address.hasFocus()) showOmniboxOverlay(s);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        findBar = new LinearLayout(this);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(AikoTheme.SURFACE);
        findBar.setPadding(dp(10), dp(4), dp(8), dp(4));
        findBar.setVisibility(View.GONE);
        bottomDeck.addView(findBar, new LinearLayout.LayoutParams(-1, dp(52)));

        // GestureChrome & ElementPicker overlays hosted cleanly on root
        gestures = new GestureChrome(this, root, new GestureChrome.Callbacks() {
            @Override public boolean isEditingAddress() { return isAnyOmniboxFocused(); }
            @Override public boolean isHomeVisible() { return onHome; }
            @Override public boolean canGoBack() { return current != null && current.canGoBack; }
            @Override public boolean canGoForward() { return current != null && current.canGoForward; }
            @Override public boolean canWebScrollHorizontally(int direction) {
                return current != null && current.view != null && current.view.canScrollHorizontally(direction);
            }
            @Override public int tabCount() { return tabs.size(); }
            @Override public int currentTabIndex() { return Math.max(0, tabs.indexOf(current)); }
            @Override public String tabPreviewLabel(int index) {
                if (index < 0 || index >= tabs.size()) return "标签";
                Tab t = tabs.get(index);
                if (t.title != null && !t.title.isEmpty() && !t.title.equals(t.url)) return t.title;
                return t.cachedHost != null && !t.cachedHost.isEmpty() ? t.cachedHost : "新标签";
            }
            @Override public void onSwitchTab(int delta) { switchTabByDelta(delta); }
            @Override public void onOpenTabs() { showTabs(); }
            @Override public void onEdgeBack() { goBack(); }
            @Override public void onEdgeForward() { moveHistory(1); }
        });
        gestures.attachBottomDeck(bottomDeck);

        elementPicker = new ElementPicker(this, root, new ElementPicker.Callback() {
            @Override public void onSaveRule(String abpRule) {
                filter().appendCustomRule(abpRule, ok -> {
                    if (isDestroyed()) return;
                    if (Boolean.TRUE.equals(ok)) {
                        applyCurrentFilter();
                        toast("已添加屏蔽规则：" + abpRule);
                    } else {
                        toast("规则无效或保存失败，请尝试扩大选区");
                    }
                });
            }
            @Override public void onToast(String message) { toast(message); }
            @Override public void onStateChanged(boolean active) {
                if (active && gestures != null) gestures.showBar(true);
            }
        });

        setContentView(root);
        viewport.requestFocus();
        root.post(() -> {
            if (!firstDraw) {
                firstDraw = true;
                reportFullyDrawn();
            }
        });
    }

    /**
     * Prevents underlying WebView viewport resize/reflow when typing in the omnibox,
     * while preserving natural adjustResize when typing inside webpage forms.
     */
    private void applyInsetsLayout() {
        if (viewport == null) return;
        boolean editingChrome = isAnyOmniboxFocused() || findTarget != null;
        int extraIme = Math.max(0, imeBottom - insetBottom);
        if (editingChrome) {
            viewport.setPadding(insetLeft, insetTop, insetRight, insetBottom);
            bottomDeck.setTranslationY(-extraIme);
            if (omniboxOverlay != null && omniboxOverlay.getVisibility() == View.VISIBLE) {
                FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) omniboxOverlay.getLayoutParams();
                int bottomOffset = extraIme + dp(62);
                int availH = Math.max(dp(96), root.getHeight() - insetTop - insetBottom - bottomOffset - dp(16));
                int maxOverlayH = Math.min(dp(260), availH);
                if (olp.bottomMargin != bottomOffset) {
                    olp.bottomMargin = bottomOffset;
                    omniboxOverlay.setLayoutParams(olp);
                }
                if (omniboxScroll != null) {
                    ViewGroup.LayoutParams slp = omniboxScroll.getLayoutParams();
                    int desiredH = Math.min(maxOverlayH, measureOmniboxContentHeight());
                    if (slp.height != desiredH) {
                        slp.height = desiredH;
                        omniboxScroll.setLayoutParams(slp);
                    }
                }
            }
        } else {
            viewport.setPadding(insetLeft, insetTop, insetRight, Math.max(insetBottom, imeBottom));
            if (gestures == null || !gestures.isBarHidden()) {
                bottomDeck.setTranslationY(0f);
            }
        }
        updatePageBottomInset();
    }

    private int measureOmniboxContentHeight() {
        if (omniboxList == null) return dp(120);
        int count = omniboxList.getChildCount();
        return Math.max(dp(56), count * dp(50) + dp(8));
    }

    private void updatePageBottomInset() {
        if (page == null) return;
        int desiredMargin = (onHome || !cachedAutoHide) ? dp(58) : 0;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) page.getLayoutParams();
        if (lp != null && lp.bottomMargin != desiredMargin) {
            lp.bottomMargin = desiredMargin;
            page.setLayoutParams(lp);
        }
    }

    private void applyGesturePreferences() {
        if (siteExtras == null) return;
        applyGestureValues(siteExtras.isAutoHideBar(), siteExtras.isEdgeGestures());
    }

    private void applyGestureValues(boolean autoHide, boolean edgeGestures) {
        cachedAutoHide = autoHide;
        cachedEdgeGestures = edgeGestures;
        if (gestures != null) {
            gestures.setAutoHideEnabled(autoHide);
            gestures.setEdgeGesturesEnabled(edgeGestures);
            gestures.updateExclusionRects(page,
                    current != null && current.canGoBack,
                    current != null && current.canGoForward);
        }
        updatePageBottomInset();
    }

    private void restoreTabs(String incoming) {
        try {
            JSONArray stored = new JSONArray(mainPrefs.getString("tabs", "[]"));
            for (int i = 0; i < stored.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject item = stored.getJSONObject(i);
                String url = item.optString("url");
                Tab tab = new Tab(HttpUrlUtil.isHttp(url) ? url : null, item.optString("title"));
                tab.positions.remember(ScrollPositions.URL_ONLY, tab.url,
                        item.optInt("scrollX"), item.optInt("scrollY"));
                tabs.add(tab);
            }
        } catch (Exception ignored) { }
        int index = mainPrefs.getInt("selected", 0);
        if (incoming != null && tabs.size() < MAX_TABS) {
            tabs.add(new Tab(incoming, incoming));
            index = tabs.size() - 1;
        }
        if (tabs.isEmpty()) tabs.add(new Tab(null, "新标签"));
        showTab(tabs.get(Math.min(Math.max(index, 0), tabs.size() - 1)), false);
    }

    /** Serializes tab snapshot on background I/O thread so UI thread never stalls at 185Hz. */
    private void saveTabsAsync() {
        final ArrayList<TabSnapshot> snap = new ArrayList<>(tabs.size());
        final int selected = Math.max(0, tabs.indexOf(current));
        for (Tab t : tabs) {
            ScrollPositions.Position pos = t.positions.get(ScrollPositions.URL_ONLY, t.url);
            snap.add(new TabSnapshot(
                    t.url == null ? "" : t.url,
                    t.title == null ? "" : t.title,
                    pos != null,
                    pos == null ? 0 : pos.x,
                    pos == null ? 0 : pos.y));
        }
        ioExecutor.execute(() -> {
            JSONArray list = new JSONArray();
            for (TabSnapshot s : snap) {
                JSONObject item = new JSONObject();
                try {
                    item.put("url", s.url);
                    item.put("title", s.title);
                    if (s.hasScroll) {
                        item.put("scrollX", s.scrollX);
                        item.put("scrollY", s.scrollY);
                    }
                    list.put(item);
                } catch (Exception ignored) { }
            }
            mainPrefs.edit().putString("tabs", list.toString()).putInt("selected", selected).apply();
        });
    }

    private static final class TabSnapshot {
        final String url, title;
        final boolean hasScroll;
        final int scrollX, scrollY;

        TabSnapshot(String url, String title, boolean hasScroll, int scrollX, int scrollY) {
            this.url = url;
            this.title = title;
            this.hasScroll = hasScroll;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
        }
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 600);
    }

    private void addTab(String url, boolean focusBox) {
        if (tabs.size() >= MAX_TABS) {
            toast("最多保留 " + MAX_TABS + " 个标签");
            return;
        }
        Tab tab = new Tab(url, url == null ? "新标签" : url);
        tabs.add(tab);
        showTab(tab, focusBox);
        if (focusBox) focusAddress();
    }

    private void switchTabByDelta(int delta) {
        if (tabs.size() <= 1 || current == null) return;
        int cur = tabs.indexOf(current);
        if (cur < 0) return;
        int next = (cur + delta + tabs.size()) % tabs.size();
        showTab(tabs.get(next), false);
    }

    private void showTab(Tab tab, boolean keepKeyboard) {
        clearCrashOverlay();
        if (elementPicker != null && elementPicker.isActive()) elementPicker.stop();
        if (current != tab) closeFind();
        hideOmniboxOverlay();
        if (!keepKeyboard) hideKeyboardIfNeeded();

        if (current != tab && current != null) {
            rememberScroll(current);
            DownloadService.PACER.release(current.priorityTicket);
            current.priorityTicket = 0;
            if (current.view != null) {
                current.view.setVisibility(View.GONE);
                current.view.onPause();
                WebViewPerf.setActiveRenderer(current.view, false);
            }
        }
        current = tab;
        tab.used = SystemClock.uptimeMillis();
        if (gestures != null) gestures.showBar(false);

        if (tab.url == null && tab.view == null) {
            onHome = true;
            showHomeView();
            if (displayMode != null) displayMode.setSiteForceMaxHz(false);
        } else {
            onHome = false;
            if (homeView != null) homeView.setVisibility(View.GONE);
            if (tab.view != null) {
                attachWarmWebView(tab.view);
                tab.view.setVisibility(View.VISIBLE);
                tab.view.onResume();
                WebViewPerf.setActiveRenderer(tab.view, true);
                if (displayMode != null) {
                    displayMode.attachView(tab.view);
                    displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(tab.cachedHost));
                }
            } else if (engineReady || WebViewPerf.isStarted() || firstDraw) {
                // Only construct WebView once async startup settled or user navigated after frame 1
                ensureWebViewAndLoad(tab);
            }
        }
        updatePageBottomInset();
        updateChrome();
        trimTabs(maxHotTabs);
        scheduleSave();
    }

    private void ensureWebViewAndLoad(Tab tab) {
        if (tab == null || tab.url == null || tab.view != null) return;
        beginNavigation(tab);
        setScrollTarget(tab, tab.positions.get(ScrollPositions.URL_ONLY, tab.url));
        tab.view = createWebView(tab);
        prepareTopLoad(tab, tab.url, true, false);
        attachWarmWebView(tab.view);
        if (tab == current) {
            onHome = false;
            if (homeView != null) homeView.setVisibility(View.GONE);
            tab.view.setVisibility(View.VISIBLE);
            tab.view.onResume();
            WebViewPerf.setActiveRenderer(tab.view, true);
            if (displayMode != null) {
                displayMode.attachView(tab.view);
                displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(tab.cachedHost));
            }
        } else {
            tab.view.setVisibility(View.GONE);
            tab.view.onPause();
            WebViewPerf.setActiveRenderer(tab.view, false);
        }
        if (tab.navigation == null || tab.view.restoreState(tab.navigation) == null) {
            tab.view.loadUrl(tab.url);
        }
        tab.navigation = null;
        updatePageBottomInset();
        if (tab == current) updateChrome();
    }

    /** Keeps hot WebViews attached inside `page` as GONE instead of tearing down GPU surfaces. */
    private void attachWarmWebView(WebView web) {
        if (web.getParent() == page) return;
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        page.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    private void trimTabs(int limit) {
        int cap = Math.max(1, limit);
        for (;;) {
            int count = 0;
            Tab oldest = null;
            for (Tab t : tabs) {
                if (t.view == null) continue;
                count++;
                if (t != current && (oldest == null || t.used < oldest.used)) oldest = t;
            }
            if (count <= cap || oldest == null) return;
            rememberScroll(oldest);
            oldest.navigation = new Bundle();
            WebViewPerf.saveStateBounded(oldest.view, oldest.navigation, STATE_SAVE_MAX_BYTES);
            destroyWeb(oldest);
        }
    }

    private void destroyWeb(Tab tab) {
        WebView web = tab.view;
        if (web == null) return;
        tab.view = null;
        tab.desktopApplied = false;
        tab.filterPagePrepared = false;
        tab.canGoBack = false;
        tab.canGoForward = false;
        userScripts.detach(web);
        setScrollTarget(tab, null);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) {
            trimTabs(1);
            for (Tab t : tabs) if (t != current) t.favicon = null;
        } else if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN
                || level == TRIM_MEMORY_BACKGROUND) {
            trimTabs(Math.min(2, maxHotTabs));
        }
    }

    // ==================== START PAGE (HOME) ====================

    private void showHomeView() {
        clearCrashOverlay();
        ensureHomeView();
        homeView.setVisibility(View.VISIBLE);
        refreshHomeContent();
    }

    private void ensureHomeView() {
        if (homeView != null) return;
        homeView = new ScrollView(this);
        homeView.setFillViewport(true);
        homeView.setVerticalScrollBarEnabled(false);
        homeView.setBackgroundColor(AikoTheme.BG);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(20), dp(42), dp(20), dp(36));
        homeView.addView(container, new ScrollView.LayoutParams(-1, -2));

        // Airy Hero Header: Aiko emblem + brand + live refresh/shield pill
        FrameLayout emblemFrame = new FrameLayout(this);
        emblemFrame.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, dp(20)));
        emblemFrame.setElevation(dp(6));
        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.aiko_pixel);
        emblem.setScaleType(ImageView.ScaleType.FIT_CENTER);
        emblemFrame.addView(emblem, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        container.addView(emblemFrame, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView brand = new TextView(this);
        brand.setText("Aiko");
        brand.setTextSize(26);
        brand.setTextColor(AikoTheme.TEXT);
        brand.setTypeface(AikoTheme.BOLD);
        brand.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(12);
        container.addView(brand, blp);

        homeStatusPill = AikoTheme.badge(this, density, "极速高刷 · 过滤就绪",
                AikoTheme.CYAN, AikoTheme.SURFACE, AikoTheme.STROKE);
        homeStatusPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        homeStatusPill.setOnClickListener(v -> showRefreshSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(24);
        container.addView(homeStatusPill, slp);

        // Floating Pill Omnibox with clickable Search Engine badge
        LinearLayout boxRow = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int w = Math.min(MeasureSpec.getSize(widthSpec), dp(540));
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        boxRow.setGravity(Gravity.CENTER_VERTICAL);
        boxRow.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(24)));
        boxRow.setElevation(dp(6));
        boxRow.setPadding(dp(8), dp(4), dp(6), dp(4));
        container.addView(boxRow, new LinearLayout.LayoutParams(-1, dp(56)));

        homeEngineBadge = new TextView(this);
        homeEngineBadge.setTextSize(12);
        homeEngineBadge.setTypeface(AikoTheme.MEDIUM);
        homeEngineBadge.setTextColor(AikoTheme.ACCENT);
        homeEngineBadge.setGravity(Gravity.CENTER);
        homeEngineBadge.setPadding(dp(12), dp(6), dp(10), dp(6));
        homeEngineBadge.setBackground(AikoTheme.rounded(AikoTheme.CARD, dp(16)));
        homeEngineBadge.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        homeEngineBadge.setContentDescription("切换搜索引擎");
        homeEngineBadge.setOnClickListener(v -> showSearchEnginePicker());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, dp(36));
        elp.rightMargin = dp(8);
        boxRow.addView(homeEngineBadge, elp);

        homeSearch = input("搜索或输入网址");
        homeSearch.setInputType(InputType.TYPE_NULL);
        homeSearch.setShowSoftInputOnFocus(false);
        homeSearch.setCursorVisible(false);
        homeSearch.setBackgroundColor(Color.TRANSPARENT);
        homeSearch.setPadding(dp(2), 0, dp(4), 0);
        homeSearch.setGravity(Gravity.CENTER_VERTICAL);
        homeSearch.setTextSize(15);
        homeSearch.setTypeface(AikoTheme.MEDIUM);
        boxRow.addView(homeSearch, new LinearLayout.LayoutParams(0, -1, 1));

        // Tapping Start Page search pill seamlessly opens the bottom omnibox + scrollable suggestions above IME
        homeSearch.setOnClickListener(v -> focusAddress());
        homeSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusAddress();
        });

        BrowserIcon go = icon(GO, "搜索或前往", this::focusAddress);
        go.tint(AikoTheme.ACCENT);
        boxRow.addView(go, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // Speed Dial 4-column grid
        LinearLayout dialHeader = new LinearLayout(this);
        dialHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(-1, -2);
        dhlp.topMargin = dp(24);
        dhlp.bottomMargin = dp(8);
        container.addView(dialHeader, dhlp);

        TextView dialTitle = label("快捷访问", 12, AikoTheme.TEXT_DIM);
        dialTitle.setTypeface(AikoTheme.MEDIUM);
        dialHeader.addView(dialTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView addPin = label("+ 添加", 12, AikoTheme.ACCENT);
        addPin.setTypeface(AikoTheme.MEDIUM);
        addPin.setPadding(dp(8), dp(4), dp(4), dp(4));
        addPin.setOnClickListener(v -> showAddSpeedDialDialog());
        dialHeader.addView(addPin, new LinearLayout.LayoutParams(-2, -2));

        homeSpeedGrid = new LinearLayout(this);
        homeSpeedGrid.setOrientation(LinearLayout.VERTICAL);
        container.addView(homeSpeedGrid, new LinearLayout.LayoutParams(-1, -2));

        // Reading Shelf ("稍后读 · 0MB 内存") section
        homeShelfSection = new LinearLayout(this);
        homeShelfSection.setOrientation(LinearLayout.VERTICAL);
        homeShelfSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(-1, -2);
        shlp.topMargin = dp(22);
        container.addView(homeShelfSection, shlp);

        TextView shelfTitle = label("稍后读 · 0MB 内存", 12, AikoTheme.TEXT_DIM);
        shelfTitle.setTypeface(AikoTheme.MEDIUM);
        shelfTitle.setPadding(0, 0, 0, dp(8));
        homeShelfSection.addView(shelfTitle);

        homeShelfList = new LinearLayout(this);
        homeShelfList.setOrientation(LinearLayout.VERTICAL);
        homeShelfSection.addView(homeShelfList, new LinearLayout.LayoutParams(-1, -2));

        page.addView(homeView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void refreshHomeContent() {
        if (homeView == null) return;
        String engineName = SearchEngines.engineName(SearchEngines.currentId(this));
        if (homeEngineBadge != null) homeEngineBadge.setText(engineName + " ▾");
        if (homeStatusPill != null && displayMode != null) {
            float hz = displayMode.activeRefreshRate();
            String hzText = hz > 0 ? String.format(Locale.ROOT, "%.0fHz", hz) : "高刷";
            homeStatusPill.setText(hzText + " · " + DisplayModeEngine.policyLabel(DisplayModeEngine.loadPolicy(this)));
            homeStatusPill.setTextColor(displayMode.isOemCapped() ? AikoTheme.AMBER : AikoTheme.CYAN);
        }

        // Rebuild 4-column Speed Dial grid
        homeSpeedGrid.removeAllViews();
        List<BrowserLibrary.Entry> pins = library.pins();
        int cols = 4;
        int total = pins.size();
        for (int i = 0; i < total; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rlp.topMargin = dp(8);
            homeSpeedGrid.addView(row, rlp);

            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < total) {
                    row.addView(buildSpeedDialTile(pins.get(idx)), speedDialCellParams(c < cols - 1));
                } else {
                    row.addView(new View(this), speedDialCellParams(c < cols - 1));
                }
            }
        }

        // Rebuild Reading Shelf section
        List<ReadingShelf.Entry> shelf = readingShelf.list();
        homeShelfList.removeAllViews();
        if (shelf.isEmpty()) {
            homeShelfSection.setVisibility(View.GONE);
        } else {
            homeShelfSection.setVisibility(View.VISIBLE);
            int limit = Math.min(shelf.size(), 8);
            for (int i = 0; i < limit; i++) {
                homeShelfList.addView(buildShelfCard(shelf.get(i)));
            }
        }
    }

    private LinearLayout.LayoutParams speedDialCellParams(boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(82), 1);
        if (rightGap) lp.rightMargin = dp(8);
        return lp;
    }

    private View buildSpeedDialTile(BrowserLibrary.Entry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(10), dp(6), dp(8));
        tile.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
        tile.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));

        String host = HttpUrlUtil.extractHost(entry.url);
        String mono = !host.isEmpty() ? host.substring(0, 1).toUpperCase(Locale.ROOT)
                : (!entry.title.isEmpty() ? entry.title.substring(0, 1).toUpperCase(Locale.ROOT) : "A");

        TextView circle = new TextView(this);
        circle.setText(mono);
        circle.setTextSize(15);
        circle.setTypeface(AikoTheme.BOLD);
        circle.setTextColor(AikoTheme.ACCENT);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(AikoTheme.rounded(AikoTheme.PILL, dp(12)));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView caption = new TextView(this);
        caption.setText(entry.title);
        caption.setTextSize(11);
        caption.setTextColor(AikoTheme.TEXT);
        caption.setSingleLine(true);
        caption.setGravity(Gravity.CENTER);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        tile.addView(caption, clp);

        // Finger-down preconnect (~100ms before ACTION_UP!)
        tile.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                WebViewPerf.preconnect(entry.url, siteFilter);
            }
            return false;
        });
        tile.setOnClickListener(v -> navigate(entry.url));
        tile.setOnLongClickListener(v -> {
            String[] opts = {"在后台标签打开", "从快捷访问移除"};
            new AlertDialog.Builder(this).setTitle(entry.title).setItems(opts, (d, which) -> {
                if (which == 0) queueTab(entry.url);
                else {
                    library.togglePin(entry.url, entry.title);
                    refreshHomeContent();
                }
            }).show();
            return true;
        });
        return tile;
    }

    private View buildShelfCard(ReadingShelf.Entry item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(8), dp(10));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        TextView t = label(item.title == null || item.title.isEmpty() ? item.url : item.title, 13, AikoTheme.TEXT);
        t.setTypeface(AikoTheme.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);

        String host = HttpUrlUtil.extractHost(item.url);
        boolean hasArchive = item.archivePath != null && !item.archivePath.isEmpty() && new File(item.archivePath).isFile();
        String subText = host + (hasArchive ? "  ·  离线可读" : "");
        TextView sub = label(subText, 11, hasArchive ? AikoTheme.EMERALD : AikoTheme.TEXT_MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub);

        BrowserIcon rm = icon(CLOSE, "移除稍后读", () -> {
            readingShelf.remove(item.url);
            refreshHomeContent();
        });
        rm.tint(AikoTheme.TEXT_DIM);
        card.addView(rm, new LinearLayout.LayoutParams(dp(40), dp(40)));

        card.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                WebViewPerf.preconnect(item.url, siteFilter);
            }
            return false;
        });
        card.setOnClickListener(v -> {
            if (current != null && item.scrollY > 0) {
                current.positions.remember(ScrollPositions.URL_ONLY, item.url, item.scrollX, item.scrollY);
            }
            navigate(item.url);
            if (current != null && item.scrollY > 0) {
                setScrollTarget(current, new ScrollPositions.Position(item.url, item.scrollX, item.scrollY));
            }
        });
        card.setOnLongClickListener(v -> {
            boolean offlineReady = item.archivePath != null && !item.archivePath.isEmpty()
                    && new File(item.archivePath).isFile();
            ArrayList<String> labels = new ArrayList<>();
            labels.add("在线打开");
            if (offlineReady) labels.add("打开离线快照 (MHTML)");
            labels.add("移出稍后读");
            new AlertDialog.Builder(this).setTitle(item.title)
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        String chosen = labels.get(which);
                        if ("在线打开".equals(chosen)) {
                            navigate(item.url);
                        } else if ("打开离线快照 (MHTML)".equals(chosen)) {
                            openOfflineArchive(item);
                        } else {
                            readingShelf.remove(item.url);
                            refreshHomeContent();
                        }
                    }).show();
            return true;
        });
        return card;
    }

    private void openOfflineArchive(ReadingShelf.Entry item) {
        if (item == null || item.archivePath == null) return;
        File f = new File(item.archivePath);
        if (!f.isFile()) {
            toast("离线文件已不存在，改为在线打开");
            navigate(item.url);
            return;
        }
        clearCrashOverlay();
        closeFind();
        hideKeyboardIfNeeded();
        if (current.view == null) {
            current.view = createWebView(current);
            attachWarmWebView(current.view);
        }
        onHome = false;
        if (homeView != null) homeView.setVisibility(View.GONE);
        current.view.setVisibility(View.VISIBLE);
        current.view.onResume();
        WebViewPerf.setActiveRenderer(current.view, true);
        current.setUrl(item.url);
        current.title = item.title;
        current.view.getSettings().setAllowFileAccess(true);
        current.view.loadUrl(Uri.fromFile(f).toString());
        updateChrome();
    }

    private void showAddSpeedDialDialog() {
        LinearLayout fields = stack();
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText title = input("站点名称");
        EditText link = input("https://example.com");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        fields.addView(link, new LinearLayout.LayoutParams(-1, dp(48)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("添加快捷访问").setView(fields)
                .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = link.getText().toString().trim();
            if (!HttpUrlUtil.isHttp(raw) && !raw.contains(" ") && raw.contains(".")) raw = "https://" + raw;
            if (!HttpUrlUtil.isHttp(raw)) {
                link.setError("请输入有效的 HTTP/HTTPS 网址");
                return;
            }
            String name = title.getText().toString().trim();
            if (name.isEmpty()) name = HttpUrlUtil.extractHost(raw);
            if (!library.isPinned(raw)) library.togglePin(raw, name);
            d.dismiss();
            refreshHomeContent();
        });
    }

    // ==================== LAZY FOCUS-ONLY OMNIBOX OVERLAY ====================

    private void ensureOmniboxOverlay() {
        if (omniboxOverlay != null) return;
        omniboxOverlay = new LinearLayout(this);
        omniboxOverlay.setOrientation(LinearLayout.VERTICAL);
        omniboxOverlay.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE, density, dp(18)));
        omniboxOverlay.setElevation(dp(12));
        omniboxOverlay.setPadding(dp(8), dp(6), dp(8), dp(6));
        omniboxOverlay.setVisibility(View.GONE);

        omniboxScroll = new ScrollView(this);
        omniboxScroll.setVerticalScrollBarEnabled(false);
        omniboxList = new LinearLayout(this);
        omniboxList.setOrientation(LinearLayout.VERTICAL);
        omniboxScroll.addView(omniboxList, new ScrollView.LayoutParams(-1, -2));
        omniboxOverlay.addView(omniboxScroll, new LinearLayout.LayoutParams(-1, dp(220)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(62);
        viewport.addView(omniboxOverlay, lp);
    }

    private void showOmniboxOverlay(CharSequence querySeq) {
        ensureOmniboxOverlay();
        String query = querySeq == null ? "" : querySeq.toString().trim();
        if (omniboxOverlay.getVisibility() == View.VISIBLE && TextUtils.equals(lastSuggestionQuery, query)) {
            return;
        }
        lastSuggestionQuery = query;
        omniboxList.removeAllViews();

        int rows = 0;

        // 1. Clipboard Quick-Paste Chip (only when query empty or matches active URL)
        String clip = readClipboardSnippet();
        if (clip != null && !clip.equals(query) && (current == null || !clip.equals(current.url))) {
            boolean isUrl = HttpUrlUtil.isHttp(clip);
            addSuggestionRow(COPY, isUrl ? "粘贴并前往" : "搜索剪贴板", clip, AikoTheme.CYAN,
                    isUrl ? clip : null, () -> submitRawInput(clip));
            rows++;
        }

        // 2. Matching open tabs (instant switch without duplicate tab!)
        if (!query.isEmpty()) {
            String low = query.toLowerCase(Locale.ROOT);
            for (Tab t : tabs) {
                if (t == current || t.url == null) continue;
                String title = t.title == null ? "" : t.title;
                if (t.url.toLowerCase(Locale.ROOT).contains(low) || title.toLowerCase(Locale.ROOT).contains(low)) {
                    addSuggestionRow(TABS, "切换到标签 · " + (title.isEmpty() ? t.cachedHost : title),
                            t.url, AikoTheme.EMERALD, null, () -> showTab(t, false));
                    if (++rows >= 2) break;
                }
            }
        }

        // 3. Instant local Bookmark + History matches
        List<BrowserLibrary.Entry> matches = library.search(query, Math.max(2, 6 - rows));
        for (BrowserLibrary.Entry item : matches) {
            boolean saved = library.isBookmarked(item.url);
            addSuggestionRow(saved ? STAR : HISTORY, item.title, item.url,
                    saved ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED, item.url, () -> navigate(item.url));
            rows++;
            if (rows >= 6) break;
        }

        // 4. Direct Search Engine option when user typed non-empty query
        if (!query.isEmpty() && !HttpUrlUtil.isHttp(query)) {
            String eng = SearchEngines.engineName(SearchEngines.currentId(this));
            addSuggestionRow(SEARCH, "使用 " + eng + " 搜索 “" + query + "”", null,
                    AikoTheme.ACCENT, SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this)),
                    () -> navigate(SearchEngines.buildSearchUrl(SearchEngines.currentId(this), SearchEngines.customTemplate(this), query)));
            rows++;
        }

        omniboxOverlay.setVisibility(rows > 0 ? View.VISIBLE : View.GONE);
        applyInsetsLayout();
    }

    private void addSuggestionRow(BrowserIcon.Kind kind, String title, String sub,
                                  int tint, String preconnectUrl, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));

        BrowserIcon glyph = new BrowserIcon(this, kind);
        glyph.tint(tint);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(18), dp(18));
        glp.rightMargin = dp(10);
        row.addView(glyph, glp);

        LinearLayout text = stack();
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        TextView main = label(title, 13, AikoTheme.TEXT);
        main.setTypeface(AikoTheme.MEDIUM);
        main.setSingleLine(true);
        main.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(main);

        if (sub != null && !sub.isEmpty()) {
            TextView s = label(sub, 11, AikoTheme.TEXT_DIM);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(s);
        }

        if (preconnectUrl != null) {
            row.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    WebViewPerf.preconnect(preconnectUrl, siteFilter);
                }
                return false;
            });
        }
        row.setOnClickListener(v -> {
            hideOmniboxOverlay();
            onClick.run();
        });
        omniboxList.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void hideOmniboxOverlay() {
        lastSuggestionQuery = null;
        if (omniboxOverlay != null && omniboxOverlay.getVisibility() != View.GONE) {
            omniboxOverlay.setVisibility(View.GONE);
        }
    }

    private String readClipboardSnippet() {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return null;
            String s = text.toString().trim();
            if (s.isEmpty() || s.length() > 512 || s.contains("\n")) return null;
            return s;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ==================== WEBVIEW CREATION & LIFECYCLE ====================

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createWebView(Tab tab) {
        ScrollWebView web = new ScrollWebView(this, tab);
        SiteFilter filter = filter();

        // Start with dark obsidian bg to eliminate blinding white flash during load!
        web.setBackgroundColor(AikoTheme.BG);
        WebViewPerf.configureWebView(web, false, isLowRam);
        WebViewPerf.setActiveRenderer(web, tab == current);
        if (tab.muted) WebViewPerf.setAudioMuted(web, true);
        if (displayMode != null) displayMode.attachView(web);

        WebSettings s = web.getSettings();
        tab.baseTextZoom = s.getTextZoom();
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
        s.setSupportMultipleWindows(true); // Safe user-gesture-only OAuth/SSO popups in onCreateWindow
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String raw = uri == null ? "" : uri.toString();
                boolean allowed = HttpUrlUtil.isHttp(raw);
                if (allowed) {
                    if (tab.view == view && request.isForMainFrame() && !request.isRedirect()) {
                        if (tab == current) {
                            if (!raw.equals(tab.historyTarget)) prepareFreshNavigation(tab);
                            beginNavigation(tab);
                        }
                        prepareTopLoad(tab, raw, "GET".equals(request.getMethod()) && view.getProgress() >= 100, false);
                    }
                    return false;
                }
                if (tab == current && request.isForMainFrame() && request.hasGesture()) {
                    confirmExternalAppIntent(raw);
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (tab.view != view) return;
                if (findTarget == view) closeFind();
                if (elementPicker != null && elementPicker.isActive()) elementPicker.stop();
                if (tab.started == 0) beginNavigation(tab);
                tab.progress = 1;
                tab.lastNavBfCache = false;
                if (HttpUrlUtil.isHttp(url)) {
                    if (view.getSettings().getAllowFileAccess()) {
                        view.getSettings().setAllowFileAccess(false);
                    }
                    tab.setUrl(url);
                    if (!tab.filterPagePrepared || tab.filterPage == null || !url.equals(tab.filterPage.url)) {
                        tab.filterPage = filterPage(url);
                    }
                    tab.filterPagePrepared = false;
                    applySiteProfile(tab, siteProfile(tab.cachedHost), false, false);
                    applySiteExtras(tab, tab.cachedHost, false);
                }
                if (tab == current) {
                    if (gestures != null) gestures.showBar(true);
                    updateChrome();
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return filter.blocks(tab.filterPage, request) ? SiteFilter.blocked() : null;
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                if (tab.view != view) return;
                boolean wasHistoryNav = tab.historyTarget != null && tab.historyTarget.equals(url);
                tab.historyTarget = null;
                if (HttpUrlUtil.isHttp(url) && url.equals(view.getUrl())) {
                    tab.setUrl(url);
                    SiteFilter.Page previous = tab.filterPage;
                    if (previous == null || !url.equals(previous.url)) {
                        tab.filterPage = previous == null ? filterPage(url) : previous.withHistoryUrl(url);
                    }
                    applySiteProfile(tab, siteProfile(tab.cachedHost), false, false);
                    applySiteExtras(tab, tab.cachedHost, true);
                    // Switch canvas to white ONLY when algorithmic dark web is off, avoiding white flash before paint
                    view.setBackgroundColor(effectiveDarkWeb(tab.cachedHost) ? AikoTheme.BG : Color.WHITE);
                }
                syncNavigationFlags(tab, view, url, true);
                restoreScroll(tab, view, url);
                filter.applyCss(view, tab.cachedHost, url);
                long elapsed = finishNavigation(tab, url, "可绘制");
                if (elapsed >= 0) {
                    tab.lastCommitMs = elapsed;
                    tab.lastNavBfCache = wasHistoryNav && elapsed <= 60;
                }
                if (HttpUrlUtil.isHttp(url)) {
                    library.visit(url, view.getTitle());
                    scheduleHistory();
                }
                if (tab == current) updateChrome();
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (tab.view != view) return;
                if (tab.started > 0) {
                    tab.lastFinishMs = SystemClock.elapsedRealtime() - tab.started;
                } else if (tab.lastCommitMs > 0 && tab.lastFinishMs < tab.lastCommitMs) {
                    tab.lastFinishMs = tab.lastCommitMs;
                }
                syncNavigationFlags(tab, view, url, false);
                restoreScroll(tab, view, url);
                if (tab.cachedHost != null && siteExtras.isUltraScroll(tab.cachedHost)) {
                    view.evaluateJavascript(SiteExtras.ultraScrollJs(true), null);
                }
                userScripts.pageFinished(view, url);
                if (tab == current) updateChrome();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (tab.view == view && request.isForMainFrame()) {
                    tab.historyTarget = null;
                    finishNavigation(tab, request.getUrl().toString(), "加载失败 " + error.getErrorCode());
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (tab.view != view) return;
                boolean urlChanged = !TextUtils.equals(tab.url, url);
                if (HttpUrlUtil.isHttp(url)) tab.setUrl(url);
                if (view.getProgress() >= 100) {
                    if (HttpUrlUtil.isHttp(url) && !tab.filterPagePrepared) {
                        SiteFilter.Page previous = tab.filterPage;
                        tab.filterPage = previous == null ? filterPage(url) : previous.withHistoryUrl(url);
                    }
                    syncNavigationFlags(tab, view, url, urlChanged);
                    restoreScroll(tab, view, url);
                } else {
                    tab.canGoBack = view.canGoBack();
                    tab.canGoForward = view.canGoForward();
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
                    showCrashRecovery(tab);
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
                    loading.setProgress(progress, true);
                    int nextVis = progress >= 100 ? View.INVISIBLE : View.VISIBLE;
                    if (loading.getVisibility() != nextVis) loading.setVisibility(nextVis);
                    actionButton.symbol(progress < 100 ? STOP : REFRESH);
                    actionButton.setContentDescription(progress < 100 ? "停止加载" : "刷新");
                }
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                if (tab.view != view) return;
                tab.title = title;
                library.updateVisitTitle(view.getUrl(), title);
                scheduleHistory();
                scheduleSave();
            }

            @Override public void onReceivedIcon(WebView view, Bitmap image) {
                if (tab.view == view && image != null && image.getWidth() <= 128 && image.getHeight() <= 128) {
                    tab.favicon = image;
                    tab.faviconHost = normalizeHostFast(view.getUrl());
                }
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // Only allow explicit user-gesture popups (OAuth/SSO logins, target=_blank), blocking ad popups.
                if (!isUserGesture || resultMsg == null || tabs.size() >= MAX_TABS) return false;
                if (!(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
                Tab child = new Tab(null, "新标签");
                child.view = createWebView(child);
                tabs.add(child);
                showTab(child, false);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child.view);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                for (Tab t : tabs) {
                    if (t.view == window) {
                        closeTab(t);
                        break;
                    }
                }
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> result, FileChooserParams params) {
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
                viewport.setVisibility(View.GONE);
                setImmersiveSystemBars(true);
                if (displayMode != null) displayMode.setFullscreenVideo(true);
            }

            @Override public void onHideCustomView() { exitFullscreen(); }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (HttpUrlUtil.isHttp(url)) showNewDownload(url, URLUtil.guessFileName(url, disposition, mime), ua);
            else toast("此链接不是可直接下载的 HTTP 或 HTTPS 文件");
        });

        web.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (tab.pendingScroll != null) setScrollTarget(tab, null);
                if (isAnyOmniboxFocused()) hideKeyboardIfNeeded();
            }
            return false;
        });

        web.setOnLongClickListener(v -> handleWebLongPress(web));
        userScripts.attach(web);
        return web;
    }

    private void clearCrashOverlay() {
        if (crashOverlay != null) {
            if (crashOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) crashOverlay.getParent()).removeView(crashOverlay);
            }
            crashOverlay = null;
        }
    }

    private void showCrashRecovery(Tab tab) {
        clearCrashOverlay();
        LinearLayout box = stack();
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(AikoTheme.BG);
        TextView msg = label("网页渲染进程已停止", 16, AikoTheme.TEXT);
        msg.setTypeface(AikoTheme.MEDIUM);
        msg.setGravity(Gravity.CENTER);
        box.addView(msg);
        TextView retry = AikoTheme.badge(this, density, "点此恢复页面", AikoTheme.ACCENT, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, dp(42));
        rlp.topMargin = dp(14);
        box.addView(retry, rlp);
        retry.setOnClickListener(v -> {
            clearCrashOverlay();
            showTab(tab, false);
        });
        crashOverlay = box;
        page.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Complete link & image long-press context menu with background prefetch support. */
    private boolean handleWebLongPress(WebView web) {
        if (elementPicker != null && elementPicker.isActive()) return false;
        WebView.HitTestResult hit = web.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        String extra = hit.getExtra();

        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE && HttpUrlUtil.isHttp(extra)) {
            showLinkImageMenu(extra, null);
            return true;
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE && HttpUrlUtil.isHttp(extra)) {
            showLinkImageMenu(null, extra);
            return true;
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            String imgUrl = HttpUrlUtil.isHttp(extra) ? extra : null;
            Message msg = handler.obtainMessage();
            msg.setTarget(new Handler(Looper.getMainLooper()) {
                @Override public void handleMessage(Message reply) {
                    if (isDestroyed() || isFinishing()) return;
                    Bundle data = reply.getData();
                    String href = data == null ? null : data.getString("url");
                    String src = data == null ? imgUrl : data.getString("src", imgUrl);
                    String validHref = HttpUrlUtil.isHttp(href) ? href : null;
                    String validSrc = HttpUrlUtil.isHttp(src) ? src : imgUrl;
                    if (validHref != null || validSrc != null) {
                        showLinkImageMenu(validHref, validSrc);
                    }
                }
            });
            web.requestFocusNodeHref(msg);
            return true;
        }
        return false;
    }

    private void showLinkImageMenu(String linkUrl, String imageUrl) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (linkUrl != null) {
            labels.add("在后台标签打开");
            actions.add(() -> queueTab(linkUrl));
            labels.add("在新标签打开");
            actions.add(() -> addTab(linkUrl, false));
            labels.add("复制链接");
            actions.add(() -> copyText("链接", linkUrl, "已复制链接"));
            labels.add("分享链接");
            actions.add(() -> shareLink(linkUrl));
        }
        if (imageUrl != null) {
            labels.add("保存图片");
            actions.add(() -> showNewDownload(imageUrl, URLUtil.guessFileName(imageUrl, null, "image/*"), null));
            labels.add("新标签查看图片");
            actions.add(() -> addTab(imageUrl, false));
            labels.add("复制图片链接");
            actions.add(() -> copyText("图片链接", imageUrl, "已复制图片链接"));
        }
        if (labels.isEmpty()) return;
        String header = linkUrl != null ? linkUrl : imageUrl;
        new AlertDialog.Builder(this)
                .setTitle(header.length() > 64 ? header.substring(0, 64) + "…" : header)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Safe confirmation dialog for intent://, mailto:, tel:, and custom app schemes. */
    private void confirmExternalAppIntent(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return;
        String lower = rawUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("file:") || lower.startsWith("content:")) {
            return;
        }
        Intent parsed;
        String fallbackUrl = null;
        try {
            if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                parsed = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME);
                fallbackUrl = parsed.getStringExtra("browser_fallback_url");
            } else {
                parsed = new Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl));
            }
            parsed.addCategory(Intent.CATEGORY_BROWSABLE);
            parsed.setComponent(null);
            parsed.setSelector(null);
        } catch (Exception e) {
            return;
        }
        final Intent safeIntent = parsed;
        final String fallback = HttpUrlUtil.isHttp(fallbackUrl) ? fallbackUrl : null;
        boolean resolvable = safeIntent.resolveActivity(getPackageManager()) != null;
        if (!resolvable) {
            if (fallback != null) navigate(fallback);
            return;
        }
        String scheme = Uri.parse(rawUrl).getScheme();
        String msg = "网页请求打开外部应用" + (scheme != null ? "（" + scheme + "://）" : "") + "，是否允许？";
        new AlertDialog.Builder(this)
                .setTitle("打开外部应用")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("允许打开", (d, w) -> {
                    try {
                        startActivity(safeIntent);
                    } catch (RuntimeException err) {
                        if (fallback != null) navigate(fallback);
                        else toast("未找到可处理此链接的应用");
                    }
                })
                .show();
    }

    // ==================== NAVIGATION & INPUT ====================

    private void bindSubmission(EditText field, int imeAction) {
        field.setOnEditorActionListener((view, action, key) -> {
            if (action != imeAction && (key == null || key.getKeyCode() != KeyEvent.KEYCODE_ENTER
                    || key.getAction() != KeyEvent.ACTION_UP)) {
                return false;
            }
            submitAddress(field);
            return true;
        });
    }

    private void submitAddress(EditText field) {
        String raw = field.getText().toString().trim();
        if (raw.isEmpty()) return;
        submitRawInput(raw);
    }

    private void submitRawInput(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String url = SearchEngines.resolveInput(this, raw.trim());
        navigate(url);
    }

    private void navigate(String url) {
        if (!HttpUrlUtil.isHttp(url)) return;
        clearCrashOverlay();
        if (elementPicker != null && elementPicker.isActive()) elementPicker.stop();
        closeFind();
        hideOmniboxOverlay();
        hideKeyboardIfNeeded();
        prepareFreshNavigation(current);
        beginNavigation(current);
        current.setUrl(url);
        if (current.view == null) {
            current.view = createWebView(current);
        }
        attachWarmWebView(current.view);
        onHome = false;
        if (homeView != null) homeView.setVisibility(View.GONE);
        current.view.setVisibility(View.VISIBLE);
        current.view.onResume();
        WebViewPerf.setActiveRenderer(current.view, true);
        if (displayMode != null) {
            displayMode.attachView(current.view);
            displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(current.cachedHost));
        }
        prepareTopLoad(current, url, true, true);
        current.view.loadUrl(url);
        updatePageBottomInset();
        updateChrome();
        trimTabs(maxHotTabs);
        scheduleSave();
    }

    private SiteFilter filter() {
        if (siteFilter == null) {
            siteFilter = new SiteFilter(this);
            siteFilter.load(this::onFilterReady);
        }
        return siteFilter;
    }

    private void onFilterReady() {
        filterReady = true;
        if (isDestroyed()) return;
        applyCurrentFilter();
        updateAddress();
    }

    private void applyCurrentFilter() {
        if (!isDestroyed() && current != null && current.view != null) {
            siteFilter.applyCss(current.view, current.cachedHost, current.view.getUrl());
        }
    }

    private static SiteFilter.Page filterPage(String url) {
        return url == null ? null : new SiteFilter.Page(url);
    }

    private static String normalizeHostFast(String url) {
        if (url == null) return null;
        String host = HttpUrlUtil.extractHost(url);
        return host.isEmpty() ? null : host;
    }

    private SiteProfile siteProfile(String host) {
        return host == null ? SiteProfile.DEFAULT : SiteProfile.decode(siteProfiles.getInt(host, 0));
    }

    private void saveSiteProfile(String host, SiteProfile profile) {
        int stored = profile.encode();
        if (stored == 0) siteProfiles.edit().remove(host).apply();
        else siteProfiles.edit().putInt(host, stored).apply();
    }

    private boolean effectiveDarkWeb(String host) {
        if (siteExtras == null) return false;
        int ov = host == null ? 0 : siteExtras.darkOverride(host);
        if (ov == 1) return true;
        if (ov == 2) return false;
        return siteExtras.isGlobalDarkWeb();
    }

    private void prepareTopLoad(Tab tab, String url, boolean updateAgent, boolean stopOldLoad) {
        SiteFilter.Page prepared = tab.filterPage;
        if (!tab.filterPagePrepared || prepared == null || !url.equals(prepared.url)) {
            tab.filterPage = filterPage(url);
        }
        tab.filterPagePrepared = true;
        String host = normalizeHostFast(url);
        tab.cachedHost = host;
        if (tab.view != null && tab.view.getSettings().getAllowFileAccess()) {
            tab.view.getSettings().setAllowFileAccess(false);
        }
        applySiteProfile(tab, siteProfile(host), updateAgent, stopOldLoad);
        applySiteExtras(tab, host, false);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void applySiteProfile(Tab tab, SiteProfile profile, boolean updateAgent, boolean stopOldLoad) {
        WebView view = tab.view;
        if (view == null) return;
        WebSettings settings = view.getSettings();
        int textZoom = Math.round(tab.baseTextZoom * profile.zoom / 100f);
        if (settings.getTextZoom() != textZoom) settings.setTextZoom(textZoom);
        if (settings.getBlockNetworkImage() != profile.blockImages) settings.setBlockNetworkImage(profile.blockImages);
        if (!updateAgent || tab.desktopApplied == profile.desktop) return;
        if (profile.desktop && !hasDesktopAgent()) return;
        if (stopOldLoad && view.getProgress() < 100) view.stopLoading();
        settings.setUserAgentString(profile.desktop ? desktopAgent : null);
        tab.desktopApplied = profile.desktop;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void applySiteExtras(Tab tab, String host, boolean injectUltraScrollNow) {
        WebView view = tab.view;
        if (view == null || siteExtras == null) return;
        boolean jsEnabled = host == null || !siteExtras.isJsDisabled(host);
        WebSettings settings = view.getSettings();
        if (settings.getJavaScriptEnabled() != jsEnabled) settings.setJavaScriptEnabled(jsEnabled);

        boolean dark = effectiveDarkWeb(host);
        WebViewPerf.setAlgorithmicDarkening(view, dark);

        if (tab == current && displayMode != null) {
            displayMode.setSiteForceMaxHz(host != null && siteExtras.isForceMaxHz(host));
        }
        if (injectUltraScrollNow && host != null && siteExtras.isUltraScroll(host)) {
            view.evaluateJavascript(SiteExtras.ultraScrollJs(true), null);
        }
    }

    private boolean hasDesktopAgent() {
        if (desktopAgent == null) desktopAgent = SiteProfile.desktopAgent(WebSettings.getDefaultUserAgent(this));
        return desktopAgent != null;
    }

    /** Avoids redundant copyBackForwardList() JNI copies across lifecycle callbacks. */
    private static void syncNavigationFlags(Tab tab, WebView view, String url, boolean refreshIndex) {
        tab.canGoBack = view.canGoBack();
        tab.canGoForward = view.canGoForward();
        if (!HttpUrlUtil.isHttp(url) || !url.equals(view.getUrl())) return;
        boolean changed = !url.equals(tab.visibleUrl);
        tab.visibleUrl = url;
        if (refreshIndex || changed || tab.visibleIndex == ScrollPositions.URL_ONLY) {
            tab.visibleIndex = view.copyBackForwardList().getCurrentIndex();
        }
    }

    private static void rememberScroll(Tab tab) {
        WebView view = tab.view;
        if (view == null || tab.pendingScroll != null || tab.visibleUrl == null
                || !tab.visibleUrl.equals(view.getUrl())) {
            return;
        }
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
        // If BFCache already restored exact scroll offset, skip redundant scrollTo!
        if (view.getScrollY() > 0 && Math.abs(view.getScrollY() - position.y) <= dp(24)) {
            setScrollTarget(tab, null);
            return;
        }
        tab.scrollQueued = true;
        restoreScrollFrame(tab, (ScrollWebView) view, position, 0);
    }

    /** VSync-aligned Choreographer/postOnAnimation scroll restore (zero coarse timer jump at 185Hz). */
    private void restoreScrollFrame(Tab tab, ScrollWebView view, ScrollPositions.Position position, int attempt) {
        view.postOnAnimation(() -> {
            if (tab.pendingScroll != position || tab.view != view) return;
            if (!position.url.equals(view.getUrl())) {
                setScrollTarget(tab, null);
                return;
            }
            int reachable = view.maxScrollY();
            if (reachable >= position.y || attempt >= 14) {
                int targetY = Math.min(reachable, position.y);
                if (view.getScrollX() != position.x || view.getScrollY() != targetY) {
                    view.scrollTo(position.x, targetY);
                }
                setScrollTarget(tab, null);
            } else {
                restoreScrollFrame(tab, view, position, attempt + 1);
            }
        });
    }

    private final class ScrollWebView extends WebView {
        private final Tab owner;

        ScrollWebView(android.content.Context context, Tab owner) {
            super(context);
            this.owner = owner;
        }

        int maxScrollY() {
            return Math.max(0, computeVerticalScrollRange() - computeVerticalScrollExtent());
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (owner == current && gestures != null && !onHome) {
                gestures.onPageScroll(t, oldt, maxScrollY());
            }
        }
    }

    private final class BottomDeckLayout extends LinearLayout {
        BottomDeckLayout() { super(MainActivity.this); }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (gestures != null && gestures.onBottomBarIntercept(ev)) return true;
            return super.onInterceptTouchEvent(ev);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (gestures != null && gestures.onBottomBarTouch(this, ev)) return true;
            return super.onTouchEvent(ev);
        }
    }

    private void beginNavigation(Tab tab) {
        tab.started = SystemClock.elapsedRealtime();
        tab.lastCommitMs = 0;
        tab.lastFinishMs = 0;
        if (tab == current && visible) tab.priorityTicket = DownloadService.PACER.protect(1350);
    }

    private long finishNavigation(Tab tab, String url, String outcome) {
        DownloadService.PACER.release(tab.priorityTicket);
        tab.priorityTicket = 0;
        if (tab.started == 0) return -1;
        long elapsed = SystemClock.elapsedRealtime() - tab.started;
        tab.started = 0;
        if (tab != current) return elapsed;
        String host = normalizeHostFast(url);
        timings.addFirst(String.format(Locale.ROOT, "%s  ·  %d ms  ·  %s",
                host == null ? "网页" : host, elapsed, outcome));
        if (timings.size() > 12) timings.removeLast();
        return elapsed;
    }

    // ==================== 185HZ LIVE FRAME-PACING HUD ====================

    private void setupHudIfEnabled() {
        hudEnabled = DisplayModeEngine.loadHudEnabled(this);
        if (hudEnabled) attachHud();
        else detachHud();
    }

    private void setHudEnabled(boolean enabled) {
        hudEnabled = enabled;
        DisplayModeEngine.saveHudEnabled(this, enabled);
        if (enabled) attachHud();
        else detachHud();
    }

    private void attachHud() {
        if (hudPill == null) {
            hudPill = new TextView(this);
            hudPill.setTextSize(11);
            hudPill.setTypeface(AikoTheme.MONO);
            hudPill.setTextColor(AikoTheme.EMERALD);
            hudPill.setPadding(dp(10), dp(4), dp(10), dp(4));
            hudPill.setBackground(AikoTheme.card(0xCC0B0D12, AikoTheme.STROKE, density, dp(12)));
            hudPill.setElevation(dp(10));
            hudPill.setText("185Hz · 监测中");
            hudPill.setOnClickListener(v -> {
                if (displayMode != null && displayMode.isOemCapped()) {
                    DisplayModeEngine.openRefreshRateSettings(this);
                } else {
                    showRefreshSettings();
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(8);
            lp.rightMargin = dp(10);
            viewport.addView(hudPill, lp);
        }
        hudPill.setVisibility(View.VISIBLE);
        if (displayMode != null) {
            displayMode.setHudListener((activeHz, maxHz, renderedFps, p99Ms, jankPct, oemCapped) ->
                    handler.post(() -> {
                        if (!hudEnabled || hudPill == null || isDestroyed()) return;
                        String text = String.format(Locale.ROOT, "%.0fHz · %.0f fps · p99 %.1fms%s",
                                activeHz, renderedFps, p99Ms, oemCapped ? " ⚠受限" : "");
                        if (!TextUtils.equals(hudPill.getText(), text)) hudPill.setText(text);
                        hudPill.setTextColor(oemCapped ? AikoTheme.AMBER
                                : (jankPct > 5f ? AikoTheme.CORAL : AikoTheme.EMERALD));
                    }));
        }
    }

    private void detachHud() {
        if (displayMode != null) displayMode.setHudListener(null);
        if (hudPill != null) hudPill.setVisibility(View.GONE);
    }

    // ==================== CHROME UPDATES & OMNIBOX FOCUS ====================

    private void updateChrome() {
        if (current == null) return;
        boolean editing = address.hasFocus();
        addressRow.setVisibility(onHome && !editing ? View.GONE : View.VISIBLE);
        homeSpacer.setVisibility(onHome && !editing ? View.VISIBLE : View.GONE);
        backButton.setVisibility(onHome || editing ? View.GONE : View.VISIBLE);
        updateAddress();
        tabButton.count(tabs.size());
        tabButton.setContentDescription("标签页，" + tabs.size() + " 个，长按新建");
        boolean canBack = current.view != null && current.canGoBack;
        backButton.tint(canBack ? AikoTheme.TEXT : AikoTheme.TEXT_DIM);
        loading.setProgress(current.progress, true);
        loading.setVisibility(current.progress < 100 && !onHome ? View.VISIBLE : View.INVISIBLE);
        actionButton.symbol(current.progress < 100 ? STOP : REFRESH);
        actionButton.setContentDescription(current.progress < 100 ? "停止加载" : "刷新");
        if (gestures != null) {
            gestures.updateExclusionRects(page, current.canGoBack, current.canGoForward);
        }
    }

    private void updateAddress() {
        if (address.hasFocus() || current == null) return;
        String url = current.url;
        String host = current.cachedHost;
        boolean insecure = url != null && url.regionMatches(true, 0, "http://", 0, 7);
        String display = host == null ? "" : (insecure ? "http://" : "") + host;
        if (!TextUtils.equals(address.getText(), display)) address.setText(display);
        siteButton.symbol(host == null ? SEARCH : SHIELD);
        // Avoid synchronous prefs read on frame 1 before SiteFilter finishes async preload
        boolean protectedSite = host != null && !insecure && (!filterReady || filter().enabled(host));
        siteButton.tint(host == null ? AikoTheme.TEXT_MUTED : (insecure ? AikoTheme.CORAL : (protectedSite ? AikoTheme.ACCENT : AikoTheme.AMBER)));
        siteButton.setContentDescription(host == null ? "搜索或输入网址" : "站点控制：" + host);
    }

    /** Instant Via-grade omnibox focus with ZERO artificial 80ms delay & clean bottom-docked editing! */
    private void focusAddress() {
        if (gestures != null) gestures.showBar(false);
        if (address == null) return;
        addressRow.setVisibility(View.VISIBLE);
        homeSpacer.setVisibility(View.GONE);
        if (address.requestFocus()) {
            address.post(() -> {
                if (!address.hasFocus()) return;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController wic = address.getWindowInsetsController();
                    if (wic != null) {
                        wic.show(WindowInsets.Type.ime());
                        return;
                    }
                }
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private EditText activeAddress() {
        return address;
    }

    private boolean isAnyOmniboxFocused() {
        return editingOmnibox || (address != null && address.hasFocus());
    }

    // ==================== TABS SHEET (ZERO-ALLOC TABROW) ====================

    private void showTabs() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "标签页 · " + tabs.size(), dialog::dismiss);

        LinearLayout quick = sheetRow(panel);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> addTab(null, true));
        View recent = sheetAction(dialog, quick, HISTORY, "最近关闭", !closedTabs.isEmpty(), this::showClosedTabs);
        sheetAction(dialog, quick, PRIVATE, "无痕窗口", true, () -> startActivity(new Intent(this, PrivateActivity.class)));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        list.setPadding(0, dp(8), 0, 0);
        int visibleRows = Math.max(2, (getResources().getConfiguration().screenHeightDp - 190) / 64);
        int height = Math.min(Math.min(tabs.size(), visibleRows), 6) * 64 + 8;
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(height)));

        final BaseAdapter[] adapterRef = new BaseAdapter[1];
        final Runnable onCloseCallback = () -> {
            if (adapterRef[0] != null) adapterRef[0].notifyDataSetChanged();
            boolean hasClosed = !closedTabs.isEmpty();
            recent.setEnabled(hasClosed);
            recent.setFocusable(hasClosed);
            recent.setAlpha(hasClosed ? 1f : 0.38f);
            list.getLayoutParams().height = dp(Math.min(Math.min(tabs.size(), visibleRows), 6) * 64 + 8);
            list.requestLayout();
        };

        adapterRef[0] = new BaseAdapter() {
            @Override public int getCount() { return tabs.size(); }
            @Override public Object getItem(int index) { return tabs.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                TabRow row = recycled instanceof TabRow ? (TabRow) recycled : new TabRow(dialog, onCloseCallback);
                row.bind(tabs.get(index));
                return row;
            }
        };
        list.setAdapter(adapterRef[0]);
        showSheet(dialog, panel);
        list.setSelection(Math.max(0, tabs.indexOf(current)));
    }

    private void closeTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index == -1) return;
        if (tab == current) clearCrashOverlay();
        if (findTarget != null && findTarget == tab.view) closeFind();
        if (HttpUrlUtil.isHttp(tab.url)) {
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
        if (tab == current) showTab(tabs.get(Math.min(index, tabs.size() - 1)), false);
        updateChrome();
        scheduleSave();
    }

    /** Zero-allocation ListView row: ripple, backgrounds, and listeners allocated ONCE in constructor. */
    private final class TabRow extends LinearLayout {
        private final LinearLayout link;
        private final ImageView image;
        private final TextView fallback, title, domain;
        private final BrowserIcon close;
        private final GradientDrawable selectedBg;
        private Tab bound;

        TabRow(Dialog dialog, Runnable onClose) {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(2), dp(3), dp(2), dp(3));
            setLayoutParams(new AbsListView.LayoutParams(-1, dp(64)));

            selectedBg = AikoTheme.card(AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT, density, dp(14));

            link = new LinearLayout(MainActivity.this);
            link.setGravity(Gravity.CENTER_VERTICAL);
            link.setPadding(dp(10), 0, dp(4), 0);
            link.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            link.setOnClickListener(v -> {
                if (bound != null) {
                    dialog.dismiss();
                    showTab(bound, false);
                }
            });
            addView(link, new LinearLayout.LayoutParams(0, -1, 1));

            FrameLayout tile = new FrameLayout(MainActivity.this);
            tile.setBackground(AikoTheme.rounded(AikoTheme.PILL, dp(10)));
            link.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));

            fallback = label("", 14, AikoTheme.ACCENT);
            fallback.setTypeface(AikoTheme.BOLD);
            fallback.setGravity(Gravity.CENTER);
            tile.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout text = stack();
            text.setPadding(dp(10), 0, dp(4), 0);
            link.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

            title = label("", 14, AikoTheme.TEXT);
            title.setTypeface(AikoTheme.MEDIUM);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);

            domain = label("", 11, AikoTheme.TEXT_MUTED);
            domain.setSingleLine(true);
            domain.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(domain);

            close = icon(CLOSE, "关闭标签", () -> {
                if (bound != null) {
                    closeTab(bound);
                    onClose.run();
                }
            });
            close.tint(AikoTheme.TEXT_DIM);
            addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }

        void bind(Tab tab) {
            bound = tab;
            String host = tab.cachedHost;
            String caption = tab.title;
            if (caption == null || caption.isEmpty() || caption.equals(tab.url)) {
                caption = host == null ? "新标签" : host;
            }
            boolean isCurrent = tab == current;
            if (!TextUtils.equals(title.getText(), caption)) title.setText(caption);
            title.setTextColor(isCurrent ? AikoTheme.ACCENT : AikoTheme.TEXT);

            String sub = isCurrent
                    ? (host == null ? "当前标签" : host + (tab.muted ? "  ·  已静音  ·  当前" : "  ·  当前"))
                    : (host == null ? "" : (tab.muted ? host + "  ·  已静音" : host));
            if (!TextUtils.equals(domain.getText(), sub)) domain.setText(sub);
            domain.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);

            boolean useIcon = host != null && host.equals(tab.faviconHost) && tab.favicon != null;
            image.setImageBitmap(useIcon ? tab.favicon : null);
            fallback.setVisibility(useIcon ? View.GONE : View.VISIBLE);
            if (!useIcon) {
                String letter = host == null ? "A" : host.substring(0, 1).toUpperCase(Locale.ROOT);
                if (!TextUtils.equals(fallback.getText(), letter)) fallback.setText(letter);
            }
            link.setBackground(isCurrent ? selectedBg : null);
            close.setContentDescription("关闭标签：" + caption);
        }
    }

    private void flushHistorySafe() {
        if (library != null) library.flushHistory();
    }

    private void runDebouncedFind() {
        if (findTarget != null && findInput != null) {
            CharSequence q = findInput.getText();
            if (q != null && q.length() > 0) findTarget.findAllAsync(q.toString());
        }
    }

    private void scheduleHistory() {
        handler.removeCallbacks(saveHistoryTask);
        handler.postDelayed(saveHistoryTask, 1500);
    }

    private void queueTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            toast("最多保留 " + MAX_TABS + " 个标签");
            return;
        }
        tabs.add(new Tab(url, url));
        // Original flagship feature: zero-WebView Chromium prefetch for background tab!
        WebViewPerf.prefetchBackgroundUrl(this, url, siteFilter);
        updateChrome();
        scheduleSave();
        toast("已加入后台标签并静默预热");
    }

    // ==================== SHIELD & SPEED X-RAY SHEET ====================

    private void showSiteControls() {
        Tab tab = current;
        if (tab == null) return;
        String url = tab.url, host = tab.cachedHost;
        if (host == null) return;

        SiteFilter filters = filter();
        SiteProfile profile = siteProfile(host);
        boolean filtered = filters.enabled(host);
        boolean https = url != null && url.regionMatches(true, 0, "https://", 0, 8);
        boolean ultraScroll = siteExtras.isUltraScroll(host);
        boolean forceMaxHz = siteExtras.isForceMaxHz(host);
        boolean jsDisabled = siteExtras.isJsDisabled(host);
        int darkOv = siteExtras.darkOverride(host);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "站点盾牌与极速 X-Ray", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(460, Math.max(220, getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Speed X-Ray Telemetry Card
        LinearLayout xray = stack();
        xray.setPadding(dp(14), dp(12), dp(14), dp(12));
        xray.setBackground(AikoTheme.glowBanner(dp(16)));
        body.addView(xray, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout hostRow = new LinearLayout(this);
        hostRow.setGravity(Gravity.CENTER_VERTICAL);
        xray.addView(hostRow, new LinearLayout.LayoutParams(-1, -2));

        TextView siteTitle = label(host, 16, AikoTheme.TEXT);
        siteTitle.setTypeface(AikoTheme.BOLD);
        siteTitle.setSingleLine(true);
        siteTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        hostRow.addView(siteTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView protoBadge = AikoTheme.badge(this, density, https ? "HTTPS" : "HTTP",
                https ? AikoTheme.EMERALD : AikoTheme.CORAL, AikoTheme.BG, AikoTheme.STROKE);
        hostRow.addView(protoBadge, new LinearLayout.LayoutParams(-2, -2));

        SiteFilter.Page fp = tab.filterPage;
        FilterTrace.Snapshot hits = fp != null && host.equals(fp.host) ? fp.trace.snapshot() : null;
        long hitCount = hits == null ? 0L : hits.count;
        String timingLine = String.format(Locale.ROOT, "首帧 %s  ·  完成 %s  ·  %s  ·  拦截 %d",
                tab.lastCommitMs > 0 ? tab.lastCommitMs + "ms" : "--",
                tab.lastFinishMs > 0 ? tab.lastFinishMs + "ms" : "--",
                tab.lastNavBfCache ? "BFCache 瞬时命中" : (filtered ? "过滤护航" : "过滤暂停"),
                hitCount);
        TextView metricRow = label(timingLine, 12, AikoTheme.CYAN);
        metricRow.setTypeface(AikoTheme.MONO);
        metricRow.setPadding(0, dp(6), 0, 0);
        xray.addView(metricRow);

        if (hitCount > 0) {
            TextView traceLink = label("查看最近 " + hitCount + " 条拦截命中 ›", 12, AikoTheme.ACCENT);
            traceLink.setTypeface(AikoTheme.MEDIUM);
            traceLink.setPadding(0, dp(6), 0, 0);
            traceLink.setOnClickListener(v -> { dialog.dismiss(); showFilterTrace(tab, host); });
            xray.addView(traceLink);
        }

        // 2-Column Flagship Quick Toggles
        body.addView(AikoTheme.sectionHeader(this, density, "站点加速与控制"));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, SHIELD, "广告过滤", filtered ? "已开启" : "已暂停",
                        filtered, true, AikoTheme.EMERALD, () -> {
                            dialog.dismiss();
                            filters.setEnabled(host, !filtered);
                            if (sameOpenSite(tab, host)) reloadPreservingScroll();
                            updateAddress();
                        }),
                AikoTheme.toggleTile(this, density, BOLT, "185Hz 抗卡盾", ultraScroll ? "已开启" : "默认",
                        ultraScroll, tab.view != null, AikoTheme.CYAN, () -> {
                            dialog.dismiss();
                            boolean next = !ultraScroll;
                            siteExtras.setUltraScroll(host, next);
                            if (sameOpenSite(tab, host) && tab.view != null) {
                                tab.view.evaluateJavascript(SiteExtras.ultraScrollJs(next), null);
                            }
                            toast(next ? "已开启 185Hz 抗卡盾" : "已关闭 185Hz 抗卡盾");
                        }));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, SPEED, "站点强制高刷", forceMaxHz ? "锁定最高 Hz" : "跟随全局",
                        forceMaxHz, true, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            boolean next = !forceMaxHz;
                            siteExtras.setForceMaxHz(host, next);
                            if (sameOpenSite(tab, host) && displayMode != null) displayMode.setSiteForceMaxHz(next);
                            toast(next ? "本站已锁定最高刷新率" : "本站恢复全局刷新率策略");
                        }),
                AikoTheme.toggleTile(this, density, CODE, "站点脚本 JS", jsDisabled ? "已禁用" : "已启用",
                        !jsDisabled, tab.view != null, AikoTheme.EMERALD, () -> {
                            dialog.dismiss();
                            siteExtras.setJsDisabled(host, !jsDisabled);
                            if (sameOpenSite(tab, host)) reloadPreservingScroll();
                            toast(!jsDisabled ? "已禁用此站 JavaScript" : "已恢复此站 JavaScript");
                        }));

        String darkSub = darkOv == 1 ? "强制暗黑" : (darkOv == 2 ? "强制浅色" : "跟随全局");
        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, DARK, "网页暗黑", darkSub,
                        darkOv == 1, tab.view != null, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            int next = (darkOv + 1) % 3;
                            siteExtras.setDarkOverride(host, next);
                            if (sameOpenSite(tab, host)) {
                                applySiteExtras(tab, host, false);
                                if (tab.view != null) {
                                    tab.view.setBackgroundColor(effectiveDarkWeb(host) ? AikoTheme.BG : Color.WHITE);
                                }
                            }
                            toast("本站暗黑模式：" + (next == 1 ? "强制暗黑" : next == 2 ? "强制浅色" : "跟随全局"));
                        }),
                AikoTheme.toggleTile(this, density, DESKTOP, "桌面标识", profile.desktop ? "桌面版" : "手机版",
                        profile.desktop, tab.view != null, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            if (!profile.desktop && !hasDesktopAgent()) {
                                toast("此 WebView 未提供可识别的 Chromium 版本");
                                return;
                            }
                            saveSiteProfile(host, new SiteProfile(!profile.desktop, profile.blockImages, profile.zoom));
                            if (sameOpenSite(tab, host)) reloadPreservingScroll();
                        }));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, IMAGE, "网络图片", profile.blockImages ? "已停载" : "正常加载",
                        profile.blockImages, tab.view != null, AikoTheme.AMBER, () -> {
                            dialog.dismiss();
                            SiteProfile chosen = new SiteProfile(profile.desktop, !profile.blockImages, profile.zoom);
                            saveSiteProfile(host, chosen);
                            if (sameOpenSite(tab, host)) applySiteProfile(tab, chosen, false, false);
                            toast(chosen.blockImages ? "此站已暂停加载网络图片" : "此站已恢复加载网络图片");
                        }),
                AikoTheme.toggleTile(this, density, PICKER, "点选屏蔽", "手动标记广告",
                        false, tab.view != null, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            if (sameOpenSite(tab, host) && elementPicker != null) {
                                elementPicker.start(tab.view, host, url);
                            }
                        }));

        // Inline Text Zoom pill selector
        body.addView(AikoTheme.sectionHeader(this, density, "站点字号 · " + profile.zoom + "%"));
        HorizontalZoomStrip(body, dialog, tab, host, profile);

        // Built-in Filter List Toggles
        body.addView(AikoTheme.sectionHeader(this, density, "过滤规则列表"));
        LinearLayout listRow = new LinearLayout(this);
        listRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(listRow, new LinearLayout.LayoutParams(-1, -2));
        String[][] fLists = {{"easylist", "EasyList"}, {"easyprivacy", "EasyPrivacy"}, {"adguard-cn", "AdGuard 中文"}};
        for (int i = 0; i < fLists.length; i++) {
            String id = fLists[i][0], name = fLists[i][1];
            boolean on = filters.isListEnabled(id);
            TextView pill = AikoTheme.badge(this, density, (on ? "✓ " : "") + name,
                    on ? AikoTheme.TEXT : AikoTheme.TEXT_DIM,
                    on ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    on ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            pill.setOnClickListener(v -> {
                dialog.dismiss();
                toast("正在更新规则列表…");
                filters.setListEnabled(id, !on, () -> {
                    if (!isDestroyed()) {
                        applyCurrentFilter();
                        toast(name + (!on ? " 已启用" : " 已停用"));
                    }
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            if (i < fLists.length - 1) lp.rightMargin = dp(6);
            listRow.addView(pill, lp);
        }

        // Bottom Utility Actions
        LinearLayout actions = sheetRow(body);
        LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) actions.getLayoutParams();
        alp.topMargin = dp(10);
        sheetAction(dialog, actions, REFRESH, "刷新", tab.view != null, () -> {
            if (sameOpenSite(tab, host)) reloadPreservingScroll();
        });
        sheetAction(dialog, actions, COPY, "复制网址", true, () -> copyText("网址", url, "网址已复制"));
        sheetAction(dialog, actions, HISTORY, "拦截详情", true, () -> showFilterTrace(tab, host));
        boolean modified = !filtered || profile.encode() != 0 || ultraScroll || forceMaxHz || jsDisabled || darkOv != 0;
        sheetAction(dialog, actions, CLOSE, "恢复默认", modified, () -> {
            saveSiteProfile(host, SiteProfile.DEFAULT);
            siteExtras.resetHost(host);
            filters.setEnabled(host, true);
            if (sameOpenSite(tab, host)) reloadPreservingScroll();
            updateAddress();
            toast("已恢复此站默认设置");
        });

        showSheet(dialog, panel);
    }

    private void HorizontalZoomStrip(LinearLayout parent, Dialog dialog, Tab tab, String host, SiteProfile old) {
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(row);
        int[] levels = {75, 90, 100, 110, 125, 150, 175, 200};
        for (int z : levels) {
            boolean sel = z == old.zoom;
            TextView pill = AikoTheme.badge(this, density, z + "%",
                    sel ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED,
                    sel ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    sel ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            pill.setOnClickListener(v -> {
                SiteProfile next = new SiteProfile(old.desktop, old.blockImages, z);
                saveSiteProfile(host, next);
                if (sameOpenSite(tab, host)) applySiteProfile(tab, next, false, false);
                dialog.dismiss();
                toast("此站字号：" + z + "%");
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.rightMargin = dp(6);
            row.addView(pill, lp);
        }
        parent.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
    }

    // ==================== TOOLS SHEET ====================

    private void showTools() {
        Tab tab = current;
        String url = tab == null ? null : tab.url;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(url);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "浏览工具", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(470, Math.max(220, getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Quick Row
        LinearLayout quick = sheetRow(body);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> addTab(null, true));
        sheetAction(dialog, quick, PRIVATE, "无痕", true, () -> startActivity(new Intent(this, PrivateActivity.class)));
        sheetAction(dialog, quick, DOWNLOAD, "下载", true, this::showDownloads);
        sheetAction(dialog, quick, CODE, "油猴脚本", true, scriptTools::show);

        // 2. Current Page (2-column sleek grid)
        body.addView(AikoTheme.sectionHeader(this, density, "当前页面"));
        boolean bookmarked = pageOpen && library.isBookmarked(url);
        boolean pinnedHome = pageOpen && library.isPinned(url);
        boolean onShelf = pageOpen && readingShelf.has(url);
        boolean muted = pageOpen && tab.muted;

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, READER, "阅读模式", "沉浸去干扰",
                        false, pageOpen, AikoTheme.CYAN, () -> {
                            dialog.dismiss();
                            if (tab != null && tab.view != null) {
                                ReaderMode.toggle(this, tab.view, state -> {
                                    if ("empty".equals(state)) toast("当前页面暂不适合阅读模式");
                                    else if ("unavailable".equals(state)) toast("无法启动阅读模式");
                                });
                            }
                        }),
                AikoTheme.toggleTile(this, density, PIN, onShelf ? "已在稍后读" : "稍后读 · 0MB",
                        onShelf ? "点按移除" : "保存位置/离线",
                        onShelf, pageOpen, AikoTheme.EMERALD, () -> {
                            dialog.dismiss();
                            if (tab != null && pageOpen) handleReadingShelfAction(tab, onShelf);
                        }));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, STAR, bookmarked ? "取消收藏" : "收藏书签",
                        pinnedHome ? "已固定首页" : "点按收藏",
                        bookmarked, pageOpen, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            if (tab != null && pageOpen) toggleBookmark(tab, url);
                        }),
                AikoTheme.toggleTile(this, density, muted ? MUTE : UNMUTE, muted ? "取消静音" : "标签静音",
                        muted ? "当前静音" : "声音开启",
                        muted, pageOpen, AikoTheme.AMBER, () -> {
                            dialog.dismiss();
                            if (tab != null && tab.view != null) {
                                boolean next = !tab.muted;
                                tab.muted = next;
                                WebViewPerf.setAudioMuted(tab.view, next);
                                toast(next ? "当前标签已静音" : "已恢复标签声音");
                            }
                        }));

        LinearLayout pageRow = sheetRow(body);
        sheetAction(dialog, pageRow, FIND, "页内查找", pageOpen, () -> { if (tab != null && tab.view != null) openFind(tab.view); });
        sheetAction(dialog, pageRow, HOME, pinnedHome ? "取消固定" : "固定首页", pageOpen, () -> {
            if (tab != null && pageOpen) {
                library.togglePin(url, tab.title);
                toast(pinnedHome ? "已从首页移除" : "已固定到首页快捷访问");
            }
        });
        sheetAction(dialog, pageRow, PDF, "打印/PDF", pageOpen, () -> { if (tab != null && tab.view != null) printCurrentPage(tab); });
        sheetAction(dialog, pageRow, SHARE, "分享", pageOpen, () -> shareLink(url));

        // 3. Flagship Performance & Experience
        body.addView(AikoTheme.sectionHeader(this, density, "旗舰性能与体验"));
        int policy = DisplayModeEngine.loadPolicy(this);
        String policySub = DisplayModeEngine.policyLabel(policy) + (hudEnabled ? " · HUD开" : "");
        String engineSub = SearchEngines.engineName(SearchEngines.currentId(this));
        boolean globalDark = siteExtras.isGlobalDarkWeb();
        boolean gesturesOn = siteExtras.isAutoHideBar() || siteExtras.isEdgeGestures();

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, SPEED, "刷新率策略", policySub,
                        policy == DisplayModeEngine.POLICY_MAX, true, AikoTheme.CYAN, () -> {
                            dialog.dismiss();
                            showRefreshSettings();
                        }),
                AikoTheme.toggleTile(this, density, ENGINE, "搜索引擎", engineSub,
                        false, true, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            showSearchEnginePicker();
                        }));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, DARK, "全局网页暗黑", globalDark ? "已开启" : "已关闭",
                        globalDark, true, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            boolean next = !globalDark;
                            siteExtras.setGlobalDarkWeb(next);
                            for (Tab t : tabs) {
                                if (t.view != null) {
                                    applySiteExtras(t, t.cachedHost, false);
                                    t.view.setBackgroundColor(effectiveDarkWeb(t.cachedHost) ? AikoTheme.BG : Color.WHITE);
                                }
                            }
                            toast(next ? "已开启全局网页深色模式" : "已关闭全局网页深色模式");
                        }),
                AikoTheme.toggleTile(this, density, GESTURE, "手势与隐栏", gesturesOn ? "已开启" : "已关闭",
                        gesturesOn, true, AikoTheme.EMERALD, () -> {
                            dialog.dismiss();
                            showGestureSettings();
                        }));

        // 4. Library & Diagnostics
        body.addView(AikoTheme.sectionHeader(this, density, "记录与规则"));
        LinearLayout saved = sheetRow(body);
        sheetAction(dialog, saved, STAR, "书签", true, () -> showLibrary(true));
        sheetAction(dialog, saved, HISTORY, "历史", true, () -> showLibrary(false));
        sheetAction(dialog, saved, SHIELD, "过滤规则", true, this::showFilterRules);
        sheetAction(dialog, saved, BOLT, "性能记录", true, this::showPerformance);

        showSheet(dialog, panel);
    }

    private void addTwoColRow(LinearLayout parent, View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(8);
        parent.addView(row, rlp);

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = dp(8);
        row.addView(left, lp1);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void handleReadingShelfAction(Tab tab, boolean alreadySaved) {
        if (alreadySaved) {
            readingShelf.remove(tab.url);
            toast("已从稍后读移除");
            return;
        }
        int sx = tab.view != null ? tab.view.getScrollX() : 0;
        int sy = tab.view != null ? tab.view.getScrollY() : 0;
        String[] choices = {"仅保存链接与阅读位置（瞬时）", "保存并生成离线快照（MHTML）"};
        new AlertDialog.Builder(this).setTitle("加入稍后读 · 0MB 内存")
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        readingShelf.pin(tab.url, tab.title, sx, sy, null);
                        toast("已加入首页稍后读");
                    } else if (tab.view != null) {
                        readingShelf.pin(tab.url, tab.title, sx, sy, null);
                        readingShelf.saveArchiveFor(tab.view, tab.url, path -> {
                            if (isDestroyed()) return;
                            if (path != null && !path.isEmpty()) {
                                readingShelf.pin(tab.url, tab.title, sx, sy, path);
                                toast("已保存离线快照到稍后读");
                            } else {
                                toast("已保存链接（离线快照生成失败）");
                            }
                        });
                    }
                }).show();
    }

    private void printCurrentPage(Tab tab) {
        if (tab == null || tab.view == null) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) {
            toast("当前设备不支持打印服务");
            return;
        }
        String name = (tab.title == null || tab.title.isEmpty() ? "Aiko 页面" : tab.title);
        pm.print(name, tab.view.createPrintDocumentAdapter(name), new PrintAttributes.Builder().build());
    }

    // ==================== REFRESH RATE / SEARCH ENGINE / GESTURE SHEETS ====================

    private void showRefreshSettings() {
        int currentPolicy = DisplayModeEngine.loadPolicy(this);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "185Hz 刷新率引擎", dialog::dismiss);

        float active = displayMode != null ? displayMode.activeRefreshRate() : 0f;
        float max = displayMode != null ? displayMode.maxSupportedRefreshRate() : 0f;
        boolean capped = displayMode != null && displayMode.isOemCapped();

        TextView summary = label(String.format(Locale.ROOT,
                "当前刷新率 %.1f Hz  ·  屏幕最高 %.1f Hz%s",
                active, max, capped ? "\n⚠ 检测到系统可能限制了本应用刷新率" : "\n全屏视频自动让步匹配片源帧率，浏览时释放最高流畅度"),
                12, capped ? AikoTheme.AMBER : AikoTheme.TEXT_MUTED);
        summary.setPadding(dp(4), dp(2), dp(4), dp(12));
        panel.addView(summary);

        int[] policies = {DisplayModeEngine.POLICY_MAX, DisplayModeEngine.POLICY_AUTO, DisplayModeEngine.POLICY_POWER};
        String[] descs = {"请求屏幕最高 120–185Hz 丝滑模式", "交给系统 LTPO 自适应调节", "锁定节能 60Hz 延长续航"};
        for (int i = 0; i < policies.length; i++) {
            int p = policies[i];
            boolean on = p == currentPolicy;
            View tile = AikoTheme.toggleTile(this, density, SPEED, DisplayModeEngine.policyLabel(p), descs[i],
                    on, true, AikoTheme.CYAN, () -> {
                        DisplayModeEngine.savePolicy(this, p);
                        if (displayMode != null) displayMode.setPolicy(p);
                        dialog.dismiss();
                        if (onHome) refreshHomeContent();
                        toast("刷新率策略：" + DisplayModeEngine.policyLabel(p));
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            panel.addView(tile, lp);
        }

        View hudTile = AikoTheme.toggleTile(this, density, BOLT, "实时帧率浮标 (HUD)",
                hudEnabled ? "已开启 · 实时显示 Hz / FPS / P99" : "已关闭 · 零后台开销",
                hudEnabled, true, AikoTheme.EMERALD, () -> {
                    setHudEnabled(!hudEnabled);
                    dialog.dismiss();
                    toast(hudEnabled ? "已开启实时帧率浮标" : "已关闭实时帧率浮标");
                });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.bottomMargin = dp(8);
        panel.addView(hudTile, hlp);

        if (capped) {
            TextView oemBtn = AikoTheme.badge(this, density, "前往系统显示/高刷白名单设置 ›",
                    AikoTheme.AMBER, AikoTheme.CARD, AikoTheme.STROKE);
            oemBtn.setOnClickListener(v -> {
                dialog.dismiss();
                DisplayModeEngine.openRefreshRateSettings(this);
            });
            panel.addView(oemBtn, new LinearLayout.LayoutParams(-1, dp(42)));
        }

        showSheet(dialog, panel);
    }

    private void showSearchEnginePicker() {
        String currentId = SearchEngines.currentId(this);
        String currentCustom = SearchEngines.customTemplate(this);
        String[] ids = SearchEngines.engineIds();
        CharSequence[] names = new CharSequence[ids.length];
        int selected = 0;
        for (int i = 0; i < ids.length; i++) {
            names[i] = SearchEngines.engineName(ids[i]);
            if (ids[i].equals(currentId)) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("选择搜索引擎")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    String chosen = ids[which];
                    dialog.dismiss();
                    if ("custom".equals(chosen)) {
                        showCustomSearchEngineDialog(currentCustom);
                    } else {
                        SearchEngines.save(this, chosen, currentCustom);
                        if (onHome) refreshHomeContent();
                        toast("已切换搜索引擎：" + SearchEngines.engineName(chosen));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomSearchEngineDialog(String existing) {
        EditText input = input("https://example.com/search?q=%s");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(existing == null ? "" : existing);
        LinearLayout box = stack();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(label("使用 %s 代表搜索关键词占位符。", 12, AikoTheme.TEXT_MUTED));
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(48)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("自定义搜索引擎").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String tpl = input.getText().toString().trim();
            if (!tpl.contains("%s") || !HttpUrlUtil.isHttp(tpl.replace("%s", "test"))) {
                input.setError("请输入含 %s 的有效 HTTP(S) 模板");
                return;
            }
            SearchEngines.save(this, "custom", tpl);
            d.dismiss();
            if (onHome) refreshHomeContent();
            toast("已保存自定义搜索引擎");
        });
    }

    private void showGestureSettings() {
        boolean autoHide = siteExtras.isAutoHideBar();
        boolean edge = siteExtras.isEdgeGestures();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "手势与底栏", dialog::dismiss);

        View t1 = AikoTheme.toggleTile(this, density, GESTURE, "滚动自动隐藏底栏",
                autoHide ? "已开启 · GPU 平滑隐栏" : "已关闭 · 固定底栏",
                autoHide, true, AikoTheme.EMERALD, () -> {
                    siteExtras.setAutoHideBar(!autoHide);
                    applyGesturePreferences();
                    dialog.dismiss();
                });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-1, -2);
        lp1.bottomMargin = dp(8);
        panel.addView(t1, lp1);

        View t2 = AikoTheme.toggleTile(this, density, BACK, "屏幕边缘手势前进/后退",
                edge ? "已开启 · 动态避让系统侧滑" : "已关闭",
                edge, true, AikoTheme.ACCENT, () -> {
                    siteExtras.setEdgeGestures(!edge);
                    applyGesturePreferences();
                    dialog.dismiss();
                });
        panel.addView(t2, new LinearLayout.LayoutParams(-1, -2));

        TextView tip = label("提示：在底部地址栏左右滑动可瞬时切换标签，上滑打开标签列表。", 12, AikoTheme.TEXT_DIM);
        tip.setPadding(dp(4), dp(12), dp(4), dp(2));
        panel.addView(tip);

        showSheet(dialog, panel);
    }

    // ==================== PERFORMANCE / LIBRARY / FILTERS ====================

    private void showPerformance() {
        PackageInfo engine = WebView.getCurrentWebViewPackage();
        float activeHz = displayMode != null ? displayMode.activeRefreshRate() : 0f;
        float maxHz = displayMode != null ? displayMode.maxSupportedRefreshRate() : 0f;
        boolean capped = displayMode != null && displayMode.isOemCapped();

        String stats = "系统 WebView：" + (engine == null ? "未知" : engine.versionName)
                + String.format(Locale.ROOT, "\n当前屏幕刷新率：%.1f Hz（最高 %.1f Hz）", activeHz, maxHz)
                + "\n刷新率策略：" + DisplayModeEngine.policyLabel(DisplayModeEngine.loadPolicy(this))
                + (capped ? "\n提示：系统可能限制了本应用刷新率，可在刷新率设置中调整。" : "")
                + "\n热标签上限：" + maxHotTabs + " 个"
                + "\n引擎状态：" + WebViewPerf.diagnosticSummary()
                + "\n\n导航到 WebView 可绘制，含网络等待。"
                + "\n\n" + (timings.isEmpty() ? "打开一个网页后查看记录。" : String.join("\n", timings));

        new AlertDialog.Builder(this).setTitle("性能记录").setMessage(stats)
                .setNegativeButton("刷新率 / HUD", (d, w) -> showRefreshSettings())
                .setNeutralButton("复制", (d, w) -> copyText("Aiko Browser 性能记录", stats, "已复制性能记录"))
                .setPositiveButton("关闭", null).show();
    }

    private void showLibrary(boolean bookmarks) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, bookmarks ? "书签" : "浏览历史", dialog::dismiss);

        // Live Search bar inside Bookmarks / History
        EditText filterBox = input(bookmarks ? "搜索书签…" : "搜索历史…");
        filterBox.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(14)));
        filterBox.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(42));
        flp.bottomMargin = dp(8);
        panel.addView(filterBox, flp);

        List<BrowserLibrary.Entry> all = library.pages(bookmarks);
        ArrayList<BrowserLibrary.Entry> shown = new ArrayList<>(all);

        ListView rows = new ListView(this);
        rows.setDivider(null);
        int maxH = Math.min(340, Math.max(160, getResources().getConfiguration().screenHeightDp - 240));
        panel.addView(rows, new LinearLayout.LayoutParams(-1, dp(maxH)));

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : stack();
                if (convert == null) {
                    row.setPadding(dp(8), dp(8), dp(8), dp(8));
                    row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(10)));
                    TextView t = label("", 14, AikoTheme.TEXT);
                    t.setTypeface(AikoTheme.MEDIUM);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    TextView u = label("", 11, AikoTheme.TEXT_DIM);
                    u.setSingleLine(true);
                    u.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(t);
                    row.addView(u);
                }
                BrowserLibrary.Entry item = shown.get(pos);
                ((TextView) row.getChildAt(0)).setText(item.title);
                ((TextView) row.getChildAt(1)).setText(item.url);
                return row;
            }
        };
        rows.setAdapter(adapter);
        rows.setOnItemClickListener((parent, view, pos, id) -> {
            dialog.dismiss();
            navigate(shown.get(pos).url);
        });

        if (bookmarks) {
            rows.setOnItemLongClickListener((parent, view, pos, id) -> {
                BrowserLibrary.Entry entry = shown.get(pos);
                String[] opts = {"在后台标签打开", library.isPinned(entry.url) ? "从首页取消固定" : "固定到首页", "删除书签"};
                new AlertDialog.Builder(this).setTitle(entry.title).setItems(opts, (d, which) -> {
                    if (which == 0) {
                        dialog.dismiss();
                        queueTab(entry.url);
                    } else if (which == 1) {
                        library.togglePin(entry.url, entry.title);
                        toast("已更新首页快捷访问");
                    } else {
                        library.removeBookmark(entry.url);
                        all.remove(entry);
                        shown.remove(entry);
                        adapter.notifyDataSetChanged();
                    }
                }).show();
                return true;
            });
        }

        filterBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (BrowserLibrary.Entry e : all) {
                    if (q.isEmpty() || e.title.toLowerCase(Locale.ROOT).contains(q)
                            || e.url.toLowerCase(Locale.ROOT).contains(q)) {
                        shown.add(e);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Footer actions: Import / Export HTML for Bookmarks, Clear for History
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ftlp = new LinearLayout.LayoutParams(-1, -2);
        ftlp.topMargin = dp(8);
        panel.addView(footer, ftlp);

        if (bookmarks) {
            TextView imp = AikoTheme.badge(this, density, "导入 HTML", AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
            imp.setOnClickListener(v -> { dialog.dismiss(); startBookmarkImport(); });
            TextView exp = AikoTheme.badge(this, density, "导出 HTML", AikoTheme.ACCENT, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
            exp.setOnClickListener(v -> { dialog.dismiss(); startBookmarkExport(); });
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp1.rightMargin = dp(8);
            footer.addView(imp, lp1);
            footer.addView(exp, new LinearLayout.LayoutParams(0, dp(38), 1));
        } else if (!all.isEmpty()) {
            TextView clear = AikoTheme.badge(this, density, "清除浏览历史", AikoTheme.CORAL, AikoTheme.CARD, AikoTheme.STROKE);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("清除本应用的浏览历史？")
                    .setNegativeButton("保留", null)
                    .setPositiveButton("清除", (c, b) -> {
                        library.clearHistory();
                        dialog.dismiss();
                        toast("已清除浏览历史");
                    }).show());
            footer.addView(clear, new LinearLayout.LayoutParams(-1, dp(38)));
        }

        showSheet(dialog, panel);
    }

    private void startBookmarkExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/html")
                .putExtra(Intent.EXTRA_TITLE, "aiko-bookmarks.html");
        try { startActivityForResult(intent, BOOKMARKS_EXPORT); }
        catch (RuntimeException e) { toast("找不到可用的文件管理器"); }
    }

    private void startBookmarkImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try { startActivityForResult(intent, BOOKMARKS_IMPORT); }
        catch (RuntimeException e) { toast("找不到可用的文件选择器"); }
    }

    private void showFilterRules() {
        SiteFilter filter = filter();
        FilterRules rules = filter.rules;
        String summary = rules == FilterRules.EMPTY ? "正在准备规则，基础过滤已启用。"
                : rules.networkCount + " 条请求规则，" + rules.cosmeticCount + " 条站点元素规则。"
                + "\n跳过 " + rules.skipped + " 条不支持的规则。";
        new AlertDialog.Builder(this).setTitle("广告过滤规则")
                .setMessage("EasyList · EasyPrivacy · AdGuard 中文\n\n" + summary
                        + "\n\n点击地址栏盾牌可暂停单站过滤或启用点选屏蔽。")
                .setNegativeButton("关闭", null)
                .setNeutralButton("自定义规则", (dialog, which) -> showCustomFilters())
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

    /** Reads custom rules on ioExecutor (zero main-thread disk read!). */
    private void showCustomFilters() {
        SiteFilter active = filter();
        ioExecutor.execute(() -> {
            String loaded;
            try {
                loaded = active.customSource();
            } catch (java.io.IOException failure) {
                handler.post(() -> toast("无法读取自定义规则"));
                return;
            }
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                openCustomFilterDialog(active, loaded);
            });
        });
    }

    private void openCustomFilterDialog(SiteFilter active, String initialSource) {
        EditText rules = new EditText(this);
        rules.setTextSize(13);
        rules.setTypeface(AikoTheme.MONO);
        rules.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rules.setGravity(Gravity.START | Gravity.TOP);
        rules.setMinLines(6);
        rules.setMaxLines(11);
        rules.setHint("||ads.example.com^\n@@||example.com^$document\nexample.com##.advertisement");
        rules.setText(initialSource);

        LinearLayout body = stack();
        body.setPadding(dp(20), dp(4), dp(20), dp(8));
        body.addView(label("每行一条 ABP 规则。以 ! 开头添加注释。清空并保存可移除全部自定义规则。", 12, AikoTheme.TEXT_MUTED));
        body.addView(rules, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义过滤规则")
                .setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String source = rules.getText().toString();
            view.setEnabled(false);
            rules.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false);
            active.saveCustom(source, (count, error) -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                if (error != null) {
                    view.setEnabled(true);
                    rules.setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.setCancelable(true);
                    new AlertDialog.Builder(this).setTitle("规则未保存").setMessage(error)
                            .setPositiveButton("返回编辑", null).show();
                    return;
                }
                dialog.dismiss();
                applyCurrentFilter();
                toast("已保存 " + count + " 条规则，刷新网页即可完整应用");
            });
        });
    }

    private void showFilterTrace(Tab tab, String host) {
        SiteFilter.Page page = tab.filterPage;
        FilterTrace.Snapshot log = page != null && host.equals(page.host) ? page.trace.snapshot() : null;
        StringBuilder message = new StringBuilder();
        if (log == null || log.count == 0) {
            message.append("当前文档暂无拦截记录。");
        } else {
            message.append("当前文档拦截了 ").append(log.count).append(" 个网络请求。");
            for (FilterTrace.Hit hit : log.recent) {
                message.append("\n\n").append(hit.host).append('\n').append(hit.source);
                if (!hit.reason.isEmpty()) message.append(" · ").append(hit.reason);
            }
        }
        message.append("\n\n仅保留当前文档最近的命中，不记录完整网址、参数或 Cookie。");
        new AlertDialog.Builder(this).setTitle("拦截记录").setMessage(message)
                .setPositiveButton("关闭", null).show();
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

    private boolean sameOpenSite(Tab tab, String host) {
        return current == tab && tab.view != null && host.equals(tab.cachedHost);
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
            showTab(tab, false);
        }).show();
    }

    // ==================== FIND IN PAGE ====================

    private void openFind(WebView target) {
        closeFind();
        findTarget = target;
        if (gestures != null) gestures.showBar(false);
        EditText search = input("在此页查找");
        findInput = search;
        search.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(14)));
        search.setPadding(dp(12), 0, dp(10), 0);
        TextView count = label("0 / 0", 12, AikoTheme.TEXT_MUTED);
        count.setTypeface(AikoTheme.MONO);
        count.setGravity(Gravity.CENTER);
        findBar.addView(search, new LinearLayout.LayoutParams(0, dp(38), 1));
        findBar.addView(count, new LinearLayout.LayoutParams(dp(56), -1));
        target.setFindListener((index, matches, complete) -> {
            if (findTarget == target && complete) {
                count.setText(matches == 0 ? "0 / 0" : (index + 1) + " / " + matches);
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int length) {
                if (findTarget != target) return;
                handler.removeCallbacks(findDebounceTask);
                if (s.length() == 0) {
                    target.clearMatches();
                    count.setText("0 / 0");
                } else {
                    handler.postDelayed(findDebounceTask, 70);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        addFindControl("‹", "上一处匹配", () -> target.findNext(false));
        addFindControl("›", "下一处匹配", () -> target.findNext(true));
        addFindControl("×", "退出查找", this::closeFind);
        findBar.setVisibility(View.VISIBLE);
        applyInsetsLayout();
        search.requestFocus();
        search.post(() -> {
            if (findTarget == target) {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void addFindControl(String text, String description, Runnable command) {
        TextView control = action(text, description);
        findBar.addView(control, new LinearLayout.LayoutParams(dp(40), -1));
        control.setOnClickListener(view -> command.run());
    }

    private void closeFind() {
        handler.removeCallbacks(findDebounceTask);
        if (findTarget == null) return;
        findTarget.setFindListener(null);
        findTarget.clearMatches();
        resetFindBar();
    }

    private void resetFindBar() {
        findTarget = null;
        findInput = null;
        findBar.removeAllViews();
        findBar.setVisibility(View.GONE);
        hideKeyboardIfNeeded();
        applyInsetsLayout();
    }

    // ==================== DOWNLOADS & NAVIGATION HELPERS ====================

    void showNewDownload(String url, String name, String ua) {
        LinearLayout fields = stack();
        fields.setPadding(dp(24), dp(8), dp(24), 0);
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
                if (!HttpUrlUtil.isHttp(chosen)) { link.setError("请输入 HTTP 或 HTTPS 文件链接"); return; }
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
                        != PackageManager.PERMISSION_GRANTED && !mainPrefs.getBoolean("notif", false)) {
                    mainPrefs.edit().putBoolean("notif", true).apply();
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

    void toggleTransferPriority() {
        boolean next = !DownloadService.PACER.browserFirst();
        DownloadService.PACER.setBrowserFirst(next);
        mainPrefs.edit().putBoolean("browserFirst", next).apply();
    }

    static boolean http(String url) {
        return HttpUrlUtil.isHttp(url);
    }

    @Override public void onBackPressed() {
        if (exitFullscreen()) return;
        if (elementPicker != null && elementPicker.isActive()) { elementPicker.stop(); return; }
        if (findTarget != null) { closeFind(); return; }
        if (isAnyOmniboxFocused()) {
            hideOmniboxOverlay();
            hideKeyboardIfNeeded();
            updateChrome();
            return;
        }
        if (!goBack()) super.onBackPressed();
    }

    private boolean goBack() {
        if (exitFullscreen()) return true;
        return moveHistory(-1);
    }

    private boolean moveHistory(int offset) {
        if (current == null || current.view == null || !current.view.canGoBackOrForward(offset)) return false;
        closeFind();
        rememberScroll(current);
        WebView view = current.view;
        WebBackForwardList history = view.copyBackForwardList();
        int destinationIndex = history.getCurrentIndex() + offset;
        WebHistoryItem target = history.getItemAtIndex(destinationIndex);
        current.historyTarget = target == null ? null : target.getUrl();
        setScrollTarget(current, target == null ? null : current.positions.get(destinationIndex, target.getUrl()));
        if (target != null) prepareTopLoad(current, target.getUrl(), true, true);
        beginNavigation(current);
        view.goBackOrForward(offset);
        return true;
    }

    private void reloadPreservingScroll() {
        if (current == null || current.view == null) return;
        String liveUrl = current.view.getUrl();
        if (liveUrl != null && liveUrl.startsWith("file://")) {
            current.view.getSettings().setAllowFileAccess(true);
            current.view.reload();
            return;
        }
        if (!HttpUrlUtil.isHttp(current.url)) return;
        rememberScroll(current);
        setScrollTarget(current, current.positions.get(ScrollPositions.URL_ONLY, current.url));
        beginNavigation(current);
        current.filterPagePrepared = false;
        prepareTopLoad(current, current.url, true, true);
        current.view.reload();
    }

    private boolean exitFullscreen() {
        if (fullscreen == null) return false;
        root.removeView(fullscreen);
        fullscreen = null;
        viewport.setVisibility(View.VISIBLE);
        setImmersiveSystemBars(false);
        if (displayMode != null) displayMode.setFullscreenVideo(false);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
        return true;
    }

    private void setImmersiveSystemBars(boolean immersive) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl == null) return;
            if (immersive) {
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ctrl.hide(WindowInsets.Type.systemBars());
            } else {
                ctrl.show(WindowInsets.Type.systemBars());
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = immersive
                    ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    : View.SYSTEM_UI_FLAG_VISIBLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ScriptTools.PICK_FILE) {
            if (resultCode == RESULT_OK && data != null) scriptTools.importFile(data.getData());
            return;
        }
        if (requestCode == FILE_PICKER && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            picker = null;
            return;
        }
        if (requestCode == BOOKMARKS_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String html = library.exportBookmarksHtml();
            ioExecutor.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) out.write(html.getBytes(StandardCharsets.UTF_8));
                    handler.post(() -> toast("已导出书签 HTML"));
                } catch (Exception e) {
                    handler.post(() -> toast("导出书签失败"));
                }
            });
            return;
        }
        if (requestCode == BOOKMARKS_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            ioExecutor.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                    if (in == null) return;
                    byte[] tmp = new byte[8192];
                    int total = 0;
                    for (int n; (n = in.read(tmp)) >= 0; ) {
                        total += n;
                        if (total > 2 * 1024 * 1024) break;
                        buf.write(tmp, 0, n);
                    }
                    String html = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                    int imported = library.importBookmarksHtml(html);
                    handler.post(() -> {
                        if (onHome) refreshHomeContent();
                        toast("已导入 " + imported + " 条书签");
                    });
                } catch (Exception e) {
                    handler.post(() -> toast("导入书签失败"));
                }
            });
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (displayMode != null) displayMode.start();
        if (current != null && current.view != null && !onHome) {
            current.view.onResume();
            WebViewPerf.setActiveRenderer(current.view, true);
            if (current.started != 0) current.priorityTicket = DownloadService.PACER.protect(1350);
        }
        if (hudEnabled) attachHud();
    }

    @Override protected void onStop() {
        userScripts.flushValues();
        visible = false;
        if (displayMode != null) displayMode.stop();
        if (current != null) {
            rememberScroll(current);
            DownloadService.PACER.release(current.priorityTicket);
            current.priorityTicket = 0;
            if (current.view != null) {
                current.view.onPause();
                WebViewPerf.setActiveRenderer(current.view, false);
            }
        }
        handler.removeCallbacks(saveTask);
        handler.removeCallbacks(saveHistoryTask);
        library.flushHistory();
        saveTabsAsync();
        super.onStop();
    }

    @Override protected void onDestroy() {
        detachHud();
        if (elementPicker != null) elementPicker.stop();
        userScripts.flushValues();
        if (siteFilter != null) siteFilter.close();
        handler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
        if (picker != null) picker.onReceiveValue(null);
        for (Tab t : tabs) destroyWeb(t);
        super.onDestroy();
    }

    // ==================== SHARED UI HELPERS (PEER-COMPATIBLE) ====================

    private void copyText(String labelText, String value, String confirm) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(labelText, value));
            toast(confirm);
        }
    }

    private void shareLink(String url) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, "分享链接"));
    }

    private LinearLayout stack() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        return stack;
    }

    private LinearLayout sheetRow(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(68)));
        return row;
    }

    private View sheetAction(Dialog dialog, LinearLayout row, BrowserIcon.Kind kind, String text,
                             boolean available, Runnable command) {
        LinearLayout tile = stack();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        tile.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(60), 1);
        size.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tile, size);
        BrowserIcon glyph = new BrowserIcon(this, kind);
        glyph.tint(AikoTheme.ACCENT);
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView title = label(text, 11, AikoTheme.TEXT);
        title.setTypeface(AikoTheme.MEDIUM);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(3);
        tile.addView(title, tlp);
        tile.setContentDescription(text);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setOnClickListener(v -> { dialog.dismiss(); command.run(); });
        tile.setFocusable(available);
        tile.setEnabled(available);
        tile.setAlpha(available ? 1f : 0.38f);
        return tile;
    }

    void showSheet(Dialog dialog, View panel) {
        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    int dp(int value) { return Math.round(value * density); }

    void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void hideKeyboardIfNeeded() {
        View focused = getCurrentFocus();
        boolean hadEditableFocus = focused instanceof EditText;
        editingOmnibox = false;
        viewport.requestFocus();
        if (hadEditableFocus || imeBottom > 0) {
            android.os.IBinder token = focused != null ? focused.getWindowToken() : viewport.getWindowToken();
            if (token != null) {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(token, 0);
            }
        }
    }

    GradientDrawable shape(int color, int radius) {
        return AikoTheme.rounded(color, dp(radius));
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
        v.setTextColor(AikoTheme.TEXT);
        v.setHintTextColor(AikoTheme.TEXT_DIM);
        v.setHint(hint);
        return v;
    }

    private TextView action(String symbol, String desc) {
        TextView v = label(symbol, 22, AikoTheme.TEXT);
        v.setContentDescription(desc);
        v.setGravity(Gravity.CENTER);
        v.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        return v;
    }

    BrowserIcon icon(BrowserIcon.Kind kind, String text, Runnable click) {
        BrowserIcon v = new BrowserIcon(this, kind);
        v.setContentDescription(text);
        v.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        v.setOnClickListener(w -> click.run());
        v.setFocusable(true);
        return v;
    }
}
