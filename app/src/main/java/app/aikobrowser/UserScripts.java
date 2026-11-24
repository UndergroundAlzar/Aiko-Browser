package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.Process;
import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.JavaScriptExecutionWorld;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the normal profile's locally installed scripts and WebView registrations. */
public final class UserScripts {
    private static final String BRIDGE = "__aikoScriptValues";
    private static final String PREFS_LIST = "userscripts";
    private static final String PREFS_VALUES = "userscript_values";
    private static final String RELEASE = "0.3.0";
    private static final int MAX_SCRIPTS = 24, MAX_SOURCES = 2 * 1024 * 1024, MAX_VALUE_CHARS = 32768;
    private static final long FLUSH_DELAY_MS = 400L;
    private static final long REBIND_FALLBACK_MS = 600L;
    private static final ExecutorService REMOTE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
            catch (RuntimeException ignored) { }
            r.run();
        }, "aiko-script-remote");
        t.setDaemon(true);
        return t;
    });

    /** Result of fetching and parsing a remote .user.js install URL. */
    public static final class RemoteScript {
        public final String url, source, error;
        public final UserScript parsed;
        public final boolean isUpdate;
        public final String existingId;

        RemoteScript(String url, String source, String error, UserScript parsed,
                     boolean isUpdate, String existingId) {
            this.url = url;
            this.source = source;
            this.error = error;
            this.parsed = parsed;
            this.isUpdate = isUpdate;
            this.existingId = existingId;
        }
    }

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<WebView, List<Binding>> views = new IdentityHashMap<>();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<String, JSONObject> values = new HashMap<>();
    private final Set<Entry> pending = new HashSet<>();
    private final Set<Entry> dirtyBindings = new HashSet<>();
    private final ArrayDeque<WebView> rebindQueue = new ArrayDeque<>();
    private final Runnable flush = this::flushValues;
    private final Runnable rebindFallback = this::drainOneRebind;
    private final MessageQueue.IdleHandler rebindIdle = () -> {
        drainOneRebind();
        return !rebindQueue.isEmpty();
    };
    private volatile SharedPreferences listPrefs, valuePrefs;
    private volatile boolean preloadStarted;
    private volatile CountDownLatch preloadLatch;
    private volatile Preloaded preloaded;
    private boolean loaded, registrationFailed, rebindScheduled;
    private String runtime;
    private Boolean isolatedWorlds, earlyScripts;

    private static final class Preloaded {
        final List<Entry> entries;
        final String runtime;
        final Map<String, JSONObject> values;
        final Map<String, String> serializedValues;

        Preloaded(List<Entry> entries, String runtime, Map<String, JSONObject> values,
                  Map<String, String> serializedValues) {
            this.entries = entries;
            this.runtime = runtime;
            this.values = values;
            this.serializedValues = serializedValues;
        }
    }

    public static final class Entry {
        public final String id;
        public final UserScript script;
        public boolean enabled;
        String program;
        String quotedCode;
        String beforeValues, afterValues;
        String serializedValues;

        Entry(String id, UserScript script, boolean enabled) {
            this.id = id;
            this.script = script;
            this.enabled = enabled;
        }
    }

    private static final class Binding {
        final Entry entry;
        final JavaScriptExecutionWorld world;
        final boolean bridge, legacyStart;
        final WebViewCompat.WebMessageListener listener;
        Set<String> extraOrigins;
        ScriptHandler handle;

        Binding(Entry entry, JavaScriptExecutionWorld world, boolean bridge, boolean legacyStart,
                WebViewCompat.WebMessageListener listener) {
            this.entry = entry;
            this.world = world;
            this.bridge = bridge;
            this.legacyStart = legacyStart;
            this.listener = listener;
        }

        Set<String> activeOrigins() {
            if (extraOrigins == null || extraOrigins.isEmpty() || entry.script.origins.contains("*")) {
                return entry.script.origins;
            }
            Set<String> merged = new LinkedHashSet<>(entry.script.origins);
            merged.addAll(extraOrigins);
            return merged;
        }

        boolean addExtraOrigin(String origin) {
            if (entry.script.origins.contains("*") || entry.script.origins.contains(origin)) return false;
            if (extraOrigins == null) extraOrigins = new LinkedHashSet<>();
            return extraOrigins.add(origin);
        }

        void registerBridge(WebView view) {
            if (!bridge || listener == null) return;
            Set<String> allowed = activeOrigins();
            try {
                WebViewCompat.addWebMessageListener(view, BRIDGE, allowed, world, listener);
            } catch (IllegalArgumentException unsupportedOrigin) {
                WebViewCompat.addWebMessageListener(view, BRIDGE, Collections.singleton("*"), world, listener);
            }
        }

        void register(WebView view, String javascript, int event) {
            if (handle != null) {
                handle.remove();
                handle = null;
            }
            Set<String> allowed = activeOrigins();
            try {
                handle = legacyStart ? WebViewCompat.addDocumentStartJavaScript(view, javascript, allowed)
                        : WebViewCompat.addJavaScriptOnEvent(view, javascript, event, allowed, world);
            } catch (IllegalArgumentException unsupportedOrigin) {
                Set<String> fallback = Collections.singleton("*");
                handle = legacyStart ? WebViewCompat.addDocumentStartJavaScript(view, javascript, fallback)
                        : WebViewCompat.addJavaScriptOnEvent(view, javascript, event, fallback, world);
            }
        }

        void remove(WebView view) {
            try {
                if (handle != null) {
                    handle.remove();
                    handle = null;
                }
            } finally {
                if (bridge) WebViewCompat.removeWebMessageListener(view, world, BRIDGE);
            }
        }
    }

    public UserScripts(Context context) {
        app = context.getApplicationContext();
    }

    /** Detects HTTP(S) .user.js install URLs (Greasy Fork, Sleazy Fork, ScriptCat, OpenUserJS, GitHub raw, etc.). */
    public static boolean isUserScriptUrl(String url) {
        return UserScript.isUserScriptUrl(url);
    }

    /** Detects whether a Content-Disposition or download filename ends with .user.js. */
    public static boolean isUserScriptFilename(String filename) {
        return UserScript.isUserScriptFilename(filename);
    }

    /**
     * Asynchronously downloads (15s timeout, 512 KiB max) and parses a remote .user.js script.
     * Always invokes onDone on the main thread.
     */
    public void fetchRemoteScript(String url, Consumer<RemoteScript> onDone) {
        if (onDone == null) return;
        if (UserScriptInput.safeRemoteAddress(url) == null) {
            handler.post(() -> onDone.accept(new RemoteScript(url, null, "无效的脚本链接", null, false, null)));
            return;
        }
        REMOTE_EXEC.execute(() -> {
            String downloadedSource = null;
            try {
                UserScriptInput.Downloaded downloaded = UserScriptInput.downloadRemote(url, UserScript.MAX_BYTES);
                downloadedSource = downloaded.source;
                UserScript parsed = UserScript.parse(downloadedSource);
                final String finalSource = downloadedSource;
                handler.post(() -> {
                    Entry existing = findExisting(parsed);
                    onDone.accept(new RemoteScript(
                            url, finalSource, null, parsed,
                            existing != null, existing != null ? existing.id : null));
                });
            } catch (IOException | RuntimeException error) {
                final String src = downloadedSource;
                String msg = error.getMessage();
                final String reason = (msg == null || msg.trim().isEmpty()) ? "无法读取脚本，请检查网络连接" : msg;
                handler.post(() -> onDone.accept(new RemoteScript(url, src, reason, null, false, null)));
            }
        });
    }

    /**
     * Installs or updates a fetched remote script and enables it immediately.
     * Updating preserves the existing script ID and all saved GM_setValue storage.
     * Invokes onError on the main thread with null on success or a readable error message.
     */
    public void installRemote(RemoteScript remote, Consumer<String> onError) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> installRemote(remote, onError));
            return;
        }
        if (remote == null || remote.parsed == null || remote.error != null) {
            String msg = (remote != null && remote.error != null) ? remote.error : "无效的脚本数据";
            postError(onError, msg);
            return;
        }
        try {
            load();
            UserScript script = remote.parsed;
            Entry previous = null;
            if (remote.existingId != null) {
                for (Entry item : entries) {
                    if (item.id.equals(remote.existingId)) {
                        previous = item;
                        break;
                    }
                }
            }
            if (previous == null) previous = findExisting(script);

            int totalChars = script.source.length();
            for (Entry item : entries) if (item != previous) totalChars += item.script.source.length();
            if (totalChars > MAX_SOURCES || (previous == null && entries.size() >= MAX_SCRIPTS)) {
                postError(onError, "最多保存 24 个脚本，源码总量不能超过约 2 MiB。");
                return;
            }
            Entry collision = duplicate(script);
            if (collision != null && collision != previous) {
                postError(onError, "已有同名、同命名空间的脚本。");
                return;
            }
            Entry next = new Entry(previous == null ? UUID.randomUUID().toString() : previous.id, script, true);
            String incompat = compatibility(next);
            if (!incompat.isEmpty()) {
                postError(onError, incompat);
                return;
            }
            flushValues();
            if (previous != null && script.needsStorage() && previous.serializedValues != null) {
                next.serializedValues = previous.serializedValues;
            }
            if (previous == null) entries.add(next);
            else entries.set(entries.indexOf(previous), next);
            saveList();
            refreshViews();
            postError(onError, null);
        } catch (RuntimeException error) {
            String msg = error.getMessage();
            postError(onError, (msg == null || msg.trim().isEmpty()) ? "安装脚本失败" : msg);
        }
    }

    /** Returns all currently enabled scripts whose @match/@include rules match the given URL. */
    public List<UserScript> matchingForUrl(String url) {
        URI address = UserScript.address(url);
        if (address == null) return Collections.emptyList();
        load();
        ArrayList<UserScript> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.enabled && entry.script.matches(address)) {
                matched.add(entry.script);
            }
        }
        return matched;
    }

    private void postError(Consumer<String> onError, String message) {
        if (onError != null) handler.post(() -> onError.accept(message));
    }

    private Entry findExisting(UserScript parsed) {
        load();
        for (Entry entry : entries) {
            if (entry.script.name.equals(parsed.name) && entry.script.namespace.equals(parsed.namespace)) {
                return entry;
            }
        }
        if (parsed.namespace.isEmpty()) {
            for (Entry entry : entries) {
                if (entry.script.name.equals(parsed.name)) return entry;
            }
        }
        return null;
    }

    /** Preload saved scripts, values, and runtime.js off the UI thread. */
    public void preloadAsync() {
        if (loaded || preloadStarted) return;
        preloadStarted = true;
        CountDownLatch latch = new CountDownLatch(1);
        preloadLatch = latch;
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
            } catch (RuntimeException ignored) { }
            try {
                SharedPreferences lp = listPrefs();
                ArrayList<Entry> parsed = parseList(lp.getString("list", "[]"));
                boolean anyEnabled = false;
                for (Entry entry : parsed) {
                    if (entry.enabled) {
                        anyEnabled = true;
                        break;
                    }
                }
                String loadedRuntime = null;
                Map<String, JSONObject> loadedValues = Collections.emptyMap();
                Map<String, String> loadedSerialized = Collections.emptyMap();
                if (anyEnabled) {
                    loadedRuntime = readRuntimeAsset();
                    SharedPreferences vp = valuePrefs();
                    loadedValues = new HashMap<>();
                    loadedSerialized = new HashMap<>();
                    for (Entry entry : parsed) {
                        if (!entry.enabled) continue;
                        entry.quotedCode = JSONObject.quote(entry.script.code);
                        if (entry.script.needsStorage()) {
                            String raw = readRawValues(vp, lp, entry.id);
                            JSONObject obj;
                            try { obj = new JSONObject(raw); }
                            catch (JSONException ignored) { obj = new JSONObject(); raw = "{}"; }
                            loadedValues.put(entry.id, obj);
                            loadedSerialized.put(entry.id, raw);
                        }
                    }
                }
                preloaded = new Preloaded(parsed, loadedRuntime, loadedValues, loadedSerialized);
            } catch (IOException | RuntimeException error) {
                Log.w("AikoScripts", "Could not preload userscripts", error);
            } finally {
                latch.countDown();
                handler.post(this::applyPreloadedOnMain);
            }
        }, "aiko-scripts-preload");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyPreloadedOnMain() {
        if (loaded) return;
        if (!adoptPreloaded()) {
            load();
        }
        if (!hasEnabled() || views.isEmpty()) return;
        for (Map.Entry<WebView, List<Binding>> item : views.entrySet()) {
            if (item.getValue().isEmpty()) {
                register(item.getKey(), item.getValue());
            }
        }
    }

    private boolean adoptPreloaded() {
        Preloaded snap = preloaded;
        if (snap == null) return false;
        preloaded = null;
        loaded = true;
        entries.clear();
        entries.addAll(snap.entries);
        if (runtime == null && snap.runtime != null) runtime = snap.runtime;
        for (Map.Entry<String, JSONObject> item : snap.values.entrySet()) {
            values.putIfAbsent(item.getKey(), item.getValue());
        }
        for (Entry entry : entries) {
            String raw = snap.serializedValues.get(entry.id);
            if (raw != null && entry.serializedValues == null) entry.serializedValues = raw;
        }
        return true;
    }

    List<Entry> items() { load(); return new ArrayList<>(entries); }

    Entry duplicate(UserScript script) {
        load();
        for (Entry entry : entries) {
            if (entry.script.name.equals(script.name) && entry.script.namespace.equals(script.namespace)) return entry;
        }
        return null;
    }

    Entry save(UserScript script, Entry previous) {
        load();
        int count = script.source.length();
        for (Entry item : entries) if (item != previous) count += item.script.source.length();
        if (count > MAX_SOURCES || (previous == null && entries.size() >= MAX_SCRIPTS))
            throw new IllegalArgumentException("最多保存 24 个脚本，源码总量不能超过约 2 MiB。");
        Entry collision = duplicate(script);
        if (collision != null && collision != previous)
            throw new IllegalArgumentException("已有同名、同命名空间的脚本。");
        flushValues();
        Entry next = new Entry(previous == null ? UUID.randomUUID().toString() : previous.id, script, false);
        if (previous == null) entries.add(next); else entries.set(entries.indexOf(previous), next);
        saveList();
        refreshViews();
        return next;
    }

    void setEnabled(Entry entry, boolean enabled) {
        load();
        if (!entries.contains(entry)) return;
        entry.enabled = enabled;
        saveList();
        refreshViews();
    }

    void delete(Entry entry) {
        load();
        if (!entries.remove(entry)) return;
        pending.remove(entry);
        dirtyBindings.remove(entry);
        values.remove(entry.id);
        String key = "values-" + entry.id;
        valuePrefs().edit().remove(key).apply();
        SharedPreferences lp = listPrefs();
        if (lp.contains(key)) lp.edit().remove(key).apply();
        saveList();
        refreshViews();
    }

    void attach(WebView view) {
        List<Binding> bindings = new ArrayList<>();
        views.put(view, bindings);
        // Empty installs never query the provider's script capabilities; finish any in-flight
        // preload before returning so scripts and bridges are registered prior to loadUrl().
        if (hasEnabled()) register(view, bindings);
    }

    void detach(WebView view) {
        views.remove(view);
        rebindQueue.remove(view);
    }

    void pageFinished(WebView view, String url) {
        if (!hasEnabled() || !views.containsKey(view) || !Objects.equals(url, view.getUrl())) return;
        boolean worlds = isolatedWorlds();
        boolean early = earlyScripts();
        if (!worlds && !early) {
            URI address = UserScript.address(url);
            if (address == null) return;
            for (Entry entry : entries) {
                UserScript script = entry.script;
                if (entry.enabled && !script.requiresIsolation && !script.runAt.equals("document-start")
                        && script.matches(address)) {
                    try {
                        view.evaluateJavascript(payload(entry), null);
                    } catch (IOException | RuntimeException error) {
                        registrationFailed = true;
                        Log.w("AikoScripts", "Could not inject end-of-load userscript");
                    }
                }
            }
            return;
        }
        // Fast check: 99.99% of URLs use default ports and are already covered by narrowed origins.
        if (!hasExplicitPortInAuthority(url)) return;
        URI address = UserScript.address(url);
        if (address == null) return;
        int port = address.getPort();
        String scheme = address.getScheme().toLowerCase(Locale.ROOT);
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        if (port < 0 || port == defaultPort) return;
        String origin = scheme + "://" + address.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        List<Binding> bindings = views.get(view);
        if (bindings == null) return;
        for (Binding binding : bindings) {
            UserScript script = binding.entry.script;
            if (!binding.entry.enabled || !script.anyPort || script.origins.contains("*")
                    || !script.matches(address)) continue;
            if (binding.addExtraOrigin(origin)) {
                try {
                    if (binding.bridge) {
                        WebViewCompat.removeWebMessageListener(view, binding.world, BRIDGE);
                        binding.registerBridge(view);
                    }
                    binding.register(view, payload(binding.entry), event(script));
                    if (!script.requiresIsolation && !script.runAt.equals("document-start")) {
                        view.evaluateJavascript(payload(binding.entry), null);
                    }
                } catch (IOException | RuntimeException | LinkageError error) {
                    Log.w("AikoScripts", "Could not expand non-default port userscript origin");
                }
            }
        }
    }

    private static boolean hasExplicitPortInAuthority(String url) {
        if (url == null) return false;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return false;
        int start = schemeEnd + 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int lastColon = url.lastIndexOf(':', end - 1);
        if (lastColon < start) return false;
        int bracket = url.lastIndexOf(']', end - 1);
        return lastColon > bracket;
    }

    String compatibility(Entry entry) {
        if (isolatedWorlds()) return "";
        if (entry.script.requiresIsolation) return "当前系统 WebView 缺少隔离执行支持。更新 WebView 后可运行。";
        if (entry.script.runAt.equals("document-start") && !earlyScripts())
            return "当前系统 WebView 不支持 document-start。更新 WebView 后可运行。";
        return "";
    }

    String status() {
        if (registrationFailed) return "部分脚本未注册成功。可在详情中检查相容性。";
        if (isolatedWorlds()) return "隔离执行可用";
        return "旧版 WebView · 仅运行无授权脚本";
    }

    private boolean hasEnabled() {
        load();
        for (Entry entry : entries) if (entry.enabled) return true;
        return false;
    }

    private void refreshViews() {
        registrationFailed = false;
        dirtyBindings.clear();
        rebindQueue.clear();
        cancelRebindSchedule();
        for (Map.Entry<WebView, List<Binding>> item : views.entrySet()) {
            for (Binding binding : item.getValue()) {
                try { binding.remove(item.getKey()); }
                catch (RuntimeException | LinkageError error) {
                    registrationFailed = true;
                    Log.w("AikoScripts", "Could not remove userscript registration");
                }
            }
            item.getValue().clear();
            if (hasEnabled()) register(item.getKey(), item.getValue());
        }
    }

    private void register(WebView view, List<Binding> output) {
        boolean worlds = isolatedWorlds();
        boolean legacyStart = !worlds && earlyScripts();
        if (!worlds && !legacyStart) return;
        for (Entry entry : entries) {
            if (!entry.enabled || entry.script.requiresIsolation && !worlds) continue;
            Binding binding = null;
            try {
                JavaScriptExecutionWorld world = worlds ? WebViewCompat.getExecutionWorld(view,
                        entry.script.pageWorld ? JavaScriptExecutionWorld.PAGE_WORLD_NAME : "aiko-" + entry.id) : null;
                boolean bridge = worlds && entry.script.needsStorage();
                WebViewCompat.WebMessageListener listener = bridge ? (source, message, origin, main, reply) -> {
                    if (main && source == view && views.containsKey(view)
                            && message.getType() == WebMessageCompat.TYPE_STRING)
                        receive(entry, message.getData(), origin.toString());
                } : null;
                binding = new Binding(entry, world, bridge, legacyStart, listener);
                if (bridge) binding.registerBridge(view);
                binding.register(view, payload(entry), event(entry.script));
                output.add(binding);
            } catch (IOException | RuntimeException | LinkageError error) {
                registrationFailed = true;
                if (binding != null) {
                    try { binding.remove(view); } catch (RuntimeException | LinkageError ignored) {}
                }
                Log.w("AikoScripts", "Could not register userscript");
            }
        }
    }

    private static int event(UserScript script) {
        return script.runAt.equals("document-end") || script.runAt.equals("document-idle")
                ? WebViewCompat.INJECTION_EVENT_DOCUMENT_END : WebViewCompat.INJECTION_EVENT_DOCUMENT_START;
    }

    private boolean isolatedWorlds() {
        if (isolatedWorlds == null) isolatedWorlds = supports(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD);
        return isolatedWorlds;
    }

    private boolean earlyScripts() {
        if (earlyScripts == null) earlyScripts = supports(WebViewFeature.DOCUMENT_START_SCRIPT);
        return earlyScripts;
    }

    private static boolean supports(String feature) {
        try { return WebViewFeature.isFeatureSupported(feature); }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    private void receive(Entry entry, String message, String sourceOrigin) {
        if (!entry.enabled || !entries.contains(entry) || message == null || message.length() > 76000) return;
        try {
            JSONArray data = new JSONArray(message);
            if (data.length() < 3 || data.length() > 4) return;
            URI address = UserScript.address(data.getString(0));
            if (!UserScript.sameOrigin(address, UserScript.address(sourceOrigin)) || !entry.script.matches(address)) return;
            String op = data.getString(1), key = data.getString(2);
            if (key.length() > 128) return;
            JSONObject current = values(entry);
            String serialized;
            if (op.equals("set") && data.length() == 4 && entry.script.permits("setValue")) {
                String encoded = data.getString(3);
                if (encoded.length() > MAX_VALUE_CHARS) return;
                JSONTokener tokener = new JSONTokener(encoded);
                Object incoming = tokener.nextValue();
                if (tokener.nextClean() != 0) return;
                Object previous = current.opt(key);
                current.put(key, incoming);
                if (current.length() > 128) {
                    if (previous == null) current.remove(key); else current.put(key, previous);
                    return;
                }
                serialized = current.toString();
                if (serialized.length() > MAX_VALUE_CHARS) {
                    if (previous == null) current.remove(key); else current.put(key, previous);
                    return;
                }
            } else if (op.equals("delete") && data.length() == 3 && entry.script.permits("deleteValue")) {
                if (current.remove(key) == null) return;
                serialized = current.toString();
            } else {
                return;
            }
            entry.serializedValues = serialized;
            entry.program = null;
            if (pending.isEmpty()) handler.postDelayed(flush, FLUSH_DELAY_MS);
            pending.add(entry);
        } catch (JSONException | RuntimeException invalid) {
            Log.d("AikoScripts", "Invalid userscript storage message");
        }
    }

    void flushValues() {
        handler.removeCallbacks(flush);
        if (pending.isEmpty()) return;
        SharedPreferences.Editor edit = valuePrefs().edit();
        for (Entry entry : pending) {
            String serialized = entry.serializedValues != null ? entry.serializedValues : values(entry).toString();
            entry.serializedValues = serialized;
            edit.putString("values-" + entry.id, serialized);
            entry.program = null;
            dirtyBindings.add(entry);
        }
        edit.apply();
        pending.clear();
        // New documents receive fresh synchronous GM_getValue snapshots without stalling active scroll frames.
        scheduleIncrementalRebind();
    }

    private void scheduleIncrementalRebind() {
        if (dirtyBindings.isEmpty() || views.isEmpty()) {
            dirtyBindings.clear();
            return;
        }
        rebindQueue.clear();
        // Prioritize the currently visible WebView first so the active tab updates on the next idle slice.
        for (WebView view : views.keySet()) {
            if (view.getVisibility() == WebView.VISIBLE && view.isAttachedToWindow()) {
                rebindQueue.addFirst(view);
            } else {
                rebindQueue.addLast(view);
            }
        }
        if (!rebindScheduled) {
            rebindScheduled = true;
            Looper.myQueue().addIdleHandler(rebindIdle);
        }
        handler.removeCallbacks(rebindFallback);
        handler.postDelayed(rebindFallback, REBIND_FALLBACK_MS);
    }

    private void cancelRebindSchedule() {
        if (!rebindScheduled) return;
        rebindScheduled = false;
        Looper.myQueue().removeIdleHandler(rebindIdle);
        handler.removeCallbacks(rebindFallback);
    }

    private void drainOneRebind() {
        handler.removeCallbacks(rebindFallback);
        while (!rebindQueue.isEmpty()) {
            WebView view = rebindQueue.pollFirst();
            List<Binding> bindings = views.get(view);
            if (bindings == null) continue;
            boolean updated = false;
            for (Binding binding : bindings) {
                if (!binding.bridge || !dirtyBindings.contains(binding.entry)) continue;
                try {
                    binding.register(view, payload(binding.entry), event(binding.entry.script));
                    updated = true;
                } catch (IOException | RuntimeException | LinkageError error) {
                    registrationFailed = true;
                    Log.w("AikoScripts", "Could not refresh userscript storage snapshot");
                }
            }
            if (updated) break;
        }
        if (rebindQueue.isEmpty()) {
            dirtyBindings.clear();
            cancelRebindSchedule();
        } else {
            handler.postDelayed(rebindFallback, REBIND_FALLBACK_MS);
        }
    }

    private SharedPreferences listPrefs() {
        SharedPreferences p = listPrefs;
        if (p == null) {
            p = app.getSharedPreferences(PREFS_LIST, Context.MODE_PRIVATE);
            listPrefs = p;
        }
        return p;
    }

    private SharedPreferences valuePrefs() {
        SharedPreferences p = valuePrefs;
        if (p == null) {
            p = app.getSharedPreferences(PREFS_VALUES, Context.MODE_PRIVATE);
            valuePrefs = p;
        }
        return p;
    }

    private static String readRawValues(SharedPreferences vp, SharedPreferences lp, String id) {
        String key = "values-" + id;
        String raw = vp.getString(key, null);
        if (raw == null) raw = lp.getString(key, null);
        return raw == null ? "{}" : raw;
    }

    private JSONObject values(Entry entry) {
        JSONObject existing = values.get(entry.id);
        if (existing != null) return existing;
        String raw = readRawValues(valuePrefs(), listPrefs(), entry.id);
        try { existing = new JSONObject(raw); }
        catch (JSONException ignored) { existing = new JSONObject(); raw = "{}"; }
        values.put(entry.id, existing);
        if (entry.serializedValues == null) entry.serializedValues = raw;
        return existing;
    }

    private String readRuntimeAsset() throws IOException {
        try (InputStream stream = app.getAssets().open("userscripts/runtime.js")) {
            return UserScriptInput.read(stream, 65536);
        }
    }

    private String payload(Entry entry) throws IOException {
        if (entry.program != null) return entry.program;
        if (runtime == null) runtime = readRuntimeAsset();
        UserScript script = entry.script;
        if (entry.quotedCode == null) entry.quotedCode = JSONObject.quote(script.code);
        try {
            if (entry.beforeValues == null) {
                JSONObject staticConfig = new JSONObject()
                        .put("id", entry.id).put("release", RELEASE).put("runAt", script.runAt)
                        .put("includes", patterns(script.includes)).put("excludes", patterns(script.excludes))
                        .put("grants", new JSONArray(script.grants)).put("storage", script.needsStorage())
                        .put("script", new JSONObject().put("name", script.name).put("namespace", script.namespace)
                                .put("version", script.version));
                if (!script.needsStorage()) staticConfig.put("values", new JSONObject());
                String guard = fastGuard(script);
                if (script.needsStorage()) {
                    entry.beforeValues = "(function(){if(window.top!==window)return;" + guard + "(" + runtime
                            + ")(Object.assign(JSON.parse(" + JSONObject.quote(staticConfig.toString())
                            + "),{values:JSON.parse(";
                    entry.afterValues = ")})," + entry.quotedCode + ");})();";
                } else {
                    entry.program = "(function(){if(window.top!==window)return;" + guard + "(" + runtime
                            + ")(JSON.parse(" + JSONObject.quote(staticConfig.toString()) + "),"
                            + entry.quotedCode + ");})();";
                    return entry.program;
                }
            }
            String valuesJson = entry.serializedValues != null ? entry.serializedValues : values(entry).toString();
            entry.serializedValues = valuesJson;
            entry.program = entry.beforeValues + JSONObject.quote(valuesJson) + entry.afterValues;
            return entry.program;
        } catch (JSONException malformed) {
            throw new IOException("Invalid userscript metadata", malformed);
        }
    }

    private static String fastGuard(UserScript script) {
        StringBuilder out = new StringBuilder();
        boolean allHttps = true, allHttp = true, anyHost = false;
        for (UserScript.UrlPattern rule : script.includes) {
            if (!"https".equals(rule.scheme)) allHttps = false;
            if (!"http".equals(rule.scheme)) allHttp = false;
            if ("*".equals(rule.host)) anyHost = true;
        }
        if (allHttps) out.append("if(location.protocol!==\"https:\")return;");
        else if (allHttp) out.append("if(location.protocol!==\"http:\")return;");
        else out.append("if(location.protocol!==\"https:\"&&location.protocol!==\"http:\")return;");
        if (anyHost) return out.toString();

        LinkedHashMap<String, Boolean> hosts = new LinkedHashMap<>();
        for (UserScript.UrlPattern rule : script.includes) {
            Boolean existing = hosts.get(rule.host);
            if (existing == null || (!existing && rule.subdomains)) hosts.put(rule.host, rule.subdomains);
        }
        out.append("var h=location.hostname.toLowerCase();if(h.endsWith(\".\"))h=h.slice(0,-1);if(!(");
        boolean first = true;
        for (Map.Entry<String, Boolean> item : hosts.entrySet()) {
            if (!first) out.append("||");
            first = false;
            String host = item.getKey();
            String qHost = JSONObject.quote(host);
            if (item.getValue()) {
                out.append("h===").append(qHost).append("||h.endsWith(").append(JSONObject.quote("." + host)).append(')');
            } else {
                out.append("h===").append(qHost);
            }
        }
        out.append("))return;");
        return out.toString();
    }

    private static JSONArray patterns(List<UserScript.UrlPattern> rules) {
        JSONArray result = new JSONArray();
        for (UserScript.UrlPattern rule : rules) {
            result.put(new JSONArray().put(rule.scheme).put(rule.host).put(rule.subdomains).put(rule.port).put(rule.path));
        }
        return result;
    }

    private static ArrayList<Entry> parseList(String raw) {
        ArrayList<Entry> parsed = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(raw);
            for (int i = 0; i < Math.min(list.length(), MAX_SCRIPTS); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    String id = UUID.fromString(item.getString("id")).toString();
                    parsed.add(new Entry(id, UserScript.parse(item.getString("source")), item.optBoolean("enabled")));
                } catch (JSONException | IllegalArgumentException invalid) {
                    Log.w("AikoScripts", "Ignored invalid saved userscript");
                }
            }
        } catch (JSONException invalid) {
            Log.w("AikoScripts", "Ignored invalid saved userscript list");
        }
        return parsed;
    }

    private void load() {
        if (loaded) return;
        if (adoptPreloaded()) return;
        CountDownLatch latch = preloadLatch;
        if (latch != null) {
            try {
                latch.await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (adoptPreloaded()) return;
        }
        loaded = true;
        entries.addAll(parseList(listPrefs().getString("list", "[]")));
    }

    private void saveList() {
        JSONArray list = new JSONArray();
        for (Entry entry : entries) {
            try {
                list.put(new JSONObject().put("id", entry.id).put("source", entry.script.source)
                        .put("enabled", entry.enabled));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        listPrefs().edit().putString("list", list.toString()).apply();
    }
}
