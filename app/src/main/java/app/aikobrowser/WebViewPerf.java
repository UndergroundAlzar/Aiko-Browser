package app.aikobrowser;

import android.app.ActivityManager;
import android.content.Context;
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
import androidx.webkit.PrefetchException;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AndroidX WebKit 1.18.0 flagship performance pipeline:
 * async provider startup, renderer warm-up, BFCache, confirmed-navigation preconnect,
 * unmetered background prefetch, bounded state save, and hung-renderer recovery.
 */
public final class WebViewPerf {
    private static final long PRECONNECT_TTL_NS = 10_000_000_000L;
    private static final long PREFETCH_TTL_NS = 15_000_000_000L;
    private static final long STARTUP_WATCHDOG_MS = 1500L;
    private static final long HUNG_RENDERER_GRACE_MS = 8000L;
    private static final int RECENT_ORIGINS_CAP = 24;
    private static final int RECENT_PREFETCH_CAP = 16;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object START_LOCK = new Object();
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

    private static volatile boolean started;
    private static boolean starting;
    private static volatile boolean usedAsyncStartup;
    private static volatile Long startupUiMs;

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
                WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(STARTUP_EXEC)
                        .setShouldRunUiThreadStartUpTasks(true)
                        .build();
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
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                    && WebViewCompat.isMultiProcessEnabled()) {
                // Always use getOrCreateProfile("Default"); getProfile() can block on native startup.
                Profile profile = ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME);
                if (profile != null) profile.warmUpRendererProcess();
            }
        } catch (Throwable ignored) { }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
                WebViewCompat.startSafeBrowsing(appContext, null);
            }
        } catch (Throwable ignored) { }
    }

    public static boolean isStarted() { return started; }

    public static void configureWebView(WebView web, boolean isPrivate, boolean isLowRam) {
        if (web == null) return;
        WebSettings settings = web.getSettings();
        settings.setOffscreenPreRaster(false);

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

    public static String diagnosticSummary() {
        String mode = usedAsyncStartup
                ? ("异步启动" + (startupUiMs != null ? " (UI " + startupUiMs + "ms)" : ""))
                : "标准启动";
        return mode + " · BFCache " + (isBfCacheSupported() ? "开" : "不支持");
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
