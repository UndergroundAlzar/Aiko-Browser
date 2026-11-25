package app.aikobrowser;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewRenderProcess;
import android.webkit.WebViewRenderProcessClient;

import androidx.webkit.BackForwardCacheSettings;
import androidx.webkit.HttpCache;
import androidx.webkit.PrefetchException;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.SpeculativeLoadingConfig;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AndroidX WebKit 1.18.0 flagship performance pipeline:
 * async provider startup, renderer warm-up, BFCache, confirmed-navigation preconnect,
 * unmetered background prefetch, bounded state save, and hung-renderer recovery.
 */
public final class WebViewPerf {
    private static final long PRECONNECT_TTL_NS = 10_000_000_000L;
    private static final long PRECONNECT_HOST_TTL_NS = 45_000_000_000L;
    private static final long PREFETCH_TTL_NS = 15_000_000_000L;
    private static final long STARTUP_WATCHDOG_MS = 1500L;
    private static final long HUNG_RENDERER_GRACE_MS = 8000L;
    private static final int RECENT_ORIGINS_CAP = 24;
    private static final int RECENT_PREFETCH_CAP = 16;
    private static final int HOST_PRECONNECT_CAP = 64;
    private static final int HOST_PRECONNECT_TIMEOUT_MS = 1500;

    private static final Handler MAIN;

    static {
        Handler mainHandler = null;
        try {
            mainHandler = new Handler(Looper.getMainLooper());
        } catch (RuntimeException ignored) {
            // Host JVM unit tests running against android.jar stubs
        }
        MAIN = mainHandler;
    }
    private static final Object START_LOCK = new Object();
    private static final Object HOST_PRECONNECT_LOCK = new Object();
    private static final LinkedHashMap<String, Long> RECENT_HOST_PRECONNECT = new LinkedHashMap<>(32, 0.75f, true);
    private static final ArrayList<Runnable> PENDING_READY = new ArrayList<>(4);
    private static final ExecutorService STARTUP_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-wv-startup");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService PREFETCH_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-wv-prefetch");
        t.setDaemon(true);
        return t;
    });
    private static final ThreadPoolExecutor HOST_PRECONNECT_EXEC = createHostPreconnectPool();

    private static ThreadPoolExecutor createHostPreconnectPool() {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                2, 2, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "aiko-host-preconnect");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    private static volatile boolean started;
    private static boolean starting;
    private static volatile boolean usedAsyncStartup;
    private static volatile Long startupUiMs;
    private static volatile boolean quicHintsActive;
    private static volatile boolean speculativeConfigActive;
    private static volatile boolean serviceWorkerFilterActive;
    private static volatile long httpCacheQuotaMb;
    private static final AtomicLong SW_BLOCKED_COUNT = new AtomicLong();

    private static final Set<String> BASE_QUIC_HOSTS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "www.google.com", "clients5.google.com", "translate.googleapis.com",
            "www.youtube.com", "github.com", "www.cloudflare.com",
            "www.bilibili.com", "zh.wikipedia.org", "linux.do",
            "greasyfork.org", "go.itab.link", "janitorai.com"
    )));

    // UI-thread-only origin/URL dedup ring buffers (zero allocation on hot path).
    private static final String[] recentOrigins = new String[RECENT_ORIGINS_CAP];
    private static final long[] recentOriginAt = new long[RECENT_ORIGINS_CAP];
    private static int originSlot;

    private static final String[] recentPrefetchUrls = new String[RECENT_PREFETCH_CAP];
    private static final long[] recentPrefetchAt = new long[RECENT_PREFETCH_CAP];
    private static int prefetchSlot;

    private WebViewPerf() {}

    /**
     * Asynchronously warms up Android System WebView before the first WebView is constructed.
     * NEVER call before WebView.setDataDirectorySuffix(...) in the :private process!
     */
    public static void startUpAsync(Context appContext, Runnable onReady) {
        if (started) {
            if (onReady != null) postOnMain(onReady);
            return;
        }
        boolean launch = false;
        synchronized (START_LOCK) {
            if (started) {
                if (onReady != null) postOnMain(onReady);
                return;
            }
            if (onReady != null) PENDING_READY.add(onReady);
            if (!starting) {
                starting = true;
                launch = true;
            }
        }
        if (!launch) return;

        final Context ctx = appContext.getApplicationContext();
        final Runnable watchdog = () -> completeStartup(ctx, false, null);
        MAIN.postDelayed(watchdog, STARTUP_WATCHDOG_MS);

        try {
            if (WebViewFeature.isStartupFeatureSupported(
                    ctx, WebViewFeature.STARTUP_FEATURE_SET_UI_THREAD_STARTUP_MODE_V2)) {
                WebViewStartUpConfig.Builder builder = new WebViewStartUpConfig.Builder(STARTUP_EXEC)
                        .setShouldRunUiThreadStartUpTasks(true);
                if (WebViewFeature.isStartupFeatureSupported(
                        ctx, WebViewFeature.STARTUP_FEATURE_SET_PROFILES_TO_LOAD)) {
                    builder.setProfilesToLoadDuringStartup(Collections.singleton(Profile.DEFAULT_PROFILE_NAME));
                }
                WebViewStartUpConfig config = builder.build();
                WebViewCompat.startUpWebView(ctx, config,
                        new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                            @Override public void onResult(WebViewStartUpResult result) {
                                MAIN.removeCallbacks(watchdog);
                                Long uiMs = result != null ? result.getTotalTimeInUiThreadMillis() : null;
                                completeStartup(ctx, true, uiMs);
                            }

                            @Override public void onError(WebViewStartupException error) {
                                MAIN.removeCallbacks(watchdog);
                                completeStartup(ctx, false, null);
                            }
                        });
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to synchronous fallback.
        }
        MAIN.removeCallbacks(watchdog);
        completeStartup(ctx, false, null);
    }

    private static void completeStartup(Context appContext, boolean async, Long uiMs) {
        ArrayList<Runnable> callbacks;
        synchronized (START_LOCK) {
            if (started) return;
            started = true;
            starting = false;
            usedAsyncStartup = async;
            startupUiMs = uiMs;
            callbacks = new ArrayList<>(PENDING_READY);
            PENDING_READY.clear();
        }
        postOnMain(() -> {
            warmUpAfterStart(appContext);
            for (Runnable r : callbacks) {
                try { r.run(); } catch (RuntimeException ignored) { }
            }
        });
    }

    private static void warmUpAfterStart(Context appContext) {
        try {
            if (!isLowRam(appContext)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                    && WebViewCompat.isMultiProcessEnabled()) {
                // Always use getOrCreateProfile("Default"); getProfile() can block on native startup.
                Profile profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
                if (profile != null) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) {
                        profile.warmUpRendererProcess();
                    }
                    applyQuicHintsLocked(profile, BASE_QUIC_HOSTS);
                }
            }
        } catch (Throwable ignored) { }
        // Intentionally omit startSafeBrowsing IPC during cold start: eliminates non-GMS China ROM
        // startup IPC stalls and SafeBrowsing.API error noise while keeping cold start sub-170ms.
    }

    /** Registers HTTP/3 QUIC 0-RTT hints for top modern hosts + user Speed Dial / search engine hosts. */
    public static void warmUpQuicHints(Set<String> extraHosts) {
        if (!started) return;
        postOnMain(() -> {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                        || !WebViewCompat.isMultiProcessEnabled()) return;
                Profile profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
                if (profile == null) return;
                LinkedHashSet<String> merged = new LinkedHashSet<>(BASE_QUIC_HOSTS);
                if (extraHosts != null) {
                    for (String h : extraHosts) {
                        if (h != null && !h.trim().isEmpty()) merged.add(h.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
                applyQuicHintsLocked(profile, merged);
            } catch (Throwable ignored) { }
        });
    }

    private static void applyQuicHintsLocked(Profile profile, Set<String> hosts) {
        if (profile == null || hosts == null || hosts.isEmpty()) return;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ADD_QUIC_HINTS_V1)) {
                LinkedHashSet<String> urls = new LinkedHashSet<>(hosts.size());
                for (String raw : hosts) {
                    if (raw == null) continue;
                    String s = raw.trim();
                    if (s.isEmpty()) continue;
                    if (s.startsWith("https://")) {
                        urls.add(s);
                    } else if (!s.contains("://") && !s.contains(" ") && !s.contains("/")) {
                        urls.add("https://" + s);
                    }
                }
                if (!urls.isEmpty()) {
                    profile.preferQuicFor(urls);
                    quicHintsActive = true;
                }
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Tunes Chromium's native Speculative Loading Cache (maxPrefetches, TTL, maxPrerenders)
     * and HTTP Disk Cache Quota (512 MiB Rampage / 256 MiB Balanced / default Eco) for flagship UFS chips.
     */
    public static void applyFlagshipProfileTuning(PerfProfiles.Profile profile, boolean isLowRam) {
        if (!started) return;
        PerfProfiles.Profile p = profile != null ? profile : PerfProfiles.preset(PerfProfiles.ID_BALANCED);
        postOnMain(() -> {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                        || !WebViewCompat.isMultiProcessEnabled()) return;
                Profile wvProfile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
                if (wvProfile == null) return;

                boolean aggressive = !isLowRam && p.preloadLevel == PerfProfiles.PRELOAD_AGGRESSIVE;
                boolean balanced = !isLowRam && p.preloadLevel == PerfProfiles.PRELOAD_PRECONNECT;
                int maxPrefetches = aggressive ? 8 : (balanced ? 4 : 0);
                int maxPrerenders = aggressive ? 3 : (balanced ? 1 : 0);
                int ttlSec = aggressive ? 300 : 180;

                if (WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING_CONFIG)) {
                    SpeculativeLoadingConfig cfg = new SpeculativeLoadingConfig.Builder()
                            .setMaxPrefetches(maxPrefetches)
                            .setPrefetchTtlSeconds(ttlSec)
                            .setMaxPrerenders(maxPrerenders)
                            .build();
                    wvProfile.setSpeculativeLoadingConfig(cfg);
                    speculativeConfigActive = maxPrefetches > 0 || maxPrerenders > 0;
                } else if (WebViewFeature.isFeatureSupported(WebViewFeature.SET_MAX_PRERENDERS_V1)) {
                    wvProfile.setMaxPrerenders(maxPrerenders);
                    speculativeConfigActive = maxPrerenders > 0;
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)) {
                    HttpCache httpCache = wvProfile.getHttpCache();
                    if (httpCache != null) {
                        if (isLowRam || PerfProfiles.ID_ECO.equals(p.id)) {
                            httpCache.useDefaultQuota();
                            httpCacheQuotaMb = Math.max(0L, httpCache.getDefaultQuotaBytes() / (1024L * 1024L));
                        } else {
                            long mb = PerfProfiles.ID_RAMPAGE.equals(p.id) ? 512L : 256L;
                            httpCache.setQuotaBytes(mb * 1024L * 1024L);
                            httpCacheQuotaMb = mb;
                        }
                    }
                }
            } catch (Throwable ignored) { }
        });
    }

    /**
     * Installs Service Worker ad/tracker interception so PWAs & modern sites (YouTube, X, Bilibili)
     * cannot bypass WebViewClient.shouldInterceptRequest via background Service Workers.
     */
    public static void installServiceWorkerFilter(SiteFilter filter) {
        if (filter == null) return;
        postOnMain(() -> {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
                        || !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                    return;
                }
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(new ServiceWorkerClientCompat() {
                    @Override
                    public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebResourceRequest request) {
                        if (request != null && filter.blocksServiceWorker(request)) {
                            SW_BLOCKED_COUNT.incrementAndGet();
                            return SiteFilter.blocked();
                        }
                        return null;
                    }
                });
                serviceWorkerFilterActive = true;
            } catch (Throwable ignored) { }
        });
    }

    public static long serviceWorkerBlockedCount() {
        return SW_BLOCKED_COUNT.get();
    }

    public static boolean isStarted() { return started; }

    public static void configureWebView(WebView web, boolean isPrivate, boolean isLowRam) {
        if (web == null) return;
        WebSettings settings = web.getSettings();
        settings.setOffscreenPreRaster(false);

        // Privacy + Speed triple-combo:
        // 1. Strip X-Requested-With: app.aikobrowser header so sites treat Aiko like genuine Chrome
        // 2. Disable background ad-attribution pings
        // 3. Disable SafeBrowsing IPC overhead on non-GMS / China ROMs
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
            }
        } catch (Throwable ignored) { }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
                WebSettingsCompat.setAttributionRegistrationBehavior(
                        settings, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED);
            }
        } catch (Throwable ignored) { }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
            }
        } catch (Throwable ignored) { }

        if (!isLowRam) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
                    WebSettingsCompat.setBackForwardCacheEnabled(settings, true);
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE_SETTINGS)) {
                        BackForwardCacheSettings bfc = WebSettingsCompat.getBackForwardCacheSettings(settings);
                        if (bfc != null) {
                            bfc.setMaxPagesInCache(isPrivate ? 2 : 3);
                            bfc.setTimeoutSeconds(isPrivate ? 120L : 300L);
                            bfc.setKeepForwardEntriesEnabled(true);
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }

        // API 29+ hung-renderer recovery watchdog (cancelled immediately on detach).
        try {
            final Runnable[] pendingTerminate = new Runnable[1];
            web.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }

                @Override public void onViewDetachedFromWindow(View v) {
                    if (pendingTerminate[0] != null) {
                        MAIN.removeCallbacks(pendingTerminate[0]);
                        pendingTerminate[0] = null;
                    }
                }
            });
            web.setWebViewRenderProcessClient(new WebViewRenderProcessClient() {
                @Override public void onRenderProcessUnresponsive(WebView view, WebViewRenderProcess renderer) {
                    if (renderer == null || !view.isAttachedToWindow()) return;
                    if (pendingTerminate[0] != null) MAIN.removeCallbacks(pendingTerminate[0]);
                    Runnable task = () -> {
                        pendingTerminate[0] = null;
                        if (!view.isAttachedToWindow()) return;
                        try { renderer.terminate(); } catch (RuntimeException ignored) { }
                    };
                    pendingTerminate[0] = task;
                    MAIN.postDelayed(task, HUNG_RENDERER_GRACE_MS);
                }

                @Override public void onRenderProcessResponsive(WebView view, WebViewRenderProcess renderer) {
                    if (pendingTerminate[0] != null) {
                        MAIN.removeCallbacks(pendingTerminate[0]);
                        pendingTerminate[0] = null;
                    }
                }
            });
        } catch (RuntimeException ignored) { }
    }

    public static void setActiveRenderer(WebView web, boolean active) {
        if (web == null) return;
        try {
            web.setRendererPriorityPolicy(
                    active ? WebView.RENDERER_PRIORITY_IMPORTANT : WebView.RENDERER_PRIORITY_BOUND,
                    !active);
        } catch (RuntimeException ignored) { }
    }

    /**
     * Applies a PerfProfiles.Profile to a WebView: BFCache depth/timeout, Speculative Loading
     * (prerender), and foreground-only Offscreen Pre-Raster.
     */
    public static void applyProfile(WebView web, PerfProfiles.Profile profile,
                                    boolean isPrivate, boolean isLowRam, boolean activeTab) {
        if (web == null) return;
        PerfProfiles.Profile p = profile != null ? profile : PerfProfiles.preset(PerfProfiles.ID_BALANCED);
        WebSettings settings = web.getSettings();

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
                int pages = isLowRam ? 0 : (isPrivate ? Math.min(2, p.bfCachePages) : p.bfCachePages);
                boolean enableBf = pages > 0;
                WebSettingsCompat.setBackForwardCacheEnabled(settings, enableBf);
                if (enableBf && WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE_SETTINGS)) {
                    BackForwardCacheSettings bfc = WebSettingsCompat.getBackForwardCacheSettings(settings);
                    if (bfc != null) {
                        bfc.setMaxPagesInCache(pages);
                        bfc.setTimeoutSeconds(isPrivate ? 120L : (pages >= 4 ? 600L : 300L));
                        bfc.setKeepForwardEntriesEnabled(true);
                    }
                }
            }
        } catch (Throwable ignored) { }

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) {
                boolean enablePrerender = p.preloadLevel == PerfProfiles.PRELOAD_AGGRESSIVE
                        && !isLowRam && !isPrivate;
                WebSettingsCompat.setSpeculativeLoadingStatus(settings,
                        enablePrerender ? WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED
                                : WebSettingsCompat.SPECULATIVE_LOADING_DISABLED);
            }
        } catch (Throwable ignored) { }

        setActivePreRaster(web, activeTab && p.activePreRaster && !isLowRam);
    }

    /**
     * Toggles Offscreen Pre-Raster on a single WebView. Callers MUST enable this only on
     * the single active foreground tab and disable it on inactive tabs and onTrimMemory().
     */
    public static void setActivePreRaster(WebView web, boolean enable) {
        if (web == null) return;
        try {
            web.getSettings().setOffscreenPreRaster(enable);
        } catch (RuntimeException ignored) { }
    }

    /** Human-readable Android System WebView / Chromium version summary for the About panel. */
    public static String webViewVersionSummary(Context ctx) {
        try {
            PackageInfo pkg = ctx != null ? WebViewCompat.getCurrentWebViewPackage(ctx) : null;
            if (pkg == null) pkg = WebViewCompat.getCurrentLoadedWebViewPackage();
            if (pkg == null) return "系统 WebView (未知版本)";
            String ver = (pkg.versionName != null && !pkg.versionName.isEmpty()) ? pkg.versionName : "未知";
            String name;
            String pkgName = pkg.packageName;
            if ("com.google.android.webview".equals(pkgName) || "com.android.webview".equals(pkgName)) {
                name = "Android System WebView";
            } else if ("com.android.chrome".equals(pkgName)) {
                name = "Chrome WebView";
            } else if ("com.google.android.trichromelibrary".equals(pkgName)) {
                name = "Trichrome WebView";
            } else if (pkgName != null && !pkgName.isEmpty()) {
                name = pkgName;
            } else {
                name = "Chromium WebView";
            }
            return name + " " + ver;
        } catch (Throwable ignored) {
            return "系统 WebView (未知版本)";
        }
    }

    @SuppressWarnings("deprecation")
    public static void setAlgorithmicDarkening(WebView web, boolean allowed) {
        if (web == null) return;
        WebSettings settings = web.getSettings();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                settings.setAlgorithmicDarkeningAllowed(allowed);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allowed);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings,
                        allowed ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) { }
    }

    public static void setAudioMuted(WebView web, boolean muted) {
        if (web == null) return;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(web, muted);
            }
        } catch (Throwable ignored) { }
    }

    public static boolean isAudioMuted(WebView web) {
        if (web == null) return false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                return WebViewCompat.isAudioMuted(web);
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public static boolean saveStateBounded(WebView web, Bundle out, int maxBytes) {
        if (web == null || out == null || maxBytes <= 0) return false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
                WebViewCompat.saveState(web, out, maxBytes, true);
                return !out.isEmpty();
            }
        } catch (Throwable ignored) { }
        try {
            if (web.saveState(out) == null) return false;
            Parcel parcel = Parcel.obtain();
            try {
                out.writeToParcel(parcel, 0);
                if (parcel.dataSize() <= maxBytes) return true;
                out.clear();
                return false;
            } finally {
                parcel.recycle();
            }
        } catch (RuntimeException ignored) {
            out.clear();
            return false;
        }
    }

    /**
     * Dispatches a connection-only (DNS+TCP+TLS) preconnect hint for a user-confirmed or touched
     * HTTPS destination, strictly filtered by adblock with a 10s per-origin dedup window.
     */
    public static void preconnect(String url, SiteFilter filter) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> preconnect(url, filter));
            return;
        }
        String origin = HttpUrlUtil.extractOrigin(url);
        if (origin.isEmpty()) return;
        if (filter != null) {
            try {
                if (filter.isBlockedUrlFast(origin + "/")) return;
            } catch (RuntimeException ignored) { }
        }
        long now = System.nanoTime();
        if (wasRecentlySeen(recentOrigins, recentOriginAt, origin, now, PRECONNECT_TTL_NS)) return;

        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                    || !WebViewCompat.isMultiProcessEnabled()) {
                return;
            }
            boolean canPreconnect = started && WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT);
            boolean canEnqueue = WebViewFeature.isFeatureSupported(WebViewFeature.ENQUEUE_PRECONNECT);
            if (!canPreconnect && !canEnqueue) return;

            Profile profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
            if (profile == null) return;
            if (canPreconnect) profile.preconnect(origin);
            else profile.enqueuePreconnect(origin);
            originSlot = recordSeen(recentOrigins, recentOriginAt, originSlot, origin, now, PRECONNECT_TTL_NS);
        } catch (Throwable ignored) { }
    }

    /**
     * Safely prefetches a user-selected background-tab HTTPS URL only on non-low-RAM devices
     * over unmetered Wi-Fi/Ethernet and after passing adblock filtering.
     */
    public static void prefetchBackgroundUrl(Context ctx, String url, SiteFilter filter) {
        if (ctx == null || url == null || !started) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Context app = ctx.getApplicationContext();
            MAIN.post(() -> prefetchBackgroundUrl(app, url, filter));
            return;
        }
        if (isLowRam(ctx) || isNetworkMetered(ctx)) return;
        if (HttpUrlUtil.extractOrigin(url).isEmpty()) return;
        if (filter != null) {
            try {
                if (filter.isBlockedUrlFast(url)) return;
            } catch (RuntimeException ignored) { }
        }
        long now = System.nanoTime();
        if (wasRecentlySeen(recentPrefetchUrls, recentPrefetchAt, url, now, PREFETCH_TTL_NS)) return;

        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                    || !WebViewCompat.isMultiProcessEnabled()) {
                return;
            }
            Profile profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
            if (profile == null) return;
            prefetchSlot = recordSeen(recentPrefetchUrls, recentPrefetchAt, prefetchSlot, url, now, PREFETCH_TTL_NS);
            profile.prefetchUrlAsync(url, null, PREFETCH_EXEC,
                    new WebViewOutcomeReceiver<Void, PrefetchException>() {
                        @Override public void onResult(Void result) { }
                        @Override public void onError(PrefetchException error) { }
                    });
        } catch (Throwable ignored) { }
    }

    /**
     * Non-blocking background DNS + lightweight TCP/TLS route warm-up for a target URL's host.
     * Deduplicated per host:port with a 45-second cooldown on a bounded 2-thread daemon pool.
     */
    public static void preconnectHost(String url) {
        if (!HttpUrlUtil.isHttp(url)) return;
        String host = HttpUrlUtil.extractHost(url);
        if (host.isEmpty()) return;
        boolean https = url.length() >= 8 && url.regionMatches(true, 0, "https://", 0, 8);
        int port = parseExplicitPort(url, https ? 443 : 80);
        String key = host + ":" + port;
        long now = System.nanoTime();
        synchronized (HOST_PRECONNECT_LOCK) {
            Long prev = RECENT_HOST_PRECONNECT.get(key);
            if (prev != null && now - prev < PRECONNECT_HOST_TTL_NS) return;
            RECENT_HOST_PRECONNECT.put(key, now);
            if (RECENT_HOST_PRECONNECT.size() > HOST_PRECONNECT_CAP) {
                Iterator<Map.Entry<String, Long>> it = RECENT_HOST_PRECONNECT.entrySet().iterator();
                while (RECENT_HOST_PRECONNECT.size() > HOST_PRECONNECT_CAP && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        final String targetHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        final int targetPort = port;
        try {
            HOST_PRECONNECT_EXEC.execute(() -> {
                try {
                    InetAddress addr = InetAddress.getByName(targetHost);
                    if (addr == null) return;
                    try (Socket sock = new Socket()) {
                        sock.connect(new InetSocketAddress(addr, targetPort), HOST_PRECONNECT_TIMEOUT_MS);
                    }
                } catch (IOException | RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
    }

    private static int parseExplicitPort(String url, int defaultPort) {
        int start = url.indexOf("://");
        if (start < 0) return defaultPort;
        start += 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        if (start < end && url.charAt(start) == '[') {
            int close = url.indexOf(']', start + 1);
            if (close < 0 || close + 2 >= end || url.charAt(close + 1) != ':') return defaultPort;
            return parsePortRange(url, close + 2, end, defaultPort);
        }
        int colon = url.indexOf(':', start);
        if (colon < 0 || colon >= end) return defaultPort;
        return parsePortRange(url, colon + 1, end, defaultPort);
    }

    private static int parsePortRange(String s, int from, int to, int fallback) {
        if (from >= to || to - from > 5) return fallback;
        int p = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return fallback;
            p = p * 10 + (c - '0');
        }
        return (p > 0 && p <= 65535) ? p : fallback;
    }

    /**
     * Returns the smart tab-dormancy active WebView cap for a PerfProfiles.Profile:
     * Rampage -> 12, Balanced -> 6, Eco -> 3, custom profiles scaled cleanly in [3, 12].
     */
    public static int maxActiveTabsForProfile(PerfProfiles.Profile p) {
        if (p == null) return 6;
        if (PerfProfiles.ID_RAMPAGE.equals(p.id)) return 12;
        if (PerfProfiles.ID_BALANCED.equals(p.id)) return 6;
        if (PerfProfiles.ID_ECO.equals(p.id)) return 3;
        return Math.max(3, Math.min(12, p.maxHotTabs));
    }

    public static int recommendedHotTabs(Context ctx) {
        if (ctx == null) return 5;
        ActivityManager am = (ActivityManager) ctx.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 5;
        if (am.isLowRamDevice()) return 3;
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long total = info.totalMem;
            if (total >= 14_500_000_000L) return 8;
            if (total >= 10_500_000_000L) return 7;
            if (total > 0L && total < 4_500_000_000L) return 3;
        } catch (RuntimeException ignored) { }
        return 5;
    }

    public static boolean isLowRam(Context ctx) {
        if (ctx == null) return false;
        ActivityManager am = (ActivityManager) ctx.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }

    public static boolean isBfCacheSupported() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE); }
        catch (Throwable ignored) { return false; }
    }

    public static String flagshipFeatureSummary() {
        StringBuilder sb = new StringBuilder(64);
        if (quicHintsActive) sb.append("HTTP/3 QUIC");
        if (serviceWorkerFilterActive) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("SW过滤");
        }
        if (httpCacheQuotaMb > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(httpCacheQuotaMb).append("MB缓存");
        }
        if (isBfCacheSupported()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("BFCache");
        }
        return sb.length() > 0 ? sb.toString() : "Chromium 旗舰内核";
    }

    public static String diagnosticSummary() {
        String mode = usedAsyncStartup
                ? ("异步启动" + (startupUiMs != null ? " (UI " + startupUiMs + "ms)" : ""))
                : "标准启动";
        StringBuilder sb = new StringBuilder(128);
        sb.append(mode)
                .append(" · BFCache ").append(isBfCacheSupported() ? "开" : "不支持")
                .append(" · HTTP/3 QUIC ").append(quicHintsActive ? "已预热" : "待机")
                .append(" · 预渲染 ").append(speculativeConfigActive ? "开" : "默认")
                .append(" · SW拦截 ").append(serviceWorkerFilterActive ? ("开(" + SW_BLOCKED_COUNT.get() + ")") : "未启用");
        if (httpCacheQuotaMb > 0) {
            sb.append(" · 磁盘缓存 ").append(httpCacheQuotaMb).append("MB");
        }
        return sb.toString();
    }

    private static boolean isNetworkMetered(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean wasRecentlySeen(String[] keys, long[] times, String key, long now, long ttl) {
        for (int i = 0; i < keys.length; i++) {
            if (key.equals(keys[i]) && now - times[i] < ttl) return true;
        }
        return false;
    }

    private static int recordSeen(String[] keys, long[] times, int cursor, String key, long now, long ttl) {
        for (int i = 0; i < keys.length; i++) {
            if (key.equals(keys[i]) || keys[i] == null || now - times[i] >= ttl) {
                keys[i] = key;
                times[i] = now;
                return cursor;
            }
        }
        keys[cursor] = key;
        times[cursor] = now;
        return (cursor + 1) % keys.length;
    }

    private static void postOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
    }
}
