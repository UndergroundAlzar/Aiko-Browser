package app.aikobrowser;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local bookmarks, history, and Speed Dial pins with background preload/serialization,
 * live search, and Netscape HTML bookmark import/export.
 * Preserves legacy "bookmarks" and "history" SharedPreferences JSON schemas.
 */
public final class BrowserLibrary {
    public static final int BOOKMARK_LIMIT = 500;
    public static final int HISTORY_LIMIT = 160;
    public static final int PIN_LIMIT = 16;

    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PINS = "pins";

    private static final Pattern HTML_LINK = Pattern.compile(
            "<a\\b[^>]*\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-library-io");
        t.setDaemon(true);
        return t;
    });

    public static final class Entry {
        public final String url, title;

        public Entry(String url, String title) {
            this.url = url;
            this.title = title == null || title.trim().isEmpty() ? url : title.trim();
        }
    }

    private final SharedPreferences storage;
    private final Object lock = new Object();
    private LinkedHashMap<String, Entry> bookmarks, history, pins;
    private boolean dirty;

    public BrowserLibrary(SharedPreferences storage) {
        this.storage = storage;
    }

    /** Preloads bookmarks, history, and Speed Dial pins off the UI thread. */
    public void preloadAsync() {
        IO.execute(() -> {
            synchronized (lock) {
                bookmarksLocked();
                historyLocked();
                pinsLocked();
            }
        });
    }

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        synchronized (lock) {
            return bookmarksLocked().containsKey(url);
        }
    }

    public boolean bookmarksFull() {
        synchronized (lock) {
            return bookmarksLocked().size() >= BOOKMARK_LIMIT;
        }
    }

    public void addBookmark(String url, String title) {
        if (!HttpUrlUtil.isHttp(url)) return;
        ArrayList<Entry> snapshot;
        synchronized (lock) {
            LinkedHashMap<String, Entry> list = bookmarksLocked();
            if (list.containsKey(url) || list.size() >= BOOKMARK_LIMIT) return;
            list.put(url, new Entry(url, title));
            snapshot = new ArrayList<>(list.values());
        }
        saveAsync(KEY_BOOKMARKS, snapshot);
    }

    public void removeBookmark(String url) {
        if (url == null) return;
        ArrayList<Entry> snapshot;
        synchronized (lock) {
            LinkedHashMap<String, Entry> list = bookmarksLocked();
            if (list.remove(url) == null) return;
            snapshot = new ArrayList<>(list.values());
        }
        saveAsync(KEY_BOOKMARKS, snapshot);
    }

    public void visit(String url, String title) {
        if (!HttpUrlUtil.isHttp(url)) return;
        synchronized (lock) {
            LinkedHashMap<String, Entry> list = historyLocked();
            Entry old = list.remove(url);
            append(list, new Entry(url, title == null && old != null ? old.title : title), HISTORY_LIMIT);
            dirty = true;
        }
    }

    public void updateVisitTitle(String url, String title) {
        if (url == null || title == null) return;
        synchronized (lock) {
            if (history == null || !history.containsKey(url)) return;
            Entry updated = new Entry(url, title);
            if (!updated.title.equals(history.get(url).title)) {
                history.put(url, updated);
                dirty = true;
            }
        }
    }

    public List<Entry> pages(boolean saved) {
        ArrayList<Entry> entries;
        synchronized (lock) {
            entries = new ArrayList<>((saved ? bookmarksLocked() : historyLocked()).values());
        }
        Collections.reverse(entries);
        return entries;
    }

    public void clearHistory() {
        synchronized (lock) {
            historyLocked().clear();
            dirty = false;
        }
        IO.execute(() -> storage.edit().remove(KEY_HISTORY).apply());
    }

    public void flushHistory() {
        ArrayList<Entry> snapshot;
        synchronized (lock) {
            if (!dirty || history == null) return;
            snapshot = new ArrayList<>(history.values());
            dirty = false;
        }
        saveAsync(KEY_HISTORY, snapshot);
    }

    /** Instant case-insensitive search across bookmarks (priority) and history, deduplicated by URL. */
    public List<Entry> search(String query, int limit) {
        if (limit <= 0) return Collections.emptyList();
        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] terms = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        ArrayList<Entry> bSnap, hSnap;
        synchronized (lock) {
            bSnap = new ArrayList<>(bookmarksLocked().values());
            hSnap = new ArrayList<>(historyLocked().values());
        }
        Collections.reverse(bSnap);
        Collections.reverse(hSnap);

        ArrayList<Entry> out = new ArrayList<>(Math.min(limit, 16));
        HashSet<String> seen = new HashSet<>();
        collectMatches(bSnap, terms, limit, seen, out);
        if (out.size() < limit) collectMatches(hSnap, terms, limit, seen, out);
        return out;
    }

    private static void collectMatches(List<Entry> source, String[] terms, int limit,
                                       HashSet<String> seen, List<Entry> out) {
        for (int i = 0; i < source.size() && out.size() < limit; i++) {
            Entry e = source.get(i);
            if (seen.contains(e.url)) continue;
            if (matchesAll(e, terms)) {
                seen.add(e.url);
                out.add(e);
            }
        }
    }

    private static boolean matchesAll(Entry e, String[] terms) {
        if (terms.length == 0) return true;
        String lowerTitle = e.title.toLowerCase(Locale.ROOT);
        String lowerUrl = e.url.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!lowerTitle.contains(term) && !lowerUrl.contains(term)) return false;
        }
        return true;
    }

    /** Speed Dial pins in user-configured display order. */
    public List<Entry> pins() {
        synchronized (lock) {
            return new ArrayList<>(pinsLocked().values());
        }
    }

    public boolean isPinned(String url) {
        if (url == null) return false;
        synchronized (lock) {
            return pinsLocked().containsKey(url);
        }
    }

    /** Toggles Speed Dial pin status; returns true if pinned after the call, false if unpinned. */
    public boolean togglePin(String url, String title) {
        if (!HttpUrlUtil.isHttp(url)) return false;
        boolean nowPinned;
        ArrayList<Entry> snapshot;
        synchronized (lock) {
            LinkedHashMap<String, Entry> list = pinsLocked();
            if (list.remove(url) != null) {
                nowPinned = false;
            } else {
                if (list.size() >= PIN_LIMIT) {
                    list.remove(list.keySet().iterator().next());
                }
                list.put(url, new Entry(url, title));
                nowPinned = true;
            }
            snapshot = new ArrayList<>(list.values());
        }
        saveAsync(KEY_PINS, snapshot);
        return nowPinned;
    }

    public void setPins(List<Entry> entries) {
        ArrayList<Entry> snapshot;
        synchronized (lock) {
            LinkedHashMap<String, Entry> next = new LinkedHashMap<>();
            if (entries != null) {
                for (Entry e : entries) {
                    if (e != null && HttpUrlUtil.isHttp(e.url) && next.size() < PIN_LIMIT) {
                        next.put(e.url, new Entry(e.url, e.title));
                    }
                }
            }
            pins = next;
            snapshot = new ArrayList<>(next.values());
        }
        saveAsync(KEY_PINS, snapshot);
    }

    /** Exports bookmarks in standard Netscape Bookmark File 1 HTML format. */
    public String exportBookmarksHtml() {
        List<Entry> list = pages(true);
        StringBuilder sb = new StringBuilder(256 + list.size() * 96);
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
        sb.append("<!-- Exported by Aiko Browser -->\n");
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
        sb.append("<TITLE>Aiko Browser Bookmarks</TITLE>\n");
        sb.append("<H1>Bookmarks</H1>\n<DL><p>\n");
        for (Entry e : list) {
            sb.append("    <DT><A HREF=\"").append(escapeHtml(e.url)).append("\">")
                    .append(escapeHtml(e.title)).append("</A>\n");
        }
        sb.append("</DL><p>\n");
        return sb.toString();
    }

    /** Imports bookmarks from Netscape HTML format; returns number of newly added entries. */
    public int importBookmarksHtml(String html) {
        if (html == null || html.isEmpty()) return 0;
        ArrayList<Entry> parsed = new ArrayList<>();
        Matcher m = HTML_LINK.matcher(html);
        while (m.find()) {
            String rawHref = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            if (rawHref == null) continue;
            String url = unescapeHtml(rawHref.trim());
            if (!HttpUrlUtil.isHttp(url)) continue;
            String rawTitle = m.group(4);
            String cleanTitle = rawTitle == null ? url
                    : unescapeHtml(HTML_TAG.matcher(rawTitle).replaceAll("")).trim();
            parsed.add(new Entry(url, cleanTitle));
        }
        if (parsed.isEmpty()) return 0;

        int added = 0;
        ArrayList<Entry> snapshot = null;
        synchronized (lock) {
            LinkedHashMap<String, Entry> list = bookmarksLocked();
            for (Entry e : parsed) {
                if (list.size() >= BOOKMARK_LIMIT) break;
                if (!list.containsKey(e.url)) {
                    list.put(e.url, e);
                    added++;
                }
            }
            if (added > 0) snapshot = new ArrayList<>(list.values());
        }
        if (snapshot != null) saveAsync(KEY_BOOKMARKS, snapshot);
        return added;
    }

    private LinkedHashMap<String, Entry> bookmarksLocked() {
        if (bookmarks == null) bookmarks = load(KEY_BOOKMARKS, BOOKMARK_LIMIT);
        return bookmarks;
    }

    private LinkedHashMap<String, Entry> historyLocked() {
        if (history == null) history = load(KEY_HISTORY, HISTORY_LIMIT);
        return history;
    }

    private LinkedHashMap<String, Entry> pinsLocked() {
        if (pins == null) {
            String raw = storage.getString(KEY_PINS, null);
            if (raw == null) {
                pins = defaultPins();
            } else {
                pins = parseJsonEntries(raw, PIN_LIMIT);
            }
        }
        return pins;
    }

    private static LinkedHashMap<String, Entry> defaultPins() {
        LinkedHashMap<String, Entry> defaults = new LinkedHashMap<>();
        defaults.put("https://github.com/", new Entry("https://github.com/", "GitHub"));
        defaults.put("https://linux.do/", new Entry("https://linux.do/", "Linux.do"));
        defaults.put("https://www.bilibili.com/", new Entry("https://www.bilibili.com/", "Bilibili"));
        defaults.put("https://m.youtube.com/", new Entry("https://m.youtube.com/", "YouTube"));
        defaults.put("https://zh.wikipedia.org/", new Entry("https://zh.wikipedia.org/", "Wikipedia"));
        defaults.put("https://www.bing.com/", new Entry("https://www.bing.com/", "Bing"));
        defaults.put("https://www.google.com/", new Entry("https://www.google.com/", "Google"));
        defaults.put("https://developer.mozilla.org/", new Entry("https://developer.mozilla.org/", "MDN"));
        return defaults;
    }

    private LinkedHashMap<String, Entry> load(String key, int limit) {
        return parseJsonEntries(storage.getString(key, "[]"), limit);
    }

    private static LinkedHashMap<String, Entry> parseJsonEntries(String raw, int limit) {
        LinkedHashMap<String, Entry> list = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray rows = new JSONArray(raw);
            for (int i = Math.max(0, rows.length() - limit); i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                String url = row.optString(0);
                if (HttpUrlUtil.isHttp(url)) append(list, new Entry(url, row.optString(1)), limit);
            }
        } catch (org.json.JSONException ignored) { }
        return list;
    }

    private void saveAsync(String key, ArrayList<Entry> snapshot) {
        IO.execute(() -> {
            JSONArray rows = new JSONArray();
            for (int i = 0; i < snapshot.size(); i++) {
                Entry item = snapshot.get(i);
                rows.put(new JSONArray().put(item.url).put(item.title));
            }
            storage.edit().putString(key, rows.toString()).apply();
        });
    }

    private static void append(LinkedHashMap<String, Entry> list, Entry entry, int limit) {
        list.put(entry.url, entry);
        if (list.size() > limit) list.remove(list.keySet().iterator().next());
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                default: out.append(c); break;
            }
        }
        return out.toString();
    }

    private static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length());
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    int decoded = decodeEntity(ent);
                    if (decoded >= 0) {
                        out.append((char) decoded);
                        i = semi;
                        continue;
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int decodeEntity(String ent) {
        if ("amp".equalsIgnoreCase(ent)) return '&';
        if ("lt".equalsIgnoreCase(ent)) return '<';
        if ("gt".equalsIgnoreCase(ent)) return '>';
        if ("quot".equalsIgnoreCase(ent)) return '"';
        if ("apos".equalsIgnoreCase(ent)) return '\'';
        if ("nbsp".equalsIgnoreCase(ent)) return ' ';
        if (ent.startsWith("#x") || ent.startsWith("#X")) {
            try { return Integer.parseInt(ent.substring(2), 16); } catch (NumberFormatException ignored) { }
        } else if (ent.startsWith("#")) {
            try { return Integer.parseInt(ent.substring(1), 10); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }
}
