package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Omnibox `!bang` quick search commands (built-in 20 + user custom bangs in SharedPreferences "aiko_bangs")
 * and `/command` instant browser action shortcuts.
 */
public final class BangCatalog {
    public static final String PREFS_NAME = "aiko_bangs";
    private static final String KEY_CUSTOM_BANGS = "custom_bangs";
    private static final int MAX_CUSTOM_BANGS = 64;

    public static final class Entry {
        public final String bang;
        public final String label;
        public final String template;
        public final String homeUrl;
        public final boolean builtin;

        public Entry(String bang, String label, String template, String homeUrl, boolean builtin) {
            this.bang = bang;
            this.label = label;
            this.template = template;
            this.homeUrl = homeUrl;
            this.builtin = builtin;
        }
    }

    public static final class Match {
        public final String bang;
        public final String label;
        public final String url;

        public Match(String bang, String label, String url) {
            this.bang = bang;
            this.label = label;
            this.url = url;
        }
    }

    public enum SlashCmd {
        BABEL, DEVTOOLS, GALLERY, LONGSHOT, QR, BACKUP, UNLOCK, DARK, CLEAN, SETTINGS
    }

    private static final List<Entry> BUILTINS = Collections.unmodifiableList(Arrays.asList(
            new Entry("gh", "GitHub", "https://github.com/search?q=%s", "https://github.com/", true),
            new Entry("gf", "Greasy Fork", "https://greasyfork.org/zh-CN/scripts?q=%s", "https://greasyfork.org/zh-CN", true),
            new Entry("b", "Bilibili", "https://search.bilibili.com/all?keyword=%s", "https://www.bilibili.com/", true),
            new Entry("yt", "YouTube", "https://www.youtube.com/results?search_query=%s", "https://www.youtube.com/", true),
            new Entry("zh", "知乎", "https://www.zhihu.com/search?type=content&q=%s", "https://www.zhihu.com/", true),
            new Entry("w", "Wikipedia ZH", "https://zh.wikipedia.org/w/index.php?search=%s", "https://zh.wikipedia.org/", true),
            new Entry("we", "Wikipedia EN", "https://en.wikipedia.org/w/index.php?search=%s", "https://en.wikipedia.org/", true),
            new Entry("r", "Reddit", "https://www.reddit.com/search/?q=%s", "https://www.reddit.com/", true),
            new Entry("so", "StackOverflow", "https://stackoverflow.com/search?q=%s", "https://stackoverflow.com/", true),
            new Entry("npm", "npm", "https://www.npmjs.com/search?q=%s", "https://www.npmjs.com/", true),
            new Entry("mdn", "MDN", "https://developer.mozilla.org/zh-CN/search?q=%s", "https://developer.mozilla.org/", true),
            new Entry("x", "X / Twitter", "https://x.com/search?q=%s", "https://x.com/", true),
            new Entry("db", "豆瓣", "https://www.douban.com/search?q=%s", "https://www.douban.com/", true),
            new Entry("tb", "淘宝", "https://s.taobao.com/search?q=%s", "https://www.taobao.com/", true),
            new Entry("jd", "京东", "https://search.jd.com/Search?keyword=%s", "https://www.jd.com/", true),
            new Entry("ld", "Linux.do", "https://linux.do/search?q=%s", "https://linux.do/", true),
            new Entry("g", "Google", "https://www.google.com/search?q=%s", "https://www.google.com/", true),
            new Entry("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com/", true),
            new Entry("bd", "百度", "https://www.baidu.com/s?wd=%s", "https://www.baidu.com/", true),
            new Entry("xhs", "小红书", "https://www.xiaohongshu.com/search_result?keyword=%s", "https://www.xiaohongshu.com/", true)
    ));

    private static final Map<String, Entry> BUILTIN_MAP = buildBuiltinMap();

    private static Map<String, Entry> buildBuiltinMap() {
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>(BUILTINS.size() * 2);
        for (Entry e : BUILTINS) map.put(e.bang, e);
        return Collections.unmodifiableMap(map);
    }

    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry> customMap = new LinkedHashMap<>();

    public BangCatalog(Context ctx) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public BangCatalog(SharedPreferences prefs) {
        this.prefs = prefs;
        loadCustom();
    }

    public static List<Entry> builtinBangs() {
        return BUILTINS;
    }

    public void reload() {
        loadCustom();
    }

    private void loadCustom() {
        if (prefs == null) return;
        String raw = prefs.getString(KEY_CUSTOM_BANGS, null);
        synchronized (lock) {
            customMap.clear();
            if (raw == null || raw.isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length() && customMap.size() < MAX_CUSTOM_BANGS; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String key = normalizeBangKey(o.optString("bang", ""));
                    String tpl = sanitizeTemplate(o.optString("template", ""));
                    if (key == null || tpl == null) continue;
                    String label = sanitizeLabel(o.optString("label", ""), key);
                    String home = deriveHomeUrl(tpl);
                    customMap.put(key, new Entry(key, label, tpl, home, false));
                }
            } catch (JSONException ignored) { }
        }
    }

    public boolean addCustomBang(String rawBang, String label, String template) {
        String key = normalizeBangKey(rawBang);
        String tpl = sanitizeTemplate(template);
        if (key == null || tpl == null) return false;
        String cleanLabel = sanitizeLabel(label, key);
        String home = deriveHomeUrl(tpl);
        String json;
        synchronized (lock) {
            if (!customMap.containsKey(key) && customMap.size() >= MAX_CUSTOM_BANGS) {
                return false;
            }
            customMap.put(key, new Entry(key, cleanLabel, tpl, home, false));
            json = encodeCustomLocked();
        }
        if (prefs != null) prefs.edit().putString(KEY_CUSTOM_BANGS, json).apply();
        return true;
    }

    public boolean removeCustomBang(String rawBang) {
        String key = normalizeBangKey(rawBang);
        if (key == null) return false;
        String json;
        synchronized (lock) {
            if (customMap.remove(key) == null) return false;
            json = encodeCustomLocked();
        }
        if (prefs != null) prefs.edit().putString(KEY_CUSTOM_BANGS, json).apply();
        return true;
    }

    public List<Entry> listCustomBangs() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(customMap.values()));
        }
    }

    public List<Entry> listAllBangs() {
        synchronized (lock) {
            LinkedHashMap<String, Entry> merged = new LinkedHashMap<>(BUILTINS.size() + customMap.size());
            for (Entry e : BUILTINS) merged.put(e.bang, e);
            for (Entry e : customMap.values()) merged.put(e.bang, e);
            return Collections.unmodifiableList(new ArrayList<>(merged.values()));
        }
    }

    /** Resolves leading `!bang query`, trailing `query !bang`, or bare `!bang` against custom + built-in bangs. */
    public Match resolveBang(String rawInput) {
        if (rawInput == null) return null;
        String input = rawInput.trim();
        if (input.length() < 2) return null;

        String bangKey = null;
        String query = "";

        if (input.charAt(0) == '!') {
            int sp = findWhitespace(input, 1);
            if (sp < 0) {
                bangKey = normalizeBangKey(input.substring(1));
                query = "";
            } else {
                bangKey = normalizeBangKey(input.substring(1, sp));
                query = input.substring(sp + 1).trim();
            }
        } else {
            // Also support DuckDuckGo-style trailing !bang (e.g. "android webview !so")
            int lastSpace = findLastWhitespace(input);
            if (lastSpace > 0 && lastSpace + 2 <= input.length() && input.charAt(lastSpace + 1) == '!') {
                bangKey = normalizeBangKey(input.substring(lastSpace + 2));
                query = input.substring(0, lastSpace).trim();
            }
        }
        if (bangKey == null) return null;

        Entry entry = lookupEntry(bangKey);
        if (entry == null) return null;
        String url = buildUrl(entry, query);
        if (url == null) return null;
        return new Match(entry.bang, entry.label, url);
    }

    /** Pure static resolution against built-in bangs only (no SharedPreferences required). */
    public static Match resolveBuiltinBang(String rawInput) {
        return new BangCatalog((SharedPreferences) null).resolveBang(rawInput);
    }

    /** Suggests matching bang entries when the user starts typing "!" in the omnibox. */
    public List<Entry> suggestBangs(String rawPrefix, int limit) {
        if (rawPrefix == null) return Collections.emptyList();
        String s = rawPrefix.trim();
        if (!s.startsWith("!")) return Collections.emptyList();
        int sp = findWhitespace(s, 1);
        String prefix = (sp < 0 ? s.substring(1) : s.substring(1, sp)).toLowerCase(Locale.ROOT);
        int cap = Math.max(1, limit);
        ArrayList<Entry> out = new ArrayList<>(Math.min(cap, 12));
        for (Entry e : listAllBangs()) {
            if (prefix.isEmpty() || e.bang.startsWith(prefix)
                    || e.label.toLowerCase(Locale.ROOT).contains(prefix)) {
                out.add(e);
                if (out.size() >= cap) break;
            }
        }
        return out;
    }

    /** Resolves omnibox `/command` shortcuts (with convenient English & Chinese aliases). */
    public static SlashCmd resolveSlashCommand(String rawInput) {
        if (rawInput == null) return null;
        String s = rawInput.trim();
        if (s.length() < 2 || s.charAt(0) != '/') return null;
        int sp = findWhitespace(s, 1);
        String token = (sp < 0 ? s.substring(1) : s.substring(1, sp)).toLowerCase(Locale.ROOT);
        switch (token) {
            case "babel":
            case "tr":
            case "translate":
            case "翻译":
                return SlashCmd.BABEL;
            case "devtools":
            case "dev":
            case "console":
            case "f12":
                return SlashCmd.DEVTOOLS;
            case "gallery":
            case "img":
            case "images":
            case "看图":
                return SlashCmd.GALLERY;
            case "longshot":
            case "shot":
            case "screenshot":
            case "长截图":
                return SlashCmd.LONGSHOT;
            case "qr":
            case "qrcode":
            case "二维码":
                return SlashCmd.QR;
            case "backup":
            case "export":
            case "restore":
            case "备份":
                return SlashCmd.BACKUP;
            case "unlock":
            case "copy":
            case "解锁":
                return SlashCmd.UNLOCK;
            case "dark":
            case "night":
            case "夜间":
                return SlashCmd.DARK;
            case "clean":
            case "clear":
            case "清理":
                return SlashCmd.CLEAN;
            case "settings":
            case "setting":
            case "config":
            case "设置":
                return SlashCmd.SETTINGS;
            default:
                return null;
        }
    }

    public static String slashLabel(SlashCmd cmd) {
        if (cmd == null) return "";
        switch (cmd) {
            case BABEL: return "/babel · 巴别塔沉浸式翻译";
            case DEVTOOLS: return "/devtools · 网页开发者工具";
            case GALLERY: return "/gallery · 页面高清图集";
            case LONGSHOT: return "/longshot · 整页长截图";
            case QR: return "/qr · 当前页二维码 / 扫码";
            case BACKUP: return "/backup · 一键备份与恢复 (.aiko)";
            case UNLOCK: return "/unlock · 解锁页面复制限制";
            case DARK: return "/dark · 切换网页深色 / 护眼";
            case CLEAN: return "/clean · 清理浏览痕迹";
            case SETTINGS: return "/settings · 主页与导航设置";
            default: return "";
        }
    }

    public static List<SlashCmd> suggestSlashCommands(String rawPrefix) {
        if (rawPrefix == null) return Collections.emptyList();
        String s = rawPrefix.trim();
        if (!s.startsWith("/")) return Collections.emptyList();
        String prefix = s.substring(1).toLowerCase(Locale.ROOT);
        ArrayList<SlashCmd> out = new ArrayList<>();
        for (SlashCmd cmd : SlashCmd.values()) {
            String name = cmd.name().toLowerCase(Locale.ROOT);
            String desc = slashLabel(cmd).toLowerCase(Locale.ROOT);
            if (prefix.isEmpty() || name.startsWith(prefix) || desc.contains(prefix)) {
                out.add(cmd);
            }
        }
        return out;
    }

    private Entry lookupEntry(String bangKey) {
        synchronized (lock) {
            Entry custom = customMap.get(bangKey);
            if (custom != null) return custom;
        }
        return BUILTIN_MAP.get(bangKey);
    }

    private static String buildUrl(Entry entry, String query) {
        if (query == null || query.isEmpty()) {
            return entry.homeUrl;
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        String built;
        if (entry.template.contains("%s")) {
            built = entry.template.replace("%s", encoded);
        } else if (entry.template.contains("{searchTerms}")) {
            built = entry.template.replace("{searchTerms}", encoded);
        } else {
            built = entry.template + encoded;
        }
        return HttpUrlUtil.isHttp(built) ? built : null;
    }

    private String encodeCustomLocked() {
        JSONArray arr = new JSONArray();
        for (Entry e : customMap.values()) {
            try {
                arr.put(new JSONObject()
                        .put("bang", e.bang)
                        .put("label", e.label)
                        .put("template", e.template));
            } catch (JSONException ignored) { }
        }
        return arr.toString();
    }

    static String normalizeBangKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("!")) s = s.substring(1).trim();
        if (s.isEmpty() || s.length() > 16) return null;
        s = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return null;
        }
        return s;
    }

    private static String sanitizeTemplate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 512) return null;
        String probe = s.replace("%s", "test").replace("{searchTerms}", "test");
        return HttpUrlUtil.isHttp(probe) ? s : null;
    }

    private static String sanitizeLabel(String raw, String fallbackBang) {
        if (raw == null) return "!" + fallbackBang;
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.isEmpty()) return "!" + fallbackBang;
        return cleaned.length() > 32 ? cleaned.substring(0, 32).trim() : cleaned;
    }

    private static String deriveHomeUrl(String template) {
        String origin = HttpUrlUtil.extractOrigin(template.replace("%s", "x").replace("{searchTerms}", "x"));
        if (!origin.isEmpty()) return origin + "/";
        String host = HttpUrlUtil.extractHost(template.replace("%s", "x").replace("{searchTerms}", "x"));
        return host.isEmpty() ? template : "http://" + host + "/";
    }

    private static int findWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static int findLastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
