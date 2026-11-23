package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Additive per-site & global browsing preferences stored in dedicated "site_extras" prefs.
 * Never modifies legacy "site_profiles" storage.
 */
public final class SiteExtras {
    public static final int DARK_FOLLOW = 0;
    public static final int DARK_FORCE_ON = 1;
    public static final int DARK_FORCE_OFF = 2;

    private static final String PREFS = "site_extras";
    private static final String HOST_PREFIX = "h:";
    private static final String KEY_GLOBAL_DARK = "g:dark_web";
    private static final String KEY_AUTO_HIDE = "g:auto_hide_bar";
    private static final String KEY_EDGE_GESTURES = "g:edge_gestures";

    // Packed host flag bits:
    // bit 0: ultraScroll
    // bit 1: jsDisabled
    // bit 2: forceMaxHz
    // bits 3..4: darkOverride (0=follow, 1=dark, 2=light)
    private static final int BIT_ULTRA_SCROLL = 1;
    private static final int BIT_JS_DISABLED = 1 << 1;
    private static final int BIT_FORCE_MAX_HZ = 1 << 2;
    private static final int DARK_SHIFT = 3;
    private static final int DARK_MASK = 3 << DARK_SHIFT;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-site-extras");
        t.setDaemon(true);
        return t;
    });

    private static final String ULTRA_SCROLL_CSS =
            "*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important;}" +
            "article,[role='article'],.topic-post,.Post,[data-testid='cellInnerDiv'],.Feed-item{" +
            "content-visibility:auto;contain-intrinsic-size:auto 480px;box-shadow:none!important;}" +
            "header,nav,[style*='backdrop-filter']{-webkit-backdrop-filter:none!important;backdrop-filter:none!important;}";

    private static final String ULTRA_SCROLL_ENABLE_JS =
            "(function(){try{var id='__aiko_ultra_scroll';if(document.getElementById(id))return;" +
            "var s=document.createElement('style');s.id=id;s.textContent=\"" + ULTRA_SCROLL_CSS + "\";" +
            "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";

    private static final String ULTRA_SCROLL_DISABLE_JS =
            "(function(){try{var e=document.getElementById('__aiko_ultra_scroll');if(e&&e.parentNode)e.parentNode.removeChild(e);}catch(x){}})();";

    private final Context appContext;
    private final Object lock = new Object();
    private SharedPreferences prefs;
    private HashMap<String, Integer> hostBits;
    private boolean globalDarkWeb;
    private boolean autoHideBar = true;
    private boolean edgeGestures = true;
    private boolean loaded;

    public SiteExtras(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void preloadAsync() {
        IO.execute(this::ensureLoaded);
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) return;
            prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            hostBits = new HashMap<>();
            globalDarkWeb = prefs.getBoolean(KEY_GLOBAL_DARK, false);
            autoHideBar = prefs.getBoolean(KEY_AUTO_HIDE, true);
            edgeGestures = prefs.getBoolean(KEY_EDGE_GESTURES, true);
            Map<String, ?> all = prefs.getAll();
            if (all != null) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    if (k != null && k.startsWith(HOST_PREFIX) && v instanceof Integer) {
                        int bits = (Integer) v;
                        if (bits != 0) hostBits.put(k.substring(HOST_PREFIX.length()), bits);
                    }
                }
            }
            loaded = true;
        }
    }

    public boolean isUltraScroll(String host) {
        return (getHostBits(host) & BIT_ULTRA_SCROLL) != 0;
    }

    public void setUltraScroll(String host, boolean enabled) {
        updateHostBit(host, BIT_ULTRA_SCROLL, enabled);
    }

    public boolean isJsDisabled(String host) {
        return (getHostBits(host) & BIT_JS_DISABLED) != 0;
    }

    public void setJsDisabled(String host, boolean disabled) {
        updateHostBit(host, BIT_JS_DISABLED, disabled);
    }

    public int darkOverride(String host) {
        int v = (getHostBits(host) & DARK_MASK) >>> DARK_SHIFT;
        return (v == DARK_FORCE_ON || v == DARK_FORCE_OFF) ? v : DARK_FOLLOW;
    }

    public void setDarkOverride(String host, int mode) {
        int clamped = (mode == DARK_FORCE_ON || mode == DARK_FORCE_OFF) ? mode : DARK_FOLLOW;
        String key = normalizeHost(host);
        if (key.isEmpty()) return;
        ensureLoaded();
        int next;
        synchronized (lock) {
            int cur = hostBits.containsKey(key) ? hostBits.get(key) : 0;
            next = (cur & ~DARK_MASK) | (clamped << DARK_SHIFT);
            if (next == cur) return;
            if (next == 0) hostBits.remove(key);
            else hostBits.put(key, next);
        }
        persistHost(key, next);
    }

    public boolean isForceMaxHz(String host) {
        return (getHostBits(host) & BIT_FORCE_MAX_HZ) != 0;
    }

    public void setForceMaxHz(String host, boolean enabled) {
        updateHostBit(host, BIT_FORCE_MAX_HZ, enabled);
    }

    public void resetHost(String host) {
        String key = normalizeHost(host);
        if (key.isEmpty()) return;
        ensureLoaded();
        synchronized (lock) {
            if (hostBits.remove(key) == null) return;
        }
        persistHost(key, 0);
    }

    public boolean isGlobalDarkWeb() {
        ensureLoaded();
        synchronized (lock) { return globalDarkWeb; }
    }

    public void setGlobalDarkWeb(boolean enabled) {
        ensureLoaded();
        synchronized (lock) {
            if (globalDarkWeb == enabled) return;
            globalDarkWeb = enabled;
        }
        persistGlobal(KEY_GLOBAL_DARK, enabled);
    }

    public boolean isAutoHideBar() {
        ensureLoaded();
        synchronized (lock) { return autoHideBar; }
    }

    public void setAutoHideBar(boolean enabled) {
        ensureLoaded();
        synchronized (lock) {
            if (autoHideBar == enabled) return;
            autoHideBar = enabled;
        }
        persistGlobal(KEY_AUTO_HIDE, enabled);
    }

    public boolean isEdgeGestures() {
        ensureLoaded();
        synchronized (lock) { return edgeGestures; }
    }

    public void setEdgeGestures(boolean enabled) {
        ensureLoaded();
        synchronized (lock) {
            if (edgeGestures == enabled) return;
            edgeGestures = enabled;
        }
        persistGlobal(KEY_EDGE_GESTURES, enabled);
    }

    /** Returns pure-CSS injection or removal script for 185Hz anti-jank Ultra-Scroll mode. */
    public static String ultraScrollJs(boolean enable) {
        return enable ? ULTRA_SCROLL_ENABLE_JS : ULTRA_SCROLL_DISABLE_JS;
    }

    private int getHostBits(String host) {
        String key = normalizeHost(host);
        if (key.isEmpty()) return 0;
        ensureLoaded();
        synchronized (lock) {
            Integer v = hostBits.get(key);
            return v == null ? 0 : v;
        }
    }

    private void updateHostBit(String host, int bit, boolean enabled) {
        String key = normalizeHost(host);
        if (key.isEmpty()) return;
        ensureLoaded();
        int next;
        synchronized (lock) {
            int cur = hostBits.containsKey(key) ? hostBits.get(key) : 0;
            next = enabled ? (cur | bit) : (cur & ~bit);
            if (next == cur) return;
            if (next == 0) hostBits.remove(key);
            else hostBits.put(key, next);
        }
        persistHost(key, next);
    }

    private void persistHost(String host, int bits) {
        IO.execute(() -> {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (bits == 0) sp.edit().remove(HOST_PREFIX + host).apply();
            else sp.edit().putInt(HOST_PREFIX + host, bits).apply();
        });
    }

    private void persistGlobal(String key, boolean value) {
        IO.execute(() -> appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply());
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String s = host.trim();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') end--;
        return end <= 0 ? "" : s.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
