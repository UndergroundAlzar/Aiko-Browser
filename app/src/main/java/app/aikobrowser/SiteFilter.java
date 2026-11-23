package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final Context app;
    private final SharedPreferences storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        task.run();
    }, "aiko-filter"));
    private volatile Set<String> exclusions;
    volatile FilterRules rules = FilterRules.EMPTY;
    private boolean started, updating;

    SiteFilter(Context context) {
        app = context.getApplicationContext();
        storage = app.getSharedPreferences("site_filter", Context.MODE_PRIVATE);
        exclusions = new HashSet<>(storage.getStringSet("allow", Collections.emptySet()));
    }

    void load(Runnable ready) {
        if (started) return;
        started = true;
        worker.execute(() -> {
            try {
                rules = compile(loadCustom());
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not load filter lists", e);
            }
            main.post(ready);
        });
    }

    interface RefreshResult { void done(int refreshed, boolean applied); }

    boolean update(RefreshResult callback) {
        if (updating) return false;
        updating = true;
        worker.execute(() -> {
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
                    rules = compile(loadCustom());
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
        });
        return true;
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
            int count = 0;
            String error = null;
            try {
                CustomFilterList list = CustomFilterList.parse(source);
                FilterRules next = compile(list.rules);
                AtomicFile file = customFile();
                FileOutputStream stream = file.startWrite();
                try {
                    stream.write(source.getBytes(StandardCharsets.UTF_8));
                    file.finishWrite(stream);
                } catch (IOException | RuntimeException failure) {
                    file.failWrite(stream);
                    throw failure;
                }
                rules = next;
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

    private FilterRules compile(String customRules) throws IOException {
        FilterRules.Builder builder = new FilterRules.Builder();
        builder.suffixes(new InputStreamReader(
                new GZIPInputStream(app.getAssets().open("filters/psl.txt.gz")), StandardCharsets.UTF_8));
        builder.read(new StringReader(customRules), "自定义规则");
        for (int i = 0; i < IDS.length; i++) {
            AtomicFile cache = cache(i);
            InputStream source;
            try {
                source = cache.openRead();
            } catch (FileNotFoundException e) {
                source = new GZIPInputStream(app.getAssets().open("filters/" + IDS[i] + ".txt.gz"));
            }
            builder.read(new InputStreamReader(source, StandardCharsets.UTF_8), TITLES[i]);
        }
        return builder.build();
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

    boolean enabled(String site) { return site != null && !exclusions.contains(site); }

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
        String scheme = uri.getScheme(), host = normal(uri.getHost());
        if (host == null || (!"http".equals(scheme) && !"https".equals(scheme))) return false;
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
        java.util.Map<String, String> headers = request.getRequestHeaders();
        String destination = header(headers, "Sec-Fetch-Dest"), relation = header(headers, "Sec-Fetch-Site");
        int type = FilterRules.requestType(destination, destination == null ? uri.getEncodedPath() : null);
        if (type == 0 && "empty".equals(destination)) {
            String mode = header(headers, "Sec-Fetch-Mode");
            if ("cors".equals(mode) || "same-origin".equals(mode)) type = 64;
        }
        FilterRules.Rule match = snapshot.blockingRule(page.host, decision.key, host, uri.toString(), type, relation);
        if (match == null) return false;
        page.trace.add(host, snapshot, match);
        return true;
    }

    private static String header(java.util.Map<String, String> headers, String name) {
        if (headers == null) return null;
        String value = headers.get(name);
        if (value != null) return value;
        for (java.util.Map.Entry<String, String> item : headers.entrySet()) {
            if (name.equalsIgnoreCase(item.getKey())) return item.getValue();
        }
        return null;
    }

    static android.webkit.WebResourceResponse blocked() {
        return new android.webkit.WebResourceResponse("text/plain", "UTF-8", 204,
                "No Content", Collections.emptyMap(), null);
    }

    void applyCss(android.webkit.WebView view, String url) {
        if (url == null || !url.equals(view.getUrl())) return;
        String site = normal(Uri.parse(url).getHost());
        if (!enabled(site)) return;
        String css = rules.css(site, url);
        if (css.isEmpty()) return;
        view.evaluateJavascript("(function(){if(location.href!==" + org.json.JSONObject.quote(url)
                + ")return;var s=document.getElementById('aiko-filter-style');"
                + "if(!s){s=document.createElement('style');s.id='aiko-filter-style';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=" + org.json.JSONObject.quote(css) + ";})();", null);
    }

    void setEnabled(String site, boolean enabled) {
        if (site == null) return;
        Set<String> next = new HashSet<>(exclusions);
        if (enabled) next.remove(site); else next.add(site);
        exclusions = next;
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
