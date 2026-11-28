package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent configuration store for v0.4.0 Start Page / New Tab customization,
 * bottom bar layout, App-Jump Deep-Link Guard, AMOLED Night Comfort & Auto Day/Night,
 * typography/zoom overrides, and privacy/DoH preferences.
 *
 * Pure-Java static helpers and SharedPreferences constructor allow full host JVM testing.
 */
public final class StartPageConfig {
    public static final String PREFS_NAME = "start_page_config";

    public static final int HOST_POLICY_DEFAULT = 0;
    public static final int HOST_POLICY_ALLOW = 1;
    public static final int HOST_POLICY_BLOCK = -1;

    public static final String DOH_SYSTEM = "system";
    public static final String DOH_CLOUDFLARE = "cloudflare";
    public static final String DOH_GOOGLE = "google";
    public static final String DOH_ALIDNS = "alidns";
    public static final String DOH_DNSPOD = "dnspod";
    public static final String DOH_CUSTOM = "custom";

    public enum PageMode { NATIVE, CUSTOM_URL }
    public enum BarStyle { STANDARD, CAPSULE }
    public enum AppJumpPolicy { ASK, BLOCK_ALL, ALLOW_ALL }

    public static final class Preset {
        public final String name;
        public final String desc;
        public final String url;

        public Preset(String name, String desc, String url) {
            this.name = name;
            this.desc = desc;
            this.url = url;
        }
    }

    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("Aiko 原生极速页", "内置原生极速主页与快捷拨号，零延迟启动", ""),
            new Preset("iTab 新标签页", "高颜值卡片式云端主页（go.itab.link）", "https://go.itab.link/"),
            new Preset("WeTab 新标签页", "多组件效率云端新标签页（wetab.link）", "https://wetab.link/"),
            new Preset("MTab 轻导航", "极简轻量云端图标导航（mtab.cc）", "https://mtab.cc/"),
            new Preset("空白极简页", "纯空白画布（about:blank），零干扰瞬开", "about:blank")
    ));

    private static final Set<String> INTERNAL_OR_STANDARD_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "about", "file", "content", "data", "blob",
            "javascript", "view-source", "mailto", "tel", "sms", "geo", "ftp"
    ));

    private static final String[] KNOWN_APP_SCHEME_PREFIXES = {
            "intent", "android-app", "market",
            "zhihu", "bilibili", "tbopen", "taobao", "tmall",
            "snssdk", "douyin", "aweme", "kwai", "kuaishou",
            "weixin", "wechat", "alipays", "alipay",
            "xhsdiscover", "xhslink", "sinaweibo", "weibo",
            "baiduboxapp", "baiduboxlite", "bdapp", "tieba",
            "openapp.jdmobile", "jdmobile", "pinduoduo", "pddopen",
            "mqq", "mqqwpa", "mqqapi", "tim", "qqmusic", "orpheus",
            "meituan", "imeituan", "dianping", "ctrip", "amapuri", "iosamap",
            "dingtalk", "feishu", "lark", "ucbrowser", "quark",
            "twitter", "tg", "discord", "reddit", "youtube", "vnd.youtube", "spotify"
    };

    private static final String KEY_HOME_MODE = "home_mode";
    private static final String KEY_SEARCH_ONLY = "native_search_only";
    private static final String KEY_HOME_URL = "home_url";
    private static final String KEY_NEWTAB_FOLLOWS_HOME = "newtab_follows_home";
    private static final String KEY_NEWTAB_MODE = "newtab_mode";
    private static final String KEY_NEWTAB_URL = "newtab_url";
    private static final String KEY_PRELOAD_NEWTAB = "preload_newtab";

    private static final String KEY_BAR_STYLE = "bar_style";
    private static final String KEY_SHOW_HOME_BTN = "show_home_btn";
    private static final String KEY_HOME_BTN_EXPLICIT = "show_home_btn_explicit";
    private static final String KEY_SHOW_BABEL_BTN = "show_babel_btn";
    private static final String KEY_SWIPE_BAR_TABS = "swipe_bar_switch_tabs";
    private static final String KEY_SWIPE_UP_TABS = "swipe_up_for_tabs";
    private static final String KEY_TAB_GRID_VIEW = "tab_grid_view";

    private static final String KEY_APP_JUMP_POLICY = "app_jump_policy";
    private static final String KEY_APP_JUMP_HOSTS = "app_jump_hosts_json";

    private static final String KEY_AUTO_THEME_TIME = "auto_theme_by_time";
    private static final String KEY_NIGHT_START_HOUR = "night_start_hour";
    private static final String KEY_NIGHT_END_HOUR = "night_end_hour";
    private static final String KEY_NIGHT_FILTER_ENABLED = "night_filter_enabled";
    private static final String KEY_NIGHT_DIM = "night_dim_percent";
    private static final String KEY_NIGHT_WARMTH = "night_warmth_percent";

    private static final String KEY_CUSTOM_FONT_NAME = "custom_font_name";
    private static final String KEY_CUSTOM_FONT_FILE = "custom_font_file";
    private static final String KEY_MIN_FONT_SIZE = "min_font_size_px";
    private static final String KEY_FORCE_PINCH_ZOOM = "force_pinch_zoom";

    private static final String KEY_CLIPBOARD_ASSIST = "clipboard_assist";
    private static final String KEY_FORM_DRAFT = "form_draft_protection";
    private static final String KEY_ANTI_ADBLOCK = "anti_adblock_scriptlets";
    private static final String KEY_BLOCK_WEBRTC = "block_webrtc_leak";
    private static final String KEY_DOH_PRESET = "doh_preset";
    private static final String KEY_CUSTOM_DOH_URL = "custom_doh_url";

    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final Map<String, Integer> hostAppJumpMap = new HashMap<>();

    private PageMode homeMode = PageMode.NATIVE;
    private boolean searchOnly = true;
    private String homeUrl = "";
    private boolean newTabFollowsHome = true;
    private PageMode newTabMode = PageMode.NATIVE;
    private String newTabUrl = "";
    private boolean preloadNewTab = true;

    private BarStyle barStyle = BarStyle.STANDARD;
    private boolean showHomeButton = false;
    private boolean homeButtonExplicit = false;
    private boolean showBabelButton = false;
    private boolean swipeBarSwitchTabs = true;
    private boolean swipeUpForTabs = true;
    private boolean tabGridView = false;

    private AppJumpPolicy appJumpPolicy = AppJumpPolicy.ASK;

    private boolean autoThemeByTime = false;
    private int nightStartHour = 22;
    private int nightEndHour = 7;
    private boolean nightFilterEnabled = false;
    private int nightDimPercent = 25;
    private int nightWarmthPercent = 20;

    private String customFontName = "";
    private String customFontFile = "";
    private int minFontSizePx = 0;
    private boolean forcePinchZoom = false;

    private boolean clipboardAssist = true;
    private boolean formDraftProtection = true;
    private boolean antiAdblockScriptlets = true;
    private boolean blockWebRtcLeak = false;
    private String dohPreset = DOH_SYSTEM;
    private String customDohUrl = "";

    public StartPageConfig(Context ctx) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public StartPageConfig(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    public static List<Preset> presets() {
        return PRESETS;
    }

    public void reload() {
        load();
    }

    private void load() {
        if (prefs == null) return;
        synchronized (lock) {
            homeMode = parsePageMode(prefs.getString(KEY_HOME_MODE, PageMode.NATIVE.name()));
            searchOnly = prefs.getBoolean(KEY_SEARCH_ONLY, true);
            String hUrl = normalizeStartUrl(prefs.getString(KEY_HOME_URL, ""));
            homeUrl = hUrl == null ? "" : hUrl;
            newTabFollowsHome = prefs.getBoolean(KEY_NEWTAB_FOLLOWS_HOME, true);
            newTabMode = parsePageMode(prefs.getString(KEY_NEWTAB_MODE, PageMode.NATIVE.name()));
            String nUrl = normalizeStartUrl(prefs.getString(KEY_NEWTAB_URL, ""));
            newTabUrl = nUrl == null ? "" : nUrl;
            preloadNewTab = prefs.getBoolean(KEY_PRELOAD_NEWTAB, true);

            barStyle = parseBarStyle(prefs.getString(KEY_BAR_STYLE, BarStyle.STANDARD.name()));
            homeButtonExplicit = prefs.getBoolean(KEY_HOME_BTN_EXPLICIT, false);
            showHomeButton = prefs.getBoolean(KEY_SHOW_HOME_BTN, false);
            showBabelButton = prefs.getBoolean(KEY_SHOW_BABEL_BTN, false);
            swipeBarSwitchTabs = prefs.getBoolean(KEY_SWIPE_BAR_TABS, true);
            swipeUpForTabs = prefs.getBoolean(KEY_SWIPE_UP_TABS, true);
            tabGridView = prefs.getBoolean(KEY_TAB_GRID_VIEW, false);

            appJumpPolicy = parseAppJumpPolicy(prefs.getString(KEY_APP_JUMP_POLICY, AppJumpPolicy.ASK.name()));
            hostAppJumpMap.clear();
            parseHostPoliciesLocked(prefs.getString(KEY_APP_JUMP_HOSTS, null));

            autoThemeByTime = prefs.getBoolean(KEY_AUTO_THEME_TIME, false);
            nightStartHour = clampHour(prefs.getInt(KEY_NIGHT_START_HOUR, 22));
            nightEndHour = clampHour(prefs.getInt(KEY_NIGHT_END_HOUR, 7));
            nightFilterEnabled = prefs.getBoolean(KEY_NIGHT_FILTER_ENABLED, false);
            nightDimPercent = clampInt(prefs.getInt(KEY_NIGHT_DIM, 25), 0, 70);
            nightWarmthPercent = clampInt(prefs.getInt(KEY_NIGHT_WARMTH, 20), 0, 60);

            customFontName = safeTrim(prefs.getString(KEY_CUSTOM_FONT_NAME, ""), 48);
            customFontFile = sanitizeFontFilename(prefs.getString(KEY_CUSTOM_FONT_FILE, ""));
            minFontSizePx = clampMinFontSize(prefs.getInt(KEY_MIN_FONT_SIZE, 0));
            forcePinchZoom = prefs.getBoolean(KEY_FORCE_PINCH_ZOOM, false);

            clipboardAssist = prefs.getBoolean(KEY_CLIPBOARD_ASSIST, true);
            formDraftProtection = prefs.getBoolean(KEY_FORM_DRAFT, true);
            antiAdblockScriptlets = prefs.getBoolean(KEY_ANTI_ADBLOCK, true);
            blockWebRtcLeak = prefs.getBoolean(KEY_BLOCK_WEBRTC, false);
            dohPreset = sanitizeDohPreset(prefs.getString(KEY_DOH_PRESET, DOH_SYSTEM));
            String dUrl = prefs.getString(KEY_CUSTOM_DOH_URL, "");
            customDohUrl = HttpUrlUtil.isHttp(dUrl) && dUrl.toLowerCase(Locale.ROOT).startsWith("https://")
                    ? dUrl.trim() : "";
        }
    }

    // --- Homepage & New Tab ---

    public PageMode getHomeMode() { synchronized (lock) { return homeMode; } }
    public String getHomeUrl() { synchronized (lock) { return homeUrl; } }
    public boolean isNewTabFollowsHome() { synchronized (lock) { return newTabFollowsHome; } }

    public boolean isSearchOnly() { synchronized (lock) { return searchOnly; } }

    public void setSearchOnly(boolean enabled) {
        synchronized (lock) { searchOnly = enabled; }
        putBoolean(KEY_SEARCH_ONLY, enabled);
    }
    public PageMode getNewTabMode() { synchronized (lock) { return newTabMode; } }
    public String getNewTabUrl() { synchronized (lock) { return newTabUrl; } }
    public boolean isPreloadNewTab() { synchronized (lock) { return preloadNewTab; } }

    public boolean shouldPreloadNewTab() {
        synchronized (lock) {
            if (!preloadNewTab) return false;
            String url = resolveNewTabUrlLocked();
            return url != null && !"about:blank".equals(url);
        }
    }

    /** Returns null for Aiko native start page, or normalized custom URL ("https://...", "http://...", "file://...", "about:blank"). */
    public String resolveHomeUrl() {
        synchronized (lock) {
            return resolveStartUrl(homeMode, homeUrl);
        }
    }

    /** Returns null for Aiko native start page, or normalized custom URL for new tabs. */
    public String resolveNewTabUrl() {
        synchronized (lock) {
            return resolveNewTabUrlLocked();
        }
    }

    private String resolveNewTabUrlLocked() {
        if (newTabFollowsHome) return resolveStartUrl(homeMode, homeUrl);
        return resolveStartUrl(newTabMode, newTabUrl);
    }

    public static String resolveStartUrl(PageMode mode, String rawUrl) {
        if (mode != PageMode.CUSTOM_URL) return null;
        String norm = normalizeStartUrl(rawUrl);
        if (norm == null || norm.isEmpty()) return null;
        return norm;
    }

    public boolean setHome(PageMode mode, String rawUrl) {
        PageMode nextMode = mode == null ? PageMode.NATIVE : mode;
        String norm = normalizeStartUrl(rawUrl);
        if (nextMode == PageMode.CUSTOM_URL && (norm == null || norm.isEmpty())) {
            return false;
        }
        String storedUrl = norm == null ? "" : norm;
        boolean autoEnableHomeBtn = false;
        synchronized (lock) {
            homeMode = nextMode;
            homeUrl = storedUrl;
            if (nextMode == PageMode.CUSTOM_URL && !homeButtonExplicit) {
                showHomeButton = true;
                autoEnableHomeBtn = true;
            }
        }
        if (prefs != null) {
            SharedPreferences.Editor ed = prefs.edit()
                    .putString(KEY_HOME_MODE, nextMode.name())
                    .putString(KEY_HOME_URL, storedUrl);
            if (autoEnableHomeBtn) ed.putBoolean(KEY_SHOW_HOME_BTN, true);
            ed.apply();
        }
        return true;
    }

    /** Applies a built-in preset URL ("" resets to native Aiko start page) or arbitrary valid custom URL. */
    public boolean applyHomePresetOrUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            return setHome(PageMode.NATIVE, "");
        }
        return setHome(PageMode.CUSTOM_URL, trimmed);
    }

    public void setNewTabFollowsHome(boolean follows) {
        synchronized (lock) { newTabFollowsHome = follows; }
        putBoolean(KEY_NEWTAB_FOLLOWS_HOME, follows);
    }

    public boolean setNewTab(PageMode mode, String rawUrl) {
        PageMode nextMode = mode == null ? PageMode.NATIVE : mode;
        String norm = normalizeStartUrl(rawUrl);
        if (nextMode == PageMode.CUSTOM_URL && (norm == null || norm.isEmpty())) {
            return false;
        }
        String storedUrl = norm == null ? "" : norm;
        synchronized (lock) {
            newTabMode = nextMode;
            newTabUrl = storedUrl;
        }
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_NEWTAB_MODE, nextMode.name())
                    .putString(KEY_NEWTAB_URL, storedUrl)
                    .apply();
        }
        return true;
    }

    public void setPreloadNewTab(boolean enabled) {
        synchronized (lock) { preloadNewTab = enabled; }
        putBoolean(KEY_PRELOAD_NEWTAB, enabled);
    }

    /**
     * Validates and normalizes a start/new-tab URL.
     * Accepts "", "about:blank", valid http://, https://, file:// URLs, or bare domains (promoted to https://).
     * Returns null if invalid or unsafe (e.g. javascript:, data:, intent:).
     */
    public static String normalizeStartUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F) return null;
        }
        if ("about:blank".equalsIgnoreCase(s)) return "about:blank";
        if (HttpUrlUtil.isHttp(s)) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file://")) {
            return s.length() > 7 ? s : null;
        }
        // Reject any other explicit scheme (javascript:, data:, intent:, etc.)
        if (s.indexOf(':') >= 0 && !looksLikeHostPort(s)) {
            return null;
        }
        if (s.indexOf(' ') >= 0) return null;
        if (s.indexOf('.') > 0 || lower.startsWith("localhost")) {
            String promoted = "https://" + s;
            if (HttpUrlUtil.isHttp(promoted)) return promoted;
        }
        return null;
    }

    public static boolean isValidStartUrl(String raw) {
        return normalizeStartUrl(raw) != null;
    }

    private static boolean looksLikeHostPort(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        int slash = s.indexOf('/');
        if (slash >= 0 && colon > slash) return false;
        int end = slash >= 0 ? slash : s.length();
        if (colon + 1 >= end) return false;
        for (int i = colon + 1; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // --- Bottom Bar & Navigation ---

    public BarStyle getBarStyle() { synchronized (lock) { return barStyle; } }
    public boolean isShowHomeButton() { synchronized (lock) { return showHomeButton; } }
    public boolean isShowBabelButton() { synchronized (lock) { return showBabelButton; } }
    public boolean isSwipeBarSwitchTabs() { synchronized (lock) { return swipeBarSwitchTabs; } }
    public boolean isSwipeUpForTabs() { synchronized (lock) { return swipeUpForTabs; } }
    public boolean isTabGridView() { synchronized (lock) { return tabGridView; } }

    public void setBarStyle(BarStyle style) {
        BarStyle safe = style == null ? BarStyle.STANDARD : style;
        synchronized (lock) { barStyle = safe; }
        putString(KEY_BAR_STYLE, safe.name());
    }

    public void setShowHomeButton(boolean show) {
        synchronized (lock) {
            showHomeButton = show;
            homeButtonExplicit = true;
        }
        if (prefs != null) {
            prefs.edit()
                    .putBoolean(KEY_SHOW_HOME_BTN, show)
                    .putBoolean(KEY_HOME_BTN_EXPLICIT, true)
                    .apply();
        }
    }

    public void setShowBabelButton(boolean show) {
        synchronized (lock) { showBabelButton = show; }
        putBoolean(KEY_SHOW_BABEL_BTN, show);
    }

    public void setSwipeBarSwitchTabs(boolean enabled) {
        synchronized (lock) { swipeBarSwitchTabs = enabled; }
        putBoolean(KEY_SWIPE_BAR_TABS, enabled);
    }

    public void setSwipeUpForTabs(boolean enabled) {
        synchronized (lock) { swipeUpForTabs = enabled; }
        putBoolean(KEY_SWIPE_UP_TABS, enabled);
    }

    public void setTabGridView(boolean grid) {
        synchronized (lock) { tabGridView = grid; }
        putBoolean(KEY_TAB_GRID_VIEW, grid);
    }

    // --- App-Jump Deep-Link Guard ---

    public AppJumpPolicy getAppJumpPolicy() { synchronized (lock) { return appJumpPolicy; } }

    public void setAppJumpPolicy(AppJumpPolicy policy) {
        AppJumpPolicy safe = policy == null ? AppJumpPolicy.ASK : policy;
        synchronized (lock) { appJumpPolicy = safe; }
        putString(KEY_APP_JUMP_POLICY, safe.name());
    }

    /** Returns per-host remembered policy: 0=default, 1=allow, -1=block. */
    public int hostAppJumpPolicy(String host) {
        String key = normalizeHost(host);
        if (key.isEmpty()) return HOST_POLICY_DEFAULT;
        synchronized (lock) {
            Integer v = hostAppJumpMap.get(key);
            return v == null ? HOST_POLICY_DEFAULT : v;
        }
    }

    public void setHostAppJumpPolicy(String host, int policy) {
        String key = normalizeHost(host);
        if (key.isEmpty()) return;
        int norm = policy > 0 ? HOST_POLICY_ALLOW : (policy < 0 ? HOST_POLICY_BLOCK : HOST_POLICY_DEFAULT);
        String json;
        synchronized (lock) {
            if (norm == HOST_POLICY_DEFAULT) hostAppJumpMap.remove(key);
            else hostAppJumpMap.put(key, norm);
            json = encodeHostPoliciesLocked();
        }
        putString(KEY_APP_JUMP_HOSTS, json);
    }

    public Map<String, Integer> allHostAppJumpPolicies() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new HashMap<>(hostAppJumpMap));
        }
    }

    public void clearHostAppJumpPolicies() {
        synchronized (lock) { hostAppJumpMap.clear(); }
        if (prefs != null) prefs.edit().remove(KEY_APP_JUMP_HOSTS).apply();
    }

    /** Evaluates effective jump decision for host: 1=allow, -1=block, 0=ask user. */
    public int effectiveAppJumpDecision(String host) {
        int hostOv = hostAppJumpPolicy(host);
        if (hostOv != HOST_POLICY_DEFAULT) return hostOv;
        AppJumpPolicy p = getAppJumpPolicy();
        if (p == AppJumpPolicy.ALLOW_ALL) return HOST_POLICY_ALLOW;
        if (p == AppJumpPolicy.BLOCK_ALL) return HOST_POLICY_BLOCK;
        return HOST_POLICY_DEFAULT;
    }

    /**
     * Identifies deep-link and external App-jump URI schemes such as intent:, zhihu:, bilibili:,
     * tbopen:, snssdk*, weixin:, alipays:, xhsdiscover:, sinaweibo:, baiduboxapp:, etc.
     */
    public static boolean isExternalAppScheme(String url) {
        String scheme = extractScheme(url);
        if (scheme == null) return false;
        if (INTERNAL_OR_STANDARD_SCHEMES.contains(scheme)) return false;
        for (int i = 0; i < KNOWN_APP_SCHEME_PREFIXES.length; i++) {
            String prefix = KNOWN_APP_SCHEME_PREFIXES[i];
            if (scheme.equals(prefix) || (prefix.equals("snssdk") && scheme.startsWith("snssdk"))) {
                return true;
            }
        }
        // Any other non-standard custom app scheme (e.g., third-party native deep links)
        return true;
    }

    public static String extractScheme(String url) {
        if (url == null) return null;
        String s = url.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        int slash = s.indexOf('/');
        int q = s.indexOf('?');
        int hash = s.indexOf('#');
        if ((slash >= 0 && slash < colon) || (q >= 0 && q < colon) || (hash >= 0 && hash < colon)) {
            return null;
        }
        char first = s.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) return null;
        for (int i = 1; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!ok) return null;
        }
        return s.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    public static String friendlyAppName(String url) {
        String scheme = extractScheme(url);
        if (scheme == null) return "外部应用";
        if (scheme.equals("zhihu")) return "知乎";
        if (scheme.equals("bilibili")) return "哔哩哔哩";
        if (scheme.equals("tbopen") || scheme.equals("taobao")) return "淘宝";
        if (scheme.equals("tmall")) return "天猫";
        if (scheme.startsWith("snssdk") || scheme.equals("douyin") || scheme.equals("aweme")) return "抖音/头条系应用";
        if (scheme.equals("weixin") || scheme.equals("wechat")) return "微信";
        if (scheme.equals("alipays") || scheme.equals("alipay")) return "支付宝";
        if (scheme.equals("xhsdiscover") || scheme.equals("xhslink")) return "小红书";
        if (scheme.equals("sinaweibo") || scheme.equals("weibo")) return "微博";
        if (scheme.equals("baiduboxapp") || scheme.equals("baiduboxlite")) return "百度 App";
        if (scheme.equals("openapp.jdmobile") || scheme.equals("jdmobile")) return "京东";
        if (scheme.equals("pinduoduo") || scheme.equals("pddopen")) return "拼多多";
        if (scheme.equals("mqq") || scheme.equals("mqqwpa") || scheme.equals("mqqapi")) return "QQ";
        if (scheme.equals("market")) return "应用商店";
        if (scheme.equals("intent")) return "外部应用 (Intent)";
        return scheme + " 应用";
    }

    // --- AMOLED Night Comfort & Auto Day/Night ---

    public boolean isAutoThemeByTime() { synchronized (lock) { return autoThemeByTime; } }
    public int getNightStartHour() { synchronized (lock) { return nightStartHour; } }
    public int getNightEndHour() { synchronized (lock) { return nightEndHour; } }
    public boolean isNightFilterEnabled() { synchronized (lock) { return nightFilterEnabled; } }
    public int getNightDimPercent() { synchronized (lock) { return nightDimPercent; } }
    public int getNightWarmthPercent() { synchronized (lock) { return nightWarmthPercent; } }

    public void setAutoThemeByTime(boolean enabled) {
        synchronized (lock) { autoThemeByTime = enabled; }
        putBoolean(KEY_AUTO_THEME_TIME, enabled);
    }

    public void setNightSchedule(int startHour, int endHour) {
        int s = clampHour(startHour);
        int e = clampHour(endHour);
        synchronized (lock) {
            nightStartHour = s;
            nightEndHour = e;
        }
        if (prefs != null) {
            prefs.edit()
                    .putInt(KEY_NIGHT_START_HOUR, s)
                    .putInt(KEY_NIGHT_END_HOUR, e)
                    .apply();
        }
    }

    public boolean isInNightWindow(int hourOfDay) {
        synchronized (lock) {
            return isInNightWindow(hourOfDay, nightStartHour, nightEndHour);
        }
    }

    public static boolean isInNightWindow(int hourOfDay, int startHour, int endHour) {
        int h = clampHour(hourOfDay);
        int s = clampHour(startHour);
        int e = clampHour(endHour);
        if (s == e) return false;
        if (s < e) return h >= s && h < e;
        return h >= s || h < e;
    }

    public void setNightFilterEnabled(boolean enabled) {
        synchronized (lock) { nightFilterEnabled = enabled; }
        putBoolean(KEY_NIGHT_FILTER_ENABLED, enabled);
    }

    public void setNightDimPercent(int percent) {
        int clamped = clampInt(percent, 0, 70);
        synchronized (lock) { nightDimPercent = clamped; }
        putInt(KEY_NIGHT_DIM, clamped);
    }

    public void setNightWarmthPercent(int percent) {
        int clamped = clampInt(percent, 0, 60);
        synchronized (lock) { nightWarmthPercent = clamped; }
        putInt(KEY_NIGHT_WARMTH, clamped);
    }

    /** Returns ARGB tint color for a non-touch-intercepting AMOLED night comfort overlay View (0 when disabled). */
    public int computeOverlayColor() {
        synchronized (lock) {
            return computeOverlayColor(nightFilterEnabled, nightDimPercent, nightWarmthPercent);
        }
    }

    /** Pure-Java ARGB overlay tint computation combining AMOLED dim (0..70%) and warm amber filter (0..60%). */
    public static int computeOverlayColor(boolean enabled, int dimPercent, int warmthPercent) {
        if (!enabled) return 0;
        int dim = clampInt(dimPercent, 0, 70);
        int warm = clampInt(warmthPercent, 0, 60);
        if (dim == 0 && warm == 0) return 0;

        double ad = dim / 100.0;
        double aw = (warm / 100.0) * 0.55; // Warm amber tint scaled so 60% remains comfortably legible
        double aOut = 1.0 - (1.0 - ad) * (1.0 - aw);
        if (aOut <= 0.0001) return 0;

        // Composite warm amber (255, 146, 36) over pure black dim layer (0, 0, 0)
        int a = clampInt((int) Math.round(aOut * 255.0), 0, 220);
        int r = clampInt((int) Math.round((255.0 * aw) / aOut), 0, 255);
        int g = clampInt((int) Math.round((146.0 * aw) / aOut), 0, 255);
        int b = clampInt((int) Math.round((36.0 * aw) / aOut), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // --- Typography & Zoom ---

    public String getCustomFontName() { synchronized (lock) { return customFontName; } }
    public String getCustomFontFile() { synchronized (lock) { return customFontFile; } }
    public int getMinFontSizePx() { synchronized (lock) { return minFontSizePx; } }
    public boolean isForcePinchZoom() { synchronized (lock) { return forcePinchZoom; } }

    public File resolveCustomFontFile(Context ctx) {
        if (ctx == null) return null;
        String file = getCustomFontFile();
        if (file.isEmpty()) return null;
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "fonts");
        File target = new File(dir, file);
        return target.isFile() ? target : null;
    }

    public void setCustomFont(String name, String filename) {
        String safeName = safeTrim(name, 48);
        String safeFile = sanitizeFontFilename(filename);
        if (safeFile.isEmpty()) safeName = "";
        synchronized (lock) {
            customFontName = safeName;
            customFontFile = safeFile;
        }
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_CUSTOM_FONT_NAME, safeName)
                    .putString(KEY_CUSTOM_FONT_FILE, safeFile)
                    .apply();
        }
    }

    public void setMinFontSizePx(int px) {
        int safe = clampMinFontSize(px);
        synchronized (lock) { minFontSizePx = safe; }
        putInt(KEY_MIN_FONT_SIZE, safe);
    }

    public void setForcePinchZoom(boolean force) {
        synchronized (lock) { forcePinchZoom = force; }
        putBoolean(KEY_FORCE_PINCH_ZOOM, force);
    }

    // --- Privacy & Productivity ---

    public boolean isClipboardAssist() { synchronized (lock) { return clipboardAssist; } }
    public boolean isFormDraftProtection() { synchronized (lock) { return formDraftProtection; } }
    public boolean isAntiAdblockScriptlets() { synchronized (lock) { return antiAdblockScriptlets; } }
    public boolean isBlockWebRtcLeak() { synchronized (lock) { return blockWebRtcLeak; } }
    public String getDohPreset() { synchronized (lock) { return dohPreset; } }
    public String getCustomDohUrl() { synchronized (lock) { return customDohUrl; } }

    public void setClipboardAssist(boolean enabled) {
        synchronized (lock) { clipboardAssist = enabled; }
        putBoolean(KEY_CLIPBOARD_ASSIST, enabled);
    }

    public void setFormDraftProtection(boolean enabled) {
        synchronized (lock) { formDraftProtection = enabled; }
        putBoolean(KEY_FORM_DRAFT, enabled);
    }

    public void setAntiAdblockScriptlets(boolean enabled) {
        synchronized (lock) { antiAdblockScriptlets = enabled; }
        putBoolean(KEY_ANTI_ADBLOCK, enabled);
    }

    public void setBlockWebRtcLeak(boolean enabled) {
        synchronized (lock) { blockWebRtcLeak = enabled; }
        putBoolean(KEY_BLOCK_WEBRTC, enabled);
    }

    public void setDoh(String preset, String customUrl) {
        String safePreset = sanitizeDohPreset(preset);
        String safeUrl = "";
        if (customUrl != null) {
            String t = customUrl.trim();
            if (HttpUrlUtil.isHttp(t) && t.toLowerCase(Locale.ROOT).startsWith("https://")) {
                safeUrl = t;
            }
        }
        synchronized (lock) {
            dohPreset = safePreset;
            customDohUrl = safeUrl;
        }
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_DOH_PRESET, safePreset)
                    .putString(KEY_CUSTOM_DOH_URL, safeUrl)
                    .apply();
        }
    }

    public String resolveDohUrl() {
        synchronized (lock) {
            return resolveDohUrl(dohPreset, customDohUrl);
        }
    }

    public static String resolveDohUrl(String preset, String customUrl) {
        String p = sanitizeDohPreset(preset);
        switch (p) {
            case DOH_CLOUDFLARE: return "https://cloudflare-dns.com/dns-query";
            case DOH_GOOGLE: return "https://dns.google/dns-query";
            case DOH_ALIDNS: return "https://dns.alidns.com/dns-query";
            case DOH_DNSPOD: return "https://doh.pub/dns-query";
            case DOH_CUSTOM:
                if (customUrl != null && HttpUrlUtil.isHttp(customUrl)
                        && customUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    return customUrl.trim();
                }
                return null;
            default:
                return null;
        }
    }

    // --- Helpers ---

    private void putBoolean(String key, boolean value) {
        if (prefs != null) prefs.edit().putBoolean(key, value).apply();
    }

    private void putInt(String key, int value) {
        if (prefs != null) prefs.edit().putInt(key, value).apply();
    }

    private void putString(String key, String value) {
        if (prefs != null) prefs.edit().putString(key, value).apply();
    }

    private void parseHostPoliciesLocked(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(rawJson);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = normalizeHost(keys.next());
                if (k.isEmpty()) continue;
                int v = obj.optInt(k, HOST_POLICY_DEFAULT);
                if (v == HOST_POLICY_ALLOW || v == HOST_POLICY_BLOCK) {
                    hostAppJumpMap.put(k, v);
                }
            }
        } catch (JSONException ignored) { }
    }

    private String encodeHostPoliciesLocked() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Integer> e : hostAppJumpMap.entrySet()) {
            try { obj.put(e.getKey(), e.getValue().intValue()); } catch (JSONException ignored) { }
        }
        return obj.toString();
    }

    private static PageMode parsePageMode(String raw) {
        if (PageMode.CUSTOM_URL.name().equals(raw)) return PageMode.CUSTOM_URL;
        return PageMode.NATIVE;
    }

    private static BarStyle parseBarStyle(String raw) {
        if (BarStyle.CAPSULE.name().equals(raw)) return BarStyle.CAPSULE;
        return BarStyle.STANDARD;
    }

    private static AppJumpPolicy parseAppJumpPolicy(String raw) {
        if (AppJumpPolicy.BLOCK_ALL.name().equals(raw)) return AppJumpPolicy.BLOCK_ALL;
        if (AppJumpPolicy.ALLOW_ALL.name().equals(raw)) return AppJumpPolicy.ALLOW_ALL;
        return AppJumpPolicy.ASK;
    }

    private static String sanitizeDohPreset(String raw) {
        if (raw == null) return DOH_SYSTEM;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (DOH_CLOUDFLARE.equals(s) || DOH_GOOGLE.equals(s) || DOH_ALIDNS.equals(s)
                || DOH_DNSPOD.equals(s) || DOH_CUSTOM.equals(s)) {
            return s;
        }
        return DOH_SYSTEM;
    }

    public static int clampMinFontSize(int px) {
        if (px <= 0) return 0;
        return clampInt(px, 12, 20);
    }

    private static int clampHour(int hour) {
        if (hour < 0) return 0;
        return Math.min(23, hour);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : Math.min(max, v);
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String s = host.trim();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') end--;
        return end <= 0 ? "" : s.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static String safeTrim(String s, int maxLen) {
        if (s == null) return "";
        String trimmed = s.replace('\r', ' ').replace('\n', ' ').trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen).trim() : trimmed;
    }

    public static String sanitizeFontFilename(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 96) return "";
        if (s.contains("..") || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf('\0') >= 0) {
            return "";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F) return "";
        }
        return s;
    }
}
