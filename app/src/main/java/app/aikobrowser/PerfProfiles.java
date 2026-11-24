package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flagship Performance Profile Engine (built-in Rampage/Balanced/Eco + user Custom modes)
 * and Clean-URL copy/share preference.
 *
 * All Profile value types, presets, clamping, and summary formatting are pure Java so host JVM
 * unit tests can validate them directly without Android runtime mocks.
 */
public final class PerfProfiles {
    public static final String ID_RAMPAGE = "rampage";
    public static final String ID_BALANCED = "balanced";
    public static final String ID_ECO = "eco";

    public static final int PRELOAD_OFF = 0;
    public static final int PRELOAD_PRECONNECT = 1;
    public static final int PRELOAD_AGGRESSIVE = 2;

    public static final int MIN_HOT_TABS = 2;
    public static final int MAX_HOT_TABS = 12;
    public static final int MIN_BFCACHE_PAGES = 0;
    public static final int MAX_BFCACHE_PAGES = 5;
    public static final int MIN_DOWNLOAD_LANES = 1;
    public static final int MAX_DOWNLOAD_LANES = 128;
    public static final int MAX_CUSTOM_PROFILES = 16;

    private static final String PREFS = "perf_profiles";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String KEY_CUSTOM_LIST = "custom_list";
    private static final String KEY_CLEAN_LINKS = "clean_links";
    private static final String LEGACY_DISPLAY_PREFS = "display_mode";
    private static final String LEGACY_DISPLAY_POLICY = "policy";

    private static final Profile PRESET_RAMPAGE = new Profile(
            ID_RAMPAGE, "狂暴性能", true,
            DisplayModeEngine.POLICY_MAX, true, 10, 5,
            PRELOAD_AGGRESSIVE, true, 128, false);

    private static final Profile PRESET_BALANCED = new Profile(
            ID_BALANCED, "智能均衡", true,
            DisplayModeEngine.POLICY_MAX, false, 6, 3,
            PRELOAD_PRECONNECT, false, 64, false);

    private static final Profile PRESET_ECO = new Profile(
            ID_ECO, "持久省电", true,
            DisplayModeEngine.POLICY_POWER, false, 2, 1,
            PRELOAD_OFF, false, 16, false);

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-perf-profiles");
        t.setDaemon(true);
        return t;
    });

    private static volatile Boolean cleanLinksCached;

    /** Immutable performance profile value object (pure Java, host-testable). */
    public static final class Profile {
        public final String id;
        public final String name;
        public final boolean builtin;
        public final int refreshPolicy;
        public final boolean adpfBoost;
        public final int maxHotTabs;
        public final int bfCachePages;
        public final int preloadLevel;
        public final boolean activePreRaster;
        public final int downloadLanes;
        public final boolean globalUltraScroll;

        public Profile(String id, String name, boolean builtin, int refreshPolicy,
                       boolean adpfBoost, int maxHotTabs, int bfCachePages,
                       int preloadLevel, boolean activePreRaster,
                       int downloadLanes, boolean globalUltraScroll) {
            this.id = id;
            this.name = name;
            this.builtin = builtin;
            this.refreshPolicy = refreshPolicy;
            this.adpfBoost = adpfBoost;
            this.maxHotTabs = maxHotTabs;
            this.bfCachePages = bfCachePages;
            this.preloadLevel = preloadLevel;
            this.activePreRaster = activePreRaster;
            this.downloadLanes = downloadLanes;
            this.globalUltraScroll = globalUltraScroll;
        }
    }

    private final Context appContext;
    private final Object lock = new Object();
    private final ArrayList<Profile> customs = new ArrayList<>();
    private String activeId = ID_BALANCED;
    private boolean loaded;

    public PerfProfiles(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Preloads saved profile selection and custom profiles off the UI thread. */
    public void preloadAsync() {
        IO.execute(this::ensureLoaded);
    }

    /** Returns built-in presets (Rampage, Balanced, Eco) followed by all user custom profiles. */
    public List<Profile> all() {
        ensureLoaded();
        synchronized (lock) {
            ArrayList<Profile> out = new ArrayList<>(3 + customs.size());
            out.add(PRESET_RAMPAGE);
            out.add(PRESET_BALANCED);
            out.add(PRESET_ECO);
            out.addAll(customs);
            return out;
        }
    }

    /** Returns the currently active performance profile (defaults to Balanced). */
    public Profile active() {
        ensureLoaded();
        synchronized (lock) {
            Profile found = findLocked(activeId);
            return found != null ? found : PRESET_BALANCED;
        }
    }

    /** Selects a built-in or custom profile by ID and persists asynchronously. */
    public void select(String id) {
        ensureLoaded();
        Profile chosen;
        synchronized (lock) {
            chosen = findLocked(id);
            if (chosen == null) chosen = PRESET_BALANCED;
            if (chosen.id.equals(activeId)) return;
            activeId = chosen.id;
        }
        final String persistId = chosen.id;
        final int policy = chosen.refreshPolicy;
        IO.execute(() -> {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ACTIVE_ID, persistId).apply();
            DisplayModeEngine.savePolicy(appContext, policy);
        });
    }

    /**
     * Saves a new custom profile or updates an existing custom profile in-place.
     * Built-in profiles cannot be overwritten; passing a built-in or empty ID allocates a fresh custom ID.
     */
    public Profile saveCustom(Profile raw) {
        ensureLoaded();
        Profile clean = sanitizeCustomForStorage(raw);
        String jsonSnapshot;
        boolean syncActivePolicy = false;
        synchronized (lock) {
            int existingIdx = -1;
            for (int i = 0; i < customs.size(); i++) {
                if (customs.get(i).id.equals(clean.id)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0) {
                customs.set(existingIdx, clean);
            } else {
                while (customs.size() >= MAX_CUSTOM_PROFILES) {
                    int dropIdx = 0;
                    if (customs.get(0).id.equals(activeId) && customs.size() > 1) dropIdx = 1;
                    customs.remove(dropIdx);
                }
                customs.add(clean);
            }
            syncActivePolicy = clean.id.equals(activeId);
            jsonSnapshot = encodeCustomsLocked();
        }
        final String serialized = jsonSnapshot;
        final boolean updatePolicy = syncActivePolicy;
        final int policy = clean.refreshPolicy;
        IO.execute(() -> {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CUSTOM_LIST, serialized).apply();
            if (updatePolicy) DisplayModeEngine.savePolicy(appContext, policy);
        });
        return clean;
    }

    /** Deletes a user custom profile by ID. Built-in profiles cannot be deleted. */
    public boolean deleteCustom(String id) {
        if (id == null || isBuiltinId(id)) return false;
        ensureLoaded();
        String jsonSnapshot;
        boolean resetActive = false;
        synchronized (lock) {
            int idx = -1;
            for (int i = 0; i < customs.size(); i++) {
                if (customs.get(i).id.equals(id)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return false;
            customs.remove(idx);
            if (id.equals(activeId)) {
                activeId = ID_BALANCED;
                resetActive = true;
            }
            jsonSnapshot = encodeCustomsLocked();
        }
        final String serialized = jsonSnapshot;
        final boolean persistReset = resetActive;
        IO.execute(() -> {
            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putString(KEY_CUSTOM_LIST, serialized);
            if (persistReset) ed.putString(KEY_ACTIVE_ID, ID_BALANCED);
            ed.apply();
            if (persistReset) DisplayModeEngine.savePolicy(appContext, PRESET_BALANCED.refreshPolicy);
        });
        return true;
    }

    /** Returns whether Clean URL stripping on copy/share is enabled (defaults to true). */
    public static boolean isCleanLinksEnabled(Context ctx) {
        Boolean cached = cleanLinksCached;
        if (cached != null) return cached;
        if (ctx == null) return true;
        boolean value = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLEAN_LINKS, true);
        cleanLinksCached = value;
        return value;
    }

    /** Updates Clean URL copy/share toggle and persists asynchronously. */
    public static void setCleanLinksEnabled(Context ctx, boolean enabled) {
        cleanLinksCached = enabled;
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLEAN_LINKS, enabled).apply());
    }

    /** Returns one of the three built-in presets (defaults to Balanced for unknown IDs). */
    public static Profile preset(String id) {
        if (ID_RAMPAGE.equals(id)) return PRESET_RAMPAGE;
        if (ID_ECO.equals(id)) return PRESET_ECO;
        return PRESET_BALANCED;
    }

    /** Returns true if the given ID belongs to a built-in preset. */
    public static boolean isBuiltinId(String id) {
        return ID_RAMPAGE.equals(id) || ID_BALANCED.equals(id) || ID_ECO.equals(id);
    }

    /** Pure-Java clamping and validation of a Profile instance. */
    public static Profile sanitized(Profile raw) {
        if (raw == null) return PRESET_BALANCED;
        return sanitized(
                raw.id, raw.name, raw.builtin, raw.refreshPolicy,
                raw.adpfBoost, raw.maxHotTabs, raw.bfCachePages,
                raw.preloadLevel, raw.activePreRaster,
                raw.downloadLanes, raw.globalUltraScroll);
    }

    /** Pure-Java clamping and validation of all performance profile levers. */
    public static Profile sanitized(
            String id, String name, boolean builtin, int refreshPolicy,
            boolean adpfBoost, int maxHotTabs, int bfCachePages,
            int preloadLevel, boolean activePreRaster,
            int downloadLanes, boolean globalUltraScroll) {
        String cleanId = sanitizeId(id, builtin);
        String cleanName = sanitizeName(name);
        int policy = clampRefreshPolicy(refreshPolicy);
        int hotTabs = Math.max(MIN_HOT_TABS, Math.min(MAX_HOT_TABS, maxHotTabs));
        int bfPages = Math.max(MIN_BFCACHE_PAGES, Math.min(MAX_BFCACHE_PAGES, bfCachePages));
        int preload = clampPreloadLevel(preloadLevel);
        int lanes = Math.max(MIN_DOWNLOAD_LANES, Math.min(MAX_DOWNLOAD_LANES, downloadLanes));
        return new Profile(cleanId, cleanName, builtin, policy, adpfBoost,
                hotTabs, bfPages, preload, activePreRaster, lanes, globalUltraScroll);
    }

    /** Concise Chinese hardware strategy summary for cards, pills, and diagnostics. */
    public static String summaryLine(Profile raw) {
        Profile p = sanitized(raw);
        StringBuilder sb = new StringBuilder(64);
        if (p.refreshPolicy == DisplayModeEngine.POLICY_POWER) sb.append("60Hz省电");
        else if (p.refreshPolicy == DisplayModeEngine.POLICY_AUTO) sb.append("自适应刷新");
        else sb.append("最高刷");

        if (p.adpfBoost) sb.append(" · ADPF助推");
        sb.append(" · ").append(p.maxHotTabs).append("热标签");
        if (p.bfCachePages > 0) sb.append(" · ").append(p.bfCachePages).append("页BFCache");
        else sb.append(" · 无BFCache");

        if (p.preloadLevel == PRELOAD_AGGRESSIVE) sb.append(" · 激进预加载");
        else if (p.preloadLevel == PRELOAD_PRECONNECT) sb.append(" · 智能预连");

        if (p.activePreRaster) sb.append(" · 预光栅");
        if (p.globalUltraScroll) sb.append(" · 全局抗卡盾");
        sb.append(" · ").append(p.downloadLanes).append("路");
        return sb.toString();
    }

    public static String preloadLabel(int level) {
        switch (clampPreloadLevel(level)) {
            case PRELOAD_AGGRESSIVE: return "激进预加载";
            case PRELOAD_PRECONNECT: return "智能预连";
            default: return "关闭预加载";
        }
    }

    public static int clampRefreshPolicy(int policy) {
        return (policy == DisplayModeEngine.POLICY_AUTO || policy == DisplayModeEngine.POLICY_POWER)
                ? policy : DisplayModeEngine.POLICY_MAX;
    }

    public static int clampPreloadLevel(int level) {
        if (level <= PRELOAD_OFF) return PRELOAD_OFF;
        if (level >= PRELOAD_AGGRESSIVE) return PRELOAD_AGGRESSIVE;
        return PRELOAD_PRECONNECT;
    }

    private static Profile sanitizeCustomForStorage(Profile raw) {
        Profile base = raw == null ? PRESET_BALANCED : raw;
        String id = base.id == null ? "" : base.id.trim();
        if (id.isEmpty() || isBuiltinId(id)) {
            id = "custom-" + UUID.randomUUID().toString();
        }
        return sanitized(
                id, base.name, false, base.refreshPolicy,
                base.adpfBoost, base.maxHotTabs, base.bfCachePages,
                base.preloadLevel, base.activePreRaster,
                base.downloadLanes, base.globalUltraScroll);
    }

    private static String sanitizeId(String id, boolean builtin) {
        String s = id == null ? "" : id.trim();
        if (builtin) {
            if (ID_RAMPAGE.equals(s) || ID_BALANCED.equals(s) || ID_ECO.equals(s)) return s;
            return ID_BALANCED;
        }
        if (s.isEmpty() || isBuiltinId(s)) return "custom-" + UUID.randomUUID().toString();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "自定义模式";
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 24));
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        String trimmed = sb.toString().trim();
        return trimmed.isEmpty() ? "自定义模式" : trimmed;
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) return;
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (cleanLinksCached == null) cleanLinksCached = sp.getBoolean(KEY_CLEAN_LINKS, true);

            customs.clear();
            String rawList = sp.getString(KEY_CUSTOM_LIST, null);
            if (rawList != null && !rawList.isEmpty()) {
                parseCustomsLocked(rawList);
            }

            String savedActive = sp.getString(KEY_ACTIVE_ID, null);
            if (savedActive != null && findLocked(savedActive) != null) {
                activeId = savedActive;
            } else if (savedActive == null) {
                // Respect legacy v0.2.x user choice if they explicitly enabled 60Hz power-save mode.
                SharedPreferences legacy = appContext.getSharedPreferences(LEGACY_DISPLAY_PREFS, Context.MODE_PRIVATE);
                if (legacy.contains(LEGACY_DISPLAY_POLICY)
                        && legacy.getInt(LEGACY_DISPLAY_POLICY, DisplayModeEngine.POLICY_MAX)
                        == DisplayModeEngine.POLICY_POWER) {
                    activeId = ID_ECO;
                } else {
                    activeId = ID_BALANCED;
                }
            } else {
                activeId = ID_BALANCED;
            }
            loaded = true;
        }
    }

    private Profile findLocked(String id) {
        if (id == null) return null;
        if (ID_RAMPAGE.equals(id)) return PRESET_RAMPAGE;
        if (ID_BALANCED.equals(id)) return PRESET_BALANCED;
        if (ID_ECO.equals(id)) return PRESET_ECO;
        for (int i = 0; i < customs.size(); i++) {
            Profile p = customs.get(i);
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private void parseCustomsLocked(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            int limit = Math.min(arr.length(), MAX_CUSTOM_PROFILES);
            for (int i = 0; i < limit; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                if (id.isEmpty() || isBuiltinId(id)) continue;
                boolean dup = false;
                for (int j = 0; j < customs.size(); j++) {
                    if (customs.get(j).id.equals(id)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                customs.add(sanitized(
                        id,
                        o.optString("name", "自定义模式"),
                        false,
                        o.optInt("refreshPolicy", DisplayModeEngine.POLICY_MAX),
                        o.optBoolean("adpfBoost", false),
                        o.optInt("maxHotTabs", 6),
                        o.optInt("bfCachePages", 3),
                        o.optInt("preloadLevel", PRELOAD_PRECONNECT),
                        o.optBoolean("activePreRaster", false),
                        o.optInt("downloadLanes", 64),
                        o.optBoolean("globalUltraScroll", false)));
            }
        } catch (JSONException ignored) { }
    }

    private String encodeCustomsLocked() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < customs.size(); i++) {
            Profile p = customs.get(i);
            try {
                arr.put(new JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("refreshPolicy", p.refreshPolicy)
                        .put("adpfBoost", p.adpfBoost)
                        .put("maxHotTabs", p.maxHotTabs)
                        .put("bfCachePages", p.bfCachePages)
                        .put("preloadLevel", p.preloadLevel)
                        .put("activePreRaster", p.activePreRaster)
                        .put("downloadLanes", p.downloadLanes)
                        .put("globalUltraScroll", p.globalUltraScroll));
            } catch (JSONException ignored) { }
        }
        return arr.toString();
    }
}
