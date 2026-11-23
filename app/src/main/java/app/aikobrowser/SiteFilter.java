package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/** Settings and background rule loading. A failed refresh keeps the existing snapshot. */
final class SiteFilter {
    private static final String[] TRACKERS = {
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "googlesyndication.com", "scorecardresearch.com", "analytics.tiktok.com",
        "bat.bing.com", "static.ads-twitter.com", "analytics.twitter.com"
    };
    private static final String[] IDS = {"easylist", "easyprivacy", "adguard-cn"};
    private static final String[] TITLES = {"EasyList", "EasyPrivacy", "AdGuard Chinese filter"};
    private static final String[] SOURCES = {
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://filters.adtidy.org/extension/chromium/filters/224.txt"
    };
    private static final int ALL_LISTS_MASK = (1 << IDS.length) - 1;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int GZIP_BUFFER = 32 * 1024;
    private static final int IO_BUFFER = 64 * 1024;
    private static final int CACHE_MAGIC = 0x41494B43; // "AIKC"
    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE = "filter-rules-v1.bin";
    private static final int CSS_CACHE_LIMIT = 16;

    /** Stateless lock-free empty stream safe for concurrent Chromium IO threads. */
    private static final InputStream EMPTY_STREAM = new InputStream() {
        @Override public int read() { return -1; }
        @Override public int read(byte[] b, int off, int len) { return -1; }
        @Override public long skip(long n) { return 0; }
        @Override public int available() { return 0; }
    };

    private static final class CssEntry {
        final String css, quoted;
        CssEntry(String css, String quoted) {
            this.css = css;
            this.quoted = quoted;
        }
    }

    private final Context app;
    private final SharedPreferences storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        task.run();
    }, "aiko-filter"));
    private final Object prefsLock = new Object();
    private final Object loadLock = new Object();
    private final Object cssCacheLock = new Object();
    private final List<Runnable> readyWaiters = new ArrayList<>(2);
    private final LinkedHashMap<String, CssEntry> cssCache = new LinkedHashMap<String, CssEntry>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CssEntry> eldest) {
            return size() > CSS_CACHE_LIMIT;
        }
    };
    private FilterRules cssCacheRules = FilterRules.EMPTY;
    private volatile boolean prefsLoaded;
    private volatile Set<String> exclusions = Collections.emptySet();
    private volatile int listMask = ALL_LISTS_MASK;
    volatile FilterRules rules = FilterRules.EMPTY;
    private boolean started, loadCompleted, updating;

    SiteFilter(Context context) {
        app = context.getApplicationContext();
        storage = app.getSharedPreferences("site_filter", Context.MODE_PRIVATE);
    }

    private void ensurePrefsLoaded() {
        if (prefsLoaded) return;
        synchronized (prefsLock) {
            if (prefsLoaded) return;
            exclusions = new HashSet<>(storage.getStringSet("allow", Collections.emptySet()));
            int mask = 0;
            for (int i = 0; i < IDS.length; i++) {
                if (storage.getBoolean("list_" + IDS[i], true)) mask |= (1 << i);
            }
            listMask = mask;
            prefsLoaded = true;
        }
    }

    /** Warms SharedPreferences and triggers initial rule load off the UI thread. */
    public void preloadAsync() {
        load(null);
    }

    void load(Runnable ready) {
        boolean shouldStart = false;
        boolean alreadyDone = false;
        synchronized (loadLock) {
            if (loadCompleted) {
                alreadyDone = true;
            } else {
                if (ready != null) readyWaiters.add(ready);
                if (!started) {
                    started = true;
                    shouldStart = true;
                }
            }
        }
        if (alreadyDone) {
            if (ready != null) main.post(ready);
            return;
        }
        if (!shouldStart) return;
        worker.execute(() -> {
            ensurePrefsLoaded();
            FilterRules loaded = null;
            byte[] fingerprintToWrite = null;
            try {
                String custom = loadCustom();
                int enabledMask = listMask;
                byte[] fp = fingerprint(custom, enabledMask);
                loaded = readBinaryCache(fp);
                if (loaded == null) {
                    loaded = compile(custom, enabledMask);
                    fingerprintToWrite = fp;
                }
                publishRules(loaded);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not load filter lists", e);
            }
            List<Runnable> callbacks;
            synchronized (loadLock) {
                loadCompleted = true;
                callbacks = new ArrayList<>(readyWaiters);
                readyWaiters.clear();
            }
            for (Runnable cb : callbacks) main.post(cb);
            if (loaded != null && fingerprintToWrite != null) {
                writeBinaryCache(loaded, fingerprintToWrite);
            }
        });
    }

    private void publishRules(FilterRules next) {
        rules = next;
        synchronized (cssCacheLock) {
            cssCache.clear();
            cssCacheRules = next;
        }
    }

    interface RefreshResult { void done(int refreshed, boolean applied); }

    boolean update(RefreshResult callback) {
        if (updating) return false;
        updating = true;
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                ensurePrefsLoaded();
                int refreshed = 0;
                for (int i = 0; i < IDS.length; i++) {
                    try {
                        AtomicFile cache = cache(i);
                        byte[] content = fetch(SOURCES[i], TITLES[i]);
                        FileOutputStream stream = cache.startWrite();
                        try {
                            stream.write(content);
                            cache.finishWrite(stream);
                            refreshed++;
                        } catch (IOException e) {
                            cache.failWrite(stream);
                            throw e;
                        }
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("AikoFilter", "Kept existing list " + IDS[i], e);
                    }
                }
                boolean compiled = false;
                if (refreshed > 0) {
                    try {
                        String custom = loadCustom();
                        int enabledMask = listMask;
                        FilterRules next = compile(custom, enabledMask);
                        publishRules(next);
                        writeBinaryCache(next, fingerprint(custom, enabledMask));
                        compiled = true;
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("AikoFilter", "Kept existing compiled rules", e);
                    }
                }
                final int count = refreshed;
                final boolean applied = compiled;
                main.post(() -> {
                    updating = false;
                    callback.done(count, applied);
                });
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            }
        });
        return true;
    }

    public boolean isListEnabled(String id) {
        int idx = listIndex(id);
        if (idx < 0) return false;
        ensurePrefsLoaded();
        return (listMask & (1 << idx)) != 0;
    }

    public void setListEnabled(String id, boolean enabled, Runnable onDone) {
        int idx = listIndex(id);
        if (idx < 0) {
            if (onDone != null) main.post(onDone);
            return;
        }
        ensurePrefsLoaded();
        int nextMask;
        synchronized (prefsLock) {
            nextMask = enabled ? (listMask | (1 << idx)) : (listMask & ~(1 << idx));
            if (nextMask == listMask) {
                if (onDone != null) main.post(onDone);
                return;
            }
            listMask = nextMask;
        }
        storage.edit().putBoolean("list_" + id, enabled).apply();
        worker.execute(() -> {
            try {
                String custom = loadCustom();
                int enabledMask = listMask;
                byte[] fp = fingerprint(custom, enabledMask);
                FilterRules next = readBinaryCache(fp);
                boolean fromCache = next != null;
                if (!fromCache) next = compile(custom, enabledMask);
                publishRules(next);
                if (!fromCache) writeBinaryCache(next, fp);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not apply filter list toggle", e);
            }
            if (onDone != null) main.post(onDone);
        });
    }

    public void appendCustomRule(String rule, Runnable onDone) {
        appendCustomRule(rule, (Boolean ok) -> {
            if (onDone != null) onDone.run();
        });
    }

    public void appendCustomRule(String rule, java.util.function.Consumer<Boolean> onDone) {
        String trimmed = rule == null ? "" : rule.trim();
        if (trimmed.isEmpty()) {
            if (onDone != null) main.post(() -> onDone.accept(Boolean.FALSE));
            return;
        }
        worker.execute(() -> {
            ensurePrefsLoaded();
            boolean saved = false;
            try {
                // Validate the single incoming rule first so invalid rules always report false.
                if (CustomFilterList.parse(trimmed).count == 0) {
                    throw new IllegalArgumentException("Empty custom rule");
                }
                String existing;
                try { existing = customSource(); }
                catch (IOException ignored) { existing = ""; }
                if (containsExactLine(existing, trimmed)) {
                    saved = true;
                } else {
                    String nextSource = existing.isEmpty() ? trimmed
                            : (existing.endsWith("\n") || existing.endsWith("\r")
                                    ? existing + trimmed : existing + "\n" + trimmed);
                    CustomFilterList list = CustomFilterList.parse(nextSource);
                    int enabledMask = listMask;
                    FilterRules next = compile(list.rules, enabledMask);
                    AtomicFile file = customFile();
                    FileOutputStream stream = file.startWrite();
                    try {
                        stream.write(nextSource.getBytes(StandardCharsets.UTF_8));
                        file.finishWrite(stream);
                    } catch (IOException | RuntimeException failure) {
                        file.failWrite(stream);
                        throw failure;
                    }
                    publishRules(next);
                    writeBinaryCache(next, fingerprint(list.rules, enabledMask));
                    saved = true;
                }
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("AikoFilter", "Could not append custom filter rule", failure);
            }
            final boolean result = saved;
            if (onDone != null) main.post(() -> onDone.accept(result));
        });
    }

    private static boolean containsExactLine(String text, String target) {
        if (text == null || text.isEmpty()) return false;
        int len = text.length(), pos = 0;
        while (pos < len) {
            int end = pos;
            while (end < len && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            int s = pos, e = end;
            while (s < e && text.charAt(s) <= ' ') s++;
            while (e > s && text.charAt(e - 1) <= ' ') e--;
            if (e - s == target.length() && text.regionMatches(s, target, 0, target.length())) return true;
            pos = end + 1;
        }
        return false;
    }

    String customSource() throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(customFile().getBaseFile().toPath());
            if (bytes.length > 4 * CustomFilterList.MAX_CHARS) throw new IOException("Custom rules too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return "";
        }
    }

    interface CustomResult { void done(int count, String error); }

    void saveCustom(String source, CustomResult callback) {
        worker.execute(() -> {
            ensurePrefsLoaded();
            int count = 0;
            String error = null;
            try {
                CustomFilterList list = CustomFilterList.parse(source);
                int enabledMask = listMask;
                FilterRules next = compile(list.rules, enabledMask);
                AtomicFile file = customFile();
                FileOutputStream stream = file.startWrite();
                try {
                    stream.write(source.getBytes(StandardCharsets.UTF_8));
                    file.finishWrite(stream);
                } catch (IOException | RuntimeException failure) {
                    file.failWrite(stream);
                    throw failure;
                }
                publishRules(next);
                writeBinaryCache(next, fingerprint(list.rules, enabledMask));
                count = list.count;
            } catch (IllegalArgumentException invalid) {
                error = invalid.getMessage();
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("AikoFilter", "Could not save custom filters", failure);
                error = "保存失败，继续使用原有规则。";
            }
            final int saved = count;
            final String detail = error;
            main.post(() -> callback.done(saved, detail));
        });
    }

    void close() { worker.shutdown(); }

    private String loadCustom() {
        try {
            return CustomFilterList.parse(customSource()).rules;
        } catch (IOException | IllegalArgumentException invalid) {
            android.util.Log.w("AikoFilter", "Ignoring invalid custom filter data");
            return "";
        }
    }

    private FilterRules compile(String customRules, int enabledMask) throws IOException {
        FilterRules.Builder builder = new FilterRules.Builder();
        builder.suffixes(new InputStreamReader(
                new GZIPInputStream(app.getAssets().open("filters/psl.txt.gz"), GZIP_BUFFER),
                StandardCharsets.UTF_8));
        if (!customRules.isEmpty()) {
            builder.read(new StringReader(customRules), "自定义规则");
        }
        for (int i = 0; i < IDS.length; i++) {
            if ((enabledMask & (1 << i)) == 0) continue;
            AtomicFile cache = cache(i);
            InputStream source;
            try {
                source = cache.openRead();
            } catch (FileNotFoundException e) {
                source = new GZIPInputStream(app.getAssets().open("filters/" + IDS[i] + ".txt.gz"), GZIP_BUFFER);
            }
            builder.read(new InputStreamReader(source, StandardCharsets.UTF_8), TITLES[i]);
        }
        return builder.build();
    }

    private byte[] fingerprint(String customRules, int enabledMask) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(256);
            sb.append("v").append(CACHE_VERSION).append(':').append(enabledMask).append(':');
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                sb.append(info.getLongVersionCode()).append(':').append(info.lastUpdateTime).append(':');
            } catch (Exception ignored) {
                sb.append("nopkg:");
            }
            for (int i = 0; i < IDS.length; i++) {
                if ((enabledMask & (1 << i)) == 0) continue;
                File f = cache(i).getBaseFile();
                sb.append(i).append('=');
                if (f.isFile()) sb.append('f').append(f.length()).append('@').append(f.lastModified());
                else sb.append('a');
                sb.append(';');
            }
            md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (customRules != null && !customRules.isEmpty()) {
                md.update(customRules.getBytes(StandardCharsets.UTF_8));
            }
            return md.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private File binaryCacheFile() {
        return new File(app.getCacheDir(), CACHE_FILE);
    }

    private FilterRules readBinaryCache(byte[] expectedFp) {
        File file = binaryCacheFile();
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), IO_BUFFER))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION) return null;
            byte[] fp = new byte[32];
            in.readFully(fp);
            if (!Arrays.equals(fp, expectedFp)) return null;
            return FilterRules.readBinary(in);
        } catch (IOException | RuntimeException corrupt) {
            // Corrupt, truncated, or stale cache: remove and fall back to text compile.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private void writeBinaryCache(FilterRules compiled, byte[] fp) {
        File target = binaryCacheFile();
        File tmp = new File(target.getParentFile(), CACHE_FILE + "." + Process.myPid() + "." + System.nanoTime() + ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), IO_BUFFER))) {
                out.writeInt(CACHE_MAGIC);
                out.writeInt(CACHE_VERSION);
                out.write(fp);
                compiled.writeBinary(out);
                out.flush();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static int listIndex(String id) {
        if (id == null) return -1;
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    private AtomicFile cache(int i) {
        return new AtomicFile(new File(app.getFilesDir(), "filter-" + IDS[i] + ".txt"));
    }

    private AtomicFile customFile() {
        return new AtomicFile(new File(app.getFilesDir(), "filters-user.txt"));
    }

    private static byte[] fetch(String url, String title) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = stream.read(buf)) >= 0;) {
                    if (result.size() + n > MAX_BYTES) throw new IOException("Filter is too large");
                    result.write(buf, 0, n);
                }
                byte[] bytes = result.toByteArray();
                String header = new String(bytes, 0, Math.min(2048, bytes.length), StandardCharsets.UTF_8);
                boolean titleMatches = false;
                for (String line : header.split("\\r?\\n")) {
                    if (line.trim().equals("! Title: " + title)) { titleMatches = true; break; }
                }
                if (bytes.length < 10000 || !titleMatches) {
                    throw new IOException("Unrecognized filter list");
                }
                return bytes;
            }
        } finally {
            connection.disconnect();
        }
    }

    boolean enabled(String site) {
        if (site == null) return false;
        ensurePrefsLoaded();
        return !exclusions.contains(site);
    }

    static final class Page {
        final String url, host;
        final FilterTrace trace;
        volatile Decision decision;
        Page(String url) {
            this(url, null);
        }
        private Page(String url, Page previous) {
            this.url = url;
            host = normal(Uri.parse(url).getHost());
            trace = previous != null && host != null && host.equals(previous.host)
                    ? previous.trace : new FilterTrace();
        }
        Page withHistoryUrl(String nextUrl) {
            return url.equals(nextUrl) ? this : new Page(nextUrl, this);
        }
    }

    private static final class Decision {
        final FilterRules rules;
        final String key;
        final boolean excepted;
        Decision(FilterRules rules, Page page) {
            this.rules = rules;
            key = rules.siteKey(page.host);
            excepted = rules.exceptsPage(page.host, page.url, false);
        }
    }

    boolean blocks(Page page, android.webkit.WebResourceRequest request) {
        if (page == null || !enabled(page.host) || request.isForMainFrame()
                || !"GET".equals(request.getMethod())) return false;
        Uri uri = request.getUrl();
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        String host = normal(uri.getHost());
        if (host == null) return false;
        FilterRules snapshot = rules;
        if (snapshot == FilterRules.EMPTY) {
            String tracker = bootstrapMatch(page.host, host);
            if (tracker == null) return false;
            page.trace.addBootstrap(host, tracker);
            return true;
        }
        Decision decision = page.decision;
        if (decision == null || decision.rules != snapshot) page.decision = decision = new Decision(snapshot, page);
        if (decision.excepted) return false;

        Map<String, String> headers = request.getRequestHeaders();
        String destination = null, relation = null, mode = null;
        if (headers != null && !headers.isEmpty()) {
            destination = headers.get("Sec-Fetch-Dest");
            relation = headers.get("Sec-Fetch-Site");
            if (destination == null || relation == null) {
                for (Map.Entry<String, String> item : headers.entrySet()) {
                    String k = item.getKey();
                    if (k == null || k.length() < 14) continue;
                    char c0 = k.charAt(0);
                    if (c0 != 'S' && c0 != 's') continue;
                    if (destination == null && "Sec-Fetch-Dest".equalsIgnoreCase(k)) destination = item.getValue();
                    else if (relation == null && "Sec-Fetch-Site".equalsIgnoreCase(k)) relation = item.getValue();
                    else if (mode == null && "Sec-Fetch-Mode".equalsIgnoreCase(k)) mode = item.getValue();
                }
            }
        }
        int type = FilterRules.requestType(destination, destination == null ? uri.getEncodedPath() : null);
        if (type == 0 && "empty".equals(destination)) {
            if (mode == null && headers != null) mode = headers.get("Sec-Fetch-Mode");
            if ("cors".equals(mode) || "same-origin".equals(mode)) type = 64;
        }
        FilterRules.Rule match = snapshot.blockingRule(page.host, decision.key, host, uri.toString(), type, relation);
        if (match == null) return false;
        page.trace.add(host, snapshot, match);
        return true;
    }

    /** Quick adblock check for preconnect/prefetch filtering without WebResourceRequest. */
    public boolean isBlockedUrlFast(String url) {
        String host = extractHostFast(url);
        if (host == null || !enabled(host)) return false;
        FilterRules snapshot = rules;
        if (snapshot == FilterRules.EMPTY) {
            for (int i = 0; i < TRACKERS.length; i++) {
                if (atDomain(host, TRACKERS[i])) return true;
            }
            return false;
        }
        return snapshot.blocksUrlFast(host, url);
    }

    private static String extractHostFast(String url) {
        if (url == null) return null;
        int len = url.length();
        int start;
        if (len > 8 && url.regionMatches(true, 0, "https://", 0, 8)) start = 8;
        else if (len > 7 && url.regionMatches(true, 0, "http://", 0, 7)) start = 7;
        else return null;
        int end = start;
        int at = -1;
        while (end < len) {
            char c = url.charAt(end);
            if (c == '/' || c == '?' || c == '#') break;
            if (c == '@') at = end;
            end++;
        }
        if (at >= 0) start = at + 1;
        if (start >= end) return null;
        if (url.charAt(start) == '[') {
            int close = url.indexOf(']', start + 1);
            if (close < 0 || close >= end) return null;
            return normal(url.substring(start, close + 1));
        }
        int colon = url.indexOf(':', start);
        if (colon >= 0 && colon < end) end = colon;
        return end <= start ? null : normal(url.substring(start, end));
    }

    static android.webkit.WebResourceResponse blocked() {
        return new android.webkit.WebResourceResponse("text/plain", "UTF-8", 204,
                "No Content", Collections.emptyMap(), EMPTY_STREAM);
    }

    public String cssForHost(String normalizedHost, String url) {
        CssEntry entry = lookupCssEntry(normalizedHost, url);
        return entry == null ? "" : entry.css;
    }

    void applyCss(android.webkit.WebView view, String url) {
        if (url == null || !url.equals(view.getUrl())) return;
        String site = normal(Uri.parse(url).getHost());
        applyCss(view, site, url);
    }

    public void applyCss(android.webkit.WebView view, String normalizedHost, String url) {
        if (url == null || normalizedHost == null || !url.equals(view.getUrl())) return;
        CssEntry entry = lookupCssEntry(normalizedHost, url);
        if (entry == null || entry.css.isEmpty()) return;
        view.evaluateJavascript("(function(){if(location.href!==" + org.json.JSONObject.quote(url)
                + ")return;var s=document.getElementById('aiko-filter-style');"
                + "if(!s){s=document.createElement('style');s.id='aiko-filter-style';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=" + entry.quoted + ";})();", null);
    }

    private CssEntry lookupCssEntry(String normalizedHost, String url) {
        if (normalizedHost == null || !enabled(normalizedHost)) return null;
        FilterRules snapshot = rules;
        if (snapshot == FilterRules.EMPTY) return null;
        if (snapshot.hasPageExceptions() && snapshot.exceptsPage(normalizedHost, url, true)) return null;
        synchronized (cssCacheLock) {
            if (cssCacheRules == snapshot) {
                CssEntry cached = cssCache.get(normalizedHost);
                if (cached != null) return cached;
            }
        }
        String css = snapshot.cssForSiteOnly(normalizedHost);
        CssEntry created = css.isEmpty() ? new CssEntry("", "\"\"")
                : new CssEntry(css, org.json.JSONObject.quote(css));
        synchronized (cssCacheLock) {
            if (cssCacheRules != snapshot) {
                cssCache.clear();
                cssCacheRules = snapshot;
            }
            cssCache.put(normalizedHost, created);
        }
        return created;
    }

    void setEnabled(String site, boolean enabled) {
        if (site == null) return;
        ensurePrefsLoaded();
        Set<String> next;
        synchronized (prefsLock) {
            next = new HashSet<>(exclusions);
            if (enabled) next.remove(site); else next.add(site);
            exclusions = next;
        }
        storage.edit().putStringSet("allow", next).apply();
    }

    private String bootstrapMatch(String site, String host) {
        if (host.equalsIgnoreCase(site)) return null;
        for (String tracker : TRACKERS) {
            if (atDomain(host, tracker) && !atDomain(site, tracker)) return tracker;
        }
        return null;
    }

    static String normal(String host) {
        if (host == null) return null;
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') end--;
        if (end == 0) return null;
        boolean convert = false;
        for (int i = 0; i < end; i++) {
            char c = host.charAt(i);
            if (c > 127 || c >= 'A' && c <= 'Z') { convert = true; break; }
        }
        String value = end == host.length() ? host : host.substring(0, end);
        if (!convert) return value;
        try { return java.net.IDN.toASCII(value).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException error) { return value.toLowerCase(Locale.ROOT); }
    }

    private static boolean atDomain(String host, String domain) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') end--;
        int start = end - domain.length();
        return start >= 0 && host.regionMatches(true, start, domain, 0, domain.length())
                && (start == 0 || host.charAt(start - 1) == '.');
    }
}
