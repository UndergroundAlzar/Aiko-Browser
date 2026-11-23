package app.aikobrowser;

import android.content.SharedPreferences;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Small local lists. New visits need no disk read once the history is loaded. */
final class BrowserLibrary {
    static final int BOOKMARK_LIMIT = 500;
    static final int HISTORY_LIMIT = 160;

    static final class Entry {
        final String url, title;
        Entry(String url, String title) {
            this.url = url;
            this.title = title == null || title.trim().isEmpty() ? url : title.trim();
        }
    }

    private final SharedPreferences storage;
    private LinkedHashMap<String, Entry> bookmarks, history;
    private boolean dirty;

    BrowserLibrary(SharedPreferences storage) { this.storage = storage; }

    boolean isBookmarked(String url) { return bookmarks().containsKey(url); }

    boolean bookmarksFull() { return bookmarks().size() >= BOOKMARK_LIMIT; }

    void addBookmark(String url, String title) {
        LinkedHashMap<String, Entry> list = bookmarks();
        if (list.containsKey(url) || list.size() >= BOOKMARK_LIMIT) return;
        list.put(url, new Entry(url, title));
        save("bookmarks", list);
    }

    void removeBookmark(String url) {
        if (bookmarks().remove(url) != null) save("bookmarks", bookmarks);
    }

    void visit(String url, String title) {
        LinkedHashMap<String, Entry> list = history();
        Entry old = list.remove(url);
        append(list, new Entry(url, title == null && old != null ? old.title : title), HISTORY_LIMIT);
        dirty = true;
    }

    void updateVisitTitle(String url, String title) {
        if (history == null || title == null || !history.containsKey(url)) return;
        Entry updated = new Entry(url, title);
        if (!updated.title.equals(history.get(url).title)) {
            history.put(url, updated);
            dirty = true;
        }
    }

    List<Entry> pages(boolean saved) {
        ArrayList<Entry> entries = new ArrayList<>((saved ? bookmarks() : history()).values());
        java.util.Collections.reverse(entries);
        return entries;
    }

    void clearHistory() {
        history().clear();
        dirty = false;
        storage.edit().remove("history").apply();
    }

    void flushHistory() {
        if (!dirty) return;
        save("history", history);
        dirty = false;
    }

    private LinkedHashMap<String, Entry> bookmarks() {
        if (bookmarks == null) bookmarks = load("bookmarks", BOOKMARK_LIMIT);
        return bookmarks;
    }

    private LinkedHashMap<String, Entry> history() {
        if (history == null) history = load("history", HISTORY_LIMIT);
        return history;
    }

    private LinkedHashMap<String, Entry> load(String key, int limit) {
        LinkedHashMap<String, Entry> list = new LinkedHashMap<>();
        try {
            JSONArray rows = new JSONArray(storage.getString(key, "[]"));
            for (int i = Math.max(0, rows.length() - limit); i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                String url = row.optString(0);
                if (MainActivity.http(url)) append(list, new Entry(url, row.optString(1)), limit);
            }
        } catch (org.json.JSONException ignored) { }
        return list;
    }

    private void save(String key, LinkedHashMap<String, Entry> list) {
        JSONArray rows = new JSONArray();
        for (Entry item : list.values()) rows.put(new JSONArray().put(item.url).put(item.title));
        storage.edit().putString(key, rows.toString()).apply();
    }

    private static void append(LinkedHashMap<String, Entry> list, Entry entry, int limit) {
        list.put(entry.url, entry);
        if (list.size() > limit) list.remove(list.keySet().iterator().next());
    }
}
