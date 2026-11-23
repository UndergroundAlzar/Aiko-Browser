package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Zero-RAM Pinned Reading Shelf stored in dedicated "reading_shelf" prefs
 * with optional local MHTML offline archive support.
 */
public final class ReadingShelf {
    public static final int MAX_ENTRIES = 120;
    private static final String PREFS = "reading_shelf";
    private static final String KEY_ITEMS = "items";
    private static final String ARCHIVE_DIR = "reading_shelf";

    public static final class Entry {
        public final String url;
        public final String title;
        public final int scrollX;
        public final int scrollY;
        public final String archivePath;
        public final long savedAt;

        public Entry(String url, String title, int scrollX, int scrollY, String archivePath, long savedAt) {
            this.url = url;
            this.title = (title == null || title.trim().isEmpty()) ? url : title.trim();
            this.scrollX = Math.max(0, scrollX);
            this.scrollY = Math.max(0, scrollY);
            this.archivePath = archivePath == null ? "" : archivePath;
            this.savedAt = savedAt;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiko-reading-shelf");
        t.setDaemon(true);
        return t;
    });

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private LinkedHashMap<String, Entry> entries;

    public ReadingShelf(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void preloadAsync() {
        IO.execute(this::ensureLoaded);
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (entries != null) return;
            entries = new LinkedHashMap<>();
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            try {
                JSONArray arr = new JSONArray(sp.getString(KEY_ITEMS, "[]"));
                for (int i = Math.max(0, arr.length() - MAX_ENTRIES); i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    String url = obj.optString("u", "");
                    if (!HttpUrlUtil.isHttp(url)) continue;
                    Entry entry = new Entry(
                            url,
                            obj.optString("t", url),
                            obj.optInt("x", 0),
                            obj.optInt("y", 0),
                            obj.optString("a", ""),
                            obj.optLong("s", 0L));
                    entries.put(url, entry);
                }
            } catch (JSONException ignored) { }
        }
    }

    public List<Entry> list() {
        ensureLoaded();
        ArrayList<Entry> out;
        synchronized (lock) {
            out = new ArrayList<>(entries.values());
        }
        Collections.reverse(out);
        return out;
    }

    public boolean has(String url) {
        if (url == null || url.isEmpty()) return false;
        ensureLoaded();
        synchronized (lock) {
            return entries.containsKey(url);
        }
    }

    public Entry get(String url) {
        if (url == null || url.isEmpty()) return null;
        ensureLoaded();
        synchronized (lock) {
            return entries.get(url);
        }
    }

    public void pin(String url, String title, int scrollX, int scrollY, String archivePath) {
        if (!HttpUrlUtil.isHttp(url)) return;
        ensureLoaded();
        List<Entry> snapshot;
        String replacedArchive = null;
        synchronized (lock) {
            Entry prev = entries.remove(url);
            String keptArchive = (archivePath != null && !archivePath.isEmpty())
                    ? archivePath
                    : (prev != null ? prev.archivePath : "");
            if (prev != null && !prev.archivePath.isEmpty()
                    && archivePath != null && !archivePath.isEmpty()
                    && !prev.archivePath.equals(archivePath)) {
                replacedArchive = prev.archivePath;
            }
            entries.put(url, new Entry(url, title, scrollX, scrollY, keptArchive, System.currentTimeMillis()));
            while (entries.size() > MAX_ENTRIES) {
                String oldestKey = entries.keySet().iterator().next();
                Entry evicted = entries.remove(oldestKey);
                if (evicted != null && !evicted.archivePath.isEmpty()) {
                    deleteArchiveAsync(evicted.archivePath);
                }
            }
            snapshot = new ArrayList<>(entries.values());
        }
        if (replacedArchive != null) deleteArchiveAsync(replacedArchive);
        persistAsync(snapshot);
    }

    public void remove(String url) {
        if (url == null || url.isEmpty()) return;
        ensureLoaded();
        Entry removed;
        List<Entry> snapshot;
        synchronized (lock) {
            removed = entries.remove(url);
            if (removed == null) return;
            snapshot = new ArrayList<>(entries.values());
        }
        if (!removed.archivePath.isEmpty()) deleteArchiveAsync(removed.archivePath);
        persistAsync(snapshot);
    }

    /** Saves an offline MHTML snapshot for the given WebView into app-private storage. */
    public void saveArchiveFor(WebView web, String url, Consumer<String> onPath) {
        if (web == null || !HttpUrlUtil.isHttp(url)) {
            deliver(onPath, null);
            return;
        }
        IO.execute(() -> {
            File dir = new File(appContext.getFilesDir(), ARCHIVE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                deliver(onPath, null);
                return;
            }
            File target = new File(dir, hashKey(url) + ".mht");
            String path = target.getAbsolutePath();
            main.post(() -> {
                try {
                    web.saveWebArchive(path, false, saved -> {
                        if (onPath != null) onPath.accept(saved);
                    });
                } catch (RuntimeException ignored) {
                    if (onPath != null) onPath.accept(null);
                }
            });
        });
    }

    private void persistAsync(List<Entry> snapshot) {
        IO.execute(() -> {
            JSONArray arr = new JSONArray();
            for (Entry e : snapshot) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("u", e.url);
                    obj.put("t", e.title);
                    obj.put("x", e.scrollX);
                    obj.put("y", e.scrollY);
                    if (!e.archivePath.isEmpty()) obj.put("a", e.archivePath);
                    obj.put("s", e.savedAt);
                    arr.put(obj);
                } catch (JSONException ignored) { }
            }
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, arr.toString()).apply();
        });
    }

    private void deleteArchiveAsync(String path) {
        IO.execute(() -> {
            try {
                File dir = new File(appContext.getFilesDir(), ARCHIVE_DIR).getCanonicalFile();
                File f = new File(path).getCanonicalFile();
                if (f.getPath().startsWith(dir.getPath() + File.separator)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception ignored) { }
        });
    }

    private void deliver(Consumer<String> cb, String value) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) cb.accept(value);
        else main.post(() -> cb.accept(value));
    }

    private static String hashKey(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                int v = digest[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
