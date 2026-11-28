package app.aikobrowser;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.aikobrowser.BrowserIcon.Kind.*;

public final class MainActivity extends Activity {
    // Preserved static palette aliases kept synced with live AikoTheme tokens.
    static int BG = AikoTheme.BG;
    static int PANEL = AikoTheme.SURFACE;
    static int FIELD = AikoTheme.PILL;
    static final int TEXT = AikoTheme.TEXT;
    static final int MUTED = AikoTheme.TEXT_MUTED;
    static int RUBY = AikoTheme.ACCENT;

    private static final String REPO_URL = "https://github.com/UndergroundAlzar/Aiko-Browser";
    private static final String REPO_RELEASES_URL = "https://github.com/UndergroundAlzar/Aiko-Browser/releases";
    private static final String GREASYFORK_URL = "https://greasyfork.org/zh-CN";
    private static final int MAX_TABS = 64;
    private static final int FILE_PICKER = 11;
    private static final int BOOKMARKS_EXPORT = 13;
    private static final int BOOKMARKS_IMPORT = 14;
    private static final int BACKUP_EXPORT = 15;
    private static final int BACKUP_IMPORT = 16;
    private static final int BACKUP_EXPORT_ENCRYPTED = 18;
    private char[] pendingBackupPassword;
    private static final int PICK_CUSTOM_FONT = 17;
    private static final int STATE_SAVE_MAX_BYTES = 256 * 1024;
    private static final int MAX_CUSTOM_FONT_BYTES = 16 * 1024 * 1024;
    private static final String CUSTOM_FONT_URL_PREFIX = "https://aiko-font.local/";
    private static final String WEBRTC_BLOCK_JS =
            "(function(){try{"
            + "Object.defineProperty(window,'RTCPeerConnection',{configurable:true,get:function(){return undefined;}});"
            + "Object.defineProperty(window,'webkitRTCPeerConnection',{configurable:true,get:function(){return undefined;}});"
            + "Object.defineProperty(window,'mozRTCPeerConnection',{configurable:true,get:function(){return undefined;}});"
            + "}catch(e){}})();";
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
    private final Runnable reapplyInsetsTask = this::applyInsetsLayout;
    private final Runnable autoRefreshTask = this::runAutoRefreshTick;
    private final Runnable preloadNewTabTask = this::ensurePreloadedNewTab;
    private final Runnable hideClipboardCapsuleTask = this::hideClipboardCapsule;
    private final Runnable hideScrollTopTask = this::hideScrollTopPill;

    private float density;
    private boolean isLowRam;
    private int maxHotTabs = 6;

    private DisplayModeEngine displayMode;
    private PerfProfiles perfProfiles;
    private PerfProfiles.Profile activeProfile = PerfProfiles.preset(PerfProfiles.ID_BALANCED);
    private BrowserLibrary library;
    private SharedPreferences mainPrefs;
    private SharedPreferences siteProfiles;
    private SiteExtras siteExtras;
    private ReadingShelf readingShelf;
    private SiteFilter siteFilter;
    private UserScripts userScripts;
    private ScriptTools scriptTools;
    private StartPageConfig startPageConfig;
    private MenuConfig menuConfig;
    private BangCatalog bangCatalog;
    private BabelConfig babelConfig;
    private BabelEngine babelEngine;
    private BabelUi babelUi;
    private AikoV040Ui.ReadAloudController readAloud;
    private AikoV040Ui.Host v040Host;
    private SettingsCenterUi.Host settingsHost;
    private String desktopAgent;
    private boolean fetchingRemoteScript;
    private long lastAdpfBoostMs;
    private long lastBackPressMs;

    // Chrome view hierarchy
    private FrameLayout root, viewport, page;
    private FrameLayout scrollTopPill;
    private BrowserIcon scrollTopIcon;
    private View omniboxScrim;
    private View nightComfortOverlay;
    private BottomDeckLayout bottomDeck;
    private LinearLayout controls, addressRow, findBar, clipboardCapsule;
    private GradientDrawable addressCapsuleBg;
    private View homeSpacer, deckDivider;
    private EditText address, homeSearch, findInput;
    private BrowserIcon backButton, homeButton, babelButton, omniboxDismissButton, tabButton, menuButton, siteButton,
            addressClearButton, addressCopyButton, addressQrButton, goButton, actionButton;
    private ProgressBar loading;
    private TextView pwaTogglePill;
    private boolean pwaStandaloneMode;
    private boolean pwaControlsRevealed;

    // Reusable Home view & dynamic sections
    private ScrollView homeView;
    private boolean homeBuiltSearchOnly;
    private FrameLayout homeEmblemFrame;
    private LinearLayout homeBoxRow, homeSpeedGrid, homeShelfSection, homeShelfList;
    private BrowserIcon homeGoButton, homeQrButton;
    private TextView homeEngineBadge, homeStatusPill, homeAddPin;
    private boolean onHome;

    // Lazy focus-only Omnibox suggestions overlay
    private LinearLayout omniboxOverlay, omniboxList;
    private ScrollView omniboxScroll;
    private boolean editingOmnibox;
    private String lastSuggestionQuery;
    private String lastClipboardHintText;

    // Preloaded New Tab (iTab / custom URL zero-flash warmup)
    private ScrollWebView preloadedNewTabView;
    private String preloadedNewTabUrl;

    // Background Audio + Media Sniffer + Form Drafts + Auto Refresh
    private boolean bgAudioEnabled;
    private Tab bgAudioTab;
    private int cachedSniffedCount;
    private int activeAutoRefreshSec;
    private final Map<String, String> savedFormDrafts = new LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 24;
        }
    };

    private final MediaPlaybackService.PlaybackListener mediaPlaybackListener = new MediaPlaybackService.PlaybackListener() {
        @Override public void onTogglePlayPause() {
            Tab target = bgAudioTab != null ? bgAudioTab : current;
            if (target == null || target.view == null) return;
            target.view.evaluateJavascript(
                    "(function(){var m=document.querySelectorAll('video,audio');"
                    + "var playing=false;for(var i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended){playing=true;break;}}"
                    + "for(var j=0;j<m.length;j++){try{if(playing)m[j].pause();else m[j].play();}catch(e){}}"
                    + "return !playing;})();",
                    raw -> {
                        boolean nowPlaying = "true".equals(raw);
                        if (bgAudioEnabled) MediaPlaybackService.updateState(MainActivity.this, nowPlaying);
                    });
        }
        @Override public void onStopBackgroundAudio() {
            setBackgroundAudioEnabled(bgAudioTab, false, false);
        }
    };

    private final QrSheet.ScanCallback qrScanCallback = new QrSheet.ScanCallback() {
        @Override public void onQrScanned(String content) {
            if (content == null || content.trim().isEmpty()) return;
            String trimmed = content.trim();
            if (HttpUrlUtil.isHttp(trimmed)) {
                if (UserScripts.isUserScriptUrl(trimmed)) {
                    handleRemoteUserScript(trimmed, URLUtil.guessFileName(trimmed, null, "application/javascript"), null);
                } else {
                    navigate(trimmed);
                }
            } else {
                submitRawInput(trimmed);
            }
        }
        @Override public void toast(String msg) { MainActivity.this.toast(msg); }
    };

    // Opt-in 185Hz Live Frame-Pacing HUD pill (0% overhead when off)
    private TextView hudPill;
    private boolean hudEnabled;

    // Gesture & on-demand overlays
    private GestureChrome gestures;
    private ElementPicker elementPicker;
    private View crashOverlay;
    private boolean cachedAutoHide = true;
    private boolean cachedEdgeGestures = true;

    // Window insets & real measured bottomDeck height (zero overlap, zero per-scroll relayout)
    private int insetLeft, insetTop, insetRight, insetBottom, imeBottom;
    private int deckContentHeight;
    private boolean legacyImeOpen;

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
        boolean pinned;
        String groupName = "";
        long lastCrashRecoveryMs;
        String pendingDraftJson;

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
        AikoTheme.applyTheme(this);
        syncStaticPaletteAliases();
        density = getResources().getDisplayMetrics().density;
        ActivityManager am = getSystemService(ActivityManager.class);
        isLowRam = am != null && am.isLowRamDevice();
        maxHotTabs = WebViewPerf.recommendedHotTabs(this);

        // Configure window colors & edge-to-edge immediately before first frame
        Window win = getWindow();
        win.setBackgroundDrawable(new ColorDrawable(AikoTheme.BG));
        win.setStatusBarColor(AikoTheme.BG);
        win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            win.setDecorFitsSystemWindows(false);
            win.setNavigationBarColor(Color.TRANSPARENT);
            win.setNavigationBarContrastEnforced(false);
        } else {
            win.setNavigationBarColor(AikoTheme.SURFACE);
        }

        // Unlock flagship 120-185Hz display mode from frame 1
        displayMode = new DisplayModeEngine(this);
        displayMode.apply();

        // Kick off ALL disk/engine warmup asynchronously BEFORE touching WebView
        mainPrefs = getPreferences(MODE_PRIVATE);
        siteProfiles = getSharedPreferences("site_profiles", MODE_PRIVATE);
        perfProfiles = new PerfProfiles(this);
        library = new BrowserLibrary(getSharedPreferences("library", MODE_PRIVATE));
        siteExtras = new SiteExtras(this);
        readingShelf = new ReadingShelf(this);
        userScripts = new UserScripts(this);
        scriptTools = new ScriptTools(this, userScripts);
        startPageConfig = new StartPageConfig(this);
        menuConfig = new MenuConfig(this);
        bangCatalog = new BangCatalog(this);
        babelConfig = new BabelConfig(this);
        babelEngine = new BabelEngine(babelConfig);
        readAloud = new AikoV040Ui.ReadAloudController(this, this::toast);
        babelUi = new BabelUi(this, babelConfig, babelEngine, new BabelUi.HostCallbacks() {
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public void speakText(String text, String lang) {
                if (readAloud != null) readAloud.speak(text, lang);
            }
            @Override public void onStateChanged() { updateChrome(); }
        });
        babelUi.setDisplayModeEngine(displayMode);
        v040Host = buildV040Host();
        settingsHost = buildSettingsHost();
        wireUserScriptsHostActions();
        MediaPlaybackService.setPlaybackListener(mediaPlaybackListener);

        perfProfiles.preloadAsync();
        library.preloadAsync();
        siteExtras.preloadAsync();
        readingShelf.preloadAsync();
        userScripts.preloadAsync();
        filter(); // Start background adblock compile immediately (no 600ms lag!)
        WebViewPerf.startUpAsync(getApplicationContext(), this::onWebViewEngineReady);

        // Read minimal startup prefs (browserFirst + tabs) & paint chrome on frame 1 (zero sync extras/filter disk reads!)
        DownloadService.PACER.setBrowserFirst(mainPrefs.getBoolean("browserFirst", true));
        pwaStandaloneMode = getIntent() != null && getIntent().getBooleanExtra(PwaHelper.EXTRA_PWA_STANDALONE, false);
        buildChrome();
        applyAutoNightSchedule();
        applyNightComfortOverlay();
        restoreTabs(state == null ? incomingLink(getIntent()) : null);
        applyPwaStandaloneChrome();
        setupHudIfEnabled();
        if (state == null && getIntent() != null && getIntent().getBooleanExtra("downloads", false)) showDownloads();
        if (state == null) handleScriptIntent(getIntent());

        // Sync preloaded SiteExtras gesture flags & active PerfProfile off frame 1 without blocking UI thread
        ioExecutor.execute(() -> {
            boolean ah = siteExtras.isAutoHideBar();
            boolean eg = siteExtras.isEdgeGestures();
            PerfProfiles.Profile prof = perfProfiles.active();
            handler.post(() -> {
                if (!isDestroyed()) {
                    applyGestureValues(ah, eg);
                    applyPerformanceProfile(prof, false);
                }
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
        // Flagship v0.5.0 engine stack: Service Worker adblock + Speculative Loading + 512MB UFS cache + HTTP/3 QUIC 0-RTT
        WebViewPerf.installServiceWorkerFilter(filter());
        WebViewPerf.applyFlagshipProfileTuning(activeProfile, isLowRam);
        WebViewPerf.warmUpQuicHints(collectQuicHosts());

        if (current != null && current.url != null && current.view == null) {
            ensureWebViewAndLoad(current);
        }
        // Subtle home preconnect to configured search engine once engine is warm (honors Eco preload off)
        if (onHome && activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
            String origin = SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this));
            if (origin != null && !origin.isEmpty()) {
                WebViewPerf.preconnect(origin, siteFilter);
            }
        }
        scheduleNewTabPreload(400L);
    }

    private Set<String> collectQuicHosts() {
        Set<String> hosts = new HashSet<>();
        String searchOrigin = SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this));
        String sh = HttpUrlUtil.extractHost(searchOrigin);
        if (!sh.isEmpty()) hosts.add(sh);
        if (startPageConfig != null) {
            String hh = HttpUrlUtil.extractHost(startPageConfig.resolveHomeUrl());
            if (!hh.isEmpty()) hosts.add(hh);
            String nh = HttpUrlUtil.extractHost(startPageConfig.resolveNewTabUrl());
            if (!nh.isEmpty()) hosts.add(nh);
        }
        if (library != null) {
            for (BrowserLibrary.Entry pin : library.pins()) {
                String ph = HttpUrlUtil.extractHost(pin.url);
                if (!ph.isEmpty()) hosts.add(ph);
            }
        }
        return hosts;
    }

    private AikoV040Ui.Host buildV040Host() {
        return new AikoV040Ui.Host() {
            @Override public Activity activity() { return MainActivity.this; }
            @Override public float density() { return density; }
            @Override public DisplayModeEngine displayMode() { return displayMode; }
            @Override public StartPageConfig startPageConfig() { return startPageConfig; }
            @Override public BangCatalog bangCatalog() { return bangCatalog; }
            @Override public SiteFilter siteFilter() { return filter(); }
            @Override public SiteExtras siteExtras() { return siteExtras; }
            @Override public UserScripts userScripts() { return userScripts; }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public void navigate(String url) { MainActivity.this.navigate(url); }
            @Override public void addTab(String url, boolean focusBox) { MainActivity.this.addTab(url, focusBox); }
            @Override public void downloadUrl(String url, String filename, String ua) { showNewDownload(url, filename, ua); }
            @Override public void copyText(String label, String text, String confirm) { MainActivity.this.copyText(label, text, confirm); }
            @Override public void onStartPageOrBarChanged() {
                applyInsetsLayout();
                updateChrome();
                if (onHome) showHomeView();
                releasePreloadedNewTab();
                scheduleNewTabPreload(450L);
            }
            @Override public void onNightComfortChanged() {
                applyAutoNightSchedule();
                applyNightComfortOverlay();
            }
            @Override public void onTypographyOrPrivacyChanged() {
                for (Tab t : tabs) {
                    if (t.view != null && HttpUrlUtil.isHttp(t.url)) {
                        applyPageToolkitStyles(t, t.view);
                        if (startPageConfig.isBlockWebRtcLeak()) t.view.evaluateJavascript(WEBRTC_BLOCK_JS, null);
                    }
                }
            }
            @Override public void pickCustomFont() { startCustomFontPicker(); }
            @Override public void startBackupExport(boolean encrypted) { MainActivity.this.startBackupExport(encrypted); }
            @Override public void startBackupImport() { MainActivity.this.startBackupImport(); }
            @Override public void reloadCurrentTab() { reloadPreservingScroll(); }
            @Override public boolean effectiveDarkWeb(String host) { return MainActivity.this.effectiveDarkWeb(host); }
        };
    }

    private SettingsCenterUi.Host buildSettingsHost() {
        return new SettingsCenterUi.Host() {
            @Override public Activity activity() { return MainActivity.this; }
            @Override public float density() { return density; }
            @Override public DisplayModeEngine displayMode() { return displayMode; }
            @Override public MenuConfig menuConfig() { return menuConfig; }
            @Override public StartPageConfig startPageConfig() { return startPageConfig; }
            @Override public PerfProfiles.Profile activePerfProfile() { return activeProfile; }
            @Override public String appVersionName() { return MainActivity.this.appVersionName(); }
            @Override public String babelSummary() { return babelConfig.activeEngineDisplayName(); }
            @Override public String searchEngineName() { return SearchEngines.engineName(SearchEngines.currentId(MainActivity.this)); }
            @Override public String themeSummary() { return AikoTheme.modeShortLabel(AikoTheme.loadMode(MainActivity.this)); }
            @Override public String filterSummary() {
                SiteFilter f = filter();
                int count = f.totalRuleCount();
                return count > 0 ? String.format(Locale.ROOT, "%,d 条有效规则", count) : "7 合 1 规则引擎";
            }
            @Override public boolean isGlobalDarkWeb() { return siteExtras != null && siteExtras.isGlobalDarkWeb(); }
            @Override public boolean isCleanLinks() { return PerfProfiles.isCleanLinksEnabled(MainActivity.this); }
            @Override public boolean isPageOpen() {
                return current != null && current.view != null && HttpUrlUtil.isHttp(current.url);
            }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }

            @Override public void openThemeStudio() { showThemeStudio(); }
            @Override public void openPerformanceCenter() { showPerformanceCenter(); }
            @Override public void openPerformanceDiagnostics() { showPerformance(); }
            @Override public void openCustomizationSheet() { AikoV040Ui.showCustomizationSheet(v040Host); }
            @Override public void openGestureSettings() { showGestureSettings(); }
            @Override public void openBabelSheet() {
                Tab tab = current;
                boolean ok = tab != null && tab.view != null && HttpUrlUtil.isHttp(tab.url);
                babelUi.showSheet(ok ? tab.view : null, ok ? tab.url : null);
            }
            @Override public void openFilterRules() { showFilterRules(); }
            @Override public void openSearchEnginePicker() { showSearchEnginePicker(); }
            @Override public void openUserScripts() { showUserScriptsSheet(); }
            @Override public void openBangManager() { AikoV040Ui.showBangManagerSheet(v040Host); }
            @Override public void openBackupSheet() { AikoV040Ui.showBackupSheet(v040Host); }
            @Override public void openAboutSheet() { showAboutSheet(); }
            @Override public void toggleGlobalDarkWeb() {
                boolean next = !siteExtras.isGlobalDarkWeb();
                siteExtras.setGlobalDarkWeb(next);
                refreshAllTabsDarkMode();
                toast(next ? "已开启全局网页深色模式" : "已关闭全局网页深色模式");
            }
            @Override public void toggleCleanLinks() {
                boolean next = !PerfProfiles.isCleanLinksEnabled(MainActivity.this);
                PerfProfiles.setCleanLinksEnabled(MainActivity.this, next);
                toast(next ? "已开启分享/复制链接自动净化" : "已关闭链接自动净化");
            }
            @Override public void clearWebHttpCache(Runnable onDone) {
                for (Tab t : tabs) {
                    if (t.view != null) {
                        try { t.view.clearCache(true); } catch (RuntimeException ignored) { }
                        break;
                    }
                }
                if (onDone != null) onDone.run();
            }
            @Override public void clearAllHistory() {
                library.clearHistory();
                toast("已清空全部浏览历史");
            }
            @Override public void executeMenuAction(MenuConfig.ActionId action) { MainActivity.this.executeMenuAction(action); }
            @Override public String actionDynamicSubtitle(MenuConfig.ActionId action) { return MainActivity.this.actionDynamicSubtitle(action); }
            @Override public boolean actionIsActive(MenuConfig.ActionId action) { return MainActivity.this.actionIsActive(action); }
            @Override public int actionAccentColor(MenuConfig.ActionId action) { return MainActivity.this.actionAccentColor(action); }
        };
    }

    private void wireUserScriptsHostActions() {
        userScripts.setHostActions(new UserScripts.HostActions() {
            @Override public void downloadUrl(String url, String filename) {
                handler.post(() -> {
                    if (isDestroyed() || !HttpUrlUtil.isHttp(url)) return;
                    String fname = (filename == null || filename.trim().isEmpty())
                            ? URLUtil.guessFileName(url, null, null) : filename.trim();
                    int lanes = Math.min(16, activeProfile.downloadLanes);
                    try {
                        DownloadService.start(MainActivity.this, url, fname, null, lanes,
                                () -> toast("已加入下载队列：" + fname));
                    } catch (Exception e) {
                        showNewDownload(url, fname, null);
                    }
                });
            }

            @Override public void openInTab(String url) {
                handler.post(() -> {
                    if (!isDestroyed() && HttpUrlUtil.isHttp(url)) addTab(url, false);
                });
            }

            @Override public void requestConnectApproval(String scriptId, String scriptName, String host,
                                                         java.util.function.Consumer<Boolean> decision) {
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) {
                        if (decision != null) decision.accept(false);
                        return;
                    }
                    AikoV040Ui.showConnectApprovalSheet(MainActivity.this, displayMode, density,
                            scriptName, host, choice -> {
                                if (choice == 2) {
                                    if (decision != null) decision.accept(true);
                                } else if (choice == 1) {
                                    if (decision != null) decision.accept(true);
                                    // Allow Once: revoke persisted host right after UserScripts dispatches this request
                                    handler.postDelayed(() -> userScripts.revokeConnectHost(scriptId, host), 350L);
                                } else {
                                    if (decision != null) decision.accept(false);
                                }
                            });
                });
            }

            @Override public void onMenuCommandsChanged(WebView view) {
                handler.post(() -> {
                    if (!isDestroyed()) updateChrome();
                });
            }
        });
    }

    private void syncStaticPaletteAliases() {
        BG = AikoTheme.BG;
        PANEL = AikoTheme.SURFACE;
        FIELD = AikoTheme.PILL;
        RUBY = AikoTheme.ACCENT;
    }

    /** Re-skins all live browser chrome, window bars, home start page, and dark canvases without restarting. */
    private void applyLiveTheme() {
        syncStaticPaletteAliases();
        Window win = getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(AikoTheme.BG));
            win.setStatusBarColor(AikoTheme.BG);
            if (Build.VERSION.SDK_INT < 30) {
                win.setNavigationBarColor(AikoTheme.SURFACE);
            }
        }
        if (root != null) root.setBackgroundColor(AikoTheme.BG);
        if (page != null) page.setBackgroundColor(AikoTheme.BG);
        if (findBar != null) findBar.setBackgroundColor(AikoTheme.SURFACE);
        if (findInput != null) {
            findInput.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(14)));
        }
        if (addressRow != null) {
            addressCapsuleBg = AikoTheme.capsule(density, dp(22));
            int hairlinePx = Math.max(1, Math.round(density));
            if (address != null && address.hasFocus()) {
                addressCapsuleBg.setStroke(hairlinePx, AikoTheme.STROKE_ACCENT);
            }
            addressRow.setBackground(addressCapsuleBg);
        }
        if (loading != null) {
            int curProgress = loading.getProgress();
            loading.setProgressDrawable(createLoadingDrawable(dp(2)));
            loading.setProgress(curProgress);
        }
        if (goButton != null) {
            goButton.tint(AikoTheme.ACCENT);
            goButton.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        }
        BrowserIcon[] chromeBtns = {backButton, homeButton, babelButton, omniboxDismissButton, tabButton, menuButton,
                siteButton, addressClearButton, addressCopyButton, addressQrButton, actionButton};
        for (BrowserIcon btn : chromeBtns) {
            if (btn != null) btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        }
        if (addressCopyButton != null) addressCopyButton.tint(AikoTheme.ACCENT_CYAN);
        if (addressQrButton != null) addressQrButton.tint(AikoTheme.TEXT_MUTED);
        if (homeQrButton != null) {
            homeQrButton.tint(AikoTheme.TEXT_MUTED);
            homeQrButton.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        }
        if (omniboxOverlay != null) {
            omniboxOverlay.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, dp(20)));
        }
        if (hudPill != null) {
            hudPill.setBackground(AikoTheme.card((AikoTheme.BG & 0x00FFFFFF) | 0xCC000000, AikoTheme.STROKE, density, dp(12)));
        }
        if (scrollTopPill != null) {
            scrollTopPill.setBackground(AikoTheme.card(
                    (AikoTheme.SURFACE & 0x00FFFFFF) | 0xE8000000, AikoTheme.STROKE_ACCENT, density, dp(21)));
            scrollTopPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(21)));
        }
        if (scrollTopIcon != null) scrollTopIcon.tint(AikoTheme.ACCENT);
        if (homeView != null) {
            homeView.setBackgroundColor(AikoTheme.BG);
            if (homeEmblemFrame != null) {
                homeEmblemFrame.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, dp(22)));
            }
            if (homeBoxRow != null) homeBoxRow.setBackground(AikoTheme.capsule(density, dp(27)));
            if (homeEngineBadge != null) {
                homeEngineBadge.setTextColor(AikoTheme.ACCENT);
                homeEngineBadge.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(18)));
                homeEngineBadge.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(18)));
            }
            if (homeStatusPill != null) {
                homeStatusPill.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, dp(14)));
                homeStatusPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            }
            if (homeGoButton != null) {
                homeGoButton.tint(AikoTheme.ACCENT);
                homeGoButton.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
            }
            if (homeAddPin != null) {
                homeAddPin.setTextColor(AikoTheme.ACCENT);
                homeAddPin.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(10)));
            }
            refreshHomeContent();
        }
        for (Tab t : tabs) {
            if (t.view != null && (t.url == null || effectiveDarkWeb(t.cachedHost))) {
                t.view.setBackgroundColor(AikoTheme.BG);
            }
        }
        applyInsetsLayout();
        updateChrome();
    }

    /** Applies the active performance profile to DisplayModeEngine, DownloadService, and live WebViews. */
    private void applyPerformanceProfile(PerfProfiles.Profile profile, boolean showToast) {
        if (profile == null) return;
        boolean prevGlobalUltra = activeProfile.globalUltraScroll;
        activeProfile = PerfProfiles.sanitized(profile);
        int capFromProfile = WebViewPerf.maxActiveTabsForProfile(activeProfile);
        maxHotTabs = isLowRam ? Math.min(capFromProfile, 3) : capFromProfile;
        if (displayMode != null) displayMode.applyProfile(activeProfile);
        DownloadService.setMaxLanesLimit(activeProfile.downloadLanes);
        WebViewPerf.applyFlagshipProfileTuning(activeProfile, isLowRam);
        for (Tab t : tabs) {
            if (t.view != null) {
                boolean activeTab = (t == current && !onHome && visible);
                WebViewPerf.applyProfile(t.view, activeProfile, false, isLowRam, activeTab);
                WebViewPerf.setActivePreRaster(t.view, activeTab && activeProfile.activePreRaster && !isLowRam);
                if (t.cachedHost != null) {
                    if (isEffectiveUltraScroll(t.cachedHost)) {
                        t.view.evaluateJavascript(SiteExtras.ultraScrollJs(true), null);
                    } else if (prevGlobalUltra) {
                        t.view.evaluateJavascript(SiteExtras.ultraScrollJs(false), null);
                    }
                }
            }
        }
        if (PerfProfiles.ID_ECO.equals(activeProfile.id)) releasePreloadedNewTab();
        else scheduleNewTabPreload(500L);
        trimTabs(maxHotTabs);
        if (onHome) refreshHomeContent();
        if (showToast) toast("性能模式：" + activeProfile.name);
    }

    private boolean isEffectiveUltraScroll(String host) {
        return activeProfile.globalUltraScroll || (host != null && siteExtras != null && siteExtras.isUltraScroll(host));
    }

    private void triggerAdpfBoost(long durationMs) {
        if (!activeProfile.adpfBoost || displayMode == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastAdpfBoostMs < 350L) return;
        lastAdpfBoostMs = now;
        displayMode.boostInteraction(durationMs);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        density = getResources().getDisplayMetrics().density;
        if (displayMode != null) displayMode.apply();
        applyInsetsLayout();
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
        if (intent.hasExtra(PwaHelper.EXTRA_PWA_STANDALONE)) {
            pwaStandaloneMode = intent.getBooleanExtra(PwaHelper.EXTRA_PWA_STANDALONE, false);
            pwaControlsRevealed = false;
            applyPwaStandaloneChrome();
        }
        if (intent.getBooleanExtra("downloads", false)) showDownloads();
        if (handleScriptIntent(intent)) return;
        String link = incomingLink(intent);
        if (link != null) addTab(link, false);
    }

    private boolean handleScriptIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri data = intent.getData();
        if (data == null || !"content".equals(data.getScheme())) return false;
        scriptTools.importFile(data);
        return true;
    }

    private String incomingLink(Intent intent) {
        if (intent == null) return null;
        String pwaUrl = intent.getStringExtra(PwaHelper.EXTRA_PWA_URL);
        if (HttpUrlUtil.isHttp(pwaUrl)) return pwaUrl;
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

        page = new FrameLayout(this);
        page.setBackgroundColor(AikoTheme.BG);
        viewport.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Tap-outside backdrop shown behind omniboxOverlay & bottomDeck when editing address bar
        omniboxScrim = new View(this);
        omniboxScrim.setBackgroundColor(0x66000000);
        omniboxScrim.setVisibility(View.GONE);
        omniboxScrim.setAlpha(0f);
        omniboxScrim.setOnClickListener(v -> dismissOmnibox());
        root.addView(omniboxScrim, new FrameLayout.LayoutParams(-1, -1));

        // Bottom deck: direct child of root so viewport sits strictly above it (ZERO overlap!)
        int hairlinePx = Math.max(1, Math.round(density));
        deckContentHeight = dp(56) + hairlinePx;

        bottomDeck = new BottomDeckLayout();
        bottomDeck.setBackgroundColor(AikoTheme.SURFACE);
        bottomDeck.setElevation(dp(6));
        root.addView(bottomDeck, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout deckStack = new LinearLayout(this);
        deckStack.setOrientation(LinearLayout.VERTICAL);
        bottomDeck.addView(deckStack, new FrameLayout.LayoutParams(-1, -2));

        deckDivider = new View(this);
        deckDivider.setBackgroundColor(AikoTheme.DIVIDER);
        deckStack.addView(deckDivider, new LinearLayout.LayoutParams(-1, hairlinePx));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        deckStack.addView(controls, new LinearLayout.LayoutParams(-1, dp(56)));

        backButton = icon(BACK, "后退，长按前进", this::goBack);
        AikoTheme.attachPressFeedback(backButton, 0.92f);
        controls.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        backButton.setOnLongClickListener(v -> {
            if (!moveHistory(1)) toast("没有可前进的页面");
            return true;
        });

        homeButton = icon(HOME, "主页，长按定制主页", this::goHomeAction);
        homeButton.tint(AikoTheme.TEXT_MUTED);
        homeButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(homeButton, 0.92f);
        homeButton.setOnLongClickListener(v -> {
            AikoV040Ui.showCustomizationSheet(v040Host);
            return true;
        });
        controls.addView(homeButton, new LinearLayout.LayoutParams(dp(40), dp(44)));

        omniboxDismissButton = icon(DOWN, "收起地址栏", this::dismissOmnibox);
        omniboxDismissButton.tint(AikoTheme.TEXT_MUTED);
        omniboxDismissButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(omniboxDismissButton, 0.92f);
        controls.addView(omniboxDismissButton, new LinearLayout.LayoutParams(dp(40), dp(44)));

        addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(4), 0, dp(4), 0);
        addressCapsuleBg = AikoTheme.capsule(density, dp(22));
        addressRow.setBackground(addressCapsuleBg);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(0, dp(44), 1);
        arlp.leftMargin = dp(4);
        arlp.rightMargin = dp(4);
        controls.addView(addressRow, arlp);

        siteButton = icon(SEARCH, "搜索或输入网址", () -> {
            if (current == null || current.url == null) focusAddress();
            else showSiteControls();
        });
        AikoTheme.attachPressFeedback(siteButton, 0.92f);
        addressRow.addView(siteButton, new LinearLayout.LayoutParams(dp(38), -1));

        address = input("搜索或输入网址");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(dp(2), 0, dp(2), 0);
        address.setTextSize(14);
        address.setTypeface(AikoTheme.MEDIUM);
        address.setLetterSpacing(0.005f);
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
        AikoTheme.attachPressFeedback(actionButton, 0.92f);
        addressRow.addView(actionButton, new LinearLayout.LayoutParams(dp(38), -1));

        addressClearButton = icon(CLOSE, "清空输入", () -> {
            address.setText("");
            address.requestFocus();
        });
        addressClearButton.tint(AikoTheme.TEXT_DIM);
        addressClearButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(addressClearButton, 0.90f);
        addressRow.addView(addressClearButton, new LinearLayout.LayoutParams(dp(34), -1));

        addressCopyButton = icon(COPY, "一键复制链接", () -> {
            if (current != null && HttpUrlUtil.isHttp(current.url)) {
                copyUrl("网址", current.url);
                dismissOmnibox();
            }
        });
        addressCopyButton.tint(AikoTheme.ACCENT_CYAN);
        addressCopyButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(addressCopyButton, 0.92f);
        addressRow.addView(addressCopyButton, new LinearLayout.LayoutParams(dp(36), -1));

        addressQrButton = icon(QR, "扫码或识别二维码", () -> {
            dismissOmnibox();
            QrSheet.showScannerHintOrImageDecode(this, qrScanCallback);
        });
        addressQrButton.tint(AikoTheme.TEXT_MUTED);
        addressQrButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(addressQrButton, 0.92f);
        addressRow.addView(addressQrButton, new LinearLayout.LayoutParams(dp(36), -1));

        goButton = icon(GO, "前往", () -> submitAddress(address));
        goButton.tint(AikoTheme.ACCENT);
        goButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(goButton, 0.92f);
        addressRow.addView(goButton, new LinearLayout.LayoutParams(dp(38), -1));

        homeSpacer = new View(this);
        homeSpacer.setVisibility(View.GONE);
        controls.addView(homeSpacer, new LinearLayout.LayoutParams(0, -1, 1));

        babelButton = icon(TRANSLATE, "巴别塔翻译，长按设置", () -> {
            if (current != null && current.view != null && HttpUrlUtil.isHttp(current.url)) {
                babelUi.togglePageTranslation(current.view, current.url);
            } else {
                babelUi.showSheet(null, null);
            }
        });
        babelButton.tint(AikoTheme.TEXT_MUTED);
        babelButton.setVisibility(View.GONE);
        AikoTheme.attachPressFeedback(babelButton, 0.92f);
        babelButton.setOnLongClickListener(v -> {
            babelUi.showSheet(current != null ? current.view : null, current != null ? current.url : null);
            return true;
        });
        controls.addView(babelButton, new LinearLayout.LayoutParams(dp(40), dp(44)));

        tabButton = icon(TABS, "标签页，长按新建", this::showTabs);
        AikoTheme.attachPressFeedback(tabButton, 0.92f);
        tabButton.setOnLongClickListener(v -> { openNewTabDefault(false); return true; });
        controls.addView(tabButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        menuButton = icon(MENU, "浏览工具，长按打开下载", this::showTools);
        AikoTheme.attachPressFeedback(menuButton, 0.92f);
        menuButton.setOnLongClickListener(v -> { showDownloads(); return true; });
        controls.addView(menuButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        address.setOnFocusChangeListener((field, editing) -> {
            if (current == null) return;
            editingOmnibox = editing;
            addressCapsuleBg.setStroke(hairlinePx, editing ? AikoTheme.STROKE_ACCENT : AikoTheme.CAPSULE_STROKE);
            if (editing) {
                if (gestures != null) gestures.showBar(false);
                if (activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
                    String origin = SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this));
                    if (origin != null && !origin.isEmpty()) WebViewPerf.preconnect(origin, siteFilter);
                }
                addressRow.setVisibility(View.VISIBLE);
                homeSpacer.setVisibility(View.GONE);
                showOmniboxScrim();
            } else {
                hideOmniboxScrim();
                addressRow.setVisibility(onHome ? View.GONE : View.VISIBLE);
                homeSpacer.setVisibility(onHome ? View.VISIBLE : View.GONE);
            }
            int vis = editing ? View.GONE : View.VISIBLE;
            backButton.setVisibility(editing || onHome ? View.GONE : View.VISIBLE);
            if (homeButton != null) {
                homeButton.setVisibility(!editing && startPageConfig != null && startPageConfig.isShowHomeButton()
                        ? View.VISIBLE : View.GONE);
            }
            if (babelButton != null) {
                babelButton.setVisibility(!editing && !onHome && startPageConfig != null && startPageConfig.isShowBabelButton()
                        ? View.VISIBLE : View.GONE);
            }
            omniboxDismissButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            tabButton.setVisibility(vis);
            menuButton.setVisibility(vis);
            siteButton.setVisibility(vis);
            actionButton.setVisibility(editing ? View.GONE : View.VISIBLE);
            if (addressQrButton != null) addressQrButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            goButton.setVisibility(editing ? View.VISIBLE : View.GONE);
            address.setPadding(editing ? dp(12) : dp(2), 0, editing ? dp(4) : dp(2), 0);
            address.setEllipsize(editing ? null : TextUtils.TruncateAt.END);
            if (editing) {
                address.setText(current.url == null ? "" : current.url);
                address.selectAll();
                updateOmniboxEditButtons(address.getText());
                showOmniboxOverlay(address.getText());
            } else {
                addressClearButton.setVisibility(View.GONE);
                addressCopyButton.setVisibility(View.GONE);
                hideOmniboxOverlay();
                updateAddress();
            }
            applyInsetsLayout();
        });
        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (address.hasFocus()) {
                    updateOmniboxEditButtons(s);
                    showOmniboxOverlay(s);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        findBar = new LinearLayout(this);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(AikoTheme.SURFACE);
        findBar.setPadding(dp(10), dp(4), dp(8), dp(4));
        findBar.setVisibility(View.GONE);
        deckStack.addView(findBar, new LinearLayout.LayoutParams(-1, dp(52)));

        // Sleek 2.5dp luminous periwinkle-to-cyan gradient hairline flush along top edge of bottomDeck
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setProgressDrawable(createLoadingDrawable(dp(2)));
        loading.setMax(100);
        loading.setAlpha(0f);
        loading.setVisibility(View.INVISIBLE);
        int progressPx = Math.max(2, Math.round(2.5f * density));
        bottomDeck.addView(loading, new FrameLayout.LayoutParams(-1, progressPx, Gravity.TOP));

        // Track real measured bottomDeck height cleanly (zero hardcoded assumptions, zero layout loops)
        bottomDeck.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getVisibility() != View.VISIBLE) return;
            int measured = Math.max(0, (b - t) - v.getPaddingBottom());
            if (measured > 0 && measured != deckContentHeight) {
                deckContentHeight = measured;
                root.removeCallbacks(reapplyInsetsTask);
                root.post(reapplyInsetsTask);
            }
        });

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
            @Override public void onBarLayoutChangeNeeded() { applyInsetsLayout(); }
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

        // PWA Standalone floating toggle pill (hidden unless launched as standalone PWA)
        pwaTogglePill = AikoTheme.badge(this, density, "Aiko",
                AikoTheme.TEXT_MUTED, (AikoTheme.SURFACE & 0x00FFFFFF) | 0xCC000000, AikoTheme.STROKE);
        pwaTogglePill.setElevation(dp(12));
        pwaTogglePill.setAlpha(0.72f);
        pwaTogglePill.setVisibility(View.GONE);
        pwaTogglePill.setOnClickListener(v -> {
            pwaControlsRevealed = !pwaControlsRevealed;
            applyPwaStandaloneChrome();
        });
        pwaTogglePill.setOnLongClickListener(v -> {
            pwaStandaloneMode = false;
            pwaControlsRevealed = false;
            applyPwaStandaloneChrome();
            toast("已退出独立应用全屏模式");
            return true;
        });
        FrameLayout.LayoutParams pwaLp = new FrameLayout.LayoutParams(-2, dp(28), Gravity.BOTTOM | Gravity.END);
        pwaLp.rightMargin = dp(10);
        pwaLp.bottomMargin = dp(18);
        root.addView(pwaTogglePill, pwaLp);

        // 185Hz Quick Scroll-to-Top floating pill (appears only on upward scroll deep in long pages)
        scrollTopPill = new FrameLayout(this);
        scrollTopPill.setBackground(AikoTheme.card(
                (AikoTheme.SURFACE & 0x00FFFFFF) | 0xE8000000, AikoTheme.STROKE_ACCENT, density, dp(21)));
        scrollTopPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(21)));
        scrollTopPill.setElevation(dp(10));
        scrollTopPill.setContentDescription("返回顶部");
        scrollTopPill.setVisibility(View.GONE);
        scrollTopIcon = new BrowserIcon(this, BrowserIcon.Kind.UP);
        scrollTopIcon.tint(AikoTheme.ACCENT);
        scrollTopPill.addView(scrollTopIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        scrollTopPill.setOnClickListener(v -> {
            hideScrollTopPill();
            if (current != null && current.view != null) {
                if (current.view.getScrollY() > dp(2400)) {
                    current.view.scrollTo(0, dp(900));
                }
                current.view.evaluateJavascript(
                        "(function(){try{if(window.scrollY>2400)window.scrollTo(0,900);window.scrollTo({top:0,behavior:'smooth'});}catch(e){window.scrollTo(0,0);}})()",
                        null);
            }
        });
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM | Gravity.END);
        topLp.rightMargin = dp(14);
        topLp.bottomMargin = dp(82);
        root.addView(scrollTopPill, topLp);

        // Full-screen non-touch-intercepting AMOLED Night Comfort dimmer/warmth overlay
        nightComfortOverlay = new View(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }
            @Override public boolean onTouchEvent(MotionEvent event) { return false; }
        };
        nightComfortOverlay.setClickable(false);
        nightComfortOverlay.setFocusable(false);
        nightComfortOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nightComfortOverlay.setElevation(dp(40));
        nightComfortOverlay.setVisibility(View.GONE);
        root.addView(nightComfortOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        applyInsetsLayout();
        viewport.requestFocus();
        root.post(() -> {
            if (!firstDraw) {
                firstDraw = true;
                reportFullyDrawn();
            }
        });
    }

    static Drawable createLoadingDrawable(float radiusPx) {
        ClipDrawable clip = new ClipDrawable(
                AikoTheme.progressBarDrawable(radiusPx), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{clip});
        layer.setId(0, android.R.id.progress);
        return layer;
    }

    /**
     * Single source of truth for viewport, bottomDeck, and omniboxOverlay geometry.
     * Guarantees ZERO overlap over web fixed footers/modals when bottomDeck is docked,
     * zero per-scroll-pixel relayouts during 185Hz auto-hide flings, clean WebView IME
     * expansion, steady WebView during omnibox editing, and 100% edge-to-edge fullscreen video.
     */
    private void applyInsetsLayout() {
        if (viewport == null || bottomDeck == null || isDestroyed()) return;
        boolean isFullscreen = fullscreen != null;
        boolean editingOmni = isAnyOmniboxFocused();
        boolean findOpen = findTarget != null;
        boolean barHidden = gestures != null && gestures.isBarHidden();
        boolean barSettling = gestures != null && gestures.isBarSettlingShow();
        boolean stateOverridesAutoHide = isFullscreen || editingOmni || findOpen || onHome
                || (imeBottom > insetBottom) || legacyImeOpen;

        if (stateOverridesAutoHide && gestures != null && gestures.isBarAnimating()) {
            gestures.cancelBarAnimation();
            barSettling = false;
        }

        GestureChrome.ChromeLayout layout = GestureChrome.computeChromeLayout(
                isFullscreen, onHome, editingOmni, findOpen,
                barHidden, barSettling, legacyImeOpen,
                insetBottom, imeBottom, deckContentHeight, dp(6));

        boolean pwaHideDeck = pwaStandaloneMode && !pwaControlsRevealed && !editingOmni && !findOpen && !isFullscreen;
        boolean capsuleStyle = startPageConfig != null
                && startPageConfig.getBarStyle() == StartPageConfig.BarStyle.CAPSULE
                && !onHome && !editingOmni && !findOpen && !isFullscreen && !pwaHideDeck;

        int vpBottom = pwaHideDeck ? insetBottom : (capsuleStyle && !barHidden ? layout.viewportBottomMargin + dp(10) : layout.viewportBottomMargin);

        // 1. Update viewport margins ONLY when changed (prevents spurious WebView relayout)
        FrameLayout.LayoutParams vlp = (FrameLayout.LayoutParams) viewport.getLayoutParams();
        if (vlp != null && (vlp.leftMargin != insetLeft || vlp.topMargin != insetTop
                || vlp.rightMargin != insetRight || vlp.bottomMargin != vpBottom)) {
            vlp.leftMargin = insetLeft;
            vlp.topMargin = insetTop;
            vlp.rightMargin = insetRight;
            vlp.bottomMargin = vpBottom;
            viewport.setLayoutParams(vlp);
        }

        // 2. Update bottomDeck visibility, inset padding, Capsule/Standard style, and translationY
        int targetDeckVis = (layout.deckVisible && !pwaHideDeck) ? View.VISIBLE : View.GONE;
        if (bottomDeck.getVisibility() != targetDeckVis) {
            bottomDeck.setVisibility(targetDeckVis);
        }
        FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) bottomDeck.getLayoutParams();
        int deckHMargin = capsuleStyle ? (Math.max(insetLeft, insetRight) + dp(10)) : 0;
        int deckBMargin = capsuleStyle ? (insetBottom + dp(8)) : 0;
        if (dlp != null && (dlp.leftMargin != deckHMargin || dlp.rightMargin != deckHMargin || dlp.bottomMargin != deckBMargin)) {
            dlp.leftMargin = deckHMargin;
            dlp.rightMargin = deckHMargin;
            dlp.bottomMargin = deckBMargin;
            bottomDeck.setLayoutParams(dlp);
        }
        int deckPadBottom = capsuleStyle ? 0 : layout.deckPaddingBottom;
        if (bottomDeck.getPaddingBottom() != deckPadBottom) {
            bottomDeck.setPadding(0, 0, 0, deckPadBottom);
        }
        int ctrlLeft = capsuleStyle ? dp(8) : (insetLeft + dp(6));
        int ctrlRight = capsuleStyle ? dp(8) : (insetRight + dp(6));
        if (controls != null && (controls.getPaddingLeft() != ctrlLeft || controls.getPaddingRight() != ctrlRight)) {
            controls.setPadding(ctrlLeft, dp(6), ctrlRight, dp(6));
        }
        int findLeft = insetLeft + dp(10);
        int findRight = insetRight + dp(8);
        if (findBar != null && (findBar.getPaddingLeft() != findLeft || findBar.getPaddingRight() != findRight)) {
            findBar.setPadding(findLeft, dp(4), findRight, dp(4));
        }

        boolean seamlessHomeDeck = onHome && !editingOmni && !findOpen;
        if (capsuleStyle) {
            bottomDeck.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, dp(26)));
        } else {
            bottomDeck.setBackgroundColor(seamlessHomeDeck ? AikoTheme.BG : AikoTheme.SURFACE);
        }
        if (deckDivider != null) {
            int divVis = (seamlessHomeDeck || capsuleStyle) ? View.INVISIBLE : View.VISIBLE;
            if (deckDivider.getVisibility() != divVis) deckDivider.setVisibility(divVis);
        }
        float targetElev = (targetDeckVis == View.VISIBLE && !barHidden && !seamlessHomeDeck) ? dp(capsuleStyle ? 10 : 6) : 0f;
        if (bottomDeck.getElevation() != targetElev) bottomDeck.setElevation(targetElev);

        if (pwaTogglePill != null && pwaStandaloneMode) {
            FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) pwaTogglePill.getLayoutParams();
            int pwaBottom = pwaControlsRevealed ? (deckContentHeight + insetBottom + dp(14)) : (insetBottom + dp(14));
            if (plp != null && plp.bottomMargin != pwaBottom) {
                plp.bottomMargin = pwaBottom;
                pwaTogglePill.setLayoutParams(plp);
            }
        }
        if (scrollTopPill != null) {
            if (isFullscreen || onHome || editingOmni || findOpen || (imeBottom > insetBottom) || legacyImeOpen) {
                hideScrollTopPill();
            } else {
                FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) scrollTopPill.getLayoutParams();
                int topRight = insetRight + dp(14);
                int topBottom = vpBottom + dp(16);
                if (tlp != null && (tlp.rightMargin != topRight || tlp.bottomMargin != topBottom)) {
                    tlp.rightMargin = topRight;
                    tlp.bottomMargin = topBottom;
                    scrollTopPill.setLayoutParams(tlp);
                }
            }
        }

        boolean allowSnapTranslation = stateOverridesAutoHide || gestures == null || !gestures.isBarAnimating();
        if (allowSnapTranslation && bottomDeck.getTranslationY() != layout.deckTranslationY) {
            bottomDeck.setTranslationY(layout.deckTranslationY);
        }

        // 3. Position floating omniboxOverlay cleanly above bottomDeck + IME
        if (omniboxOverlay != null && omniboxOverlay.getVisibility() == View.VISIBLE) {
            FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) omniboxOverlay.getLayoutParams();
            int oLeft = insetLeft + dp(8);
            int oRight = insetRight + dp(8);
            int oBottom = layout.omniboxBottomMargin;
            if (olp.leftMargin != oLeft || olp.rightMargin != oRight || olp.bottomMargin != oBottom) {
                olp.leftMargin = oLeft;
                olp.rightMargin = oRight;
                olp.bottomMargin = oBottom;
                omniboxOverlay.setLayoutParams(olp);
            }
            if (omniboxScroll != null) {
                int availH = Math.max(dp(96), root.getHeight() - insetTop - oBottom - dp(12));
                int maxOverlayH = Math.min(dp(260), availH);
                ViewGroup.LayoutParams slp = omniboxScroll.getLayoutParams();
                int desiredH = Math.min(maxOverlayH, measureOmniboxContentHeight());
                if (slp.height != desiredH) {
                    slp.height = desiredH;
                    omniboxScroll.setLayoutParams(slp);
                }
            }
        }

        // 4. Sync safe anchor offsets for GestureChrome & ElementPicker
        int dockedTotal = deckContentHeight + insetBottom;
        if (gestures != null) gestures.setSafeInsets(insetTop, dockedTotal);
        if (elementPicker != null) elementPicker.setSafeInsets(insetLeft, insetRight, dockedTotal);
    }

    private int measureOmniboxContentHeight() {
        if (omniboxList == null) return dp(120);
        int count = omniboxList.getChildCount();
        return Math.max(dp(56), count * dp(50) + dp(8));
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
        applyInsetsLayout();
    }

    private void restoreTabs(String incoming) {
        try {
            JSONArray stored = new JSONArray(mainPrefs.getString("tabs", "[]"));
            for (int i = 0; i < stored.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject item = stored.getJSONObject(i);
                String url = item.optString("url");
                String validUrl = isNavigableUrl(url) ? url : null;
                Tab tab = new Tab(validUrl, item.optString("title"));
                tab.pinned = item.optBoolean("pinned", false);
                tab.groupName = item.optString("group", "");
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
        if (tabs.isEmpty()) {
            String customHome = startPageConfig != null ? startPageConfig.resolveHomeUrl() : null;
            tabs.add(new Tab(customHome, customHome == null ? "新标签" : customHome));
        }
        showTab(tabs.get(Math.min(Math.max(index, 0), tabs.size() - 1)), false);
    }

    private static boolean isNavigableUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return HttpUrlUtil.isHttp(url) || "about:blank".equalsIgnoreCase(url)
                || url.regionMatches(true, 0, "file://", 0, 7);
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
                    pos == null ? 0 : pos.y,
                    t.pinned,
                    t.groupName == null ? "" : t.groupName));
        }
        ioExecutor.execute(() -> {
            JSONArray list = new JSONArray();
            for (TabSnapshot s : snap) {
                JSONObject item = new JSONObject();
                try {
                    item.put("url", s.url);
                    item.put("title", s.title);
                    if (s.pinned) item.put("pinned", true);
                    if (!s.groupName.isEmpty()) item.put("group", s.groupName);
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
        final String url, title, groupName;
        final boolean hasScroll, pinned;
        final int scrollX, scrollY;

        TabSnapshot(String url, String title, boolean hasScroll, int scrollX, int scrollY,
                    boolean pinned, String groupName) {
            this.url = url;
            this.title = title;
            this.hasScroll = hasScroll;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.pinned = pinned;
            this.groupName = groupName;
        }
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 600);
    }

    private void openNewTabDefault(boolean focusBox) {
        String target = startPageConfig != null ? startPageConfig.resolveNewTabUrl() : null;
        if (target == null) {
            addTab(null, focusBox);
            return;
        }
        if (tabs.size() >= MAX_TABS) {
            toast("最多保留 " + MAX_TABS + " 个标签");
            return;
        }
        // Zero-white-flash instant swap when preloaded hidden WebView matches custom new-tab URL!
        if (preloadedNewTabView != null && target.equals(preloadedNewTabUrl)) {
            ScrollWebView warm = preloadedNewTabView;
            preloadedNewTabView = null;
            preloadedNewTabUrl = null;
            Tab tab = new Tab(target, warm.getTitle() == null || warm.getTitle().isEmpty() ? target : warm.getTitle());
            tab.view = warm;
            warm.setOwner(tab);
            tabs.add(tab);
            showTab(tab, false);
            scheduleNewTabPreload(800L);
            return;
        }
        addTab(target, false);
        scheduleNewTabPreload(800L);
    }

    private void goHomeAction() {
        String customHome = startPageConfig != null ? startPageConfig.resolveHomeUrl() : null;
        if (customHome != null) {
            navigate(customHome);
            return;
        }
        if (current == null) return;
        closeFind();
        hideOmniboxOverlay();
        hideKeyboardIfNeeded();
        if (current.view != null) {
            rememberScroll(current);
            captureFormDraft(current);
            destroyWeb(current);
        }
        current.setUrl(null);
        current.title = "新标签";
        showTab(current, false);
    }

    private void scheduleNewTabPreload(long delayMs) {
        handler.removeCallbacks(preloadNewTabTask);
        if (startPageConfig == null || !startPageConfig.shouldPreloadNewTab()
                || PerfProfiles.ID_ECO.equals(activeProfile.id) || isLowRam) {
            releasePreloadedNewTab();
            return;
        }
        handler.postDelayed(preloadNewTabTask, delayMs);
    }

    private void ensurePreloadedNewTab() {
        if (isDestroyed() || isFinishing() || !visible || !engineReady || isLowRam) return;
        if (startPageConfig == null || !startPageConfig.shouldPreloadNewTab()
                || PerfProfiles.ID_ECO.equals(activeProfile.id)) {
            releasePreloadedNewTab();
            return;
        }
        String target = startPageConfig.resolveNewTabUrl();
        if (target == null || "about:blank".equalsIgnoreCase(target)) {
            releasePreloadedNewTab();
            return;
        }
        if (preloadedNewTabView != null && target.equals(preloadedNewTabUrl)) return;
        releasePreloadedNewTab();
        try {
            Tab warmTab = new Tab(target, target);
            ScrollWebView warm = createWebView(warmTab);
            warmTab.view = warm;
            preloadedNewTabView = warm;
            preloadedNewTabUrl = target;
            attachWarmWebView(warm);
            warm.setVisibility(View.GONE);
            WebViewPerf.setActivePreRaster(warm, false);
            WebViewPerf.setActiveRenderer(warm, false);
            prepareTopLoad(warmTab, target, true, false);
            warm.loadUrl(target);
        } catch (RuntimeException ignored) {
            releasePreloadedNewTab();
        }
    }

    private void releasePreloadedNewTab() {
        handler.removeCallbacks(preloadNewTabTask);
        ScrollWebView old = preloadedNewTabView;
        preloadedNewTabView = null;
        preloadedNewTabUrl = null;
        if (old != null) {
            try {
                if (babelUi != null) babelUi.unbindWebView(old);
                userScripts.detach(old);
                if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
                old.destroy();
            } catch (RuntimeException ignored) { }
        }
    }

    private void applyPwaStandaloneChrome() {
        if (pwaTogglePill != null) {
            pwaTogglePill.setVisibility(pwaStandaloneMode ? View.VISIBLE : View.GONE);
            pwaTogglePill.setText(pwaControlsRevealed ? "隐栏" : "Aiko");
        }
        applyInsetsLayout();
    }

    private void applyNightComfortOverlay() {
        if (nightComfortOverlay == null || startPageConfig == null) return;
        int color = startPageConfig.computeOverlayColor();
        if (color == 0) {
            nightComfortOverlay.setVisibility(View.GONE);
        } else {
            nightComfortOverlay.setBackgroundColor(color);
            nightComfortOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void showScrollTopPill() {
        if (scrollTopPill == null || isDestroyed() || onHome || fullscreen != null) return;
        handler.removeCallbacks(hideScrollTopTask);
        if (scrollTopPill.getVisibility() != View.VISIBLE) {
            scrollTopPill.animate().cancel();
            scrollTopPill.setAlpha(0f);
            scrollTopPill.setScaleX(0.86f);
            scrollTopPill.setScaleY(0.86f);
            scrollTopPill.setVisibility(View.VISIBLE);
            scrollTopPill.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(140)
                    .start();
        }
        handler.postDelayed(hideScrollTopTask, 2200L);
    }

    private void hideScrollTopPill() {
        handler.removeCallbacks(hideScrollTopTask);
        if (scrollTopPill == null || scrollTopPill.getVisibility() != View.VISIBLE) return;
        scrollTopPill.animate().cancel();
        scrollTopPill.animate()
                .alpha(0f).scaleX(0.86f).scaleY(0.86f)
                .setDuration(110)
                .withEndAction(() -> {
                    if (scrollTopPill != null) scrollTopPill.setVisibility(View.GONE);
                })
                .start();
    }

    private void applyAutoNightSchedule() {
        if (startPageConfig == null || !startPageConfig.isAutoThemeByTime()) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean inNight = startPageConfig.isInNightWindow(hour);
        boolean lastAppliedNight = mainPrefs.getBoolean("auto_night_applied", false);
        if (inNight && !lastAppliedNight) {
            mainPrefs.edit()
                    .putBoolean("auto_night_prev_dark", siteExtras.isGlobalDarkWeb())
                    .putBoolean("auto_night_applied", true)
                    .apply();
            if (!siteExtras.isGlobalDarkWeb()) {
                siteExtras.setGlobalDarkWeb(true);
                refreshAllTabsDarkMode();
            }
            if (!startPageConfig.isNightFilterEnabled()
                    && (startPageConfig.getNightDimPercent() > 0 || startPageConfig.getNightWarmthPercent() > 0)) {
                startPageConfig.setNightFilterEnabled(true);
            }
        } else if (!inNight && lastAppliedNight) {
            boolean prevDark = mainPrefs.getBoolean("auto_night_prev_dark", false);
            mainPrefs.edit().putBoolean("auto_night_applied", false).apply();
            if (siteExtras.isGlobalDarkWeb() != prevDark) {
                siteExtras.setGlobalDarkWeb(prevDark);
                refreshAllTabsDarkMode();
            }
            if (startPageConfig.isNightFilterEnabled()) {
                startPageConfig.setNightFilterEnabled(false);
            }
        }
    }

    private void refreshAllTabsDarkMode() {
        for (Tab t : tabs) {
            if (t.view != null) {
                applySiteExtras(t, t.cachedHost, false);
                t.view.setBackgroundColor(effectiveDarkWeb(t.cachedHost) ? AikoTheme.BG : Color.WHITE);
            }
        }
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
        hideScrollTopPill();
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
                WebViewPerf.setActivePreRaster(current.view, false);
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
                WebViewPerf.setActivePreRaster(tab.view, activeProfile.activePreRaster && !isLowRam);
                if (displayMode != null) {
                    displayMode.attachView(tab.view);
                    displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(tab.cachedHost));
                }
            } else if (engineReady || WebViewPerf.isStarted() || firstDraw) {
                // Only construct WebView once async startup settled or user navigated after frame 1
                ensureWebViewAndLoad(tab);
            }
        }
        applyInsetsLayout();
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
            WebViewPerf.setActivePreRaster(tab.view, activeProfile.activePreRaster && !isLowRam);
            if (displayMode != null) {
                displayMode.attachView(tab.view);
                displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(tab.cachedHost));
            }
        } else {
            tab.view.setVisibility(View.GONE);
            tab.view.onPause();
            WebViewPerf.setActivePreRaster(tab.view, false);
            WebViewPerf.setActiveRenderer(tab.view, false);
        }
        if (tab.navigation == null || tab.view.restoreState(tab.navigation) == null) {
            tab.view.loadUrl(tab.url);
        }
        tab.navigation = null;
        applyInsetsLayout();
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
                boolean protectedTab = (t == current) || t.pinned || (bgAudioEnabled && t == bgAudioTab);
                if (!protectedTab && (oldest == null || t.used < oldest.used)) oldest = t;
            }
            if (count <= cap || oldest == null) return;
            rememberScroll(oldest);
            captureFormDraft(oldest);
            oldest.navigation = new Bundle();
            WebViewPerf.saveStateBounded(oldest.view, oldest.navigation, STATE_SAVE_MAX_BYTES);
            destroyWeb(oldest);
        }
    }

    private void destroyWeb(Tab tab) {
        WebView web = tab.view;
        if (web == null) return;
        if (tab == bgAudioTab && bgAudioEnabled) {
            setBackgroundAudioEnabled(tab, false, false);
        }
        tab.view = null;
        tab.desktopApplied = false;
        tab.filterPagePrepared = false;
        tab.canGoBack = false;
        tab.canGoForward = false;
        if (babelUi != null) babelUi.unbindWebView(web);
        userScripts.detach(web);
        setScrollTarget(tab, null);
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        for (Tab t : tabs) {
            if (t.view != null) WebViewPerf.setActivePreRaster(t.view, false);
        }
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) {
            releasePreloadedNewTab();
            trimTabs(1);
            for (Tab t : tabs) if (t != current) t.favicon = null;
        } else if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN
                || level == TRIM_MEMORY_BACKGROUND) {
            releasePreloadedNewTab();
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
        boolean searchOnly = startPageConfig == null || startPageConfig.isSearchOnly();
        if (homeView != null && homeBuiltSearchOnly == searchOnly) return;
        if (homeView != null) {
            page.removeView(homeView);
            homeEmblemFrame = null;
            homeStatusPill = homeEngineBadge = homeAddPin = null;
            homeGoButton = homeQrButton = null;
            homeSpeedGrid = homeShelfSection = homeShelfList = null;
        }
        homeBuiltSearchOnly = searchOnly;
        homeView = new ScrollView(this);
        homeView.setFillViewport(true);
        homeView.setVerticalScrollBarEnabled(false);
        homeView.setBackgroundColor(AikoTheme.BG);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(searchOnly ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(20), dp(searchOnly ? 24 : 48), dp(20), dp(searchOnly ? 24 : 28));
        homeView.addView(container, new ScrollView.LayoutParams(-1, -2));

        if (!searchOnly) addHomeHeader(container);

        // Floating Pill Omnibox with clickable Search Engine badge
        homeBoxRow = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int w = Math.min(MeasureSpec.getSize(widthSpec), dp(540));
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        homeBoxRow.setGravity(Gravity.CENTER_VERTICAL);
        homeBoxRow.setBackground(AikoTheme.capsule(density, dp(27)));
        homeBoxRow.setElevation(dp(8));
        homeBoxRow.setPadding(dp(8), dp(4), dp(6), dp(4));
        container.addView(homeBoxRow, new LinearLayout.LayoutParams(-1, dp(54)));

        if (!searchOnly) addHomeEngineBadge();

        homeSearch = input("搜索或输入网址");
        homeSearch.setInputType(InputType.TYPE_NULL);
        homeSearch.setShowSoftInputOnFocus(false);
        homeSearch.setCursorVisible(false);
        homeSearch.setBackgroundColor(Color.TRANSPARENT);
        homeSearch.setPadding(dp(2), 0, dp(4), 0);
        homeSearch.setGravity(Gravity.CENTER_VERTICAL);
        homeSearch.setTextSize(15);
        homeSearch.setTypeface(AikoTheme.MEDIUM);
        homeBoxRow.addView(homeSearch, new LinearLayout.LayoutParams(0, -1, 1));

        // Tapping Start Page search pill seamlessly opens the bottom omnibox + scrollable suggestions above IME
        homeSearch.setOnClickListener(v -> focusAddress());
        homeSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusAddress();
        });

        if (searchOnly) {
            page.addView(homeView, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        homeQrButton = icon(QR, "扫码或识别二维码", () -> QrSheet.showScannerHintOrImageDecode(this, qrScanCallback));
        homeQrButton.tint(AikoTheme.TEXT_MUTED);
        AikoTheme.attachPressFeedback(homeQrButton, 0.92f);
        homeBoxRow.addView(homeQrButton, new LinearLayout.LayoutParams(dp(40), dp(44)));

        homeGoButton = icon(GO, "搜索或前往", this::focusAddress);
        homeGoButton.tint(AikoTheme.ACCENT);
        AikoTheme.attachPressFeedback(homeGoButton, 0.92f);
        homeBoxRow.addView(homeGoButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // Speed Dial 4-column grid
        LinearLayout dialHeader = new LinearLayout(this);
        dialHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(-1, -2);
        dhlp.topMargin = dp(28);
        dhlp.bottomMargin = dp(10);
        container.addView(dialHeader, dhlp);

        TextView dialTitle = label("快捷访问", 12, AikoTheme.TEXT_DIM);
        dialTitle.setTypeface(AikoTheme.MEDIUM);
        dialTitle.setLetterSpacing(0.04f);
        dialHeader.addView(dialTitle, new LinearLayout.LayoutParams(0, -2, 1));

        homeAddPin = label("+ 添加", 12, AikoTheme.ACCENT);
        homeAddPin.setTypeface(AikoTheme.MEDIUM);
        homeAddPin.setPadding(dp(10), dp(4), dp(6), dp(4));
        homeAddPin.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(10)));
        AikoTheme.attachPressFeedback(homeAddPin, 0.94f);
        homeAddPin.setOnClickListener(v -> showAddSpeedDialDialog());
        dialHeader.addView(homeAddPin, new LinearLayout.LayoutParams(-2, -2));

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

    private void addHomeHeader(LinearLayout container) {
        // Airy Hero Header: Aiko emblem + brand + live performance mode pill
        homeEmblemFrame = new FrameLayout(this);
        homeEmblemFrame.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, dp(22)));
        homeEmblemFrame.setElevation(dp(8));
        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.aiko_pixel);
        emblem.setScaleType(ImageView.ScaleType.FIT_CENTER);
        homeEmblemFrame.addView(emblem, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        container.addView(homeEmblemFrame, new LinearLayout.LayoutParams(dp(68), dp(68)));

        TextView brand = new TextView(this);
        brand.setText("Aiko");
        brand.setTextSize(28);
        brand.setTextColor(AikoTheme.TEXT);
        brand.setTypeface(AikoTheme.BOLD);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setLetterSpacing(-0.015f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(14);
        container.addView(brand, blp);

        homeStatusPill = AikoTheme.badge(this, density, "智能均衡 · 高刷",
                AikoTheme.ACCENT_CYAN, AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE);
        homeStatusPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(homeStatusPill, 0.95f);
        homeStatusPill.setOnClickListener(v -> showPerformanceCenter());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(26);
        container.addView(homeStatusPill, slp);
    }

    private void addHomeEngineBadge() {
        homeEngineBadge = new TextView(this);
        homeEngineBadge.setTextSize(12);
        homeEngineBadge.setTypeface(AikoTheme.MEDIUM);
        homeEngineBadge.setTextColor(AikoTheme.ACCENT);
        homeEngineBadge.setGravity(Gravity.CENTER);
        homeEngineBadge.setPadding(dp(12), dp(6), dp(10), dp(6));
        homeEngineBadge.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(18)));
        homeEngineBadge.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(18)));
        homeEngineBadge.setContentDescription("切换搜索引擎");
        AikoTheme.attachPressFeedback(homeEngineBadge, 0.94f);
        homeEngineBadge.setOnClickListener(v -> showSearchEnginePicker());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, dp(36));
        elp.rightMargin = dp(10);
        homeBoxRow.addView(homeEngineBadge, elp);
    }

    private void refreshHomeContent() {
        if (homeView == null || homeBuiltSearchOnly) return;
        String engineName = SearchEngines.engineName(SearchEngines.currentId(this));
        if (homeEngineBadge != null) homeEngineBadge.setText(engineName + " ▾");
        if (homeStatusPill != null && displayMode != null) {
            float hz = displayMode.activeRefreshRate();
            String hzText = hz > 0 ? String.format(Locale.ROOT, "%.0fHz", hz) : "高刷";
            String prefix = PerfProfiles.ID_RAMPAGE.equals(activeProfile.id) ? "⚡ " : "";
            homeStatusPill.setText(prefix + activeProfile.name + " · " + hzText);
            homeStatusPill.setTextColor(displayMode.isOemCapped() ? AikoTheme.AMBER
                    : (PerfProfiles.ID_ECO.equals(activeProfile.id) ? AikoTheme.EMERALD : AikoTheme.ACCENT_CYAN));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(86), 1);
        if (rightGap) lp.rightMargin = dp(9);
        return lp;
    }

    private View buildSpeedDialTile(BrowserLibrary.Entry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(6), dp(10), dp(6), dp(9));
        tile.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
        tile.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));

        String mono = siteMonogram(entry.url, entry.title);

        TextView circle = new TextView(this);
        circle.setText(mono);
        circle.setTextSize(15);
        circle.setTypeface(AikoTheme.BOLD);
        circle.setTextColor(AikoTheme.ACCENT);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(AikoTheme.card(AikoTheme.CAPSULE, AikoTheme.DIVIDER, density, dp(12)));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView caption = new TextView(this);
        caption.setText(entry.title);
        caption.setTextSize(11);
        caption.setTypeface(AikoTheme.MEDIUM);
        caption.setTextColor(AikoTheme.TEXT);
        caption.setSingleLine(true);
        caption.setGravity(Gravity.CENTER);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(7);
        tile.addView(caption, clp);

        // Finger-down preconnect (~100ms before ACTION_UP!) + silky GPU press feedback
        GestureChrome.attachPressScale(tile, 0.95f, () -> {
            if (activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
                WebViewPerf.preconnect(entry.url, siteFilter);
            }
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
        card.setPadding(dp(14), dp(11), dp(8), dp(11));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(9);
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
        AikoTheme.attachPressFeedback(rm, 0.90f);
        card.addView(rm, new LinearLayout.LayoutParams(dp(40), dp(40)));

        GestureChrome.attachPressScale(card, 0.97f, () -> {
            if (activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
                WebViewPerf.preconnect(item.url, siteFilter);
            }
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
        if (gestures != null) gestures.showBar(false);
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
        applyInsetsLayout();
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
        omniboxOverlay.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, dp(20)));
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
        lp.bottomMargin = deckContentHeight + insetBottom + dp(6);
        root.addView(omniboxOverlay, lp);
    }

    private static String siteMonogram(String url, String title) {
        if (title != null) {
            String t = title.trim();
            if (!t.isEmpty() && !t.contains("://") && !t.regionMatches(true, 0, "www.", 0, 4)) {
                int cp = t.codePointAt(0);
                if (Character.isLetterOrDigit(cp)) {
                    return new String(Character.toChars(Character.toUpperCase(cp)));
                }
            }
        }
        String host = url == null ? "" : HttpUrlUtil.extractHost(url);
        String cleanHost = stripSubdomainPrefix(host);
        if (!cleanHost.isEmpty()) return cleanHost.substring(0, 1).toUpperCase(Locale.ROOT);
        return "A";
    }

    private static String stripSubdomainPrefix(String host) {
        if (host == null || host.isEmpty()) return "";
        String s = host;
        if (s.startsWith("www.") && s.length() > 4) s = s.substring(4);
        else if (s.startsWith("m.") && s.length() > 2) s = s.substring(2);
        else if (s.startsWith("mobile.") && s.length() > 7) s = s.substring(7);
        else if (s.startsWith("wap.") && s.length() > 4) s = s.substring(4);
        return s;
    }

    private void updateOmniboxEditButtons(CharSequence text) {
        if (addressClearButton != null) {
            boolean hasText = text != null && text.length() > 0;
            addressClearButton.setVisibility(editingOmnibox && hasText ? View.VISIBLE : View.GONE);
        }
        if (addressCopyButton != null) {
            boolean hasPageUrl = current != null && HttpUrlUtil.isHttp(current.url);
            addressCopyButton.setVisibility(editingOmnibox && hasPageUrl ? View.VISIBLE : View.GONE);
        }
    }

    private void showOmniboxScrim() {
        if (omniboxScrim == null) return;
        omniboxScrim.animate().setListener(null).cancel();
        omniboxScrim.setVisibility(View.VISIBLE);
        omniboxScrim.animate().alpha(1f).setDuration(120).setInterpolator(AikoTheme.EMPHASIZED_DECEL).start();
    }

    private void hideOmniboxScrim() {
        if (omniboxScrim == null || omniboxScrim.getVisibility() == View.GONE) return;
        omniboxScrim.animate().setListener(null).cancel();
        omniboxScrim.setAlpha(0f);
        omniboxScrim.setVisibility(View.GONE);
    }

    private void dismissOmnibox() {
        hideOmniboxOverlay();
        hideOmniboxScrim();
        hideKeyboardIfNeeded();
        updateChrome();
        applyInsetsLayout();
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

        // 0a. Slash /command instant shortcuts
        if (query.startsWith("/")) {
            List<BangCatalog.SlashCmd> cmds = BangCatalog.suggestSlashCommands(query);
            for (BangCatalog.SlashCmd cmd : cmds) {
                addSuggestionRow(FLASH_CMD, BangCatalog.slashLabel(cmd), "快捷指令 · 点按立即执行",
                        AikoTheme.ACCENT_CYAN, null, () -> executeSlashCommand(cmd));
                rows++;
                if (rows >= 8) break;
            }
        }

        // 0b. !Bang quick search suggestions
        if (query.startsWith("!") && bangCatalog != null) {
            int sp = query.indexOf(' ');
            String trailingArg = sp > 0 ? query.substring(sp + 1).trim() : "";
            List<BangCatalog.Entry> bangs = bangCatalog.suggestBangs(query, 8);
            for (BangCatalog.Entry b : bangs) {
                String sub = trailingArg.isEmpty()
                        ? ("点击补全 !" + b.bang + " 或直接打开 " + b.label)
                        : ("使用 " + b.label + " 搜索 “" + trailingArg + "”");
                addSuggestionRow(FLASH_CMD, "!" + b.bang + "  ·  " + b.label, sub,
                        AikoTheme.ACCENT, b.homeUrl, () -> {
                            if (trailingArg.isEmpty()) {
                                address.setText("!" + b.bang + " ");
                                address.setSelection(address.getText().length());
                            } else {
                                submitRawInput("!" + b.bang + " " + trailingArg);
                            }
                        });
                rows++;
                if (rows >= 8) break;
            }
        }

        // 0. Active webpage quick-action card (1-tap Copy Link / QR / Share)
        boolean showedCurrentCard = false;
        if (rows == 0 && current != null && !onHome && HttpUrlUtil.isHttp(current.url)
                && (query.isEmpty() || query.equals(current.url))) {
            addCurrentPageOmniboxCard(current.title, current.url);
            showedCurrentCard = true;
            rows++;
        }

        // 1. Clipboard Quick-Paste Chip (only when query empty or matches active URL)
        String clip = readClipboardSnippet();
        if (!query.startsWith("/") && !query.startsWith("!")
                && clip != null && !clip.equals(query) && (current == null || !clip.equals(current.url))) {
            boolean isUrl = HttpUrlUtil.isHttp(clip);
            addSuggestionRow(COPY, isUrl ? "粘贴并前往" : "搜索剪贴板", clip, AikoTheme.CYAN,
                    isUrl ? clip : null, () -> submitRawInput(clip));
            rows++;
        }

        // 2. Matching open tabs (instant switch without duplicate tab!)
        if (!query.isEmpty() && !query.startsWith("/") && !query.startsWith("!")) {
            String low = query.toLowerCase(Locale.ROOT);
            for (Tab t : tabs) {
                if (t == current || t.url == null) continue;
                String title = t.title == null ? "" : t.title;
                if (t.url.toLowerCase(Locale.ROOT).contains(low) || title.toLowerCase(Locale.ROOT).contains(low)) {
                    addSuggestionRow(TABS, "切换到标签 · " + (title.isEmpty() ? t.cachedHost : title),
                            t.url, AikoTheme.EMERALD, null, () -> showTab(t, false));
                    if (++rows >= 3) break;
                }
            }
        }

        // 3. Instant local Bookmark + History matches (+ speculative DNS/TCP preconnect for top hit)
        if (!query.startsWith("/") && !query.startsWith("!")) {
            String searchKey = (current != null && query.equals(current.url)) ? "" : query;
            List<BrowserLibrary.Entry> matches = library.search(searchKey, Math.max(3, 7 - rows));
            boolean preconnectedTop = false;
            for (BrowserLibrary.Entry item : matches) {
                if (showedCurrentCard && current != null && item.url.equals(current.url)) continue;
                if (!preconnectedTop && !searchKey.isEmpty() && activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
                    WebViewPerf.preconnect(item.url, siteFilter);
                    preconnectedTop = true;
                }
                boolean saved = library.isBookmarked(item.url);
                addSuggestionRow(saved ? STAR : HISTORY, item.title, item.url,
                        saved ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED, item.url, () -> navigate(item.url));
                rows++;
                if (rows >= 6) break;
            }
        }

        // 4. Direct Search Engine option when user typed non-empty query
        if (!query.isEmpty() && !query.startsWith("/") && !query.startsWith("!") && !HttpUrlUtil.isHttp(query)) {
            String eng = SearchEngines.engineName(SearchEngines.currentId(this));
            addSuggestionRow(SEARCH, "使用 " + eng + " 搜索 “" + query + "”", null,
                    AikoTheme.ACCENT, SearchEngines.searchOrigin(SearchEngines.currentId(this), SearchEngines.customTemplate(this)),
                    () -> navigate(SearchEngines.buildSearchUrl(SearchEngines.currentId(this), SearchEngines.customTemplate(this), query)));
            rows++;
        }

        boolean wasVisible = omniboxOverlay.getVisibility() == View.VISIBLE;
        if (rows > 0) {
            omniboxOverlay.setVisibility(View.VISIBLE);
            if (!wasVisible) {
                omniboxOverlay.animate().setListener(null).cancel();
                omniboxOverlay.setLayerType(View.LAYER_TYPE_NONE, null);
                omniboxOverlay.setAlpha(0f);
                omniboxOverlay.setTranslationY(dp(10));
                omniboxOverlay.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(130)
                        .setInterpolator(AikoTheme.EMPHASIZED_DECEL)
                        .withLayer()
                        .setListener(new AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(Animator a) {
                                omniboxOverlay.setLayerType(View.LAYER_TYPE_NONE, null);
                                omniboxOverlay.animate().setListener(null);
                            }
                            @Override public void onAnimationCancel(Animator a) {
                                omniboxOverlay.setLayerType(View.LAYER_TYPE_NONE, null);
                                omniboxOverlay.animate().setListener(null);
                            }
                        })
                        .start();
            }
        } else {
            omniboxOverlay.animate().setListener(null).cancel();
            omniboxOverlay.setLayerType(View.LAYER_TYPE_NONE, null);
            omniboxOverlay.setVisibility(View.GONE);
        }
        applyInsetsLayout();
    }

    private void addCurrentPageOmniboxCard(String title, String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(6), dp(8));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE_ACCENT, density, dp(13)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(6);
        omniboxList.addView(card, clp);

        LinearLayout info = stack();
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        String heading = (title == null || title.isEmpty() || title.equals(url))
                ? HttpUrlUtil.extractHost(url) : title;
        TextView titleTv = label(heading, 12, AikoTheme.TEXT);
        titleTv.setTypeface(AikoTheme.BOLD);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(titleTv);

        TextView urlTv = label(url, 11, AikoTheme.TEXT_MUTED);
        urlTv.setSingleLine(true);
        urlTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(urlTv);

        TextView copyPill = AikoTheme.badge(this, density, "复制链接",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        copyPill.setTextSize(11);
        copyPill.setTypeface(AikoTheme.BOLD);
        copyPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        copyPill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        AikoTheme.attachPressFeedback(copyPill, 0.94f);
        copyPill.setOnClickListener(v -> {
            copyUrl("网址", url);
            dismissOmnibox();
        });
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(-2, dp(32));
        cplp.leftMargin = dp(8);
        card.addView(copyPill, cplp);

        BrowserIcon qrBtn = icon(QR, "生成二维码", () -> {
            dismissOmnibox();
            QrSheet.showGenerator(this, title, url, () -> copyUrl("网址", url), qrScanCallback);
        });
        qrBtn.tint(AikoTheme.TEXT_MUTED);
        AikoTheme.attachPressFeedback(qrBtn, 0.90f);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        qlp.leftMargin = dp(2);
        card.addView(qrBtn, qlp);

        BrowserIcon shareBtn = icon(SHARE, "分享链接", () -> {
            dismissOmnibox();
            shareLink(url);
        });
        shareBtn.tint(AikoTheme.TEXT_MUTED);
        AikoTheme.attachPressFeedback(shareBtn, 0.90f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(34), dp(34));
        slp.leftMargin = dp(2);
        card.addView(shareBtn, slp);
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
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                        && activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
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
            omniboxOverlay.animate().setListener(null).cancel();
            omniboxOverlay.setLayerType(View.LAYER_TYPE_NONE, null);
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
    private ScrollWebView createWebView(Tab initialTab) {
        ScrollWebView web = new ScrollWebView(this, initialTab);
        SiteFilter filter = filter();

        // Start with dark obsidian bg to eliminate blinding white flash during load!
        web.setBackgroundColor(AikoTheme.BG);
        WebViewPerf.configureWebView(web, false, isLowRam);
        WebViewPerf.applyProfile(web, activeProfile, false, isLowRam, initialTab == current);
        WebViewPerf.setActiveRenderer(web, initialTab == current);
        if (initialTab.muted) WebViewPerf.setAudioMuted(web, true);
        if (displayMode != null) displayMode.attachView(web);

        WebSettings s = web.getSettings();
        initialTab.baseTextZoom = s.getTextZoom();
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
                Tab tab = web.owner;
                Uri uri = request.getUrl();
                String raw = uri == null ? "" : uri.toString();
                boolean allowed = HttpUrlUtil.isHttp(raw) || "about:blank".equalsIgnoreCase(raw);
                if (allowed) {
                    if (tab != null && tab.view == view && request.isForMainFrame() && UserScripts.isUserScriptUrl(raw)) {
                        String ua = view.getSettings().getUserAgentString();
                        closeTransientPopupTabAsync(tab);
                        handleRemoteUserScript(raw, URLUtil.guessFileName(raw, null, "application/javascript"), ua);
                        return true;
                    }
                    if (tab != null && tab.view == view && request.isForMainFrame() && !request.isRedirect()
                            && HttpUrlUtil.isHttp(raw)) {
                        if (tab == current) {
                            captureFormDraft(tab);
                            if (!raw.equals(tab.historyTarget)) prepareFreshNavigation(tab);
                            beginNavigation(tab);
                        }
                        prepareTopLoad(tab, raw, "GET".equals(request.getMethod()) && view.getProgress() >= 100, false);
                    }
                    return false;
                }
                if (tab == current && request.isForMainFrame()) {
                    handleExternalAppIntent(tab, raw, request.hasGesture());
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
                userScripts.clearMenuCommands(view);
                if (tab == current) cachedSniffedCount = 0;
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
                    if (startPageConfig != null && startPageConfig.isBlockWebRtcLeak()) {
                        view.evaluateJavascript(WEBRTC_BLOCK_JS, null);
                    }
                }
                if (tab == current) {
                    if (gestures != null && fullscreen == null && findTarget == null
                            && !isAnyOmniboxFocused() && imeBottom <= insetBottom && !legacyImeOpen) {
                        gestures.showBar(true);
                    }
                    updateChrome();
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && startPageConfig != null && !startPageConfig.getCustomFontFile().isEmpty()) {
                    Uri reqUri = request.getUrl();
                    if (reqUri != null && "aiko-font.local".equals(reqUri.getHost())) {
                        return interceptCustomFontRequest(reqUri.toString());
                    }
                }
                Tab tab = web.owner;
                return tab != null && filter.blocks(tab.filterPage, request) ? SiteFilter.blocked() : null;
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
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
                    if (startPageConfig != null && startPageConfig.isAntiAdblockScriptlets()
                            && filter.enabled(tab.cachedHost)) {
                        view.evaluateJavascript(PageToolkit.antiAdblockScriptlets(tab.cachedHost), null);
                    }
                    if (startPageConfig != null && startPageConfig.isBlockWebRtcLeak()) {
                        view.evaluateJavascript(WEBRTC_BLOCK_JS, null);
                    }
                    applyPageToolkitStyles(tab, view);
                }
                syncNavigationFlags(tab, view, url, true);
                restoreScroll(tab, view, url);
                filter.applyCss(view, tab.cachedHost, url);
                if (isGreasyForkHost(tab.cachedHost)) {
                    // Tell Greasy Fork / Sleazy Fork that Aiko handles .user.js natively so 1-tap Install skips the extension modal
                    view.evaluateJavascript("try{localStorage.setItem('manualOverrideInstallJS','1');}catch(e){}", null);
                }
                long elapsed = finishNavigation(tab, url, "可绘制");
                if (elapsed >= 0) {
                    tab.lastCommitMs = elapsed;
                    tab.lastNavBfCache = wasHistoryNav && elapsed <= 60;
                }
                if (HttpUrlUtil.isHttp(url) && tab == current) {
                    library.visit(url, view.getTitle());
                    scheduleHistory();
                }
                if (tab == current) updateChrome();
            }

            @Override public void onPageFinished(WebView view, String url) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
                if (tab.started > 0) {
                    tab.lastFinishMs = SystemClock.elapsedRealtime() - tab.started;
                } else if (tab.lastCommitMs > 0 && tab.lastFinishMs < tab.lastCommitMs) {
                    tab.lastFinishMs = tab.lastCommitMs;
                }
                syncNavigationFlags(tab, view, url, false);
                restoreScroll(tab, view, url);
                filter.applyCss(view, tab.cachedHost, url);
                if (HttpUrlUtil.isHttp(url)) {
                    // Coalesce secondary helper scripts into a single idle-deferred batch so FCP/LCP & first scroll stay unblocked
                    StringBuilder idleBatch = new StringBuilder(4096);
                    idleBatch.append("(function(){var __run=function(){try{");
                    if (isEffectiveUltraScroll(tab.cachedHost)) {
                        idleBatch.append(SiteExtras.ultraScrollJs(true)).append(';');
                    }
                    idleBatch.append(VideoBooster.fullscreenGestureScript()).append(';');
                    idleBatch.append(PageToolkit.sniffMediaScript()).append(';');
                    boolean trackDraft = startPageConfig != null && startPageConfig.isFormDraftProtection();
                    if (trackDraft) {
                        idleBatch.append(PageToolkit.formDraftHookScript()).append(';');
                        String draft = tab.pendingDraftJson != null ? tab.pendingDraftJson : savedFormDrafts.get(url);
                        tab.pendingDraftJson = null;
                        if (draft != null && !draft.isEmpty()) {
                            idleBatch.append("window.__aikoRestoreDraft&&window.__aikoRestoreDraft(")
                                    .append(JSONObject.quote(draft)).append(");");
                        }
                    }
                    idleBatch.append("}catch(_){}};if(window.requestIdleCallback){requestIdleCallback(__run,{timeout:300});}else{setTimeout(__run,90);}})();");
                    applyPageToolkitStyles(tab, view);
                    view.evaluateJavascript(idleBatch.toString(), null);
                    if (babelUi != null) babelUi.onPageFinished(view, url);
                }
                userScripts.pageFinished(view, url);
                if (tab == current) updateChrome();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Tab tab = web.owner;
                if (tab != null && tab.view == view && request.isForMainFrame()) {
                    tab.historyTarget = null;
                    finishNavigation(tab, request.getUrl().toString(), "加载失败 " + error.getErrorCode());
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
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
                if (view == preloadedNewTabView) {
                    releasePreloadedNewTab();
                    return true;
                }
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return true;
                if (findTarget == view) resetFindBar();
                finishNavigation(tab, tab.url, "渲染进程退出");
                String recoverUrl = tab.url;
                String savedDraft = recoverUrl != null ? savedFormDrafts.get(recoverUrl) : null;
                destroyWeb(tab);
                tab.navigation = null;
                long now = SystemClock.elapsedRealtime();
                boolean canAutoRecover = HttpUrlUtil.isHttp(recoverUrl) && (now - tab.lastCrashRecoveryMs) > 8000L;
                if (tab == current) {
                    if (canAutoRecover) {
                        tab.lastCrashRecoveryMs = now;
                        tab.pendingDraftJson = savedDraft;
                        ensureWebViewAndLoad(tab);
                        toast("渲染进程已自动恢复，已保留阅读位置与表单草稿");
                    } else {
                        showCrashRecovery(tab);
                        tab.progress = 100;
                        updateChrome();
                    }
                } else if (canAutoRecover) {
                    tab.pendingDraftJson = savedDraft;
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
                tab.progress = progress;
                if (tab == current) {
                    updateLoadingBar(progress, true);
                    actionButton.symbol(progress < 100 ? STOP : REFRESH);
                    actionButton.setContentDescription(progress < 100 ? "停止加载" : "刷新");
                }
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                Tab tab = web.owner;
                if (tab == null || tab.view != view) return;
                tab.title = title;
                if (tab == current) {
                    library.updateVisitTitle(view.getUrl(), title);
                    scheduleHistory();
                }
                scheduleSave();
            }

            @Override public void onReceivedIcon(WebView view, Bitmap image) {
                Tab tab = web.owner;
                if (tab != null && tab.view == view && image != null && image.getWidth() <= 128 && image.getHeight() <= 128) {
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
                applyInsetsLayout();
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                viewport.setVisibility(View.GONE);
                setImmersiveSystemBars(true);
                if (displayMode != null) displayMode.setFullscreenVideo(true);
            }

            @Override public void onHideCustomView() { exitFullscreen(); }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (!HttpUrlUtil.isHttp(url)) {
                toast("此链接不是可直接下载的 HTTP 或 HTTPS 文件");
                return;
            }
            String guessed = URLUtil.guessFileName(url, disposition, mime);
            if (isUserScriptDownload(url, disposition, guessed)) {
                closeTransientPopupTabAsync(web.owner);
                handleRemoteUserScript(url, guessed, ua);
            } else {
                showNewDownload(url, guessed, ua);
            }
        });

        web.setOnTouchListener((v, event) -> {
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                Tab tab = web.owner;
                if (tab != null && tab.pendingScroll != null) setScrollTarget(tab, null);
                if (isAnyOmniboxFocused()) hideKeyboardIfNeeded();
                triggerAdpfBoost(900L);
            } else if (act == MotionEvent.ACTION_UP) {
                triggerAdpfBoost(1200L);
            }
            return false;
        });

        web.setOnLongClickListener(v -> handleWebLongPress(web));
        userScripts.attach(web);
        if (babelUi != null) babelUi.bindWebView(web);
        return web;
    }

    private void applyPageToolkitStyles(Tab tab, WebView view) {
        if (tab == null || view == null || !HttpUrlUtil.isHttp(tab.url)) return;
        String host = tab.cachedHost;
        SiteProfile sp = siteProfile(host);
        String siteCss = (host != null && siteExtras != null) ? siteExtras.customCss(host) : "";
        String fontCss = buildCustomFontCss();
        int minFont = startPageConfig != null ? startPageConfig.getMinFontSizePx() : 0;
        boolean forceZoom = sp.forceZoom || (startPageConfig != null && startPageConfig.isForcePinchZoom());
        if (!siteCss.isEmpty() || !fontCss.isEmpty() || minFont > 0 || forceZoom) {
            view.evaluateJavascript(PageToolkit.customCssAndFontScript(siteCss, fontCss, minFont, forceZoom), null);
        }
        if (sp.unlockCopy) {
            view.evaluateJavascript(PageToolkit.unlockCopyScript(), null);
        }
    }

    private String buildCustomFontCss() {
        if (startPageConfig == null) return "";
        File fontFile = startPageConfig.resolveCustomFontFile(this);
        if (fontFile == null || !fontFile.isFile()) return "";
        String fileName = fontFile.getName();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String fmt = lower.endsWith(".otf") ? "opentype" : (lower.endsWith(".woff2") ? "woff2" : "truetype");
        return "@font-face{font-family:'AikoUserFont';src:url('" + CUSTOM_FONT_URL_PREFIX + fileName
                + "') format('" + fmt + "');font-display:swap;}\n"
                + "body,p,div,span,li,article,a,h1,h2,h3,h4,h5,h6,td,th,blockquote{font-family:'AikoUserFont',sans-serif!important;}";
    }

    private WebResourceResponse interceptCustomFontRequest(String url) {
        if (startPageConfig == null) return null;
        File fontFile = startPageConfig.resolveCustomFontFile(this);
        if (fontFile == null || !fontFile.isFile()) return null;
        String expected = CUSTOM_FONT_URL_PREFIX + fontFile.getName();
        if (!expected.equals(url)) return null;
        try {
            String lower = fontFile.getName().toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".otf") ? "font/otf"
                    : (lower.endsWith(".woff2") ? "font/woff2" : "font/ttf");
            Map<String, String> headers = new HashMap<>(4);
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "public, max-age=31536000");
            return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(fontFile));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void captureFormDraft(Tab tab) {
        if (tab == null || tab.view == null || !HttpUrlUtil.isHttp(tab.url)) return;
        if (startPageConfig == null || !startPageConfig.isFormDraftProtection()) return;
        final String targetUrl = tab.url;
        try {
            tab.view.evaluateJavascript(
                    "(function(){if(window.__aikoDraftDirty&&window.__aikoDraftDirty()&&window.__aikoCollectDraft)return window.__aikoCollectDraft();return '';})();",
                    raw -> {
                        try {
                            if (raw == null || "null".equals(raw)) return;
                            Object tok = new JSONTokener(raw).nextValue();
                            if (tok instanceof String) {
                                String json = ((String) tok).trim();
                                if (!json.isEmpty() && json.contains("\"fields\":[{")) {
                                    savedFormDrafts.put(targetUrl, json);
                                }
                            }
                        } catch (Exception ignored) { }
                    });
        } catch (RuntimeException ignored) { }
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
        msg.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(msg, new LinearLayout.LayoutParams(-1, -2));
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

    /** Super Link & Image Material Context Menu with Arc Peek, Private Open, QR, Reverse Search & Babel. */
    private boolean handleWebLongPress(WebView web) {
        if (elementPicker != null && elementPicker.isActive()) return false;
        WebView.HitTestResult hit = web.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        String extra = hit.getExtra();

        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE && HttpUrlUtil.isHttp(extra)) {
            Message msg = handler.obtainMessage();
            msg.setTarget(new Handler(Looper.getMainLooper()) {
                @Override public void handleMessage(Message reply) {
                    if (isDestroyed() || isFinishing()) return;
                    Bundle data = reply.getData();
                    String title = data == null ? "" : data.getString("title", "");
                    String href = data == null ? extra : data.getString("url", extra);
                    showSuperLongPressSheet(HttpUrlUtil.isHttp(href) ? href : extra, null, title, type);
                }
            });
            web.requestFocusNodeHref(msg);
            return true;
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE && (HttpUrlUtil.isHttp(extra)
                || (extra != null && extra.startsWith("data:image/")))) {
            showSuperLongPressSheet(null, extra, "", type);
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
                    String title = data == null ? "" : data.getString("title", "");
                    String validHref = HttpUrlUtil.isHttp(href) ? href : null;
                    String validSrc = HttpUrlUtil.isHttp(src) ? src : imgUrl;
                    if (validHref != null || validSrc != null) {
                        showSuperLongPressSheet(validHref, validSrc, title, type);
                    }
                }
            });
            web.requestFocusNodeHref(msg);
            return true;
        }
        return false;
    }

    private void showSuperLongPressSheet(String linkUrl, String imageUrl, String extraText, int hitType) {
        LongPressMenuSheet.Target target = new LongPressMenuSheet.Target(linkUrl, imageUrl, extraText, hitType);
        LongPressMenuSheet.show(this, target, displayMode, new LongPressMenuSheet.Callbacks() {
            @Override public void openInBackgroundTab(String url) { queueTab(url); }
            @Override public void openInPeekSheet(String url) { AikoV040Ui.showPeekSheet(v040Host, url); }
            @Override public void openInPrivate(String url) {
                Intent i = new Intent(MainActivity.this, PrivateActivity.class);
                if (HttpUrlUtil.isHttp(url)) i.putExtra(PrivateActivity.EXTRA_INITIAL_URL, url);
                startActivity(i);
            }
            @Override public void copyText(String labelText, String text) {
                if (HttpUrlUtil.isHttp(text)) copyUrl(labelText, text);
                else MainActivity.this.copyText(labelText, text, "已复制" + labelText);
            }
            @Override public void shareUrl(String url) { shareLink(url); }
            @Override public void showQr(String title, String url) {
                QrSheet.showGenerator(MainActivity.this, title, url, () -> copyUrl("链接", url), qrScanCallback);
            }
            @Override public void saveImage(String imgUrl) {
                showNewDownload(imgUrl, URLUtil.guessFileName(imgUrl, null, "image/*"), null);
            }
            @Override public void reverseImageSearch(String engineName, String searchUrl) {
                toast("正在使用 " + engineName + " 以图搜图…");
                addTab(searchUrl, false);
            }
            @Override public void downloadLink(String url) {
                showNewDownload(url, URLUtil.guessFileName(url, null, null), null);
            }
            @Override public void translateLinkText(String text) {
                if (text == null || text.trim().isEmpty() || babelEngine == null || babelUi == null) return;
                toast("巴别塔翻译中…");
                babelEngine.translateSingleAsync("longpress", 0L, text.trim(), babelConfig.getTargetLang(), res ->
                        handler.post(() -> {
                            if (isDestroyed()) return;
                            if (res.isSuccess()) {
                                babelUi.showQuickSelectionResult(current != null ? current.view : null,
                                        text.trim(), res.translated);
                            } else {
                                toast("翻译失败：" + (res.error == null ? "请检查网络" : res.error));
                            }
                        }));
            }
        });
    }

    /** App-Jump Deep-Link Guard honoring global policy + per-host allow/block rules with Material sheet. */
    private void handleExternalAppIntent(Tab tab, String rawUrl, boolean hasUserGesture) {
        if (rawUrl == null || rawUrl.isEmpty()) return;
        String lower = rawUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("file:") || lower.startsWith("content:")) {
            return;
        }
        String pageHost = tab != null ? tab.cachedHost : null;
        boolean isAppJump = StartPageConfig.isExternalAppScheme(rawUrl);
        if (isAppJump && startPageConfig != null) {
            int decision = startPageConfig.effectiveAppJumpDecision(pageHost);
            if (decision == StartPageConfig.HOST_POLICY_BLOCK) {
                if (hasUserGesture) toast("已拦截跳转：" + StartPageConfig.friendlyAppName(rawUrl));
                return;
            }
            if (!hasUserGesture && decision != StartPageConfig.HOST_POLICY_ALLOW) {
                // Silently block background script-initiated app-jump popups!
                return;
            }
        } else if (!hasUserGesture) {
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

        Runnable launchAction = () -> {
            try {
                startActivity(safeIntent);
            } catch (RuntimeException err) {
                if (fallback != null) navigate(fallback);
                else toast("未找到可处理此链接的应用");
            }
        };

        if (isAppJump && startPageConfig != null
                && startPageConfig.effectiveAppJumpDecision(pageHost) == StartPageConfig.HOST_POLICY_ALLOW) {
            launchAction.run();
            return;
        }

        String scheme = StartPageConfig.extractScheme(rawUrl);
        String appName = StartPageConfig.friendlyAppName(rawUrl);
        AikoV040Ui.showAppJumpSheet(this, displayMode, density, pageHost, appName, scheme, rawUrl,
                launchAction,
                () -> {
                    if (startPageConfig != null && pageHost != null) {
                        startPageConfig.setHostAppJumpPolicy(pageHost, StartPageConfig.HOST_POLICY_ALLOW);
                    }
                    launchAction.run();
                },
                () -> {
                    if (startPageConfig != null && pageHost != null) {
                        startPageConfig.setHostAppJumpPolicy(pageHost, StartPageConfig.HOST_POLICY_BLOCK);
                    }
                    toast("已记住拦截此站点的外部应用跳转");
                });
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
        String trimmed = raw.trim();
        BangCatalog.SlashCmd cmd = BangCatalog.resolveSlashCommand(trimmed);
        if (cmd != null) {
            dismissOmnibox();
            executeSlashCommand(cmd);
            return;
        }
        if (bangCatalog != null) {
            BangCatalog.Match bang = bangCatalog.resolveBang(trimmed);
            if (bang != null && HttpUrlUtil.isHttp(bang.url)) {
                toast("⚡ " + bang.label);
                navigate(bang.url);
                return;
            }
        }
        if ("about:blank".equalsIgnoreCase(trimmed)) {
            navigate("about:blank");
            return;
        }
        String url = SearchEngines.resolveInput(this, trimmed);
        if (HttpUrlUtil.isHttp(url) && UserScripts.isUserScriptUrl(url)) {
            hideOmniboxOverlay();
            hideKeyboardIfNeeded();
            handleRemoteUserScript(url, URLUtil.guessFileName(url, null, "application/javascript"), null);
            return;
        }
        navigate(url);
    }

    private void executeSlashCommand(BangCatalog.SlashCmd cmd) {
        if (cmd == null) return;
        Tab tab = current;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(tab.url);
        switch (cmd) {
            case BABEL:
                babelUi.showSheet(pageOpen ? tab.view : null, pageOpen ? tab.url : null);
                break;
            case DEVTOOLS:
                if (pageOpen) {
                    WebDevTools.toggle(tab.view);
                    toast("已切换网页开发者调试台");
                } else {
                    toast("请先打开网页后再启动调试台");
                }
                break;
            case GALLERY:
                openGalleryForCurrent();
                break;
            case LONGSHOT:
                captureLongShotForCurrent();
                break;
            case QR:
                if (pageOpen) QrSheet.showGenerator(this, tab.title, tab.url, () -> copyUrl("网址", tab.url), qrScanCallback);
                else QrSheet.showScannerHintOrImageDecode(this, qrScanCallback);
                break;
            case BACKUP:
                AikoV040Ui.showBackupSheet(v040Host);
                break;
            case UNLOCK:
                if (pageOpen) {
                    tab.view.evaluateJavascript(PageToolkit.unlockCopyScript(), null);
                    toast("已解锁当前页面复制与选择限制");
                } else {
                    toast("请先打开网页");
                }
                break;
            case DARK: {
                boolean next = !siteExtras.isGlobalDarkWeb();
                siteExtras.setGlobalDarkWeb(next);
                refreshAllTabsDarkMode();
                toast(next ? "已开启全局网页暗黑模式" : "已关闭全局网页暗黑模式");
                break;
            }
            case CLEAN:
                library.clearHistory();
                toast("已清理本应用浏览历史");
                break;
            case SETTINGS:
                AikoV040Ui.showCustomizationSheet(v040Host);
                break;
            default:
                break;
        }
    }

    private void navigate(String url) {
        if (!isNavigableUrl(url)) return;
        clearCrashOverlay();
        if (elementPicker != null && elementPicker.isActive()) elementPicker.stop();
        closeFind();
        hideOmniboxOverlay();
        hideKeyboardIfNeeded();
        if (gestures != null) gestures.showBar(false);
        captureFormDraft(current);
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
        WebViewPerf.setActivePreRaster(current.view, activeProfile.activePreRaster && !isLowRam);
        if (displayMode != null) {
            displayMode.attachView(current.view);
            displayMode.setSiteForceMaxHz(siteExtras.isForceMaxHz(current.cachedHost));
        }
        if (HttpUrlUtil.isHttp(url)) {
            prepareTopLoad(current, url, true, true);
        }
        current.view.loadUrl(url);
        applyInsetsLayout();
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
        if (injectUltraScrollNow && isEffectiveUltraScroll(host)) {
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
        Tab owner;

        ScrollWebView(android.content.Context context, Tab owner) {
            super(context);
            this.owner = owner;
        }

        void setOwner(Tab next) {
            this.owner = next;
        }

        int maxScrollY() {
            return Math.max(0, computeVerticalScrollRange() - computeVerticalScrollExtent());
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (owner == current && gestures != null && !onHome
                    && fullscreen == null && findTarget == null
                    && imeBottom <= insetBottom && !legacyImeOpen
                    && !isAnyOmniboxFocused()) {
                gestures.onPageScroll(t, oldt, maxScrollY());
                int vh = getHeight();
                if (vh > 0 && t > vh * 2 && oldt - t > dp(10)) {
                    showScrollTopPill();
                } else if (t < vh || t - oldt > dp(24)) {
                    hideScrollTopPill();
                }
            }
        }
    }

    private final class BottomDeckLayout extends FrameLayout {
        private float downX, downY;

        BottomDeckLayout() { super(MainActivity.this); }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (gestures == null) return super.onInterceptTouchEvent(ev);
            boolean allowHoriz = startPageConfig == null || startPageConfig.isSwipeBarSwitchTabs();
            boolean allowUp = startPageConfig == null || startPageConfig.isSwipeUpForTabs();
            if (!allowHoriz && !allowUp) return super.onInterceptTouchEvent(ev);
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                downX = ev.getRawX();
                downY = ev.getRawY();
                return gestures.onBottomBarIntercept(ev);
            }
            if (act == MotionEvent.ACTION_MOVE) {
                float adx = Math.abs(ev.getRawX() - downX);
                float ady = Math.abs(ev.getRawY() - downY);
                if (!allowHoriz && adx > ady) return false;
                if (!allowUp && (ev.getRawY() - downY) < 0 && ady > adx) return false;
            }
            if (gestures.onBottomBarIntercept(ev)) return true;
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
        if (tab == current && visible) {
            tab.priorityTicket = DownloadService.PACER.protect(1350);
            triggerAdpfBoost(1400L);
        }
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
            hudPill.setBackground(AikoTheme.card((AikoTheme.BG & 0x00FFFFFF) | 0xCC000000, AikoTheme.STROKE, density, dp(12)));
            hudPill.setElevation(dp(10));
            hudPill.setText("185Hz · 监测中");
            hudPill.setOnClickListener(v -> {
                if (displayMode != null && displayMode.isOemCapped()) {
                    DisplayModeEngine.openRefreshRateSettings(this);
                } else {
                    showPerformanceCenter();
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
        if (homeButton != null) {
            homeButton.setVisibility(!editing && startPageConfig != null && startPageConfig.isShowHomeButton()
                    ? View.VISIBLE : View.GONE);
        }
        if (babelButton != null) {
            boolean showBabel = !editing && !onHome && startPageConfig != null && startPageConfig.isShowBabelButton();
            babelButton.setVisibility(showBabel ? View.VISIBLE : View.GONE);
            boolean translating = babelUi != null && babelUi.isTranslating(current.view);
            babelButton.tint(translating ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED);
        }
        if (menuButton != null) {
            boolean hasMenuCmds = current.view != null && userScripts != null
                    && !userScripts.menuCommandsFor(current.view).isEmpty();
            menuButton.tint(hasMenuCmds ? AikoTheme.ACCENT_CYAN : AikoTheme.TEXT);
        }
        updateAddress();
        tabButton.count(tabs.size());
        tabButton.setContentDescription("标签页，" + tabs.size() + " 个，长按新建");
        boolean canBack = current.view != null && current.canGoBack;
        backButton.tint(canBack ? AikoTheme.TEXT : AikoTheme.TEXT_DIM);
        updateLoadingBar(current.progress, false);
        actionButton.symbol(current.progress < 100 ? STOP : REFRESH);
        actionButton.setContentDescription(current.progress < 100 ? "停止加载" : "刷新");
        if (gestures != null) {
            gestures.updateExclusionRects(page, current.canGoBack, current.canGoForward);
        }
        scheduleAutoRefreshForCurrent();
    }

    private void scheduleAutoRefreshForCurrent() {
        handler.removeCallbacks(autoRefreshTask);
        activeAutoRefreshSec = 0;
        if (!visible || isDestroyed() || onHome || current == null || current.view == null
                || current.cachedHost == null || siteExtras == null) {
            return;
        }
        int sec = siteExtras.autoRefreshSec(current.cachedHost);
        if (sec > 0) {
            activeAutoRefreshSec = sec;
            handler.postDelayed(autoRefreshTask, sec * 1000L);
        }
    }

    private void runAutoRefreshTick() {
        if (!visible || isDestroyed() || onHome || current == null || current.view == null
                || activeAutoRefreshSec <= 0) {
            return;
        }
        if (!isAnyOmniboxFocused() && fullscreen == null && current.progress >= 100) {
            reloadPreservingScroll();
        }
        scheduleAutoRefreshForCurrent();
    }

    private void updateLoadingBar(int progress, boolean animateFade) {
        if (loading == null) return;
        boolean active = progress < 100 && !onHome;
        if (active) {
            loading.animate().setListener(null).cancel();
            loading.setLayerType(View.LAYER_TYPE_NONE, null);
            loading.setAlpha(1f);
            if (loading.getVisibility() != View.VISIBLE) loading.setVisibility(View.VISIBLE);
            loading.setProgress(Math.max(progress, 4), true);
        } else {
            loading.setProgress(100, animateFade);
            if (loading.getVisibility() == View.VISIBLE) {
                if (animateFade && loading.getAlpha() > 0.05f) {
                    loading.animate().setListener(null).cancel();
                    loading.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .setInterpolator(AikoTheme.FAST_OUT)
                            .withLayer()
                            .setListener(new AnimatorListenerAdapter() {
                                private boolean cancelled;
                                @Override public void onAnimationCancel(Animator a) {
                                    cancelled = true;
                                    loading.setLayerType(View.LAYER_TYPE_NONE, null);
                                    loading.animate().setListener(null);
                                }
                                @Override public void onAnimationEnd(Animator a) {
                                    loading.setLayerType(View.LAYER_TYPE_NONE, null);
                                    loading.animate().setListener(null);
                                    if (!cancelled) loading.setVisibility(View.INVISIBLE);
                                }
                            })
                            .start();
                } else {
                    loading.animate().setListener(null).cancel();
                    loading.setLayerType(View.LAYER_TYPE_NONE, null);
                    loading.setAlpha(0f);
                    loading.setVisibility(View.INVISIBLE);
                }
            }
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

    // ==================== TABS SHEET (LIST / GRID + SEARCH + PIN + GROUPS) ====================

    private void showTabs() {
        final boolean[] gridMode = {startPageConfig != null && startPageConfig.isTabGridView()};
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "标签页 · " + tabs.size(), dialog::dismiss);

        LinearLayout quick = sheetRow(panel);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> openNewTabDefault(false));
        View recent = sheetAction(dialog, quick, HISTORY, "最近关闭", !closedTabs.isEmpty(), this::showClosedTabs);
        sheetAction(dialog, quick, PRIVATE, "无痕窗口", true, () -> startActivity(new Intent(this, PrivateActivity.class)));
        TextView viewToggle = AikoTheme.badge(this, density, gridMode[0] ? "列表" : "网格",
                AikoTheme.ACCENT_CYAN, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        viewToggle.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(viewToggle, 0.94f);
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(dp(56), dp(64));
        vtlp.leftMargin = dp(4);
        quick.addView(viewToggle, vtlp);

        // Tab Search bar when >= 4 tabs
        final String[] searchQuery = {""};
        EditText tabSearch = input("搜索标签标题、域名或分组…");
        tabSearch.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(14)));
        tabSearch.setPadding(dp(12), 0, dp(12), 0);
        tabSearch.setVisibility(tabs.size() >= 4 ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(-1, dp(40));
        tslp.topMargin = dp(6);
        panel.addView(tabSearch, tslp);

        final ArrayList<Tab> filteredTabs = new ArrayList<>();
        Runnable rebuildFiltered = () -> {
            filteredTabs.clear();
            String q = searchQuery[0].toLowerCase(Locale.ROOT);
            // Pinned tabs first, then regular tabs
            for (int pass = 0; pass < 2; pass++) {
                boolean wantPinned = (pass == 0);
                for (Tab t : tabs) {
                    if (t.pinned != wantPinned) continue;
                    if (!q.isEmpty()) {
                        String ttl = t.title == null ? "" : t.title.toLowerCase(Locale.ROOT);
                        String u = t.url == null ? "" : t.url.toLowerCase(Locale.ROOT);
                        String grp = t.groupName == null ? "" : t.groupName.toLowerCase(Locale.ROOT);
                        if (!ttl.contains(q) && !u.contains(q) && !grp.contains(q)) continue;
                    }
                    filteredTabs.add(t);
                }
            }
        };
        rebuildFiltered.run();

        FrameLayout contentHolder = new FrameLayout(this);
        int maxH = Math.min(430, Math.max(180, getResources().getConfiguration().screenHeightDp - 230));
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, dp(maxH));
        chlp.topMargin = dp(6);
        panel.addView(contentHolder, chlp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        contentHolder.addView(list, new FrameLayout.LayoutParams(-1, -1));

        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        contentHolder.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        final BaseAdapter[] listAdapterRef = new BaseAdapter[1];
        final BaseAdapter[] gridAdapterRef = new BaseAdapter[1];
        final Runnable refreshTabsUi = () -> {
            rebuildFiltered.run();
            if (listAdapterRef[0] != null) listAdapterRef[0].notifyDataSetChanged();
            if (gridAdapterRef[0] != null) gridAdapterRef[0].notifyDataSetChanged();
            boolean hasClosed = !closedTabs.isEmpty();
            recent.setEnabled(hasClosed);
            recent.setFocusable(hasClosed);
            recent.setAlpha(hasClosed ? 1f : 0.38f);
            tabSearch.setVisibility(tabs.size() >= 4 ? View.VISIBLE : View.GONE);
        };

        listAdapterRef[0] = new BaseAdapter() {
            @Override public int getCount() { return filteredTabs.size(); }
            @Override public Object getItem(int index) { return filteredTabs.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                TabRow row = recycled instanceof TabRow ? (TabRow) recycled : new TabRow(dialog, refreshTabsUi);
                row.bind(filteredTabs.get(index));
                return row;
            }
        };
        list.setAdapter(listAdapterRef[0]);

        gridAdapterRef[0] = new BaseAdapter() {
            @Override public int getCount() { return filteredTabs.size(); }
            @Override public Object getItem(int index) { return filteredTabs.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                return buildTabGridCard(filteredTabs.get(index), dialog, refreshTabsUi);
            }
        };
        grid.setAdapter(gridAdapterRef[0]);

        Runnable syncViewMode = () -> {
            list.setVisibility(gridMode[0] ? View.GONE : View.VISIBLE);
            grid.setVisibility(gridMode[0] ? View.VISIBLE : View.GONE);
            viewToggle.setText(gridMode[0] ? "列表" : "网格");
        };
        syncViewMode.run();

        viewToggle.setOnClickListener(v -> {
            gridMode[0] = !gridMode[0];
            if (startPageConfig != null) startPageConfig.setTabGridView(gridMode[0]);
            syncViewMode.run();
        });

        tabSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery[0] = s == null ? "" : s.toString().trim();
                refreshTabsUi.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        showSheet(dialog, panel);
        int curIdx = filteredTabs.indexOf(current);
        if (curIdx >= 0) {
            list.setSelection(curIdx);
            grid.setSelection(curIdx);
        }
    }

    private View buildTabGridCard(Tab tab, Dialog parentDialog, Runnable onRefresh) {
        boolean isCurrent = (tab == current);
        LinearLayout card = stack();
        card.setPadding(dp(11), dp(10), dp(8), dp(10));
        card.setBackground(AikoTheme.card(
                isCurrent ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                isCurrent ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                density, dp(15)));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
        card.setLayoutParams(new AbsListView.LayoutParams(-1, dp(104)));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        String host = tab.cachedHost;
        String cleanHost = stripSubdomainPrefix(host);
        String letter = cleanHost.isEmpty() ? "A" : cleanHost.substring(0, 1).toUpperCase(Locale.ROOT);
        TextView badge = AikoTheme.badge(this, density, tab.pinned ? "📌" : letter,
                AikoTheme.ACCENT, AikoTheme.CAPSULE, AikoTheme.DIVIDER);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        top.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));

        if (tab.groupName != null && !tab.groupName.isEmpty()) {
            TextView grp = AikoTheme.badge(this, density, tab.groupName,
                    AikoTheme.ACCENT_CYAN, AikoTheme.BG, AikoTheme.STROKE);
            grp.setTextSize(9);
            grp.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.leftMargin = dp(5);
            top.addView(grp, glp);
        }

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        BrowserIcon close = icon(CLOSE, "关闭标签", () -> {
            closeTab(tab);
            onRefresh.run();
        });
        close.tint(AikoTheme.TEXT_DIM);
        top.addView(close, new LinearLayout.LayoutParams(dp(28), dp(28)));

        String caption = tab.title;
        if (caption == null || caption.isEmpty() || caption.equals(tab.url)) caption = host == null ? "新标签" : host;
        TextView ttl = label(caption, 13, AikoTheme.TEXT);
        ttl.setTypeface(AikoTheme.MEDIUM);
        ttl.setMaxLines(2);
        ttl.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1);
        tlp.topMargin = dp(6);
        card.addView(ttl, tlp);

        String sub = host == null ? "起始页" : host;
        if (tab.muted) sub += " · 静音";
        TextView dom = label(sub, 10, isCurrent ? AikoTheme.ACCENT_CYAN : AikoTheme.TEXT_DIM);
        dom.setSingleLine(true);
        dom.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(dom);

        card.setOnClickListener(v -> {
            parentDialog.dismiss();
            showTab(tab, false);
        });
        card.setOnLongClickListener(v -> {
            showTabOptionsSheet(tab, parentDialog, onRefresh);
            return true;
        });
        return card;
    }

    private void showTabOptionsSheet(Tab tab, Dialog tabsDialog, Runnable onRefresh) {
        if (tab == null) return;
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        String name = (tab.title == null || tab.title.isEmpty()) ? (tab.cachedHost == null ? "新标签" : tab.cachedHost) : tab.title;
        LinearLayout panel = AikoTheme.sheetContainer(this, density,
                name.length() > 22 ? name.substring(0, 22) + "…" : name, d::dismiss);

        addTwoColRow(panel,
                AikoTheme.toggleTile(this, density, PIN, tab.pinned ? "取消置顶" : "置顶标签",
                        tab.pinned ? "常驻内存防休眠" : "固定在顶部且不休眠",
                        tab.pinned, true, AikoTheme.ACCENT, () -> {
                            tab.pinned = !tab.pinned;
                            scheduleSave();
                            d.dismiss();
                            if (onRefresh != null) onRefresh.run();
                            toast(tab.pinned ? "已置顶此标签（受保护不休眠）" : "已取消标签置顶");
                        }),
                AikoTheme.toggleTile(this, density, COPY, "复制网址",
                        HttpUrlUtil.isHttp(tab.url) ? tab.cachedHost : "无网页链接",
                        false, HttpUrlUtil.isHttp(tab.url), AikoTheme.ACCENT_CYAN, () -> {
                            d.dismiss();
                            copyUrl("网址", tab.url);
                        }));

        panel.addView(AikoTheme.sectionHeader(this, density, "标签分组徽章"));
        String[] presets = {"无分组", "工作", "阅读", "技术", "影音", "购物"};
        FlowTagLayout grpFlow = new FlowTagLayout(this, dp(6), dp(6));
        for (String g : presets) {
            String targetVal = "无分组".equals(g) ? "" : g;
            boolean sel = targetVal.equals(tab.groupName == null ? "" : tab.groupName);
            TextView pill = AikoTheme.badge(this, density, g,
                    sel ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED,
                    sel ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    sel ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
            pill.setOnClickListener(v -> {
                tab.groupName = targetVal;
                scheduleSave();
                d.dismiss();
                if (onRefresh != null) onRefresh.run();
                toast(targetVal.isEmpty() ? "已移除标签分组" : "已归入分组：" + targetVal);
            });
            grpFlow.addView(pill);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.bottomMargin = dp(10);
        panel.addView(grpFlow, glp);

        TextView closeBtn = AikoTheme.badge(this, density, "关闭此标签", AikoTheme.CORAL, AikoTheme.CARD, AikoTheme.STROKE);
        closeBtn.setOnClickListener(v -> {
            d.dismiss();
            closeTab(tab);
            if (onRefresh != null) onRefresh.run();
        });
        panel.addView(closeBtn, new LinearLayout.LayoutParams(-1, dp(40)));
        showSheet(d, panel);
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
        private final LinearLayout card, link;
        private final View activeBar;
        private final ImageView image;
        private final TextView fallback, title, domain;
        private final BrowserIcon close;
        private final GradientDrawable selectedBg, normalBg;
        private Tab bound;

        TabRow(Dialog dialog, Runnable onClose) {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(2), dp(4), dp(2), dp(4));
            setLayoutParams(new AbsListView.LayoutParams(-1, dp(68)));

            selectedBg = AikoTheme.card(AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT, density, dp(16));
            normalBg = AikoTheme.card(AikoTheme.CARD, AikoTheme.DIVIDER, density, dp(16));

            card = new LinearLayout(MainActivity.this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(6), 0, dp(4), 0);
            addView(card, new LinearLayout.LayoutParams(-1, -1));

            activeBar = new View(MainActivity.this);
            activeBar.setBackground(AikoTheme.rounded(AikoTheme.ACCENT, dp(2)));
            LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(dp(3), dp(24));
            ablp.leftMargin = dp(2);
            ablp.rightMargin = dp(6);
            card.addView(activeBar, ablp);

            link = new LinearLayout(MainActivity.this);
            link.setGravity(Gravity.CENTER_VERTICAL);
            link.setPadding(dp(2), 0, dp(4), 0);
            link.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            AikoTheme.attachPressFeedback(link, 0.97f);
            link.setOnClickListener(v -> {
                if (bound != null) {
                    dialog.dismiss();
                    showTab(bound, false);
                }
            });
            link.setOnLongClickListener(v -> {
                if (bound != null) showTabOptionsSheet(bound, dialog, onClose);
                return true;
            });
            card.addView(link, new LinearLayout.LayoutParams(0, -1, 1));

            FrameLayout tile = new FrameLayout(MainActivity.this);
            tile.setBackground(AikoTheme.card(AikoTheme.CAPSULE, AikoTheme.DIVIDER, density, dp(10)));
            link.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));

            fallback = label("", 14, AikoTheme.ACCENT);
            fallback.setTypeface(AikoTheme.BOLD);
            fallback.setGravity(Gravity.CENTER);
            tile.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout text = stack();
            text.setPadding(dp(11), 0, dp(4), 0);
            link.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

            title = label("", 14, AikoTheme.TEXT);
            title.setTypeface(AikoTheme.MEDIUM);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);

            domain = label("", 11, AikoTheme.TEXT_MUTED);
            domain.setSingleLine(true);
            domain.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = dp(2);
            text.addView(domain, dlp);

            close = icon(CLOSE, "关闭标签", () -> {
                if (bound != null) {
                    closeTab(bound);
                    onClose.run();
                }
            });
            close.tint(AikoTheme.TEXT_DIM);
            AikoTheme.attachPressFeedback(close, 0.90f);
            card.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }

        void bind(Tab tab) {
            bound = tab;
            String host = tab.cachedHost;
            String caption = tab.title;
            if (caption == null || caption.isEmpty() || caption.equals(tab.url)) {
                caption = host == null ? "新标签" : host;
            }
            boolean isCurrent = tab == current;
            String prefix = tab.pinned ? "📌 " : "";
            String groupTag = (tab.groupName != null && !tab.groupName.isEmpty()) ? ("[" + tab.groupName + "] ") : "";
            String finalCaption = prefix + groupTag + caption;
            if (!TextUtils.equals(title.getText(), finalCaption)) title.setText(finalCaption);

            String sub = isCurrent
                    ? (host == null ? "起始页 · 当前" : host + (tab.muted ? "  ·  已静音  ·  当前" : "  ·  当前"))
                    : (host == null ? "起始页" : (tab.muted ? host + "  ·  已静音" : host));
            if (!TextUtils.equals(domain.getText(), sub)) domain.setText(sub);
            domain.setTextColor(isCurrent ? AikoTheme.CYAN : AikoTheme.TEXT_MUTED);
            domain.setVisibility(View.VISIBLE);

            boolean useIcon = host != null && host.equals(tab.faviconHost) && tab.favicon != null;
            image.setImageBitmap(useIcon ? tab.favicon : null);
            fallback.setVisibility(useIcon ? View.GONE : View.VISIBLE);
            if (!useIcon) {
                String cleanHost = stripSubdomainPrefix(host);
                String letter = cleanHost.isEmpty() ? "A" : cleanHost.substring(0, 1).toUpperCase(Locale.ROOT);
                if (!TextUtils.equals(fallback.getText(), letter)) fallback.setText(letter);
            }
            activeBar.setVisibility(isCurrent ? View.VISIBLE : View.INVISIBLE);
            card.setBackground(isCurrent ? selectedBg : normalBg);
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
        // Original flagship feature: zero-WebView Chromium prefetch for background tab (honors Eco preload off)
        if (activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF) {
            WebViewPerf.prefetchBackgroundUrl(this, url, siteFilter);
        }
        updateChrome();
        scheduleSave();
        toast(activeProfile.preloadLevel > PerfProfiles.PRELOAD_OFF ? "已加入后台标签并静默预热" : "已加入后台标签");
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
        int maxH = Math.min(490, Math.max(240, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Speed X-Ray Telemetry Card
        LinearLayout xray = stack();
        xray.setPadding(dp(16), dp(14), dp(16), dp(14));
        xray.setBackground(AikoTheme.glowBanner(dp(18)));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, -2);
        xlp.topMargin = dp(4);
        body.addView(xray, xlp);

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
                            saveSiteProfile(host, profile.withDesktop(!profile.desktop));
                            if (sameOpenSite(tab, host)) reloadPreservingScroll();
                        }));

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, IMAGE, "网络图片", profile.blockImages ? "已停载" : "正常加载",
                        profile.blockImages, tab.view != null, AikoTheme.AMBER, () -> {
                            dialog.dismiss();
                            SiteProfile chosen = profile.withBlockImages(!profile.blockImages);
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

        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, UNLOCK, "解除禁止复制", profile.unlockCopy ? "已解锁选择/复制" : "默认",
                        profile.unlockCopy, tab.view != null, AikoTheme.EMERALD, () -> {
                            dialog.dismiss();
                            boolean next = !profile.unlockCopy;
                            saveSiteProfile(host, profile.withFlag(SiteProfile.UNLOCK_COPY, next));
                            if (sameOpenSite(tab, host) && tab.view != null) {
                                if (next) tab.view.evaluateJavascript(PageToolkit.unlockCopyScript(), null);
                                else reloadPreservingScroll();
                            }
                            toast(next ? "已解除本站复制/选字限制" : "已恢复本站默认复制策略");
                        }),
                AikoTheme.toggleTile(this, density, FONT, "强制双指缩放", profile.forceZoom ? "已强制允许缩放" : "跟随网页",
                        profile.forceZoom, tab.view != null, AikoTheme.ACCENT_CYAN, () -> {
                            dialog.dismiss();
                            boolean next = !profile.forceZoom;
                            saveSiteProfile(host, profile.withFlag(SiteProfile.FORCE_ZOOM, next));
                            if (sameOpenSite(tab, host) && tab.view != null) {
                                if (next) applyPageToolkitStyles(tab, tab.view);
                                else reloadPreservingScroll();
                            }
                            toast(next ? "本站已强制允许双指缩放" : "已恢复本站默认缩放限制");
                        }));

        String curSiteCss = siteExtras.customCss(host);
        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, CODE, "站点自定义 CSS",
                        curSiteCss.isEmpty() ? "注入专属样式" : "已配置 (" + curSiteCss.length() + " 字)",
                        !curSiteCss.isEmpty(), true, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            AikoV040Ui.showSiteCssSheet(v040Host, host, () -> {
                                if (sameOpenSite(tab, host) && tab.view != null) applyPageToolkitStyles(tab, tab.view);
                            });
                        }),
                AikoTheme.toggleTile(this, density, CLOSE, "重置站内数据", "清除 Cookie / 存储",
                        false, true, AikoTheme.CORAL, () -> {
                            dialog.dismiss();
                            resetSiteDataAndCookies(tab, host, url);
                        }));

        // Per-site Auto-Refresh pill strip (0 / 15s / 30s / 60s / 5m)
        int curRefresh = siteExtras.autoRefreshSec(host);
        body.addView(AikoTheme.sectionHeader(this, density,
                "定时自动刷新" + (curRefresh > 0 ? " · 每 " + (curRefresh >= 60 ? (curRefresh / 60) + " 分钟" : curRefresh + " 秒") : " · 关闭")));
        FlowTagLayout refreshFlow = new FlowTagLayout(this, dp(6), dp(6));
        for (int sec : SiteExtras.AUTO_REFRESH_PRESETS) {
            boolean sel = sec == curRefresh;
            String lbl = sec == 0 ? "关闭" : (sec >= 60 ? (sec / 60) + " 分钟" : sec + " 秒");
            TextView pill = AikoTheme.badge(this, density, lbl,
                    sel ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED,
                    sel ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    sel ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
            pill.setOnClickListener(v -> {
                siteExtras.setAutoRefreshSec(host, sec);
                scheduleAutoRefreshForCurrent();
                dialog.dismiss();
                toast(sec == 0 ? "已关闭此站定时刷新" : "已开启此站每 " + lbl + " 自动刷新");
            });
            refreshFlow.addView(pill);
        }
        body.addView(refreshFlow, new LinearLayout.LayoutParams(-1, -2));

        // Inline Text Zoom pill selector
        body.addView(AikoTheme.sectionHeader(this, density, "站点字号 · " + profile.zoom + "%"));
        HorizontalZoomStrip(body, dialog, tab, host, profile);

        // Per-host user element-picker / custom block rules with 1-tap Undo
        LinearLayout hostRulesSection = stack();
        hostRulesSection.setVisibility(View.GONE);
        body.addView(hostRulesSection, new LinearLayout.LayoutParams(-1, -2));
        ioExecutor.execute(() -> {
            List<String> hostRules = filters.userRulesForHost(host);
            if (hostRules == null || hostRules.isEmpty()) return;
            handler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                hostRulesSection.removeAllViews();
                hostRulesSection.setVisibility(View.VISIBLE);
                hostRulesSection.addView(AikoTheme.sectionHeader(this, density,
                        "本站点点选/自定义屏蔽规则（" + hostRules.size() + " · 点击撤销）"));
                for (String rule : hostRules) {
                    LinearLayout rRow = new LinearLayout(this);
                    rRow.setOrientation(LinearLayout.HORIZONTAL);
                    rRow.setGravity(Gravity.CENTER_VERTICAL);
                    rRow.setPadding(dp(10), dp(7), dp(8), dp(7));
                    rRow.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(12)));
                    TextView rText = label(rule, 11, AikoTheme.TEXT_MUTED);
                    rText.setTypeface(AikoTheme.MONO);
                    rText.setSingleLine(true);
                    rText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    rRow.addView(rText, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView undoBtn = AikoTheme.badge(this, density, "撤销", AikoTheme.AMBER, AikoTheme.PILL, AikoTheme.STROKE);
                    undoBtn.setOnClickListener(v -> {
                        undoBtn.setEnabled(false);
                        filters.removeUserRule(rule, removed -> {
                            if (isDestroyed()) return;
                            if (removed) {
                                rRow.setVisibility(View.GONE);
                                toast("已撤销规则，正在刷新页面");
                                if (sameOpenSite(tab, host)) reloadPreservingScroll();
                            } else {
                                undoBtn.setEnabled(true);
                                toast("撤销规则失败");
                            }
                        });
                    });
                    LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, dp(28));
                    ulp.leftMargin = dp(8);
                    rRow.addView(undoBtn, ulp);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.bottomMargin = dp(6);
                    hostRulesSection.addView(rRow, rlp);
                }
            });
        });

        // Built-in Filter List Quick Toggles (dynamic CN + Global lists)
        body.addView(AikoTheme.sectionHeader(this, density, "内置过滤规则开关"));
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout listRow = new LinearLayout(this);
        listRow.setOrientation(LinearLayout.HORIZONTAL);
        filterScroll.addView(listRow);
        List<SiteFilter.BuiltinList> builtins = filters.builtinLists();
        for (int i = 0; i < builtins.size(); i++) {
            SiteFilter.BuiltinList bl = builtins.get(i);
            boolean on = filters.isListEnabled(bl.id);
            TextView pill = AikoTheme.badge(this, density, (on ? "✓ " : "") + bl.title,
                    on ? AikoTheme.TEXT : AikoTheme.TEXT_DIM,
                    on ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    on ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            pill.setOnClickListener(v -> {
                dialog.dismiss();
                toast("正在更新规则列表…");
                filters.setListEnabled(bl.id, !on, () -> {
                    if (!isDestroyed()) {
                        applyCurrentFilter();
                        toast(bl.title + (!on ? " 已启用" : " 已停用"));
                    }
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i < builtins.size() - 1) lp.rightMargin = dp(6);
            listRow.addView(pill, lp);
        }
        body.addView(filterScroll, new LinearLayout.LayoutParams(-1, -2));

        // Bottom Utility Actions
        LinearLayout actions = sheetRow(body);
        LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) actions.getLayoutParams();
        alp.topMargin = dp(10);
        sheetAction(dialog, actions, REFRESH, "刷新", tab.view != null, () -> {
            if (sameOpenSite(tab, host)) reloadPreservingScroll();
        });
        sheetAction(dialog, actions, COPY, "复制网址", true, () -> copyUrl("网址", url));
        sheetAction(dialog, actions, HISTORY, "拦截详情", true, () -> showFilterTrace(tab, host));
        boolean modified = !filtered || profile.encode() != 0 || ultraScroll || forceMaxHz || jsDisabled || darkOv != 0
                || !curSiteCss.isEmpty() || curRefresh > 0;
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
                SiteProfile next = old.withZoom(z);
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

    // ==================== CLEAN 2-TIER CUSTOMIZABLE MENU ====================

    private void showTools() {
        Tab tab = current;
        String url = tab == null ? null : tab.url;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(url);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "快捷菜单", dialog::dismiss);

        int maxH = Math.min(540, Math.max(240, getResources().getConfiguration().screenHeightDp - 132));
        ScrollView scroll = AikoTheme.maxHeightScrollView(this, dp(maxH));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Fixed Essential Dock: New Tab | Private | Downloads | Settings Center (2nd-tier hub)
        LinearLayout quick = sheetRow(body);
        sheetAction(dialog, quick, ADD, "新建标签", true, () -> openNewTabDefault(false));
        sheetAction(dialog, quick, PRIVATE, "无痕", true, () -> startActivity(new Intent(this, PrivateActivity.class)));
        sheetAction(dialog, quick, DOWNLOAD, "下载", true, this::showDownloads);
        sheetAction(dialog, quick, SETTINGS, "设置中心", true, () -> SettingsCenterUi.showSettingsCenter(settingsHost));

        // 2. User-Customizable 1st-Tier Quick Tools (clean 6 defaults + live Edit pill)
        LinearLayout customHead = new LinearLayout(this);
        customHead.setOrientation(LinearLayout.HORIZONTAL);
        customHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, -2);
        chlp.topMargin = dp(8);
        chlp.bottomMargin = dp(8);
        body.addView(customHead, chlp);

        TextView headTitle = label("一级常用工具", 12, AikoTheme.TEXT_MUTED);
        headTitle.setTypeface(AikoTheme.MEDIUM);
        customHead.addView(headTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView editPill = AikoTheme.badge(this, density, "编辑顺序", AikoTheme.ACCENT, AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT);
        editPill.setOnClickListener(v -> {
            dialog.dismiss();
            SettingsCenterUi.showMenuCustomizer(settingsHost, this::showTools);
        });
        customHead.addView(editPill, new LinearLayout.LayoutParams(-2, dp(26)));

        List<MenuConfig.ActionId> primary = menuConfig.getPrimaryActions();
        for (int i = 0; i < primary.size(); i += 2) {
            MenuConfig.ActionId left = primary.get(i);
            MenuConfig.ActionId right = (i + 1 < primary.size()) ? primary.get(i + 1) : null;
            View lv = SettingsCenterUi.buildMenuTile(dialog, settingsHost, left, pageOpen);
            View rv = right != null ? SettingsCenterUi.buildMenuTile(dialog, settingsHost, right, pageOpen) : new View(this);
            addTwoColRow(body, lv, rv);
        }

        // 3. Compact Page Utility & More Tools Bar
        LinearLayout pageRow = sheetRow(body);
        sheetAction(dialog, pageRow, FIND, "页内查找", pageOpen, () -> { if (tab != null && tab.view != null) openFind(tab.view); });
        sheetAction(dialog, pageRow, QR, pageOpen ? "二维码" : "扫码", true, () -> {
            if (pageOpen) QrSheet.showGenerator(this, tab.title, url, () -> copyUrl("网址", url), qrScanCallback);
            else QrSheet.showScannerHintOrImageDecode(this, qrScanCallback);
        });
        sheetAction(dialog, pageRow, SHARE, "分享", pageOpen, () -> shareLink(url));
        sheetAction(dialog, pageRow, GRID, "更多工具", true, () -> SettingsCenterUi.showMoreTools(settingsHost));

        showSheet(dialog, panel);
    }

    private void executeMenuAction(MenuConfig.ActionId action) {
        if (action == null) return;
        Tab tab = current;
        String url = tab == null ? null : tab.url;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(url);
        switch (action) {
            case BABEL:
                babelUi.showSheet(pageOpen ? tab.view : null, pageOpen ? url : null);
                break;
            case USERSCRIPTS:
                showUserScriptsSheet();
                break;
            case ADBLOCK:
                showFilterRules();
                break;
            case BOOKMARKS:
                showLibrary(true);
                break;
            case HISTORY:
                showLibrary(false);
                break;
            case READER:
                if (tab != null && tab.view != null && pageOpen) {
                    ReaderMode.toggle(this, tab.view, state -> {
                        if ("empty".equals(state)) toast("当前页面暂不适合阅读模式");
                        else if ("unavailable".equals(state)) toast("无法启动阅读模式");
                    });
                }
                break;
            case BOOKMARK_TOGGLE:
                if (tab != null && pageOpen) toggleBookmark(tab, url);
                break;
            case READING_SHELF:
                if (tab != null && pageOpen) handleReadingShelfAction(tab, readingShelf.has(url));
                break;
            case GALLERY:
                if (pageOpen) openGalleryForCurrent();
                break;
            case LONGSHOT:
                if (pageOpen) captureLongShotForCurrent();
                break;
            case VIDEO_BOOSTER:
                if (tab != null && tab.view != null && pageOpen) showVideoBoosterSheet(tab);
                break;
            case MEDIA_SNIFF:
                if (pageOpen) openMediaSnifferForCurrent();
                break;
            case READ_ALOUD:
                if (tab != null && tab.view != null && pageOpen && readAloud != null) {
                    readAloud.showReadAloudSheet(this, tab.view, displayMode, density);
                }
                break;
            case DEVTOOLS:
                if (tab != null && tab.view != null && pageOpen) {
                    WebDevTools.toggle(tab.view);
                    toast("已切换网页开发者调试台");
                }
                break;
            case PWA_PIN:
                if (pageOpen) pinCurrentPagePwa();
                break;
            case TAB_MUTE:
                if (tab != null && tab.view != null && pageOpen) {
                    boolean next = !tab.muted;
                    tab.muted = next;
                    WebViewPerf.setAudioMuted(tab.view, next);
                    toast(next ? "当前标签已静音" : "已恢复标签声音");
                }
                break;
            case DARK_WEB:
                boolean nextDark = !siteExtras.isGlobalDarkWeb();
                siteExtras.setGlobalDarkWeb(nextDark);
                refreshAllTabsDarkMode();
                toast(nextDark ? "已开启全局网页深色模式" : "已关闭全局网页深色模式");
                break;
            case PERF_ENGINE:
                showPerformanceCenter();
                break;
            case THEME_STUDIO:
                showThemeStudio();
                break;
            case CUSTOM_HOME:
                AikoV040Ui.showCustomizationSheet(v040Host);
                break;
            case BACKUP:
                AikoV040Ui.showBackupSheet(v040Host);
                break;
            case CLEAN_CACHE:
                SettingsCenterUi.showStorageCleaner(settingsHost);
                break;
        }
    }

    private String actionDynamicSubtitle(MenuConfig.ActionId action) {
        Tab tab = current;
        String url = tab == null ? null : tab.url;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(url);
        switch (action) {
            case BABEL: {
                boolean on = pageOpen && babelUi != null && babelUi.isTranslating(tab.view);
                return on ? ("翻译中 · " + babelConfig.activeEngineDisplayName()) : babelConfig.activeEngineDisplayName();
            }
            case USERSCRIPTS: {
                int total = userScripts != null ? userScripts.items().size() : 0;
                return total > 0 ? ("已安装 " + total + " 个脚本") : action.defaultSub;
            }
            case ADBLOCK: {
                int count = filter().totalRuleCount();
                return count > 0 ? String.format(Locale.ROOT, "%,d 条规则护航", count) : action.defaultSub;
            }
            case BOOKMARK_TOGGLE:
                return pageOpen && library.isBookmarked(url) ? "已收藏 · 点按取消" : "点按收藏此页";
            case READING_SHELF:
                return pageOpen && readingShelf.has(url) ? "已在稍后读 · 移除" : "保存位置/离线";
            case VIDEO_BOOSTER:
                return (bgAudioEnabled && tab == bgAudioTab) ? "后台音频播放中" : action.defaultSub;
            case MEDIA_SNIFF:
                return cachedSniffedCount > 0 ? ("发现 " + cachedSniffedCount + " 条流媒体") : action.defaultSub;
            case TAB_MUTE:
                return (pageOpen && tab.muted) ? "当前已静音" : "声音开启";
            case DARK_WEB:
                return siteExtras.isGlobalDarkWeb() ? "已开启" : "已关闭";
            case PERF_ENGINE: {
                float hz = displayMode != null ? displayMode.activeRefreshRate() : 0f;
                return activeProfile.name + (hz > 0 ? String.format(Locale.ROOT, " · %.0fHz", hz) : "");
            }
            case THEME_STUDIO:
                return AikoTheme.modeShortLabel(AikoTheme.loadMode(this));
            case CUSTOM_HOME:
                return startPageConfig.resolveHomeUrl() == null ? "原生主页 · 护眼/字体" : "自定义主页";
            default:
                return action.defaultSub;
        }
    }

    private boolean actionIsActive(MenuConfig.ActionId action) {
        Tab tab = current;
        String url = tab == null ? null : tab.url;
        boolean pageOpen = tab != null && tab.view != null && HttpUrlUtil.isHttp(url);
        switch (action) {
            case BABEL: return pageOpen && babelUi != null && babelUi.isTranslating(tab.view);
            case BOOKMARK_TOGGLE: return pageOpen && library.isBookmarked(url);
            case READING_SHELF: return pageOpen && readingShelf.has(url);
            case VIDEO_BOOSTER: return bgAudioEnabled && tab == bgAudioTab;
            case MEDIA_SNIFF: return cachedSniffedCount > 0;
            case TAB_MUTE: return pageOpen && tab.muted;
            case DARK_WEB: return siteExtras.isGlobalDarkWeb();
            case PERF_ENGINE: return PerfProfiles.ID_RAMPAGE.equals(activeProfile.id);
            case THEME_STUDIO: return !AikoTheme.MODE_OBSIDIAN.equals(AikoTheme.loadMode(this));
            case CUSTOM_HOME: return startPageConfig.isNightFilterEnabled() || startPageConfig.resolveHomeUrl() != null;
            default: return false;
        }
    }

    private int actionAccentColor(MenuConfig.ActionId action) {
        switch (action) {
            case ADBLOCK:
            case READING_SHELF:
            case MEDIA_SNIFF:
            case READ_ALOUD:
                return AikoTheme.EMERALD;
            case READER:
            case LONGSHOT:
            case VIDEO_BOOSTER:
            case PERF_ENGINE:
            case CUSTOM_HOME:
                return AikoTheme.ACCENT_CYAN;
            case DEVTOOLS:
            case TAB_MUTE:
            case CLEAN_CACHE:
                return AikoTheme.AMBER;
            default:
                return AikoTheme.ACCENT;
        }
    }

    private void addTwoColRow(LinearLayout parent, View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(9);
        parent.addView(row, rlp);

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1);
        lp1.rightMargin = dp(9);
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
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "加入稍后读 · 0MB 内存", dialog::dismiss);

        TextView sub = label("不常驻后台标签内存，随时从主页一键继续阅读", 12, AikoTheme.TEXT_MUTED);
        sub.setPadding(dp(4), 0, dp(4), dp(12));
        panel.addView(sub);

        View optFast = buildSheetOptionCard(BrowserIcon.Kind.PIN, AikoTheme.ACCENT,
                "仅保存链接与阅读位置（瞬时）", "极速写入当前滚动进度，0 磁盘占用", () -> {
                    dialog.dismiss();
                    readingShelf.pin(tab.url, tab.title, sx, sy, null);
                    toast("已加入首页稍后读");
                });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-1, -2);
        lp1.bottomMargin = dp(8);
        panel.addView(optFast, lp1);

        View optOffline = buildSheetOptionCard(BrowserIcon.Kind.DOWNLOAD, AikoTheme.ACCENT_CYAN,
                "保存并生成离线快照（MHTML）", "无网、断网或原文失效时依然可完整阅读", () -> {
                    dialog.dismiss();
                    if (tab.view != null) {
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
                });
        panel.addView(optOffline, new LinearLayout.LayoutParams(-1, -2));
        showSheet(dialog, panel);
    }

    private View buildSheetOptionCard(BrowserIcon.Kind kind, int iconColor, String title, String subtitle, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
        AikoTheme.attachPressFeedback(card, 0.97f);
        card.setOnClickListener(v -> onClick.run());

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(11)));
        BrowserIcon ico = new BrowserIcon(this, kind);
        ico.tint(iconColor);
        badge.addView(ico, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(38), dp(38));
        blp.rightMargin = dp(12);
        card.addView(badge, blp);

        LinearLayout textCol = stack();
        TextView t = label(title, 14, AikoTheme.TEXT);
        t.setTypeface(AikoTheme.BOLD);
        TextView s = label(subtitle, 11, AikoTheme.TEXT_MUTED);
        s.setPadding(0, dp(2), 0, 0);
        textCol.addView(t);
        textCol.addView(s);
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
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

    // ==================== PERFORMANCE ENGINE CENTER & THEME STUDIO ====================

    private void showPerformanceCenter() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "性能引擎", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Hardware telemetry banner
        boolean capped = displayMode != null && displayMode.isOemCapped();
        String hwSummary = displayMode != null ? displayMode.hardwareSummary() : "高刷引擎就绪";
        LinearLayout hwCard = stack();
        hwCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        hwCard.setBackground(AikoTheme.glowBanner(dp(16)));
        LinearLayout.LayoutParams hwLp = new LinearLayout.LayoutParams(-1, -2);
        hwLp.topMargin = dp(4);
        hwLp.bottomMargin = dp(6);
        body.addView(hwCard, hwLp);

        LinearLayout hwRow = new LinearLayout(this);
        hwRow.setGravity(Gravity.CENTER_VERTICAL);
        hwCard.addView(hwRow, new LinearLayout.LayoutParams(-1, -2));

        TextView hwTitle = label(hwSummary, 14, AikoTheme.TEXT);
        hwTitle.setTypeface(AikoTheme.BOLD);
        hwRow.addView(hwTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView activeBadge = AikoTheme.badge(this, density, activeProfile.name,
                AikoTheme.ACCENT_CYAN, AikoTheme.BG, AikoTheme.STROKE_ACCENT);
        hwRow.addView(activeBadge, new LinearLayout.LayoutParams(-2, -2));

        TextView hwSub = label(capped
                ? "⚠ 检测到系统可能限制了本应用刷新率，可在下方进入白名单设置"
                : PerfProfiles.summaryLine(activeProfile),
                11, capped ? AikoTheme.AMBER : AikoTheme.TEXT_MUTED);
        hwSub.setPadding(0, dp(6), 0, 0);
        hwCard.addView(hwSub);

        if (capped) {
            TextView oemBtn = AikoTheme.badge(this, density, "前往系统显示/高刷白名单设置 ›",
                    AikoTheme.AMBER, AikoTheme.CARD, AikoTheme.STROKE);
            oemBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            oemBtn.setOnClickListener(v -> {
                dialog.dismiss();
                DisplayModeEngine.openRefreshRateSettings(this);
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, dp(38));
            olp.topMargin = dp(8);
            hwCard.addView(oemBtn, olp);
        }

        // Built-in flagship performance modes
        body.addView(AikoTheme.sectionHeader(this, density, "内置性能模式"));
        List<PerfProfiles.Profile> allProfiles = perfProfiles.all();
        ArrayList<PerfProfiles.Profile> customProfiles = new ArrayList<>();
        for (PerfProfiles.Profile p : allProfiles) {
            if (!p.builtin) {
                customProfiles.add(p);
                continue;
            }
            body.addView(buildPerfProfileCard(dialog, p));
        }

        // Custom modes section
        LinearLayout customHeader = new LinearLayout(this);
        customHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, -2);
        chlp.topMargin = dp(8);
        chlp.bottomMargin = dp(6);
        body.addView(customHeader, chlp);

        TextView customTitle = label("自定义性能模式", 12, AikoTheme.TEXT_DIM);
        customTitle.setTypeface(AikoTheme.MEDIUM);
        customTitle.setLetterSpacing(0.04f);
        customTitle.setPadding(dp(4), 0, 0, 0);
        customHeader.addView(customTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView addCustomBtn = AikoTheme.badge(this, density, "+ 新建自定义性能模式",
                AikoTheme.ACCENT, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        addCustomBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(addCustomBtn, 0.94f);
        addCustomBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomProfileEditor(null);
        });
        customHeader.addView(addCustomBtn, new LinearLayout.LayoutParams(-2, dp(30)));

        if (customProfiles.isEmpty()) {
            TextView emptyCustom = label("可自由组合刷新率、ADPF 助推、热标签上限、BFCache 与并发下载线程。", 12, AikoTheme.TEXT_DIM);
            emptyCustom.setPadding(dp(6), dp(2), dp(6), dp(8));
            body.addView(emptyCustom);
        } else {
            for (PerfProfiles.Profile cp : customProfiles) {
                body.addView(buildPerfProfileCard(dialog, cp));
            }
        }

        // Bottom HUD & Diagnostics section
        body.addView(AikoTheme.sectionHeader(this, density, "实时监控与诊断"));
        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, SPEED, "185Hz 帧率浮标",
                        hudEnabled ? "已开启 · Hz/FPS/P99" : "已关闭 · 零开销",
                        hudEnabled, true, AikoTheme.EMERALD, () -> {
                            setHudEnabled(!hudEnabled);
                            dialog.dismiss();
                            toast(hudEnabled ? "已开启 185Hz 实时帧率浮标" : "已关闭实时帧率浮标");
                        }),
                AikoTheme.toggleTile(this, density, INFO, "性能诊断报告",
                        "耗时与内核状态",
                        false, true, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            showPerformance();
                        }));

        showSheet(dialog, panel);
    }

    private View buildPerfProfileCard(Dialog parentDialog, PerfProfiles.Profile p) {
        boolean active = p.id.equals(activeProfile.id);
        int accentTint = PerfProfiles.ID_RAMPAGE.equals(p.id) ? AikoTheme.ACCENT_CYAN
                : (PerfProfiles.ID_ECO.equals(p.id) ? AikoTheme.EMERALD : AikoTheme.ACCENT);

        LinearLayout card = stack();
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(AikoTheme.card(
                active ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                active ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                density, dp(16)));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(16)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        String iconPrefix = PerfProfiles.ID_RAMPAGE.equals(p.id) ? "⚡ " : "";
        TextView title = label(iconPrefix + p.name, 14, AikoTheme.TEXT);
        title.setTypeface(AikoTheme.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        if (active) {
            TextView pill = AikoTheme.badge(this, density, "✓ 当前生效",
                    accentTint, AikoTheme.BG, AikoTheme.STROKE_ACCENT);
            top.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        }

        if (!p.builtin) {
            TextView editBtn = AikoTheme.badge(this, density, "编辑",
                    AikoTheme.TEXT_MUTED, AikoTheme.PILL, AikoTheme.STROKE);
            editBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
            editBtn.setOnClickListener(v -> {
                parentDialog.dismiss();
                showCustomProfileEditor(p);
            });
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
            elp.leftMargin = dp(6);
            top.addView(editBtn, elp);

            TextView delBtn = AikoTheme.badge(this, density, "删除",
                    AikoTheme.CORAL, AikoTheme.PILL, AikoTheme.STROKE);
            delBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
            delBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除自定义模式？")
                    .setMessage("将删除「" + p.name + "」。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        boolean wasActive = p.id.equals(activeProfile.id);
                        if (perfProfiles.deleteCustom(p.id)) {
                            if (wasActive) applyPerformanceProfile(perfProfiles.active(), false);
                            parentDialog.dismiss();
                            toast("已删除模式：" + p.name);
                            showPerformanceCenter();
                        }
                    }).show());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = dp(6);
            top.addView(delBtn, dlp);
        }

        String descText = PerfProfiles.summaryLine(p);
        if (PerfProfiles.ID_RAMPAGE.equals(p.id)) {
            descText = "全速释放 · " + descText;
        } else if (PerfProfiles.ID_BALANCED.equals(p.id)) {
            descText = "日常推荐 · " + descText;
        } else if (PerfProfiles.ID_ECO.equals(p.id)) {
            descText = "续航优先 · " + descText;
        }
        TextView desc = label(descText, 11, active ? accentTint : AikoTheme.TEXT_MUTED);
        desc.setPadding(0, dp(5), 0, 0);
        card.addView(desc);

        AikoTheme.attachPressFeedback(card, 0.97f);
        card.setOnClickListener(v -> {
            perfProfiles.select(p.id);
            applyPerformanceProfile(p, true);
            parentDialog.dismiss();
        });
        return card;
    }

    private void showCustomProfileEditor(PerfProfiles.Profile editing) {
        final String editId = editing == null ? "" : editing.id;
        final int[] refreshPol = {editing != null ? editing.refreshPolicy : DisplayModeEngine.POLICY_MAX};
        final boolean[] adpf = {editing != null && editing.adpfBoost};
        final int[] hotTabs = {editing != null ? editing.maxHotTabs : 8};
        final int[] bfPages = {editing != null ? editing.bfCachePages : 4};
        final int[] preload = {editing != null ? editing.preloadLevel : PerfProfiles.PRELOAD_PRECONNECT};
        final boolean[] preRaster = {editing != null && editing.activePreRaster};
        final int[] dlLanes = {editing != null ? editing.downloadLanes : 64};
        final boolean[] ultraScroll = {editing != null && editing.globalUltraScroll};

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density,
                editing == null ? "新建自定义性能模式" : "编辑自定义性能模式", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView liveSummary = label("", 11, AikoTheme.ACCENT_CYAN);
        liveSummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        liveSummary.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(12)));
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(-1, -2);
        sumLp.bottomMargin = dp(8);
        body.addView(liveSummary, sumLp);

        EditText nameInput = input("模式名称，如：高刷阅读 / 满血狂飙");
        nameInput.setText(editing != null ? editing.name : "自定义性能");
        nameInput.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(14)));
        nameInput.setPadding(dp(12), 0, dp(12), 0);
        body.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(44)));

        final Runnable updateSummary = () -> {
            PerfProfiles.Profile preview = PerfProfiles.sanitized(
                    editId, nameInput.getText().toString(), false,
                    refreshPol[0], adpf[0], hotTabs[0], bfPages[0],
                    preload[0], preRaster[0], dlLanes[0], ultraScroll[0]);
            liveSummary.setText("策略预览：" + PerfProfiles.summaryLine(preview));
        };

        // 1. Refresh policy
        body.addView(AikoTheme.sectionHeader(this, density, "刷新率策略"));
        int[] polVals = {DisplayModeEngine.POLICY_MAX, DisplayModeEngine.POLICY_AUTO, DisplayModeEngine.POLICY_POWER};
        String[] polNames = {"最高刷新率", "系统自适应", "60Hz 省电"};
        addChoiceStrip(body, polNames, polVals, refreshPol, updateSummary);

        // 2. Preload strategy
        body.addView(AikoTheme.sectionHeader(this, density, "页面预加载策略"));
        int[] preVals = {PerfProfiles.PRELOAD_OFF, PerfProfiles.PRELOAD_PRECONNECT, PerfProfiles.PRELOAD_AGGRESSIVE};
        String[] preNames = {"关闭", "智能预连", "激进预加载"};
        addChoiceStrip(body, preNames, preVals, preload, updateSummary);

        // 3. Download lanes
        body.addView(AikoTheme.sectionHeader(this, density, "多线程下载并发上限"));
        int[] laneVals = {8, 32, 64, 128};
        String[] laneNames = {"8 路", "32 路", "64 路", "128 路"};
        addChoiceStrip(body, laneNames, laneVals, dlLanes, updateSummary);

        // 4. Hot tabs slider (2-12)
        TextView hotLabel = AikoTheme.sectionHeader(this, density, "热标签上限：" + hotTabs[0] + " 个");
        body.addView(hotLabel);
        SeekBar hotSeek = new SeekBar(this);
        hotSeek.setMax(PerfProfiles.MAX_HOT_TABS - PerfProfiles.MIN_HOT_TABS);
        hotSeek.setProgress(hotTabs[0] - PerfProfiles.MIN_HOT_TABS);
        hotSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                hotTabs[0] = PerfProfiles.MIN_HOT_TABS + p;
                hotLabel.setText("热标签上限：" + hotTabs[0] + " 个");
                updateSummary.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        body.addView(hotSeek, new LinearLayout.LayoutParams(-1, -2));

        // 5. BFCache slider (0-5)
        TextView bfLabel = AikoTheme.sectionHeader(this, density, "BFCache 往返缓存：" + bfPages[0] + " 页");
        body.addView(bfLabel);
        SeekBar bfSeek = new SeekBar(this);
        bfSeek.setMax(PerfProfiles.MAX_BFCACHE_PAGES);
        bfSeek.setProgress(bfPages[0]);
        bfSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                bfPages[0] = p;
                bfLabel.setText("BFCache 往返缓存：" + bfPages[0] + " 页");
                updateSummary.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        body.addView(bfSeek, new LinearLayout.LayoutParams(-1, -2));

        // 6. Hardware toggle switches
        body.addView(AikoTheme.sectionHeader(this, density, "高级硬件开关"));
        addBoolSwitchCard(body, "ADPF 大核性能助推",
                Build.VERSION.SDK_INT >= 31 ? "触控/导航突发请求 CPU 调度助推（零待机耗电）" : "需 Android 12+ 生效",
                adpf, updateSummary);
        addBoolSwitchCard(body, "前台标签预光栅化 (Pre-Raster)",
                "仅单前台标签生效，切换后台/内存紧张立即释放防 OOM",
                preRaster, updateSummary);
        addBoolSwitchCard(body, "全局 185Hz 抗卡盾",
                "为所有网页自动启用滚动抗卡顿注入",
                ultraScroll, updateSummary);

        updateSummary.run();

        // Save button
        TextView saveBtn = AikoTheme.badge(this, density, "保存并立即启用",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        saveBtn.setTextSize(14);
        saveBtn.setTypeface(AikoTheme.BOLD);
        saveBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(saveBtn, 0.96f);
        saveBtn.setOnClickListener(v -> {
            String nm = nameInput.getText().toString().trim();
            if (nm.isEmpty()) nm = "自定义模式";
            PerfProfiles.Profile draft = new PerfProfiles.Profile(
                    editId, nm, false, refreshPol[0], adpf[0], hotTabs[0], bfPages[0],
                    preload[0], preRaster[0], dlLanes[0], ultraScroll[0]);
            PerfProfiles.Profile saved = perfProfiles.saveCustom(draft);
            perfProfiles.select(saved.id);
            applyPerformanceProfile(saved, true);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(46));
        slp.topMargin = dp(10);
        panel.addView(saveBtn, slp);

        showSheet(dialog, panel);
    }

    private void addChoiceStrip(LinearLayout parent, String[] labels, int[] values, int[] holder, Runnable onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(4);
        parent.addView(row, rlp);

        TextView[] pills = new TextView[labels.length];
        Runnable sync = () -> {
            for (int i = 0; i < pills.length; i++) {
                boolean sel = holder[0] == values[i];
                pills[i].setTextColor(sel ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED);
                pills[i].setBackground(AikoTheme.card(
                        sel ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        sel ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(12)));
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int val = values[i];
            TextView pill = AikoTheme.badge(this, density, labels[i],
                    AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
            pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
            AikoTheme.attachPressFeedback(pill, 0.94f);
            pill.setOnClickListener(v -> {
                holder[0] = val;
                sync.run();
                if (onChange != null) onChange.run();
            });
            pills[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            if (i < labels.length - 1) lp.rightMargin = dp(6);
            row.addView(pill, lp);
        }
        sync.run();
    }

    private void addBoolSwitchCard(LinearLayout parent, String title, String desc, boolean[] holder, Runnable onChange) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(8);
        parent.addView(card, clp);

        LinearLayout textCol = stack();
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        TextView t = label(title, 13, AikoTheme.TEXT);
        t.setTypeface(AikoTheme.MEDIUM);
        textCol.addView(t);
        TextView d = label(desc, 11, AikoTheme.TEXT_DIM);
        textCol.addView(d);

        TextView stateBadge = AikoTheme.badge(this, density, "", AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(28));
        blp.leftMargin = dp(8);
        card.addView(stateBadge, blp);

        Runnable sync = () -> {
            boolean on = holder[0];
            card.setBackground(AikoTheme.card(
                    on ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    on ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                    density, dp(14)));
            stateBadge.setText(on ? "已开启" : "已关闭");
            stateBadge.setTextColor(on ? AikoTheme.EMERALD : AikoTheme.TEXT_DIM);
            stateBadge.setBackground(AikoTheme.card(AikoTheme.BG, on ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE, density, dp(12)));
        };
        sync.run();
        AikoTheme.attachPressFeedback(card, 0.97f);
        card.setOnClickListener(v -> {
            holder[0] = !holder[0];
            sync.run();
            if (onChange != null) onChange.run();
        });
    }

    private void showThemeStudio() {
        final String[] selectedMode = {AikoTheme.loadMode(this)};
        final int[] customSeed = {AikoTheme.loadCustomSeed(this)};
        final float[] surfaceTint = {AikoTheme.loadSurfaceTint(this)};
        final float[] hsv = AikoTheme.Palette.rgbToHsv(customSeed[0]);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "主题工坊 · Material 动态取色", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Live preview card (mini browser bottom bar capsule + button + gradient progress bar)
        LinearLayout previewBox = stack();
        previewBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, -2);
        pblp.topMargin = dp(4);
        pblp.bottomMargin = dp(8);
        body.addView(previewBox, pblp);

        TextView previewCaption = label("实时主题预览", 11, AikoTheme.TEXT_MUTED);
        previewCaption.setTypeface(AikoTheme.MEDIUM);
        previewBox.addView(previewCaption);

        View previewProgress = new View(this);
        LinearLayout.LayoutParams pplp = new LinearLayout.LayoutParams(-1, dp(3));
        pplp.topMargin = dp(8);
        pplp.bottomMargin = dp(8);
        previewBox.addView(previewProgress, pplp);

        LinearLayout previewBar = new LinearLayout(this);
        previewBar.setOrientation(LinearLayout.HORIZONTAL);
        previewBar.setGravity(Gravity.CENTER_VERTICAL);
        previewBox.addView(previewBar, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout previewCapsule = new LinearLayout(this);
        previewCapsule.setGravity(Gravity.CENTER_VERTICAL);
        previewCapsule.setPadding(dp(12), 0, dp(12), 0);
        TextView previewHost = label("aiko.browser  ·  185Hz", 12, AikoTheme.TEXT);
        previewHost.setTypeface(AikoTheme.MEDIUM);
        previewCapsule.addView(previewHost);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(0, dp(36), 1);
        pclp.rightMargin = dp(8);
        previewBar.addView(previewCapsule, pclp);

        TextView previewBtn = label("按钮", 12, 0xFF0B0D12);
        previewBtn.setTypeface(AikoTheme.BOLD);
        previewBtn.setGravity(Gravity.CENTER);
        previewBtn.setPadding(dp(14), 0, dp(14), 0);
        previewBar.addView(previewBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        // Categorized 17 curated flagship presets + Custom studio
        LinearLayout[] presetCards = new LinearLayout[AikoTheme.ALL_MODES.length];
        TextView[] presetTitles = new TextView[AikoTheme.ALL_MODES.length];
        View[] presetDots = new View[AikoTheme.ALL_MODES.length];

        // Custom color controls (Hue, Saturation, Surface Tint, Hex input)
        TextView hueLabel = label("", 12, AikoTheme.TEXT_MUTED);
        TextView satLabel = label("", 12, AikoTheme.TEXT_MUTED);
        TextView tintLabel = label("", 12, AikoTheme.TEXT_MUTED);
        EditText hexInput = input("#39C5BB");
        final LinearLayout[] customCardRef = new LinearLayout[1];
        final TextView[] applyHexBtnRef = new TextView[1];
        final SeekBar[] studioSeeks = new SeekBar[3];

        final Runnable[] refreshPreviewRef = new Runnable[1];
        refreshPreviewRef[0] = () -> {
            String m = selectedMode[0];
            AikoTheme.Palette pal;
            if (AikoTheme.MODE_OLED.equals(m)) {
                pal = AikoTheme.Palette.OLED;
            } else if (AikoTheme.MODE_CUSTOM.equals(m)) {
                pal = AikoTheme.Palette.derive(customSeed[0], surfaceTint[0], false);
            } else if (AikoTheme.MODE_DYNAMIC.equals(m)) {
                pal = AikoTheme.supportsDynamicColor()
                        ? AikoTheme.Palette.derive(AikoTheme.resolveDynamicSeed(this), 1.0f, false)
                        : AikoTheme.Palette.OBSIDIAN;
            } else {
                pal = AikoTheme.Palette.preset(m);
            }

            previewBox.setBackground(AikoTheme.card(pal.surface, pal.strokeAccent, density, dp(16)));
            GradientDrawable progGrad = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, new int[]{pal.accent, pal.accentCyan});
            progGrad.setCornerRadius(dp(2));
            previewProgress.setBackground(progGrad);
            previewCapsule.setBackground(AikoTheme.card(pal.capsule, AikoTheme.CAPSULE_STROKE, density, dp(18)));
            previewBtn.setBackground(AikoTheme.rounded(pal.accent, dp(18)));
            previewBtn.setTextColor(AikoTheme.Palette.onAccentInk(pal.accent));
            previewCaption.setText("实时预览 · " + AikoTheme.modeLabel(m) + " (" + AikoTheme.Palette.formatHex(pal.accent) + ")");
            previewCaption.setTextColor(pal.accentCyan);

            panel.setBackground(AikoTheme.topRounded(AikoTheme.SURFACE, AikoTheme.STROKE, density, dp(24)));
            if (customCardRef[0] != null) {
                customCardRef[0].setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
            }
            hexInput.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(12)));
            if (applyHexBtnRef[0] != null) {
                applyHexBtnRef[0].setBackground(AikoTheme.card(AikoTheme.ACCENT, 0, density, dp(12)));
                applyHexBtnRef[0].setTextColor(AikoTheme.onAccentColor());
            }
            android.content.res.ColorStateList accentTint = android.content.res.ColorStateList.valueOf(pal.accent);
            for (SeekBar sb : studioSeeks) {
                if (sb != null) {
                    sb.setThumbTintList(accentTint);
                    sb.setProgressTintList(accentTint);
                }
            }

            for (int i = 0; i < AikoTheme.ALL_MODES.length; i++) {
                if (presetCards[i] == null) continue;
                boolean active = AikoTheme.ALL_MODES[i].equals(m);
                presetCards[i].setBackground(AikoTheme.card(
                        active ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        active ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(14)));
                int swatch = AikoTheme.MODE_CUSTOM.equals(AikoTheme.ALL_MODES[i])
                        ? customSeed[0]
                        : AikoTheme.modePreviewColor(this, AikoTheme.ALL_MODES[i]);
                presetDots[i].setBackground(AikoTheme.card(swatch,
                        AikoTheme.MODE_OLED.equals(AikoTheme.ALL_MODES[i]) ? AikoTheme.STROKE : 0,
                        density, dp(11)));
                presetTitles[i].setTextColor(active ? AikoTheme.TEXT : AikoTheme.TEXT_MUTED);
            }
            hueLabel.setText(String.format(Locale.ROOT, "色相 Hue · %d°", Math.round(hsv[0])));
            satLabel.setText(String.format(Locale.ROOT, "饱和度 Sat · %d%%", Math.round(hsv[1] * 100f)));
            tintLabel.setText(String.format(Locale.ROOT, "底色浸染 Surface Tint · %d%%", Math.round(surfaceTint[0] * 100f)));
        };

        String[][] groups = {AikoTheme.ICONIC_MODES, AikoTheme.LUXURY_MODES, AikoTheme.CLASSIC_MODES};
        String[] groupTitles = {"标志与潮流精选", "公认高级典藏色", "经典暗夜与系统取色"};
        for (int g = 0; g < groups.length; g++) {
            body.addView(AikoTheme.sectionHeader(this, density, groupTitles[g]));
            String[] list = groups[g];
            for (int i = 0; i < list.length; i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(8);
                body.addView(row, rlp);

                for (int col = 0; col < 2; col++) {
                    int li = i + col;
                    if (li >= list.length) {
                        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                        break;
                    }
                    final String mode = list[li];
                    int allIdx = -1;
                    for (int k = 0; k < AikoTheme.ALL_MODES.length; k++) {
                        if (AikoTheme.ALL_MODES[k].equals(mode)) { allIdx = k; break; }
                    }

                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.HORIZONTAL);
                    tile.setGravity(Gravity.CENTER_VERTICAL);
                    tile.setPadding(dp(11), dp(10), dp(10), dp(10));
                    tile.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));

                    View dot = new View(this);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(22), dp(22));
                    dlp.rightMargin = dp(9);
                    tile.addView(dot, dlp);

                    LinearLayout textCol = stack();
                    tile.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

                    TextView t = label(AikoTheme.modeShortLabel(mode), 13, AikoTheme.TEXT);
                    t.setTypeface(AikoTheme.MEDIUM);
                    t.setSingleLine(true);
                    textCol.addView(t);

                    TextView sub = label(AikoTheme.modeDescription(this, mode), 10, AikoTheme.TEXT_DIM);
                    sub.setSingleLine(true);
                    sub.setEllipsize(TextUtils.TruncateAt.END);
                    textCol.addView(sub);

                    if (allIdx >= 0) {
                        presetCards[allIdx] = tile;
                        presetTitles[allIdx] = t;
                        presetDots[allIdx] = dot;
                    }

                    AikoTheme.attachPressFeedback(tile, 0.96f);
                    tile.setOnClickListener(v -> {
                        selectedMode[0] = mode;
                        if (!AikoTheme.MODE_CUSTOM.equals(mode)) {
                            AikoTheme.saveAndApply(this, mode, customSeed[0], surfaceTint[0]);
                            applyLiveTheme();
                            toast("已切换主题：" + AikoTheme.modeLabel(mode));
                        } else {
                            AikoTheme.saveAndApply(this, AikoTheme.MODE_CUSTOM, customSeed[0], surfaceTint[0]);
                            applyLiveTheme();
                        }
                        refreshPreviewRef[0].run();
                    });

                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(56), 1);
                    if (col == 0) tlp.rightMargin = dp(8);
                    row.addView(tile, tlp);
                }
            }
        }

        // Custom color studio section (Hue / Sat / Surface Tint + Hex)
        body.addView(AikoTheme.sectionHeader(this, density, "自定义调色台"));
        LinearLayout customCard = stack();
        customCardRef[0] = customCard;
        customCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        customCard.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
        body.addView(customCard, new LinearLayout.LayoutParams(-1, -2));

        SeekBar hueSeek = new SeekBar(this);
        hueSeek.setMax(360);
        hueSeek.setProgress(Math.round(hsv[0]));
        studioSeeks[0] = hueSeek;

        SeekBar satSeek = new SeekBar(this);
        satSeek.setMax(100);
        satSeek.setProgress(Math.round(hsv[1] * 100f));
        studioSeeks[1] = satSeek;

        SeekBar tintSeek = new SeekBar(this);
        tintSeek.setMax(150);
        tintSeek.setProgress(Math.round(surfaceTint[0] * 100f));
        studioSeeks[2] = tintSeek;

        final boolean[] syncingHex = {false};
        Runnable commitCustomLive = () -> {
            selectedMode[0] = AikoTheme.MODE_CUSTOM;
            customSeed[0] = AikoTheme.Palette.hsvToRgb(hsv[0], hsv[1], Math.max(0.88f, hsv[2]));
            syncingHex[0] = true;
            hexInput.setText(AikoTheme.Palette.formatHex(customSeed[0]));
            syncingHex[0] = false;
            AikoTheme.saveAndApply(this, AikoTheme.MODE_CUSTOM, customSeed[0], surfaceTint[0]);
            applyLiveTheme();
            refreshPreviewRef[0].run();
        };

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                selectedMode[0] = AikoTheme.MODE_CUSTOM;
                if (seekBar == hueSeek) hsv[0] = progress;
                else if (seekBar == satSeek) hsv[1] = progress / 100f;
                else if (seekBar == tintSeek) surfaceTint[0] = progress / 100f;
                customSeed[0] = AikoTheme.Palette.hsvToRgb(hsv[0], hsv[1], Math.max(0.88f, hsv[2]));
                syncingHex[0] = true;
                hexInput.setText(AikoTheme.Palette.formatHex(customSeed[0]));
                syncingHex[0] = false;
                refreshPreviewRef[0].run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { commitCustomLive.run(); }
        };
        hueSeek.setOnSeekBarChangeListener(sliderListener);
        satSeek.setOnSeekBarChangeListener(sliderListener);
        tintSeek.setOnSeekBarChangeListener(sliderListener);

        customCard.addView(hueLabel);
        customCard.addView(hueSeek, new LinearLayout.LayoutParams(-1, -2));
        satLabel.setPadding(0, dp(6), 0, 0);
        customCard.addView(satLabel);
        customCard.addView(satSeek, new LinearLayout.LayoutParams(-1, -2));
        tintLabel.setPadding(0, dp(6), 0, 0);
        customCard.addView(tintLabel);
        customCard.addView(tintSeek, new LinearLayout.LayoutParams(-1, -2));

        // Hex input row
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrlp = new LinearLayout.LayoutParams(-1, -2);
        hrlp.topMargin = dp(10);
        customCard.addView(hexRow, hrlp);

        hexInput.setTypeface(AikoTheme.MONO);
        hexInput.setTextSize(14);
        hexInput.setText(AikoTheme.Palette.formatHex(customSeed[0]));
        hexInput.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(12)));
        hexInput.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams hilp = new LinearLayout.LayoutParams(0, dp(40), 1);
        hilp.rightMargin = dp(8);
        hexRow.addView(hexInput, hilp);

        TextView applyHexBtn = AikoTheme.badge(this, density, "应用色值",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        applyHexBtnRef[0] = applyHexBtn;
        applyHexBtn.setTypeface(AikoTheme.BOLD);
        applyHexBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        AikoTheme.attachPressFeedback(applyHexBtn, 0.94f);
        applyHexBtn.setOnClickListener(v -> {
            if (syncingHex[0]) return;
            String raw = hexInput.getText().toString().trim();
            int parsed = AikoTheme.Palette.parseHex(raw, 0);
            if (parsed == 0) {
                hexInput.setError("请输入 #RRGGBB 格式");
                return;
            }
            float[] nextHsv = AikoTheme.Palette.rgbToHsv(parsed);
            hsv[0] = nextHsv[0];
            hsv[1] = nextHsv[1];
            hsv[2] = Math.max(0.88f, nextHsv[2]);
            customSeed[0] = parsed;
            hueSeek.setProgress(Math.round(hsv[0]));
            satSeek.setProgress(Math.round(hsv[1] * 100f));
            selectedMode[0] = AikoTheme.MODE_CUSTOM;
            AikoTheme.saveAndApply(this, AikoTheme.MODE_CUSTOM, customSeed[0], surfaceTint[0]);
            applyLiveTheme();
            refreshPreviewRef[0].run();
            toast("已应用自定义主题色 " + AikoTheme.Palette.formatHex(parsed));
        });
        hexRow.addView(applyHexBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        refreshPreviewRef[0].run();
        showSheet(dialog, panel);
    }

    private void showSearchEnginePicker() {
        String currentId = SearchEngines.currentId(this);
        String currentCustom = SearchEngines.customTemplate(this);
        String[] ids = SearchEngines.engineIds();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "选择搜索引擎", dialog::dismiss);
        int maxEngineH = Math.min(460, Math.max(220, getResources().getConfiguration().screenHeightDp - 180));
        ScrollView scroll = AikoTheme.maxHeightScrollView(this, dp(maxEngineH));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        for (String id : ids) {
            boolean active = id.equals(currentId);
            String name = SearchEngines.engineName(id);
            String sub = "custom".equals(id)
                    ? (currentCustom == null || currentCustom.isEmpty() ? "支持自定义含 %s 搜索模板" : currentCustom)
                    : (active ? "当前默认搜索引擎" : "点按切换为默认");
            View tile = AikoTheme.toggleTile(this, density, ENGINE, name, sub,
                    active, true, AikoTheme.ACCENT, () -> {
                        dialog.dismiss();
                        if ("custom".equals(id)) {
                            showCustomSearchEngineDialog(currentCustom);
                        } else {
                            SearchEngines.save(this, id, currentCustom);
                            WebViewPerf.warmUpQuicHints(collectQuicHosts());
                            if (onHome) refreshHomeContent();
                            toast("已切换搜索引擎：" + name);
                        }
                    });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.bottomMargin = dp(8);
            body.addView(tile, tlp);
        }
        showSheet(dialog, panel);
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
        String wvSummary = WebViewPerf.webViewVersionSummary(this);
        String hwSummary = displayMode != null ? displayMode.hardwareSummary() : "未知";
        boolean capped = displayMode != null && displayMode.isOemCapped();

        String stats = "系统内核：" + wvSummary
                + "\n显示面板：" + hwSummary
                + "\n当前模式：" + activeProfile.name + "（" + PerfProfiles.summaryLine(activeProfile) + "）"
                + (capped ? "\n提示：系统可能限制了本应用刷新率，可在性能引擎中调整。" : "")
                + "\n热标签上限：" + maxHotTabs + " 个"
                + "\n引擎状态：" + WebViewPerf.diagnosticSummary()
                + "\n\n导航到 WebView 可绘制，含网络等待。"
                + "\n\n" + (timings.isEmpty() ? "打开一个网页后查看记录。" : String.join("\n", timings));

        SettingsCenterUi.showTextReportSheet(this, density, displayMode,
                "性能诊断报告", stats, "性能引擎 / HUD", this::showPerformanceCenter);
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

    // ==================== BEGINNER-FRIENDLY ADBLOCK & RULES CENTER ====================

    private void showFilterRules() {
        SiteFilter filter = filter();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "广告过滤与规则中心", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(560, Math.max(260, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Top Overview Card
        LinearLayout overview = stack();
        overview.setPadding(dp(16), dp(14), dp(16), dp(14));
        overview.setBackground(AikoTheme.glowBanner(dp(18)));
        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(-1, -2);
        ovLp.topMargin = dp(4);
        ovLp.bottomMargin = dp(6);
        body.addView(overview, ovLp);

        FilterRules rules = filter.rules;
        int totalRules = rules == FilterRules.EMPTY ? 0 : (rules.networkCount + rules.cosmeticCount);
        String countLine = rules == FilterRules.EMPTY
                ? "正在编译规则，基础广告拦截已生效"
                : String.format(Locale.ROOT, "共 %,d 条有效规则", totalRules);
        TextView totalTv = label(countLine, 16, AikoTheme.TEXT);
        totalTv.setTypeface(AikoTheme.BOLD);
        overview.addView(totalTv);

        String breakdown = rules == FilterRules.EMPTY
                ? "中国 + 全球精选规则包自动就绪"
                : String.format(Locale.ROOT, "网络拦截 %,d 条  ·  网页元素隐藏 %,d 条",
                rules.networkCount, rules.cosmeticCount);
        TextView breakTv = label(breakdown, 12, AikoTheme.ACCENT_CYAN);
        breakTv.setPadding(0, dp(4), 0, 0);
        overview.addView(breakTv);

        long updatedAt = filter.lastUpdatedAt();
        String updatedLine = updatedAt > 0
                ? "上次在线更新：" + formatTimestamp(updatedAt)
                : "当前使用应用内置最新规则包（点下方按钮在线更新）";
        TextView updatedTv = label(updatedLine, 11, AikoTheme.TEXT_MUTED);
        updatedTv.setPadding(0, dp(3), 0, dp(10));
        overview.addView(updatedTv);

        TextView updateAllBtn = AikoTheme.badge(this, density, "立即更新全部规则",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        updateAllBtn.setTextSize(13);
        updateAllBtn.setTypeface(AikoTheme.BOLD);
        updateAllBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(updateAllBtn, 0.96f);
        updateAllBtn.setOnClickListener(v -> {
            updateAllBtn.setEnabled(false);
            updateAllBtn.setAlpha(0.6f);
            updateAllBtn.setText("正在后台更新全部规则…");
            filter.updateAll(summary -> {
                if (isDestroyed()) return;
                applyCurrentFilter();
                toast(summary == null || summary.isEmpty() ? "规则更新完成" : summary);
                if (dialog.isShowing()) {
                    dialog.dismiss();
                    showFilterRules();
                }
            });
        });
        overview.addView(updateAllBtn, new LinearLayout.LayoutParams(-1, dp(40)));

        // Section 1: Built-in curated CN + Global lists
        body.addView(AikoTheme.sectionHeader(this, density, "内置精选规则（中国 + 全球）"));
        List<SiteFilter.BuiltinList> builtins = filter.builtinLists();
        for (SiteFilter.BuiltinList item : builtins) {
            boolean enabled = filter.isListEnabled(item.id);
            int itemCount = filter.builtinRuleCount(item.id);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(13), dp(11), dp(11), dp(11));
            card.setBackground(AikoTheme.card(
                    enabled ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                    density, dp(15)));
            card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(8);
            body.addView(card, clp);

            LinearLayout infoCol = stack();
            card.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            infoCol.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

            TextView titleTv = label(item.title, 13, AikoTheme.TEXT);
            titleTv.setTypeface(AikoTheme.BOLD);
            titleTv.setSingleLine(true);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(titleTv, new LinearLayout.LayoutParams(-2, -2));

            if (item.region != null && !item.region.isEmpty()) {
                int regColor = "中国".equals(item.region) ? AikoTheme.AMBER
                        : ("移动".equals(item.region) ? AikoTheme.EMERALD : AikoTheme.ACCENT_CYAN);
                TextView regPill = AikoTheme.badge(this, density, item.region,
                        regColor, AikoTheme.BG, AikoTheme.STROKE);
                regPill.setTextSize(10);
                regPill.setPadding(dp(7), dp(2), dp(7), dp(2));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(6);
                titleRow.addView(regPill, rlp);
            }

            if (item.desc != null && !item.desc.isEmpty()) {
                TextView descTv = label(item.desc, 11, AikoTheme.TEXT_MUTED);
                descTv.setPadding(0, dp(3), 0, 0);
                infoCol.addView(descTv);
            }

            if (itemCount > 0) {
                TextView cntTv = label(String.format(Locale.ROOT, "约 %,d 条规则", itemCount), 10, AikoTheme.TEXT_DIM);
                cntTv.setPadding(0, dp(2), 0, 0);
                infoCol.addView(cntTv);
            }

            TextView togglePill = AikoTheme.badge(this, density, enabled ? "已开启" : "已关闭",
                    enabled ? AikoTheme.EMERALD : AikoTheme.TEXT_DIM,
                    AikoTheme.BG, enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dp(30));
            tlp.leftMargin = dp(8);
            card.addView(togglePill, tlp);

            AikoTheme.attachPressFeedback(card, 0.97f);
            card.setOnClickListener(v -> {
                togglePill.setText("应用中…");
                card.setEnabled(false);
                filter.setListEnabled(item.id, !enabled, () -> {
                    if (isDestroyed()) return;
                    applyCurrentFilter();
                    toast(item.title + (!enabled ? " 已启用" : " 已停用"));
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                        showFilterRules();
                    }
                });
            });
        }

        // Section 2: Online Subscriptions
        body.addView(AikoTheme.sectionHeader(this, density, "在线订阅规则"));
        List<SiteFilter.Subscription> subs = filter.subscriptions();
        if (subs.isEmpty()) {
            TextView noSubs = label("暂未添加在线订阅，可一键勾选推荐规则库，或粘贴任意 ABP/EasyList 订阅链接。",
                    12, AikoTheme.TEXT_DIM);
            noSubs.setPadding(dp(4), dp(2), dp(4), dp(8));
            body.addView(noSubs);
        } else {
            for (SiteFilter.Subscription sub : subs) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(8), dp(10));
                card.setBackground(AikoTheme.card(
                        sub.enabled ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        sub.enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(14)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.bottomMargin = dp(7);
                body.addView(card, clp);

                LinearLayout info = stack();
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                TextView nameTv = label(sub.name, 13, AikoTheme.TEXT);
                nameTv.setTypeface(AikoTheme.MEDIUM);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(nameTv);

                String meta = sub.lastError != null && !sub.lastError.isEmpty()
                        ? "⚠ " + sub.lastError
                        : String.format(Locale.ROOT, "%,d 条规则", sub.ruleCount)
                        + (sub.updatedAt > 0 ? "  ·  " + formatTimestamp(sub.updatedAt) : "");
                TextView metaTv = label(meta, 11,
                        sub.lastError != null && !sub.lastError.isEmpty() ? AikoTheme.CORAL : AikoTheme.TEXT_MUTED);
                metaTv.setSingleLine(true);
                metaTv.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(metaTv);

                TextView onOff = AikoTheme.badge(this, density, sub.enabled ? "开启" : "停用",
                        sub.enabled ? AikoTheme.EMERALD : AikoTheme.TEXT_DIM,
                        AikoTheme.BG, sub.enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
                onOff.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
                onOff.setOnClickListener(v -> filter.toggleSubscription(sub.id, !sub.enabled, () -> {
                    if (isDestroyed()) return;
                    applyCurrentFilter();
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                        showFilterRules();
                    }
                }));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, dp(30));
                olp.leftMargin = dp(6);
                card.addView(onOff, olp);

                BrowserIcon del = icon(CLOSE, "删除订阅", () -> new AlertDialog.Builder(this)
                        .setTitle("删除规则订阅？")
                        .setMessage(sub.name + "\n" + sub.url)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> filter.removeSubscription(sub.id, () -> {
                            if (isDestroyed()) return;
                            applyCurrentFilter();
                            toast("已删除订阅：" + sub.name);
                            if (dialog.isShowing()) {
                                dialog.dismiss();
                                showFilterRules();
                            }
                        })).show());
                del.tint(AikoTheme.TEXT_DIM);
                AikoTheme.attachPressFeedback(del, 0.90f);
                card.addView(del, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }
        }

        LinearLayout subActions = new LinearLayout(this);
        subActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-1, -2);
        salp.bottomMargin = dp(6);
        body.addView(subActions, salp);

        TextView recBtn = AikoTheme.badge(this, density, "添加推荐订阅",
                AikoTheme.ACCENT_CYAN, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        recBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(recBtn, 0.95f);
        recBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showRecommendedSubsSheet();
        });
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(0, dp(40), 1);
        rblp.rightMargin = dp(8);
        subActions.addView(recBtn, rblp);

        TextView addUrlBtn = AikoTheme.badge(this, density, "+ 自定义订阅链接",
                AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
        addUrlBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(addUrlBtn, 0.95f);
        addUrlBtn.setOnClickListener(v -> showAddCustomSubDialog(dialog));
        subActions.addView(addUrlBtn, new LinearLayout.LayoutParams(0, dp(40), 1));

        // Section 3: Beginner-friendly custom domain block/allow + ElementPicker + ABP editor
        body.addView(AikoTheme.sectionHeader(this, density, "我的自定义规则（小白友好 + 极客兼容）"));
        LinearLayout quickDomainCard = stack();
        quickDomainCard.setPadding(dp(12), dp(11), dp(12), dp(11));
        quickDomainCard.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        LinearLayout.LayoutParams qdl = new LinearLayout.LayoutParams(-1, -2);
        qdl.bottomMargin = dp(8);
        body.addView(quickDomainCard, qdl);

        TextView qdTitle = label("快速屏蔽 / 放行一个网址", 13, AikoTheme.TEXT);
        qdTitle.setTypeface(AikoTheme.MEDIUM);
        quickDomainCard.addView(qdTitle);

        TextView qdHint = label("输入域名即可一键生成标准规则，无需手写语法", 11, AikoTheme.TEXT_DIM);
        qdHint.setPadding(0, dp(2), 0, dp(8));
        quickDomainCard.addView(qdHint);

        String prefillHost = (current != null && current.cachedHost != null) ? current.cachedHost : "";
        EditText domainInput = input("例如：ads.example.com");
        domainInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        domainInput.setText(prefillHost);
        domainInput.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(12)));
        domainInput.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams dilp = new LinearLayout.LayoutParams(-1, dp(40));
        dilp.bottomMargin = dp(8);
        quickDomainCard.addView(domainInput, dilp);

        LinearLayout qdBtns = new LinearLayout(this);
        qdBtns.setOrientation(LinearLayout.HORIZONTAL);
        quickDomainCard.addView(qdBtns, new LinearLayout.LayoutParams(-1, -2));

        TextView blockDomainBtn = AikoTheme.badge(this, density, "屏蔽该域",
                AikoTheme.CORAL, AikoTheme.PILL, AikoTheme.STROKE);
        blockDomainBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(blockDomainBtn, 0.95f);
        blockDomainBtn.setOnClickListener(v -> {
            String host = domainInput.getText().toString().trim();
            if (host.isEmpty()) {
                domainInput.setError("请输入域名");
                return;
            }
            blockDomainBtn.setEnabled(false);
            filter.addDomainBlockRule(host, ok -> {
                if (isDestroyed()) return;
                blockDomainBtn.setEnabled(true);
                if (Boolean.TRUE.equals(ok)) {
                    applyCurrentFilter();
                    toast("已添加屏蔽规则：" + host);
                } else {
                    toast("域名无效或规则保存失败");
                }
            });
        });
        LinearLayout.LayoutParams bdlp = new LinearLayout.LayoutParams(0, dp(38), 1);
        bdlp.rightMargin = dp(8);
        qdBtns.addView(blockDomainBtn, bdlp);

        TextView allowDomainBtn = AikoTheme.badge(this, density, "放行该域",
                AikoTheme.EMERALD, AikoTheme.PILL, AikoTheme.STROKE);
        allowDomainBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(allowDomainBtn, 0.95f);
        allowDomainBtn.setOnClickListener(v -> {
            String host = domainInput.getText().toString().trim();
            if (host.isEmpty()) {
                domainInput.setError("请输入域名");
                return;
            }
            allowDomainBtn.setEnabled(false);
            filter.addDomainAllowRule(host, ok -> {
                if (isDestroyed()) return;
                allowDomainBtn.setEnabled(true);
                if (Boolean.TRUE.equals(ok)) {
                    applyCurrentFilter();
                    toast("已添加放行规则：" + host);
                } else {
                    toast("域名无效或规则保存失败");
                }
            });
        });
        qdBtns.addView(allowDomainBtn, new LinearLayout.LayoutParams(0, dp(38), 1));

        boolean canPick = current != null && current.view != null && current.cachedHost != null && !onHome;
        addTwoColRow(body,
                AikoTheme.toggleTile(this, density, PICKER, "点选网页元素",
                        canPick ? "点击广告直接屏蔽" : "打开网页后可用",
                        false, canPick, AikoTheme.ACCENT, () -> {
                            dialog.dismiss();
                            if (current != null && current.view != null && current.cachedHost != null && elementPicker != null) {
                                elementPicker.start(current.view, current.cachedHost, current.url);
                            }
                        }),
                AikoTheme.toggleTile(this, density, CODE, "高级 ABP 编辑",
                        "手动编写/管理原文",
                        false, true, AikoTheme.ACCENT_CYAN, () -> {
                            dialog.dismiss();
                            showCustomFilters();
                        }));

        showSheet(dialog, panel);
    }

    private void showRecommendedSubsSheet() {
        SiteFilter filter = filter();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "推荐在线规则订阅", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(480, Math.max(240, getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        Set<String> subscribedUrls = new HashSet<>();
        for (SiteFilter.Subscription s : filter.subscriptions()) {
            if (s.url != null) subscribedUrls.add(s.url.trim().toLowerCase(Locale.ROOT));
        }

        List<SiteFilter.RecommendedSub> catalog = filter.recommendedSubscriptions();
        for (SiteFilter.RecommendedSub rec : catalog) {
            boolean already = rec.url != null && subscribedUrls.contains(rec.url.trim().toLowerCase(Locale.ROOT));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(13), dp(11), dp(11), dp(11));
            card.setBackground(AikoTheme.card(
                    already ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    already ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                    density, dp(15)));
            card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(8);
            body.addView(card, clp);

            LinearLayout info = stack();
            card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            info.addView(top);

            TextView name = label(rec.name, 13, AikoTheme.TEXT);
            name.setTypeface(AikoTheme.BOLD);
            top.addView(name);

            if (rec.region != null && !rec.region.isEmpty()) {
                TextView reg = AikoTheme.badge(this, density, rec.region,
                        "中国".equals(rec.region) ? AikoTheme.AMBER : AikoTheme.ACCENT_CYAN,
                        AikoTheme.BG, AikoTheme.STROKE);
                reg.setTextSize(10);
                reg.setPadding(dp(7), dp(2), dp(7), dp(2));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(6);
                top.addView(reg, rlp);
            }

            TextView desc = label(rec.desc, 11, AikoTheme.TEXT_MUTED);
            desc.setPadding(0, dp(3), 0, 0);
            info.addView(desc);

            TextView actionBtn = AikoTheme.badge(this, density, already ? "✓ 已订阅" : "一键订阅",
                    already ? AikoTheme.EMERALD : AikoTheme.onAccentColor(),
                    already ? AikoTheme.BG : AikoTheme.ACCENT,
                    already ? AikoTheme.STROKE_ACCENT : 0);
            LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(-2, dp(32));
            ablp.leftMargin = dp(8);
            card.addView(actionBtn, ablp);

            if (!already) {
                AikoTheme.attachPressFeedback(card, 0.97f);
                card.setOnClickListener(v -> {
                    card.setEnabled(false);
                    actionBtn.setText("下载中…");
                    filter.addSubscription(rec.name, rec.url, err -> {
                        if (isDestroyed()) return;
                        if (err == null) {
                            applyCurrentFilter();
                            toast("已订阅并启用：" + rec.name);
                            if (dialog.isShowing()) {
                                dialog.dismiss();
                                showFilterRules();
                            }
                        } else {
                            card.setEnabled(true);
                            actionBtn.setText("重试订阅");
                            toast("订阅失败：" + err);
                        }
                    });
                });
            }
        }

        showSheet(dialog, panel);
    }

    private void showAddCustomSubDialog(Dialog parentSheet) {
        LinearLayout fields = stack();
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nameInput = input("规则名称（如：我的广告规则）");
        EditText urlInput = input("https://example.com/rules.txt");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(48)));
        fields.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("添加自定义订阅链接")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载并订阅", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!HttpUrlUtil.isHttp(url)) {
                urlInput.setError("请输入有效的 HTTP/HTTPS 规则链接");
                return;
            }
            String rawName = nameInput.getText().toString().trim();
            final String subName = rawName.isEmpty() ? HttpUrlUtil.extractHost(url) : rawName;
            v.setEnabled(false);
            toast("正在下载并验证规则…");
            filter().addSubscription(subName, url, err -> {
                if (isDestroyed()) return;
                if (err == null) {
                    if (d.isShowing()) d.dismiss();
                    applyCurrentFilter();
                    toast("已添加并启用订阅：" + subName);
                    if (parentSheet != null && parentSheet.isShowing()) {
                        parentSheet.dismiss();
                        showFilterRules();
                    }
                } else {
                    v.setEnabled(true);
                    urlInput.setError(err);
                    toast("订阅失败：" + err);
                }
            });
        });
    }

    private static String formatTimestamp(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    // ==================== GREASY FORK 1-TAP INSTALL & USERSCRIPTS CENTER ====================

    private void closeTransientPopupTabAsync(Tab tab) {
        if (tab == null || tab.url != null || tab.canGoBack || tabs.size() <= 1) return;
        handler.post(() -> {
            if (!isDestroyed() && tab.url == null && !tab.canGoBack && tabs.size() > 1) {
                closeTab(tab);
            }
        });
    }

    private static boolean isGreasyForkHost(String host) {
        if (host == null) return false;
        return "greasyfork.org".equals(host) || host.endsWith(".greasyfork.org")
                || "sleazyfork.org".equals(host) || host.endsWith(".sleazyfork.org");
    }

    private static boolean isUserScriptDownload(String url, String disposition, String guessedFilename) {
        if (UserScripts.isUserScriptUrl(url)) return true;
        if (UserScripts.isUserScriptFilename(guessedFilename)) return true;
        return disposition != null && disposition.toLowerCase(Locale.ROOT).contains(".user.js");
    }

    private void handleRemoteUserScript(String url, String fallbackFilename, String ua) {
        if (!HttpUrlUtil.isHttp(url)) return;
        if (fetchingRemoteScript) {
            toast("正在读取油猴脚本，请稍候…");
            return;
        }
        fetchingRemoteScript = true;
        toast("发现 .user.js 脚本，正在解析…");
        userScripts.fetchRemoteScript(url, remote -> {
            fetchingRemoteScript = false;
            if (isDestroyed() || isFinishing()) return;
            if (remote == null || remote.error != null || remote.parsed == null) {
                String err = (remote != null && remote.error != null && !remote.error.isEmpty())
                        ? remote.error : "无法识别为有效的油猴脚本。";
                String fname = (fallbackFilename == null || fallbackFilename.isEmpty())
                        ? URLUtil.guessFileName(url, null, "application/javascript") : fallbackFilename;
                new AlertDialog.Builder(this)
                        .setTitle("无法直接安装此脚本")
                        .setMessage(err + "\n\n你仍可选择将其作为普通文件下载。")
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("普通下载", (d, w) -> showNewDownload(url, fname, ua))
                        .show();
                return;
            }
            showRemoteUserScriptSheet(remote, fallbackFilename, ua);
        });
    }

    void showLocalUserScriptSheet(UserScript script, UserScripts.Entry previous, String sourceLabel, Runnable onInstalled) {
        if (script == null) return;
        String srcLabel = (sourceLabel == null || sourceLabel.isEmpty()) ? "本地文件" : sourceLabel;
        UserScripts.RemoteScript synthetic = new UserScripts.RemoteScript(
                "", script.source, null, script, previous != null, previous == null ? null : previous.id);
        showUserScriptReviewSheet(synthetic, srcLabel, null, null, onInstalled);
    }

    private void showRemoteUserScriptSheet(UserScripts.RemoteScript remote, String fallbackFilename, String ua) {
        showUserScriptReviewSheet(remote, HttpUrlUtil.extractHost(remote.url), fallbackFilename, ua, null);
    }

    private void showUserScriptReviewSheet(UserScripts.RemoteScript remote, String originBadge,
                                           String fallbackFilename, String ua, Runnable onInstalled) {
        UserScript script = remote.parsed;
        boolean hasRemoteUrl = HttpUrlUtil.isHttp(remote.url);
        String fname = hasRemoteUrl
                ? ((fallbackFilename == null || fallbackFilename.isEmpty())
                        ? URLUtil.guessFileName(remote.url, null, "application/javascript") : fallbackFilename)
                : "";
        String author = script.author != null ? script.author : "";
        String desc = script.description != null ? script.description : "";

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density,
                remote.isUpdate ? "更新油猴脚本" : "导入油猴脚本", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(480, Math.max(240, getResources().getConfiguration().screenHeightDp - 190));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Script Identity Card
        LinearLayout hero = stack();
        hero.setPadding(dp(14), dp(12), dp(14), dp(12));
        hero.setBackground(AikoTheme.glowBanner(dp(16)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(4);
        body.addView(hero, hlp);

        TextView nameTv = label(script.name, 16, AikoTheme.TEXT);
        nameTv.setTypeface(AikoTheme.BOLD);
        hero.addView(nameTv);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrlp = new LinearLayout.LayoutParams(-1, -2);
        mrlp.topMargin = dp(6);
        hero.addView(metaRow, mrlp);

        if (!script.version.isEmpty()) {
            TextView verPill = AikoTheme.badge(this, density, "v" + script.version,
                    AikoTheme.ACCENT_CYAN, AikoTheme.BG, AikoTheme.STROKE);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.rightMargin = dp(6);
            metaRow.addView(verPill, vlp);
        }
        if (remote.isUpdate) {
            TextView updPill = AikoTheme.badge(this, density, "覆盖更新 · 保留数据",
                    AikoTheme.EMERALD, AikoTheme.BG, AikoTheme.STROKE_ACCENT);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
            ulp.rightMargin = dp(6);
            metaRow.addView(updPill, ulp);
        }
        if (originBadge != null && !originBadge.isEmpty()) {
            TextView hostPill = AikoTheme.badge(this, density, originBadge,
                    AikoTheme.TEXT_MUTED, AikoTheme.BG, AikoTheme.STROKE);
            metaRow.addView(hostPill, new LinearLayout.LayoutParams(-2, -2));
        }

        if (!author.isEmpty()) {
            TextView authorTv = label("作者：" + author, 12, AikoTheme.TEXT_MUTED);
            authorTv.setPadding(0, dp(6), 0, 0);
            hero.addView(authorTv);
        }
        if (!desc.isEmpty()) {
            TextView descTv = label(desc, 12, AikoTheme.TEXT_MUTED);
            descTv.setPadding(0, dp(4), 0, 0);
            hero.addView(descTv);
        }

        // Matched Sites (@match / @include)
        body.addView(AikoTheme.sectionHeader(this, density, "生效站点（" + script.includes.size() + " 条规则 · " + script.runAt + "）"));
        LinearLayout matchBox = stack();
        matchBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        matchBox.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        body.addView(matchBox, new LinearLayout.LayoutParams(-1, -2));

        int showMatches = Math.min(script.includes.size(), 6);
        for (int i = 0; i < showMatches; i++) {
            String pat = script.includes.get(i).original;
            boolean broad = "*".equals(pat) || "*://*/*".equals(pat) || "<all_urls>".equals(pat)
                    || "http://*/*".equals(pat) || "https://*/*".equals(pat);
            TextView mTv = label((broad ? "⚠ 全部网站：" : "• ") + pat, 12,
                    broad ? AikoTheme.AMBER : AikoTheme.TEXT_MUTED);
            mTv.setTypeface(AikoTheme.MONO);
            mTv.setSingleLine(true);
            mTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            if (i > 0) mTv.setPadding(0, dp(3), 0, 0);
            matchBox.addView(mTv);
        }
        if (script.includes.size() > showMatches) {
            TextView moreTv = label("… 以及其余 " + (script.includes.size() - showMatches) + " 条站点规则",
                    11, AikoTheme.TEXT_DIM);
            moreTv.setPadding(0, dp(4), 0, 0);
            matchBox.addView(moreTv);
        }

        // Permissions (@grant badges) — FlowTagLayout wraps long grants cleanly without right-edge clipping!
        body.addView(AikoTheme.sectionHeader(this, density, "脚本权限 (@grant)"));
        FlowTagLayout grantFlow = new FlowTagLayout(this, dp(6), dp(6));
        boolean hasHighRisk = false;
        if (script.grants.isEmpty()) {
            TextView safeBadge = AikoTheme.badge(this, density, "✓ 无特殊 GM 权限（隔离/安全运行）",
                    AikoTheme.EMERALD, AikoTheme.CARD, AikoTheme.STROKE);
            safeBadge.setEllipsize(TextUtils.TruncateAt.END);
            grantFlow.addView(safeBadge);
        } else {
            for (String g : script.grants) {
                boolean highRisk = g.toLowerCase(Locale.ROOT).contains("xmlhttprequest")
                        || "unsafeWindow".equals(g) || g.toLowerCase(Locale.ROOT).contains("cookie");
                if (highRisk) hasHighRisk = true;
                TextView gBadge = AikoTheme.badge(this, density, (highRisk ? "⚠ " : "") + g,
                        highRisk ? AikoTheme.CORAL : AikoTheme.ACCENT_CYAN,
                        AikoTheme.CARD, highRisk ? AikoTheme.CORAL : AikoTheme.STROKE);
                gBadge.setEllipsize(TextUtils.TruncateAt.END);
                grantFlow.addView(gBadge);
            }
        }
        body.addView(grantFlow, new LinearLayout.LayoutParams(-1, -2));
        if (hasHighRisk) {
            TextView warnTv = label("⚠ 注意：此脚本申请了跨域网络/高权限接口，仅安装你信任的来源。",
                    11, AikoTheme.AMBER);
            warnTv.setPadding(dp(4), dp(6), dp(4), 0);
            body.addView(warnTv);
        }

        // Declared @connect hosts (wrapped cleanly via FlowTagLayout)
        if (!script.connects.isEmpty()) {
            body.addView(AikoTheme.sectionHeader(this, density, "网络白名单 (@connect · " + script.connects.size() + ")"));
            FlowTagLayout connectFlow = new FlowTagLayout(this, dp(6), dp(6));
            for (String hostRule : script.connects) {
                boolean wildcard = "*".equals(hostRule);
                TextView cBadge = AikoTheme.badge(this, density, (wildcard ? "⚠ * (任意域)" : hostRule),
                        wildcard ? AikoTheme.AMBER : AikoTheme.TEXT_MUTED,
                        AikoTheme.CARD, wildcard ? AikoTheme.AMBER : AikoTheme.STROKE);
                cBadge.setEllipsize(TextUtils.TruncateAt.END);
                connectFlow.addView(cBadge);
            }
            body.addView(connectFlow, new LinearLayout.LayoutParams(-1, -2));
        }

        // Declared @require / @resource dependencies count
        if (!script.requires.isEmpty() || !script.resources.isEmpty()) {
            body.addView(AikoTheme.sectionHeader(this, density, "外部依赖与资源缓存"));
            StringBuilder depLine = new StringBuilder();
            if (!script.requires.isEmpty()) depLine.append("@require 依赖库：").append(script.requires.size()).append(" 项");
            if (!script.resources.isEmpty()) {
                if (depLine.length() > 0) depLine.append("  ·  ");
                depLine.append("@resource 资源：").append(script.resources.size()).append(" 项");
            }
            depLine.append("\n安装后将自动异步下载并持久化缓存到本地，离线可直接运行。");
            TextView depTv = label(depLine.toString(), 11, AikoTheme.TEXT_MUTED);
            depTv.setPadding(dp(12), dp(9), dp(12), dp(9));
            depTv.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(13)));
            body.addView(depTv, new LinearLayout.LayoutParams(-1, -2));
        }

        // Expandable Source Preview
        String rawSrc = remote.source != null ? remote.source : script.source;
        String previewText = rawSrc.length() > 16384
                ? rawSrc.substring(0, 16384) + "\n\n/* … 源码较长，仅预览前 16 KiB … */" : rawSrc;
        TextView toggleSrc = AikoTheme.badge(this, density,
                "查看源码预览（" + rawSrc.length() + " 字符）▾",
                AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
        toggleSrc.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(-1, dp(38));
        tslp.topMargin = dp(10);
        body.addView(toggleSrc, tslp);

        TextView codeView = label(previewText, 11, AikoTheme.TEXT_MUTED);
        codeView.setTypeface(AikoTheme.MONO);
        codeView.setPadding(dp(10), dp(10), dp(10), dp(10));
        codeView.setBackground(AikoTheme.card(AikoTheme.BG, AikoTheme.STROKE, density, dp(12)));
        codeView.setVisibility(View.GONE);
        LinearLayout.LayoutParams cvlp = new LinearLayout.LayoutParams(-1, -2);
        cvlp.topMargin = dp(6);
        body.addView(codeView, cvlp);

        toggleSrc.setOnClickListener(v -> {
            boolean showing = codeView.getVisibility() == View.VISIBLE;
            codeView.setVisibility(showing ? View.GONE : View.VISIBLE);
            toggleSrc.setText(showing
                    ? "查看源码预览（" + rawSrc.length() + " 字符）▾"
                    : "收起源码预览 ▴");
        });

        // Primary Install / Update button + Secondary Row
        TextView installBtn = AikoTheme.badge(this, density,
                remote.isUpdate ? "立即覆盖更新（保留脚本数据）" : "一键安装并启用",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        installBtn.setTextSize(14);
        installBtn.setTypeface(AikoTheme.BOLD);
        installBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(installBtn, 0.96f);
        installBtn.setOnClickListener(v -> {
            installBtn.setEnabled(false);
            boolean hasDeps = !script.requires.isEmpty() || !script.resources.isEmpty();
            installBtn.setText(hasDeps ? "正在缓存依赖并安装…" : "正在安装…");
            userScripts.installRemote(remote, err -> {
                if (isDestroyed()) return;
                if (err != null && !err.isEmpty()) {
                    installBtn.setEnabled(true);
                    installBtn.setText(remote.isUpdate ? "立即覆盖更新（保留脚本数据）" : "一键安装并启用");
                    toast("安装失败：" + err);
                    return;
                }
                UserScripts.Entry installed = userScripts.duplicate(script);
                if (installed != null && hasDeps) {
                    userScripts.prepareDependenciesAsync(installed, null);
                }
                dialog.dismiss();
                if (onInstalled != null) onInstalled.run();
                toast(remote.isUpdate
                        ? "已更新并启用「" + script.name + "」（原数据已保留）"
                        : "已安装并启用「" + script.name + "」，刷新匹配网页生效");
            });
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(46));
        ilp.topMargin = dp(10);
        panel.addView(installBtn, ilp);

        LinearLayout secRow = new LinearLayout(this);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(8);
        panel.addView(secRow, srlp);

        if (hasRemoteUrl) {
            TextView dlBtn = AikoTheme.badge(this, density, "普通下载",
                    AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
            dlBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
            dlBtn.setOnClickListener(v -> {
                dialog.dismiss();
                showNewDownload(remote.url, fname, ua);
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(38), 1);
            dlp.rightMargin = dp(8);
            secRow.addView(dlBtn, dlp);
        } else {
            TextView saveOffBtn = AikoTheme.badge(this, density, "仅保存暂不启用",
                    AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
            saveOffBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
            saveOffBtn.setOnClickListener(v -> {
                try {
                    UserScripts.Entry prev = userScripts.duplicate(script);
                    userScripts.installLocal(script, prev, false);
                    dialog.dismiss();
                    if (onInstalled != null) onInstalled.run();
                    toast("已保存「" + script.name + "」（当前停用）");
                } catch (IllegalArgumentException err) {
                    toast(err.getMessage());
                }
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(38), 1);
            dlp.rightMargin = dp(8);
            secRow.addView(saveOffBtn, dlp);
        }

        TextView cancelBtn = AikoTheme.badge(this, density, "取消",
                AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
        cancelBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        secRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(38), 1));

        showSheet(dialog, panel);
    }

    private void showUserScriptsSheet() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "油猴脚本管理", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Top Greasy Fork + Import action bar
        TextView gfBtn = AikoTheme.badge(this, density, "前往 Greasy Fork 发现脚本（支持一键安装）",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        gfBtn.setTextSize(13);
        gfBtn.setTypeface(AikoTheme.BOLD);
        gfBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(gfBtn, 0.96f);
        gfBtn.setOnClickListener(v -> {
            dialog.dismiss();
            addTab(GREASYFORK_URL, false);
        });
        LinearLayout.LayoutParams gflp = new LinearLayout.LayoutParams(-1, dp(42));
        gflp.topMargin = dp(4);
        gflp.bottomMargin = dp(8);
        body.addView(gfBtn, gflp);

        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(-1, -2);
        irlp.bottomMargin = dp(4);
        body.addView(importRow, irlp);

        TextView importFileBtn = AikoTheme.badge(this, density, "本地 .user.js",
                AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
        importFileBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(importFileBtn, 0.95f);
        importFileBtn.setOnClickListener(v -> {
            dialog.dismiss();
            scriptTools.openFile();
        });
        LinearLayout.LayoutParams imlp = new LinearLayout.LayoutParams(0, dp(38), 1);
        imlp.rightMargin = dp(6);
        importRow.addView(importFileBtn, imlp);

        TextView pasteBtn = AikoTheme.badge(this, density, "粘贴脚本",
                AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
        pasteBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(pasteBtn, 0.95f);
        pasteBtn.setOnClickListener(v -> {
            dialog.dismiss();
            scriptTools.openEditor(null);
        });
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(0, dp(38), 1);
        pslp.rightMargin = dp(6);
        importRow.addView(pasteBtn, pslp);

        TextView urlImportBtn = AikoTheme.badge(this, density, "网址安装",
                AikoTheme.ACCENT_CYAN, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        urlImportBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(urlImportBtn, 0.95f);
        urlImportBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showInstallScriptUrlDialog();
        });
        LinearLayout.LayoutParams uimlp = new LinearLayout.LayoutParams(0, dp(38), 1);
        uimlp.rightMargin = dp(6);
        importRow.addView(urlImportBtn, uimlp);

        TextView checkUpdBtn = AikoTheme.badge(this, density, "检查更新",
                AikoTheme.EMERALD, AikoTheme.CARD, AikoTheme.STROKE);
        checkUpdBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(checkUpdBtn, 0.95f);
        checkUpdBtn.setOnClickListener(v -> {
            checkUpdBtn.setEnabled(false);
            checkUpdBtn.setText("检查中…");
            userScripts.checkUpdates(this, (updates, checked, failed) -> {
                if (isDestroyed()) return;
                checkUpdBtn.setEnabled(true);
                checkUpdBtn.setText("检查更新");
                if (checked == 0) {
                    toast("已安装脚本均未配置远程更新地址");
                    return;
                }
                if (updates.isEmpty()) {
                    toast(failed > 0
                            ? "已检查 " + checked + " 个脚本，暂无更新（" + failed + " 个请求失败）"
                            : "全部 " + checked + " 个脚本均为最新版本");
                    return;
                }
                dialog.dismiss();
                AikoV040Ui.showScriptUpdatesSheet(v040Host, updates, this::showUserScriptsSheet);
            });
        });
        importRow.addView(checkUpdBtn, new LinearLayout.LayoutParams(0, dp(38), 1));

        // Active scripts & live GM_registerMenuCommand buttons on current page
        String curUrl = (current != null && !onHome && HttpUrlUtil.isHttp(current.url)) ? current.url : null;
        if (curUrl != null) {
            body.addView(AikoTheme.sectionHeader(this, density, "当前页面已生效的脚本"));
            List<UserScript> matched = userScripts.matchingForUrl(curUrl);
            if (matched.isEmpty()) {
                TextView noneActive = label("当前页面暂无已启用的匹配脚本", 12, AikoTheme.TEXT_DIM);
                noneActive.setPadding(dp(4), dp(2), dp(4), dp(6));
                body.addView(noneActive);
            } else {
                for (UserScript s : matched) {
                    LinearLayout activeCard = new LinearLayout(this);
                    activeCard.setOrientation(LinearLayout.HORIZONTAL);
                    activeCard.setGravity(Gravity.CENTER_VERTICAL);
                    activeCard.setPadding(dp(12), dp(9), dp(12), dp(9));
                    activeCard.setBackground(AikoTheme.card(AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT, density, dp(13)));
                    LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(-1, -2);
                    aclp.bottomMargin = dp(6);
                    body.addView(activeCard, aclp);

                    TextView dot = label("●", 11, AikoTheme.EMERALD);
                    LinearLayout.LayoutParams dtlp = new LinearLayout.LayoutParams(-2, -2);
                    dtlp.rightMargin = dp(8);
                    activeCard.addView(dot, dtlp);

                    TextView sn = label(s.name + (s.version.isEmpty() ? "" : "  v" + s.version), 13, AikoTheme.TEXT);
                    sn.setTypeface(AikoTheme.MEDIUM);
                    sn.setSingleLine(true);
                    sn.setEllipsize(TextUtils.TruncateAt.END);
                    activeCard.addView(sn, new LinearLayout.LayoutParams(0, -2, 1));

                    TextView runBadge = label(s.runAt, 10, AikoTheme.ACCENT_CYAN);
                    runBadge.setTypeface(AikoTheme.MONO);
                    activeCard.addView(runBadge);
                }
            }

            List<UserScripts.MenuCommand> liveCmds = current.view != null
                    ? userScripts.menuCommandsFor(current.view) : Collections.emptyList();
            if (!liveCmds.isEmpty()) {
                body.addView(AikoTheme.sectionHeader(this, density, "脚本注册菜单命令（" + liveCmds.size() + "）"));
                FlowTagLayout cmdFlow = new FlowTagLayout(this, dp(6), dp(6));
                for (UserScripts.MenuCommand cmd : liveCmds) {
                    TextView cmdBtn = AikoTheme.badge(this, density, "▸ " + cmd.caption,
                            AikoTheme.ACCENT_CYAN, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
                    cmdBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
                    AikoTheme.attachPressFeedback(cmdBtn, 0.94f);
                    cmdBtn.setOnClickListener(v -> {
                        dialog.dismiss();
                        if (current != null && current.view != null) {
                            userScripts.invokeMenuCommand(current.view, cmd.cmdId);
                        }
                    });
                    cmdFlow.addView(cmdBtn);
                }
                body.addView(cmdFlow, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        // All installed scripts
        List<UserScripts.Entry> entries = userScripts.items();
        body.addView(AikoTheme.sectionHeader(this, density,
                "全部已安装脚本（" + entries.size() + "） · " + userScripts.status()));
        if (entries.isEmpty()) {
            TextView empty = label(
                    "在 Greasy Fork 点击任意脚本的「安装此脚本」，Aiko 会自动识别并弹窗一键安装与更新。",
                    12, AikoTheme.TEXT_MUTED);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            empty.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (UserScripts.Entry entry : entries) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(8), dp(10));
                card.setBackground(AikoTheme.card(
                        entry.enabled ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        entry.enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(14)));
                card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.bottomMargin = dp(7);
                body.addView(card, clp);

                LinearLayout info = stack();
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                TextView nm = label(entry.script.name, 13, AikoTheme.TEXT);
                nm.setTypeface(AikoTheme.MEDIUM);
                nm.setSingleLine(true);
                nm.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(nm);

                String firstMatch = entry.script.includes.isEmpty() ? "" : entry.script.includes.get(0).original;
                String sub = (entry.script.version.isEmpty() ? "" : "v" + entry.script.version + " · ")
                        + firstMatch
                        + (entry.script.includes.size() > 1 ? " 等" + entry.script.includes.size() + "处" : "");
                TextView subTv = label(sub, 11, AikoTheme.TEXT_DIM);
                subTv.setSingleLine(true);
                subTv.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(subTv);

                TextView toggleBtn = AikoTheme.badge(this, density, entry.enabled ? "已启用" : "已停用",
                        entry.enabled ? AikoTheme.EMERALD : AikoTheme.TEXT_DIM,
                        AikoTheme.BG, entry.enabled ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE);
                toggleBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
                toggleBtn.setOnClickListener(v -> {
                    boolean next = !entry.enabled;
                    String compat = userScripts.compatibility(entry);
                    if (next && !compat.isEmpty()) {
                        toast(compat);
                        return;
                    }
                    userScripts.setEnabled(entry, next);
                    toast(next ? "已启用，刷新匹配网页生效" : "已停用，刷新页面停止运行");
                    dialog.dismiss();
                    showUserScriptsSheet();
                });
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dp(30));
                tlp.leftMargin = dp(6);
                card.addView(toggleBtn, tlp);

                BrowserIcon del = icon(CLOSE, "删除脚本", () -> new AlertDialog.Builder(this)
                        .setTitle("删除油猴脚本？")
                        .setMessage(entry.script.name)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> {
                            userScripts.delete(entry);
                            toast("已删除脚本：" + entry.script.name);
                            dialog.dismiss();
                            showUserScriptsSheet();
                        }).show());
                del.tint(AikoTheme.TEXT_DIM);
                AikoTheme.attachPressFeedback(del, 0.90f);
                card.addView(del, new LinearLayout.LayoutParams(dp(36), dp(36)));

                card.setOnClickListener(v -> showScriptEntryDetail(entry, dialog));
            }
        }

        showSheet(dialog, panel);
    }

    private void showInstallScriptUrlDialog() {
        EditText urlInput = input("https://update.greasyfork.org/scripts/.../demo.user.js");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = stack();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("从网址一键安装脚本")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("解析安装", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!HttpUrlUtil.isHttp(url)) {
                urlInput.setError("请输入有效的 HTTP/HTTPS 脚本地址");
                return;
            }
            d.dismiss();
            handleRemoteUserScript(url, URLUtil.guessFileName(url, null, "application/javascript"), null);
        });
    }

    private void showScriptEntryDetail(UserScripts.Entry entry, Dialog parentSheet) {
        UserScript s = entry.script;
        StringBuilder sb = new StringBuilder();
        sb.append(entry.enabled ? "状态：已启用" : "状态：已停用");
        if (!s.version.isEmpty()) sb.append("\n版本：").append(s.version);
        if (!s.namespace.isEmpty()) sb.append("\n命名空间：").append(s.namespace);
        sb.append("\n运行时机：").append(s.runAt);
        sb.append("\n授权：").append(s.grants.isEmpty() ? "无 GM 授权" : String.join("、", s.grants));
        sb.append("\n\n匹配网址：");
        for (UserScript.UrlPattern p : s.includes) sb.append('\n').append(p.original);
        String compat = userScripts.compatibility(entry);
        if (!compat.isEmpty()) sb.append("\n\n").append(compat);

        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setMessage(sb.toString())
                .setNeutralButton("删除", (d, w) -> {
                    userScripts.delete(entry);
                    toast("已删除脚本");
                    if (parentSheet != null && parentSheet.isShowing()) {
                        parentSheet.dismiss();
                        showUserScriptsSheet();
                    }
                })
                .setNegativeButton("关闭", null)
                .setPositiveButton(entry.enabled ? "停用" : "启用", (d, w) -> {
                    boolean next = !entry.enabled;
                    if (next && !compat.isEmpty()) {
                        toast(compat);
                        return;
                    }
                    userScripts.setEnabled(entry, next);
                    toast(next ? "已启用，刷新页面生效" : "已停用");
                    if (parentSheet != null && parentSheet.isShowing()) {
                        parentSheet.dismiss();
                        showUserScriptsSheet();
                    }
                })
                .show();
    }

    // ==================== VIDEO SPEED BOOSTER SHEET ====================

    private void showVideoBoosterSheet(Tab tab) {
        if (tab == null || tab.view == null) return;
        WebView web = tab.view;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "视频倍速控制器", dialog::dismiss);

        TextView statusTv = label("正在检测当前页面 HTML5 视频…", 13, AikoTheme.ACCENT_CYAN);
        statusTv.setTypeface(AikoTheme.MEDIUM);
        statusTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusTv.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.topMargin = dp(4);
        stlp.bottomMargin = dp(10);
        panel.addView(statusTv, stlp);

        final float[] rates = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f};
        final float[] activeRate = {1.0f};
        final boolean[] videoMuted = {false};
        final TextView[] rateBtns = new TextView[rates.length];
        final TextView[] muteBtnRef = new TextView[1];

        Runnable syncSpeedButtons = () -> {
            for (int i = 0; i < rates.length; i++) {
                boolean sel = Math.abs(activeRate[0] - rates[i]) < 0.02f;
                rateBtns[i].setTextColor(sel ? AikoTheme.ACCENT : AikoTheme.TEXT);
                rateBtns[i].setBackground(AikoTheme.card(
                        sel ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        sel ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(13)));
            }
        };

        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(8);
            panel.addView(row, rlp);

            for (int col = 0; col < 4; col++) {
                int idx = rowIdx * 4 + col;
                final float r = rates[idx];
                final String lbl = (r == Math.floor(r))
                        ? String.format(Locale.ROOT, "%.1fx", r)
                        : String.format(Locale.ROOT, "%sx", Float.toString(r).replaceAll("0+$", ""));
                TextView btn = AikoTheme.badge(this, density, lbl, AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
                btn.setTextSize(13);
                btn.setTypeface(AikoTheme.MONO);
                btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(13)));
                AikoTheme.attachPressFeedback(btn, 0.94f);
                btn.setOnClickListener(v -> {
                    activeRate[0] = r;
                    syncSpeedButtons.run();
                    web.evaluateJavascript(VideoBooster.setRateJs(r), raw -> {
                        if (isDestroyed() || !dialog.isShowing()) return;
                        int cnt = 0;
                        try {
                            if (raw != null && !raw.equals("null")) {
                                cnt = new JSONObject(raw).optInt("count", 0);
                            }
                        } catch (Exception ignored) { }
                        statusTv.setText(cnt > 0
                                ? String.format(Locale.ROOT, "已应用到 %d 个视频  ·  当前倍速 %s", cnt, lbl)
                                : "已预设倍速 " + lbl + "（视频开始播放后自动生效）");
                    });
                });
                rateBtns[idx] = btn;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
                if (col < 3) lp.rightMargin = dp(7);
                row.addView(btn, lp);
            }
        }
        syncSpeedButtons.run();

        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-1, -2);
        crlp.topMargin = dp(4);
        panel.addView(ctrlRow, crlp);

        TextView muteBtn = AikoTheme.badge(this, density, "静音切换",
                AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
        muteBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(muteBtn, 0.94f);
        muteBtn.setOnClickListener(v -> web.evaluateJavascript(VideoBooster.toggleMuteJs(), raw -> {
            if (isDestroyed()) return;
            try {
                if (raw != null && !raw.equals("null")) {
                    JSONObject obj = new JSONObject(raw);
                    int cnt = obj.optInt("count", 0);
                    videoMuted[0] = obj.optBoolean("muted", false);
                    muteBtn.setText(videoMuted[0] ? "取消静音" : "视频静音");
                    toast(cnt == 0 ? "当前页面未检测到视频" : (videoMuted[0] ? "已静音网页视频" : "已恢复视频声音"));
                }
            } catch (Exception ignored) { }
        }));
        muteBtnRef[0] = muteBtn;
        LinearLayout.LayoutParams mblp = new LinearLayout.LayoutParams(0, dp(40), 1);
        mblp.rightMargin = dp(7);
        ctrlRow.addView(muteBtn, mblp);

        TextView pipBtn = AikoTheme.badge(this, density, "画中画 (PiP)",
                AikoTheme.ACCENT_CYAN, AikoTheme.CARD, AikoTheme.STROKE_ACCENT);
        pipBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(pipBtn, 0.94f);
        pipBtn.setOnClickListener(v -> web.evaluateJavascript(VideoBooster.requestPipJs(), raw -> {
            if (isDestroyed()) return;
            String res = "";
            try {
                if (raw != null && !raw.equals("null")) {
                    Object tok = new JSONTokener(raw).nextValue();
                    if (tok instanceof String) res = (String) tok;
                }
            } catch (Exception ignored) { }
            if ("requested".equals(res)) {
                dialog.dismiss();
                toast("已请求画中画播放");
            } else if ("exited".equals(res)) {
                toast("已退出画中画");
            } else {
                toast("当前视频或页面暂不支持画中画");
            }
        }));
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(0, dp(40), 1);
        pblp.rightMargin = dp(7);
        ctrlRow.addView(pipBtn, pblp);

        TextView resetBtn = AikoTheme.badge(this, density, "恢复 1.0x",
                AikoTheme.TEXT_MUTED, AikoTheme.CARD, AikoTheme.STROKE);
        resetBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        AikoTheme.attachPressFeedback(resetBtn, 0.94f);
        resetBtn.setOnClickListener(v -> {
            activeRate[0] = 1.0f;
            syncSpeedButtons.run();
            web.evaluateJavascript(VideoBooster.setRateJs(1.0f), null);
            statusTv.setText("已恢复默认 1.0x 倍速（后台监听已释放，零开销）");
        });
        ctrlRow.addView(resetBtn, new LinearLayout.LayoutParams(0, dp(40), 1));

        // v0.4.0 Background Audio (Lockscreen MediaSession) + Media Sniffer row
        boolean isBgHere = bgAudioEnabled && tab == bgAudioTab;
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
        brlp.topMargin = dp(10);
        panel.addView(bgRow, brlp);

        View bgTile = AikoTheme.toggleTile(this, density, SPEAKER,
                isBgHere ? "后台播放中" : "后台播放",
                isBgHere ? "锁屏媒控已开启" : "锁屏/切后台继续听",
                isBgHere, true, AikoTheme.EMERALD, () -> {
                    boolean next = !(bgAudioEnabled && tab == bgAudioTab);
                    setBackgroundAudioEnabled(tab, next, true);
                    dialog.dismiss();
                });
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(0, -2, 1.2f);
        btlp.rightMargin = dp(8);
        bgRow.addView(bgTile, btlp);

        View sniffTile = AikoTheme.toggleTile(this, density, SNIFF,
                "媒体嗅探", "提取 MP4 / M3U8",
                false, true, AikoTheme.ACCENT_CYAN, () -> {
                    dialog.dismiss();
                    openMediaSnifferForCurrent();
                });
        bgRow.addView(sniffTile, new LinearLayout.LayoutParams(0, -2, 1f));

        // Inspect live videos once sheet opens
        web.evaluateJavascript(VideoBooster.inspectVideoJs(), raw -> {
            if (isDestroyed() || !dialog.isShowing()) return;
            try {
                if (raw != null && !raw.equals("null")) {
                    JSONObject obj = new JSONObject(raw);
                    int count = obj.optInt("count", 0);
                    float rate = (float) obj.optDouble("rate", 1.0);
                    boolean paused = obj.optBoolean("paused", true);
                    boolean muted = obj.optBoolean("muted", false);
                    activeRate[0] = rate;
                    videoMuted[0] = muted;
                    syncSpeedButtons.run();
                    if (muteBtnRef[0] != null) muteBtnRef[0].setText(muted ? "取消静音" : "视频静音");
                    if (count == 0) {
                        statusTv.setText("暂未检测到 HTML5 视频（可先预设倍速，播放时自动接管）");
                        statusTv.setTextColor(AikoTheme.TEXT_MUTED);
                    } else {
                        statusTv.setText(String.format(Locale.ROOT,
                                "检测到 %d 个视频  ·  %.2fx  ·  %s%s",
                                count, rate, paused ? "已暂停" : "播放中", muted ? " · 静音" : ""));
                        statusTv.setTextColor(AikoTheme.ACCENT_CYAN);
                    }
                    return;
                }
            } catch (Exception ignored) { }
            statusTv.setText("就绪：点选倍速即可作用于当前网页视频");
        });

        showSheet(dialog, panel);
    }

    // ==================== ABOUT AIKO BROWSER (ALZAR) ====================

    private String appVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.isEmpty()) return pi.versionName;
        } catch (Exception ignored) { }
        return BackupManager.APP_VERSION;
    }

    private void showAboutSheet() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(this, density, "关于 Aiko Browser", dialog::dismiss);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, getResources().getConfiguration().screenHeightDp - 132));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = stack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Hero identity card (every centered multi-line TextView explicitly sets Gravity.CENTER_HORIZONTAL)
        LinearLayout hero = stack();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(16), dp(18), dp(16), dp(16));
        hero.setBackground(AikoTheme.glowBanner(dp(18)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(4);
        body.addView(hero, hlp);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, dp(20)));
        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.aiko_pixel);
        emblem.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconBox.addView(emblem, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        hero.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView appTitle = label("Aiko Browser v" + appVersionName(), 19, AikoTheme.TEXT);
        appTitle.setTypeface(AikoTheme.BOLD);
        appTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(-1, -2);
        atlp.topMargin = dp(10);
        hero.addView(appTitle, atlp);

        TextView tagline = label("极致轻量 · 巴别塔与旗舰高刷浏览器", 12, AikoTheme.ACCENT_CYAN);
        tagline.setGravity(Gravity.CENTER_HORIZONTAL);
        tagline.setPadding(dp(8), dp(4), dp(8), dp(10));
        hero.addView(tagline, new LinearLayout.LayoutParams(-1, -2));

        TextView authorPill = AikoTheme.badge(this, density, "作者：Alzar",
                AikoTheme.TEXT, AikoTheme.BG, AikoTheme.STROKE_ACCENT);
        authorPill.setGravity(Gravity.CENTER);
        hero.addView(authorPill, new LinearLayout.LayoutParams(-2, -2));

        // Open-source GitHub repository card
        body.addView(AikoTheme.sectionHeader(this, density, "开源仓库（GitHub）"));
        LinearLayout repoCard = stack();
        repoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        repoCard.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        body.addView(repoCard, new LinearLayout.LayoutParams(-1, -2));

        TextView repoUrlTv = label(REPO_URL, 12, AikoTheme.ACCENT);
        repoUrlTv.setTypeface(AikoTheme.MONO);
        repoUrlTv.setSingleLine(true);
        repoUrlTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        repoCard.addView(repoUrlTv);

        LinearLayout repoBtns = new LinearLayout(this);
        repoBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(-1, -2);
        rblp.topMargin = dp(10);
        repoCard.addView(repoBtns, rblp);

        TextView openRepo = AikoTheme.badge(this, density, "访问项目主页",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        openRepo.setTypeface(AikoTheme.BOLD);
        openRepo.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        AikoTheme.attachPressFeedback(openRepo, 0.94f);
        openRepo.setOnClickListener(v -> {
            dialog.dismiss();
            addTab(REPO_URL, false);
        });
        LinearLayout.LayoutParams orlp = new LinearLayout.LayoutParams(0, dp(36), 1);
        orlp.rightMargin = dp(6);
        repoBtns.addView(openRepo, orlp);

        TextView relBtn = AikoTheme.badge(this, density, "Release 更新",
                AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        relBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(relBtn, 0.94f);
        relBtn.setOnClickListener(v -> {
            dialog.dismiss();
            addTab(REPO_RELEASES_URL, false);
        });
        LinearLayout.LayoutParams rllp = new LinearLayout.LayoutParams(0, dp(36), 1);
        rllp.rightMargin = dp(6);
        repoBtns.addView(relBtn, rllp);

        TextView copyRepo = AikoTheme.badge(this, density, "复制地址",
                AikoTheme.TEXT_MUTED, AikoTheme.PILL, AikoTheme.STROKE);
        copyRepo.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(12)));
        AikoTheme.attachPressFeedback(copyRepo, 0.94f);
        copyRepo.setOnClickListener(v -> copyText("GitHub 仓库", REPO_URL, "已复制仓库地址"));
        repoBtns.addView(copyRepo, new LinearLayout.LayoutParams(-2, dp(36)));

        // Runtime Environment card
        body.addView(AikoTheme.sectionHeader(this, density, "运行环境"));
        LinearLayout envCard = stack();
        envCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        envCard.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        body.addView(envCard, new LinearLayout.LayoutParams(-1, -2));

        String wv = WebViewPerf.webViewVersionSummary(this);
        String hw = displayMode != null ? displayMode.hardwareSummary() : "未知";
        String modeStr = activeProfile.name + "  ·  " + AikoTheme.modeLabel(AikoTheme.loadMode(this));
        envCard.addView(label("WebView 内核：" + wv, 12, AikoTheme.TEXT));
        TextView hwTv = label("屏幕刷新率：" + hw, 12, AikoTheme.TEXT_MUTED);
        hwTv.setPadding(0, dp(4), 0, 0);
        envCard.addView(hwTv);
        TextView modTv = label("当前配置：" + modeStr, 12, AikoTheme.TEXT_MUTED);
        modTv.setPadding(0, dp(4), 0, 0);
        envCard.addView(modTv);

        // Open-source licenses & filter credits
        body.addView(AikoTheme.sectionHeader(this, density, "开源许可与规则致谢"));
        TextView noticeBtn = AikoTheme.badge(this, density, "查看开源组件许可与广告规则致谢 ›",
                AikoTheme.TEXT, AikoTheme.CARD, AikoTheme.STROKE);
        noticeBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(noticeBtn, 0.96f);
        noticeBtn.setOnClickListener(v -> showNoticesDialog());
        body.addView(noticeBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        showSheet(dialog, panel);
    }

    private void showNoticesDialog() {
        toast("正在读取开源许可说明…");
        ioExecutor.execute(() -> {
            String appNotice = readAssetText("notices/NOTICE.txt");
            String filterNotice = readAssetText("filters/NOTICE.txt");
            StringBuilder full = new StringBuilder();
            full.append("Aiko Browser — Author: Alzar\n").append(REPO_URL).append("\n\n");
            if (!appNotice.isEmpty()) {
                full.append("=== 开源组件与运行库 ===\n").append(appNotice.trim()).append("\n\n");
            }
            if (!filterNotice.isEmpty()) {
                full.append("=== 内置广告过滤规则致谢 ===\n").append(filterNotice.trim());
            }
            String content = full.toString();
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                new AlertDialog.Builder(this)
                        .setTitle("开源许可与规则致谢")
                        .setMessage(content)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        });
    }

    private String readAssetText(String assetPath) {
        try (InputStream in = getAssets().open(assetPath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
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
        AikoTheme.attachPressFeedback(control, 0.92f);
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
        EditText count = input("并行路数，留空按文件大小自动分配（当前上限 " + activeProfile.downloadLanes + " 路）");
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
                int lanes = requested.isEmpty() ? Math.min(8, activeProfile.downloadLanes) : Integer.parseInt(requested);
                if (lanes < 1 || lanes > RangeDownload.MAX_LANES) {
                    count.setError("输入 1–" + RangeDownload.MAX_LANES);
                    return;
                }
                String filename = file.getText().toString().trim();
                if (filename.isEmpty()) filename = URLUtil.guessFileName(chosen, null, null);
                DownloadService.start(this, chosen, filename, ua, lanes, () -> {
                    if (!isFinishing() && !isDestroyed()) showDownloads();
                });
                d.dismiss();
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

    void showDownloads() {
        new DownloadPanel(this, this::openMediaSnifferForCurrent, cachedSniffedCount).show();
    }

    // ==================== V0.4.0 FEATURE ACTIONS (GALLERY / LONGSHOT / SNIFF / PWA / BACKUP / TTS / CLIPBOARD) ====================

    private void openGalleryForCurrent() {
        Tab tab = current;
        if (tab == null || tab.view == null || !HttpUrlUtil.isHttp(tab.url)) {
            toast("请先打开包含图片的网页");
            return;
        }
        toast("正在提取页面高清图片…");
        String pageTitle = tab.title;
        String pageUrl = tab.url;
        tab.view.evaluateJavascript(PageToolkit.extractGalleryImagesScript(), raw -> {
            if (isDestroyed() || isFinishing()) return;
            ArrayList<GallerySheet.ImageItem> items = new ArrayList<>();
            try {
                if (raw != null && !"null".equals(raw)) {
                    Object tok = new JSONTokener(raw).nextValue();
                    JSONArray arr = tok instanceof String ? new JSONArray((String) tok)
                            : (tok instanceof JSONArray ? (JSONArray) tok : null);
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            String u = o.optString("url", "").trim();
                            if (HttpUrlUtil.isHttp(u)) {
                                items.add(new GallerySheet.ImageItem(
                                        u, o.optString("alt", ""), o.optInt("width", 0), o.optInt("height", 0)));
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
            if (items.isEmpty()) {
                toast("当前页面未检测到适合画廊预览的高清图片");
                return;
            }
            GallerySheet.show(this, pageTitle, pageUrl, items, new GallerySheet.Actions() {
                @Override public void toast(String msg) { MainActivity.this.toast(msg); }
                @Override public void downloadUrl(String url, String filename) {
                    showNewDownload(url, filename, null);
                }
                @Override public void openUrl(String url) { addTab(url, false); }
            });
        });
    }

    private void captureLongShotForCurrent() {
        Tab tab = current;
        if (tab == null || tab.view == null || !HttpUrlUtil.isHttp(tab.url)) {
            toast("请先打开网页后再截取长图");
            return;
        }
        toast("正在拼接全页长截图，请保持页面不动…");
        LongShotCapture.capture(this, tab.view, tab.title, tab.url, true, new LongShotCapture.Callback() {
            @Override public void onSaved(Uri imageUri, String displayName, int width, int height) {
                if (!isDestroyed()) {
                    toast("长截图已保存：" + displayName + " (" + width + "×" + height + ")");
                }
            }
            @Override public void onError(String message) {
                if (!isDestroyed()) toast(message == null ? "长截图失败" : message);
            }
        });
    }

    private void openMediaSnifferForCurrent() {
        Tab tab = current;
        if (tab == null || tab.view == null || !HttpUrlUtil.isHttp(tab.url)) {
            toast("请先打开网页后再嗅探媒体流");
            return;
        }
        AikoV040Ui.showMediaSnifferSheet(v040Host, tab.view, tab.title, count -> cachedSniffedCount = count);
    }

    private void pinCurrentPagePwa() {
        Tab tab = current;
        if (tab == null || !HttpUrlUtil.isHttp(tab.url)) {
            toast("请先打开网页后再添加到桌面");
            return;
        }
        if (!PwaHelper.isSupported(this)) {
            toast("当前系统桌面暂不支持固定独立快捷方式");
            return;
        }
        boolean ok = PwaHelper.pinStandaloneShortcut(this, tab.url, tab.title, tab.favicon, true);
        toast(ok ? "已请求创建独立全屏桌面图标（PWA）" : "创建桌面快捷方式失败");
    }

    private void setBackgroundAudioEnabled(Tab tab, boolean enable, boolean showToast) {
        if (enable && tab != null && tab.view != null) {
            bgAudioEnabled = true;
            bgAudioTab = tab;
            tab.view.evaluateJavascript(VideoBooster.backgroundPlaybackScript(true), null);
            String title = (tab.title == null || tab.title.isEmpty()) ? "网页媒体播放" : tab.title;
            String host = tab.cachedHost == null ? "Aiko Browser" : tab.cachedHost;
            MediaPlaybackService.start(this, title, host, true);
            if (showToast) toast("已开启后台播放与锁屏媒体控制");
        } else {
            if (bgAudioTab != null && bgAudioTab.view != null) {
                try { bgAudioTab.view.evaluateJavascript(VideoBooster.backgroundPlaybackScript(false), null); }
                catch (RuntimeException ignored) { }
            }
            bgAudioEnabled = false;
            bgAudioTab = null;
            MediaPlaybackService.stop(this);
            if (showToast) toast("已关闭后台音频播放");
        }
    }

    private void resetSiteDataAndCookies(Tab tab, String host, String url) {
        if (host == null || host.isEmpty()) return;
        String origin = HttpUrlUtil.extractOrigin(url);
        try {
            if (!origin.isEmpty()) WebStorage.getInstance().deleteOrigin(origin);
            CookieManager cm = CookieManager.getInstance();
            String existing = cm.getCookie(url);
            if (existing != null && !existing.isEmpty()) {
                for (String pair : existing.split(";")) {
                    int eq = pair.indexOf('=');
                    String name = (eq > 0 ? pair.substring(0, eq) : pair).trim();
                    if (!name.isEmpty()) {
                        cm.setCookie(url, name + "=; Max-Age=0; Path=/");
                        cm.setCookie(url, name + "=; Max-Age=0; Domain=" + host + "; Path=/");
                    }
                }
                cm.flush();
            }
        } catch (RuntimeException ignored) { }
        saveSiteProfile(host, SiteProfile.DEFAULT);
        siteExtras.resetHost(host);
        toast("已清除 " + host + " 的站内存储与 Cookie");
        if (sameOpenSite(tab, host)) reloadPreservingScroll();
    }

    private void startBackupExport(boolean encrypted) {
        clearBackupPassword();
        if (encrypted) {
            askBackupPassword(true, password -> {
                pendingBackupPassword = password;
                pickBackupDestination(true);
            });
        } else pickBackupDestination(false);
    }

    private void clearBackupPassword() {
        if (pendingBackupPassword != null) java.util.Arrays.fill(pendingBackupPassword, '\0');
        pendingBackupPassword = null;
    }

    private void pickBackupDestination(boolean encrypted) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, BackupManager.suggestedFilename());
        try { startActivityForResult(intent, encrypted ? BACKUP_EXPORT_ENCRYPTED : BACKUP_EXPORT); }
        catch (RuntimeException e) { clearBackupPassword(); toast("找不到可用的文件管理器"); }
    }

    private EditText backupPasswordField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSaveEnabled(false);
        return field;
    }

    private void askBackupPassword(boolean exporting, java.util.function.Consumer<char[]> action) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), 0, dp(18), 0);
        EditText first = backupPasswordField(exporting ? "设置密码（至少 8 个字符）" : "输入备份密码");
        EditText confirm = exporting ? backupPasswordField("再次输入密码") : null;
        fields.addView(first);
        if (confirm != null) fields.addView(confirm);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(exporting ? "加密整个备份" : "恢复加密备份")
                .setMessage(exporting ? "请保管好密码。恢复巴别塔密钥和其他数据时需要它。" : "将覆盖备份中包含的设置和文件，包括巴别塔凭据。")
                .setView(fields).setNegativeButton("取消", null)
                .setPositiveButton(exporting ? "选择保存位置" : "解密并恢复", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d -> {
            first.getText().clear();
            if (confirm != null) confirm.getText().clear();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable input = first.getText();
            if (input.length() < (exporting ? 8 : 1)) { first.setError(exporting ? "至少 8 个字符" : "请输入密码"); return; }
            if (confirm != null && !TextUtils.equals(input, confirm.getText())) { confirm.setError("两次密码不同"); return; }
            char[] password = new char[input.length()];
            input.getChars(0, input.length(), password, 0);
            dialog.dismiss();
            action.accept(password);
        });
    }

    private void startBackupImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try { startActivityForResult(intent, BACKUP_IMPORT); }
        catch (RuntimeException e) { toast("找不到可用的文件选择器"); }
    }

    private void startCustomFontPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try { startActivityForResult(intent, PICK_CUSTOM_FONT); }
        catch (RuntimeException e) { toast("找不到可用的文件选择器"); }
    }

    private void maybeShowClipboardCapsule() {
        if (isDestroyed() || isFinishing() || startPageConfig == null || !startPageConfig.isClipboardAssist()) return;
        if (isAnyOmniboxFocused() || fullscreen != null || root == null) return;
        String clip = readClipboardSnippet();
        if (clip == null || clip.equals(lastClipboardHintText)) return;
        if (!HttpUrlUtil.isHttp(clip)) return;
        if (current != null && clip.equals(current.url)) return;
        lastClipboardHintText = clip;

        boolean isScript = UserScripts.isUserScriptUrl(clip);
        if (clipboardCapsule == null) {
            clipboardCapsule = new LinearLayout(this);
            clipboardCapsule.setOrientation(LinearLayout.HORIZONTAL);
            clipboardCapsule.setGravity(Gravity.CENTER_VERTICAL);
            clipboardCapsule.setPadding(dp(12), dp(8), dp(8), dp(8));
            clipboardCapsule.setElevation(dp(14));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.leftMargin = dp(12);
            lp.rightMargin = dp(12);
            lp.bottomMargin = deckContentHeight + insetBottom + dp(14);
            root.addView(clipboardCapsule, lp);
        }
        clipboardCapsule.removeAllViews();
        clipboardCapsule.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, dp(18)));
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) clipboardCapsule.getLayoutParams();
        clp.bottomMargin = deckContentHeight + insetBottom + dp(14);
        clipboardCapsule.setLayoutParams(clp);

        LinearLayout info = stack();
        TextView head = label(isScript ? "剪贴板发现油猴脚本 · 点击安装" : "剪贴板发现网址 · 点击前往",
                12, isScript ? AikoTheme.EMERALD : AikoTheme.ACCENT_CYAN);
        head.setTypeface(AikoTheme.BOLD);
        info.addView(head);
        TextView sub = label(clip, 11, AikoTheme.TEXT_MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.addView(sub);
        clipboardCapsule.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        TextView goPill = AikoTheme.badge(this, density, isScript ? "安装" : "打开",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        goPill.setOnClickListener(v -> {
            hideClipboardCapsule();
            if (isScript) {
                handleRemoteUserScript(clip, URLUtil.guessFileName(clip, null, "application/javascript"), null);
            } else {
                navigate(clip);
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(32));
        glp.leftMargin = dp(8);
        clipboardCapsule.addView(goPill, glp);

        BrowserIcon closeBtn = icon(CLOSE, "关闭提示", this::hideClipboardCapsule);
        closeBtn.tint(AikoTheme.TEXT_DIM);
        clipboardCapsule.addView(closeBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        clipboardCapsule.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideClipboardCapsuleTask);
        handler.postDelayed(hideClipboardCapsuleTask, 7000L);
    }

    private void hideClipboardCapsule() {
        handler.removeCallbacks(hideClipboardCapsuleTask);
        if (clipboardCapsule != null) clipboardCapsule.setVisibility(View.GONE);
    }

    private void writeBackup(Uri uri, char[] password) {
        userScripts.flushValues();
        library.flushHistory();
        toast("正在导出备份…");
        ioExecutor.execute(() -> {
            try {
                BackupManager.ExportReport result;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException();
                    result = BackupManager.exportToStream(this, out, password);
                }
                handler.post(() -> toast(result.summary));
            } catch (Exception failure) {
                handler.post(() -> toast("备份导出失败，请检查存储空间和文件权限"));
            } finally { if (password != null) java.util.Arrays.fill(password, '\0'); }
        });
    }

    private void confirmBackupImport(Uri uri) {
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException();
                boolean encrypted = BackupEnvelope.encrypted(new java.io.BufferedInputStream(in));
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (encrypted) askBackupPassword(false, password -> restoreBackup(uri, password));
                    else new AlertDialog.Builder(this).setTitle("恢复便携或旧版备份？")
                            .setMessage("将覆盖备份中包含的设置和文件。只恢复可信来源的备份。明文备份内的巴别塔密钥和请求头将被忽略，同一配置和 API 地址可沿用本机凭据。")
                            .setNegativeButton("取消", null).setPositiveButton("恢复", (d, w) -> restoreBackup(uri, null)).show();
                });
            } catch (Exception failure) { handler.post(() -> toast("无法读取备份文件")); }
        });
    }

    private void restoreBackup(Uri uri, char[] password) {
        userScripts.flushValues();
        library.flushHistory();
        BackupManager.RestoreControl control = new BackupManager.RestoreControl();
        AlertDialog progress = new AlertDialog.Builder(this).setMessage("正在验证并恢复备份…")
                .setNegativeButton("取消", null).setCancelable(false).show();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            boolean canceled = control.cancel();
            progress.setMessage(canceled ? "正在取消…" : "正在写入，请稍候…");
            v.setEnabled(false);
        });
        ioExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException();
                BackupManager.RestoreReport report = BackupManager.importFromStream(this, in, password, control);
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    toast(report.summary);
                    recreate(); // Rebuild shelf, library, scripts and other in-memory snapshots together.
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    if (failure instanceof java.io.InterruptedIOException) toast("已取消恢复");
                    else toast(password == null ? "恢复失败，请检查备份文件和存储空间" : "恢复失败，请检查密码、备份文件和存储空间");
                });
            } finally { if (password != null) java.util.Arrays.fill(password, '\0'); }
        });
    }

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
            dismissOmnibox();
            return;
        }
        if (goBack()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPressMs > 1800L) {
            lastBackPressMs = now;
            toast("再按一次返回退出 Aiko");
            return;
        }
        super.onBackPressed();
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
        applyInsetsLayout();
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

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (displayMode != null) displayMode.apply();
            handler.postDelayed(this::maybeShowClipboardCapsule, 240L);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (QrSheet.handleActivityResult(this, requestCode, resultCode, data, qrScanCallback)) {
            return;
        }
        if (requestCode == ScriptTools.PICK_FILE) {
            if (resultCode == RESULT_OK && data != null) scriptTools.importFile(data.getData());
            return;
        }
        if (requestCode == FILE_PICKER && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            picker = null;
            return;
        }
        if (requestCode == BACKUP_EXPORT || requestCode == BACKUP_EXPORT_ENCRYPTED) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) { clearBackupPassword(); return; }
            char[] password = pendingBackupPassword;
            pendingBackupPassword = null;
            if (requestCode == BACKUP_EXPORT_ENCRYPTED && password == null) {
                toast("备份会话已结束，请重新设置密码");
                return;
            }
            writeBackup(data.getData(), password);
            return;
        }
        if (requestCode == BACKUP_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) confirmBackupImport(data.getData());
            return;
        }
        if (requestCode == PICK_CUSTOM_FONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            toast("正在导入自定义字体…");
            ioExecutor.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("无法读取字体文件");
                    String rawName = queryDisplayName(uri);
                    String lower = rawName.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".woff2")) {
                        rawName = rawName + ".ttf";
                    }
                    String safeFile = StartPageConfig.sanitizeFontFilename(rawName);
                    if (safeFile.isEmpty()) safeFile = "user_font.ttf";
                    File fontsDir = new File(getFilesDir(), "fonts");
                    if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) {
                        throw new java.io.IOException("无法创建字体目录");
                    }
                    File target = new File(fontsDir, safeFile);
                    File tmp = new File(fontsDir, safeFile + ".tmp");
                    byte[] buf = new byte[16384];
                    int total = 0;
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        for (int n; (n = in.read(buf)) >= 0; ) {
                            total += n;
                            if (total > MAX_CUSTOM_FONT_BYTES) {
                                throw new java.io.IOException("字体文件超过 16 MiB 上限");
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                    if (total < 64) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        throw new java.io.IOException("字体文件无效");
                    }
                    if (target.exists()) { //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                    if (!tmp.renameTo(target)) {
                        throw new java.io.IOException("保存字体文件失败");
                    }
                    final String displayName = rawName.replaceAll("\\.(ttf|otf|woff2)$", "");
                    final String finalFile = safeFile;
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        startPageConfig.setCustomFont(displayName, finalFile);
                        v040Host.onTypographyOrPrivacyChanged();
                        toast("已导入并应用字体：" + displayName);
                    });
                } catch (Exception e) {
                    String err = e.getMessage();
                    handler.post(() -> toast("导入字体失败：" + (err == null ? "请检查文件格式" : err)));
                }
            });
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

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String nm = c.getString(0);
                if (nm != null && !nm.trim().isEmpty()) return nm.trim();
            }
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return (last != null && !last.isEmpty()) ? last : "custom_font.ttf";
    }

    @Override protected void onStart() {
        super.onStart();
        DownloadService.restore(this);
        visible = true;
        if (displayMode != null) displayMode.start();
        applyAutoNightSchedule();
        applyNightComfortOverlay();
        if (current != null && current.view != null && !onHome) {
            current.view.onResume();
            WebViewPerf.setActiveRenderer(current.view, true);
            WebViewPerf.setActivePreRaster(current.view, activeProfile.activePreRaster && !isLowRam);
            if (current.started != 0) current.priorityTicket = DownloadService.PACER.protect(1350);
        }
        if (hudEnabled) attachHud();
        scheduleAutoRefreshForCurrent();
        scheduleNewTabPreload(600L);
    }

    @Override protected void onStop() {
        userScripts.flushValues();
        visible = false;
        handler.removeCallbacks(autoRefreshTask);
        if (displayMode != null) displayMode.stop();
        if (current != null) {
            rememberScroll(current);
            captureFormDraft(current);
            DownloadService.PACER.release(current.priorityTicket);
            current.priorityTicket = 0;
            boolean keepPlayingAudio = bgAudioEnabled && current == bgAudioTab;
            if (current.view != null && !keepPlayingAudio) {
                current.view.onPause();
                WebViewPerf.setActivePreRaster(current.view, false);
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
        clearBackupPassword();
        if (root != null) root.removeCallbacks(reapplyInsetsTask);
        detachHud();
        hideClipboardCapsule();
        hideScrollTopPill();
        releasePreloadedNewTab();
        if (readAloud != null) readAloud.shutdown();
        if (babelEngine != null) babelEngine.shutdown();
        MediaPlaybackService.clearPlaybackListener(mediaPlaybackListener);
        if (bgAudioEnabled) MediaPlaybackService.stop(this);
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

    private void copyUrl(String labelText, String rawUrl) {
        if (rawUrl == null) return;
        String out = rawUrl;
        boolean cleaned = false;
        if (PerfProfiles.isCleanLinksEnabled(this) && HttpUrlUtil.isHttp(rawUrl)) {
            String stripped = UrlCleaner.clean(rawUrl);
            cleaned = UrlCleaner.wasCleaned(rawUrl, stripped);
            out = stripped;
        }
        copyText(labelText, out, cleaned ? "已复制干净链接（已去除追踪参数）" : "已复制链接");
    }

    private void shareLink(String url) {
        if (url == null) return;
        String out = url;
        if (PerfProfiles.isCleanLinksEnabled(this) && HttpUrlUtil.isHttp(url)) {
            String stripped = UrlCleaner.clean(url);
            if (UrlCleaner.wasCleaned(url, stripped)) {
                out = stripped;
                toast("已自动去除链接追踪参数");
            }
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, out);
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
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(72)));
        return row;
    }

    private View sheetAction(Dialog dialog, LinearLayout row, BrowserIcon.Kind kind, String text,
                             boolean available, Runnable command) {
        LinearLayout tile = stack();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(15)));
        tile.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(15)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(64), 1);
        size.setMargins(dp(4), 0, dp(4), 0);
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
        tlp.topMargin = dp(5);
        tile.addView(title, tlp);
        tile.setContentDescription(text);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AikoTheme.attachPressFeedback(tile, 0.94f);
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
        hideOmniboxScrim();
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
