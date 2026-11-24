package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.Process;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.JavaScriptExecutionWorld;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the normal profile's locally installed scripts, dependency cache, XHR bridge, menu commands, and WebView registrations. */
public final class UserScripts {
    private static final String BRIDGE = "__aikoScriptValues";
    private static final String PREFS_LIST = "userscripts";
    private static final String PREFS_VALUES = "userscript_values";
    private static final String PREFS_CONNECTS = "userscript_connects";
    private static final String CACHE_DIR_NAME = "userscript_cache";
    private static final String RELEASE = "0.4.0";
    private static final String XHR_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) AikoBrowser/0.4.0";

    private static final int MAX_SCRIPTS = 48, MAX_SOURCES = 4 * 1024 * 1024, MAX_VALUE_CHARS = 65536;
    private static final int MAX_MESSAGE_CHARS = 2 * 1024 * 1024;
    private static final int MAX_DEP_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SCRIPT_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_XHR_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MENU_COMMANDS_PER_SCRIPT = 24;
    private static final long FLUSH_DELAY_MS = 400L;
    private static final long REBIND_FALLBACK_MS = 600L;
    private static final Set<String> ALLOWED_XHR_METHODS = new HashSet<>(Arrays.asList(
            "GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ExecutorService REMOTE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
            catch (RuntimeException ignored) { }
            r.run();
        }, "aiko-script-remote");
        t.setDaemon(true);
        return t;
    });

    private static final ThreadPoolExecutor XHR_EXEC;
    static {
        XHR_EXEC = new ThreadPoolExecutor(
                2, 6, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(() -> {
                        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                        catch (RuntimeException ignored) { }
                        r.run();
                    }, "aiko-script-xhr");
                    t.setDaemon(true);
                    return t;
                });
        XHR_EXEC.allowCoreThreadTimeOut(true);
    }

    /** Optional host callbacks (GM_download, @connect approval prompt, menu updates). All methods have safe defaults. */
    public interface HostActions {
        default void downloadUrl(String url, String filename) {}
        default void openInTab(String url) {}
        default void requestConnectApproval(String scriptId, String scriptName, String host, Consumer<Boolean> decision) {
            if (decision != null) decision.accept(false);
        }
        default void onMenuCommandsChanged(WebView view) {}
    }

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

    /** Live menu command registered via GM_registerMenuCommand on a specific WebView. */
    public static final class MenuCommand {
        public final String scriptId, scriptName, cmdId, caption;

        public MenuCommand(String scriptId, String scriptName, String cmdId, String caption) {
            this.scriptId = scriptId;
            this.scriptName = scriptName;
            this.cmdId = cmdId;
            this.caption = caption;
        }
    }

    /** Available remote update discovered by checkUpdates(). */
    public static final class UpdateResult {
        public final String id, name, oldVersion, newVersion, downloadUrl;
        public final UserScript updated;

        public UpdateResult(String id, String name, String oldVersion, String newVersion,
                            String downloadUrl, UserScript updated) {
            this.id = id;
            this.name = name;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.downloadUrl = downloadUrl;
            this.updated = updated;
        }
    }

    public interface UpdateCallback {
        void onComplete(List<UpdateResult> updates, int checkedCount, int failedCount);
    }

    private static final class MenuEntry {
        final MenuCommand command;
        final String localCmdId;

        MenuEntry(MenuCommand command, String localCmdId) {
            this.command = command;
            this.localCmdId = localCmdId;
        }
    }

    private static final class XhrTask {
        final WebView view;
        final String entryId;
        final String reqId;
        volatile boolean aborted;
        volatile HttpURLConnection connection;
        volatile Future<?> future;

        XhrTask(WebView view, String entryId, String reqId) {
            this.view = view;
            this.entryId = entryId;
            this.reqId = reqId;
        }

        void cancel() {
            aborted = true;
            HttpURLConnection c = connection;
            if (c != null) {
                try { c.disconnect(); } catch (RuntimeException ignored) { }
            }
            Future<?> f = future;
            if (f != null) f.cancel(true);
        }
    }

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<WebView, List<Binding>> views = new IdentityHashMap<>();
    private final Map<WebView, Map<String, JavaScriptReplyProxy>> replyProxies = new IdentityHashMap<>();
    private final Map<WebView, LinkedHashMap<String, MenuEntry>> menuRegistry = new IdentityHashMap<>();
    private final ConcurrentHashMap<String, XhrTask> activeXhrs = new ConcurrentHashMap<>();
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
    private volatile SharedPreferences listPrefs, valuePrefs, connectPrefs;
    private volatile HostActions hostActions;
    private volatile boolean preloadStarted;
    private volatile CountDownLatch preloadLatch;
    private volatile Preloaded preloaded;
    private boolean loaded, registrationFailed, rebindScheduled;
    private String runtime;
    private Boolean isolatedWorlds, earlyScripts, webMessageSupport;

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
        public String sourceUrl;
        String program;
        String quotedCode;
        JSONObject cachedResources;
        String beforeValues, afterValues;
        String serializedValues;
        String sessionToken;
        String bridgeName;
        boolean cachedEphemeral;

        Entry(String id, UserScript script, boolean enabled) {
            this(id, script, enabled, "");
        }

        Entry(String id, UserScript script, boolean enabled, String sourceUrl) {
            this.id = id;
            this.script = script;
            this.enabled = enabled;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        }
    }

    private static final class Binding {
        final Entry entry;
        final JavaScriptExecutionWorld world;
        final String bridgeName;
        final String token;
        final boolean bridge, legacyStart;
        final WebViewCompat.WebMessageListener listener;
        Set<String> extraOrigins;
        ScriptHandler handle;

        Binding(Entry entry, JavaScriptExecutionWorld world, String bridgeName, String token,
                boolean bridge, boolean legacyStart, WebViewCompat.WebMessageListener listener) {
            this.entry = entry;
            this.world = world;
            this.bridgeName = bridgeName;
            this.token = token;
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
                if (world != null) WebViewCompat.addWebMessageListener(view, bridgeName, allowed, world, listener);
                else WebViewCompat.addWebMessageListener(view, bridgeName, allowed, listener);
            } catch (IllegalArgumentException unsupportedOrigin) {
                Set<String> all = Collections.singleton("*");
                if (world != null) WebViewCompat.addWebMessageListener(view, bridgeName, all, world, listener);
                else WebViewCompat.addWebMessageListener(view, bridgeName, all, listener);
            }
        }

        void register(WebView view, String javascript, int event) {
            if (handle != null) {
                handle.remove();
                handle = null;
            }
            Set<String> allowed = activeOrigins();
            try {
                handle = (legacyStart || world == null)
                        ? WebViewCompat.addDocumentStartJavaScript(view, javascript, allowed)
                        : WebViewCompat.addJavaScriptOnEvent(view, javascript, event, allowed, world);
            } catch (IllegalArgumentException unsupportedOrigin) {
                Set<String> fallback = Collections.singleton("*");
                handle = (legacyStart || world == null)
                        ? WebViewCompat.addDocumentStartJavaScript(view, javascript, fallback)
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
                if (bridge) {
                    if (world != null) WebViewCompat.removeWebMessageListener(view, world, bridgeName);
                    else WebViewCompat.removeWebMessageListener(view, bridgeName);
                }
            }
        }
    }

    public UserScripts(Context context) {
        app = context.getApplicationContext();
    }

    public void setHostActions(HostActions actions) {
        this.hostActions = actions;
    }

    /** Detects HTTP(S) .user.js install URLs (Greasy Fork, Sleazy Fork, ScriptCat, OpenUserJS, GitHub raw, etc.). */
    public static boolean isUserScriptUrl(String url) {
        return UserScript.isUserScriptUrl(url);
    }

    /** Detects whether a Content-Disposition or download filename ends with .user.js. */
    public static boolean isUserScriptFilename(String filename) {
        return UserScript.isUserScriptFilename(filename);
    }

    /** Persists a user-approved @connect host for a specific script. */
    public void approveConnectHost(String scriptId, String host) {
        if (scriptId == null || scriptId.isEmpty()) return;
        String norm = UserScript.normalizeConnectRule(host);
        if (norm == null) return;
        Set<String> existing = new LinkedHashSet<>(approvedConnectHosts(scriptId));
        if (existing.add(norm)) {
            saveApprovedConnectHosts(scriptId, existing);
        }
    }

    /** Revokes a previously approved @connect host for a script. */
    public void revokeConnectHost(String scriptId, String host) {
        if (scriptId == null || scriptId.isEmpty()) return;
        String norm = UserScript.normalizeConnectRule(host);
        if (norm == null) return;
        Set<String> existing = new LinkedHashSet<>(approvedConnectHosts(scriptId));
        if (existing.remove(norm)) {
            saveApprovedConnectHosts(scriptId, existing);
        }
    }

    /** Returns the persisted user-approved @connect hosts for a script. */
    public Set<String> approvedConnectHosts(String scriptId) {
        if (scriptId == null || scriptId.isEmpty()) return Collections.emptySet();
        String raw = connectPrefs().getString("approved-" + scriptId, null);
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String h = arr.optString(i, "").trim();
                if (!h.isEmpty()) out.add(h);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    private void saveApprovedConnectHosts(String scriptId, Set<String> hosts) {
        JSONArray arr = new JSONArray();
        for (String h : hosts) arr.put(h);
        connectPrefs().edit().putString("approved-" + scriptId, arr.toString()).apply();
    }

    /** Returns live menu commands registered via GM_registerMenuCommand on the given WebView. */
    public List<MenuCommand> menuCommandsFor(WebView view) {
        if (view == null) return Collections.emptyList();
        LinkedHashMap<String, MenuEntry> map = menuRegistry.get(view);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        ArrayList<MenuCommand> out = new ArrayList<>(map.size());
        for (MenuEntry item : map.values()) out.add(item.command);
        return out;
    }

    /** Invokes a registered GM_registerMenuCommand callback inside its script world. */
    public void invokeMenuCommand(WebView view, String cmdId) {
        if (view == null || cmdId == null || cmdId.isEmpty()) return;
        LinkedHashMap<String, MenuEntry> map = menuRegistry.get(view);
        if (map == null) return;
        MenuEntry item = map.get(cmdId);
        if (item == null) return;
        Map<String, JavaScriptReplyProxy> proxies = replyProxies.get(view);
        if (proxies == null) return;
        JavaScriptReplyProxy proxy = proxies.get(item.command.scriptId);
        if (proxy == null) return;
        try {
            String msg = new JSONObject()
                    .put("type", "menu_invoke")
                    .put("cmdId", item.localCmdId)
                    .toString();
            proxy.postMessage(msg);
        } catch (JSONException | RuntimeException | LinkageError stale) {
            proxies.remove(item.command.scriptId);
        }
    }

    /** Clears live menu commands for a WebView (e.g., on navigation start). */
    public void clearMenuCommands(WebView view) {
        if (view == null) return;
        LinkedHashMap<String, MenuEntry> removed = menuRegistry.remove(view);
        if (removed != null && !removed.isEmpty()) notifyMenuChanged(view);
    }

    private void clearMenuCommandsForScript(WebView view, String scriptId) {
        LinkedHashMap<String, MenuEntry> map = menuRegistry.get(view);
        if (map == null || map.isEmpty()) return;
        boolean changed = false;
        Iterator<Map.Entry<String, MenuEntry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (scriptId.equals(it.next().getValue().command.scriptId)) {
                it.remove();
                changed = true;
            }
        }
        if (map.isEmpty()) menuRegistry.remove(view);
        if (changed) notifyMenuChanged(view);
    }

    private void clearMenuCommandsForScriptAllViews(String scriptId) {
        for (WebView v : new ArrayList<>(menuRegistry.keySet())) {
            clearMenuCommandsForScript(v, scriptId);
        }
    }

    private void notifyMenuChanged(WebView view) {
        HostActions actions = hostActions;
        if (actions != null) actions.onMenuCommandsChanged(view);
    }

    /**
     * Asynchronously downloads (15s timeout, 1 MiB max) and parses a remote .user.js script.
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
     * Installs or updates a fetched remote script, caches @require/@resource dependencies, and enables it.
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
                postError(onError, "最多保存 " + MAX_SCRIPTS + " 个脚本，源码总量不能超过约 4 MiB。");
                return;
            }
            Entry collision = duplicate(script);
            if (collision != null && collision != previous) {
                postError(onError, "已有同名、同命名空间的脚本。");
                return;
            }
            String savedSourceUrl = HttpUrlUtil.isHttp(remote.url)
                    ? remote.url
                    : (previous != null ? previous.sourceUrl : "");
            Entry next = new Entry(previous == null ? UUID.randomUUID().toString() : previous.id,
                    script, true, savedSourceUrl);
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
            if (!script.requires.isEmpty() || !script.resources.isEmpty()) {
                prepareDependenciesAsync(next, () -> {
                    invalidateEntryCache(next);
                    refreshViews();
                    postError(onError, null);
                });
            } else {
                refreshViews();
                postError(onError, null);
            }
        } catch (RuntimeException error) {
            String msg = error.getMessage();
            postError(onError, (msg == null || msg.trim().isEmpty()) ? "安装脚本失败" : msg);
        }
    }

    /**
     * Checks all installed scripts with @updateURL, @downloadURL, or saved remote source URL
     * for newer versions on a background thread, returning available updates on the main thread.
     */
    public void checkUpdates(Context ctx, UpdateCallback cb) {
        if (cb == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> checkUpdates(ctx, cb));
            return;
        }
        load();
        final List<Entry> snapshot = new ArrayList<>(entries);
        REMOTE_EXEC.execute(() -> {
            ArrayList<UpdateResult> updates = new ArrayList<>();
            int checked = 0;
            int failed = 0;
            for (Entry entry : snapshot) {
                String checkUrl = bestUpdateCheckUrl(entry);
                if (checkUrl.isEmpty()) continue;
                checked++;
                try {
                    UserScriptInput.Downloaded downloaded = UserScriptInput.downloadRemote(checkUrl, UserScript.MAX_BYTES);
                    String remoteVersion = UserScript.extractMetaField(downloaded.source, "version");
                    if (UserScript.compareVersions(remoteVersion, entry.script.version) <= 0) {
                        continue;
                    }
                    UserScript updated = null;
                    String finalDownloadUrl = bestDownloadUrl(entry, downloaded.source, checkUrl);
                    try {
                        updated = UserScript.parse(downloaded.source);
                    } catch (IllegalArgumentException maybeMetaOnly) {
                        // .meta.js endpoints omit the script body; fetch the full .user.js from downloadURL.
                        if (!finalDownloadUrl.isEmpty() && !finalDownloadUrl.equals(checkUrl)) {
                            UserScriptInput.Downloaded full = UserScriptInput.downloadRemote(finalDownloadUrl, UserScript.MAX_BYTES);
                            updated = UserScript.parse(full.source);
                        } else {
                            throw maybeMetaOnly;
                        }
                    }
                    if (updated != null && UserScript.compareVersions(updated.version, entry.script.version) > 0) {
                        updates.add(new UpdateResult(
                                entry.id, entry.script.name, entry.script.version,
                                updated.version, finalDownloadUrl, updated));
                    }
                } catch (IOException | RuntimeException error) {
                    failed++;
                    Log.w("AikoScripts", "Update check failed for " + entry.script.name + ": " + error.getMessage());
                }
            }
            final int totalChecked = checked;
            final int totalFailed = failed;
            handler.post(() -> cb.onComplete(updates, totalChecked, totalFailed));
        });
    }

    /** Convenience helper to apply a discovered UpdateResult while preserving ID, storage, and dependencies. */
    public void applyUpdate(UpdateResult update, Consumer<String> onError) {
        if (update == null || update.updated == null) {
            postError(onError, "无效的更新数据");
            return;
        }
        RemoteScript remote = new RemoteScript(
                update.downloadUrl != null ? update.downloadUrl : "",
                update.updated.source, null, update.updated, true, update.id);
        installRemote(remote, onError);
    }

    private static String bestUpdateCheckUrl(Entry entry) {
        if (entry == null || entry.script == null) return "";
        if (!entry.script.updateUrl.isEmpty() && UserScriptInput.safeRemoteAddress(entry.script.updateUrl) != null) {
            return entry.script.updateUrl;
        }
        if (!entry.script.downloadUrl.isEmpty() && UserScriptInput.safeRemoteAddress(entry.script.downloadUrl) != null) {
            return entry.script.downloadUrl;
        }
        if (entry.sourceUrl != null && !entry.sourceUrl.isEmpty()
                && UserScriptInput.safeRemoteAddress(entry.sourceUrl) != null) {
            return entry.sourceUrl;
        }
        return "";
    }

    private static String bestDownloadUrl(Entry entry, String remoteSource, String fallbackCheckUrl) {
        String remoteDownload = UserScript.extractMetaField(remoteSource, "downloadURL");
        if (!remoteDownload.isEmpty() && UserScriptInput.safeRemoteAddress(remoteDownload) != null) {
            return remoteDownload;
        }
        if (!entry.script.downloadUrl.isEmpty() && UserScriptInput.safeRemoteAddress(entry.script.downloadUrl) != null) {
            return entry.script.downloadUrl;
        }
        if (entry.sourceUrl != null && !entry.sourceUrl.isEmpty()
                && UserScriptInput.safeRemoteAddress(entry.sourceUrl) != null) {
            return entry.sourceUrl;
        }
        if (fallbackCheckUrl.endsWith(".meta.js")) {
            return fallbackCheckUrl.substring(0, fallbackCheckUrl.length() - 8) + ".user.js";
        }
        return fallbackCheckUrl;
    }

    /**
     * Synchronously pre-fetches and disk-caches @require and @resource dependencies under
     * <filesDir>/userscript_cache/<scriptId>/ (2 MiB per item, 8 MiB per script).
     * Safe to invoke from background threads.
     */
    public boolean prepareDependencies(Entry entry) {
        if (entry == null || entry.script == null) return true;
        UserScript script = entry.script;
        if (script.requires.isEmpty() && script.resources.isEmpty()) return true;
        File dir = scriptCacheDir(entry.id);
        if (!dir.exists() && !dir.mkdirs()) return false;

        JSONObject existingManifest = readManifest(dir);
        JSONObject oldReqs = existingManifest.optJSONObject("requires");
        JSONObject oldRes = existingManifest.optJSONObject("resources");
        JSONObject newReqs = new JSONObject();
        JSONObject newRes = new JSONObject();
        Set<String> keepFiles = new HashSet<>();
        keepFiles.add("manifest.json");

        int totalBytes = 0;
        boolean allOk = true;

        // 1. Cache @require JS files in exact declaration order
        for (int i = 0; i < script.requires.size(); i++) {
            String url = script.requires.get(i);
            String fileName = "req_" + i + ".js";
            File file = new File(dir, fileName);
            JSONObject prev = oldReqs != null ? oldReqs.optJSONObject(url) : null;
            if (prev != null && fileName.equals(prev.optString("file")) && file.isFile()
                    && file.length() > 0 && file.length() <= MAX_DEP_BYTES
                    && totalBytes + file.length() <= MAX_SCRIPT_CACHE_BYTES) {
                totalBytes += (int) file.length();
                keepFiles.add(fileName);
                try { newReqs.put(url, prev); } catch (JSONException ignored) { }
                continue;
            }
            int remaining = MAX_SCRIPT_CACHE_BYTES - totalBytes;
            if (remaining <= 0) {
                allOk = false;
                break;
            }
            try {
                int cap = Math.min(MAX_DEP_BYTES, remaining);
                UserScriptInput.BinaryDownload dl = UserScriptInput.downloadBinary(url, cap, 12_000);
                writeBytesAtomically(file, dl.bytes);
                totalBytes += dl.bytes.length;
                keepFiles.add(fileName);
                JSONObject meta = new JSONObject()
                        .put("file", fileName)
                        .put("size", dl.bytes.length)
                        .put("mime", dl.contentType);
                newReqs.put(url, meta);
            } catch (IOException | JSONException | RuntimeException err) {
                allOk = false;
                Log.w("AikoScripts", "Failed to cache @require " + url + ": " + err.getMessage());
            }
        }

        // 2. Cache @resource entries
        int resIdx = 0;
        for (Map.Entry<String, String> item : script.resources.entrySet()) {
            String resName = item.getKey();
            String url = item.getValue();
            String fileName = "res_" + (resIdx++) + ".bin";
            File file = new File(dir, fileName);
            JSONObject prev = oldRes != null ? oldRes.optJSONObject(resName) : null;
            if (prev != null && url.equals(prev.optString("url")) && fileName.equals(prev.optString("file"))
                    && file.isFile() && file.length() <= MAX_DEP_BYTES
                    && totalBytes + file.length() <= MAX_SCRIPT_CACHE_BYTES) {
                totalBytes += (int) file.length();
                keepFiles.add(fileName);
                try { newRes.put(resName, prev); } catch (JSONException ignored) { }
                continue;
            }
            int remaining = MAX_SCRIPT_CACHE_BYTES - totalBytes;
            if (remaining <= 0) {
                allOk = false;
                break;
            }
            try {
                int cap = Math.min(MAX_DEP_BYTES, remaining);
                UserScriptInput.BinaryDownload dl = UserScriptInput.downloadBinary(url, cap, 12_000);
                writeBytesAtomically(file, dl.bytes);
                totalBytes += dl.bytes.length;
                keepFiles.add(fileName);
                String mime = inferMime(dl.contentType, url);
                JSONObject meta = new JSONObject()
                        .put("url", url)
                        .put("file", fileName)
                        .put("size", dl.bytes.length)
                        .put("mime", mime);
                newRes.put(resName, meta);
            } catch (IOException | JSONException | RuntimeException err) {
                allOk = false;
                Log.w("AikoScripts", "Failed to cache @resource " + resName + ": " + err.getMessage());
            }
        }

        try {
            JSONObject manifest = new JSONObject()
                    .put("requires", newReqs)
                    .put("resources", newRes);
            writeBytesAtomically(new File(dir, "manifest.json"),
                    manifest.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException err) {
            Log.w("AikoScripts", "Failed to save dependency manifest", err);
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!keepFiles.contains(f.getName())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        return allOk;
    }

    /** Asynchronously prepares @require/@resource cache and invokes optional callback on main thread. */
    public void prepareDependenciesAsync(Entry entry, Runnable onDone) {
        REMOTE_EXEC.execute(() -> {
            prepareDependencies(entry);
            if (onDone != null) handler.post(onDone);
        });
    }

    private File scriptCacheDir(String scriptId) {
        return new File(new File(app.getFilesDir(), CACHE_DIR_NAME), scriptId);
    }

    private static void deleteDirQuietly(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) deleteDirQuietly(f);
                else { //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void writeBytesAtomically(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (target.exists() && !target.delete()) {
            // Best-effort overwrite fallback
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static JSONObject readManifest(File dir) {
        File mf = new File(dir, "manifest.json");
        if (!mf.isFile() || mf.length() > 262_144) return new JSONObject();
        try (FileInputStream in = new FileInputStream(mf)) {
            byte[] bytes = UserScriptInput.readBytes(in, 262_144);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
            return new JSONObject();
        }
    }

    private String buildCombinedCode(Entry entry) {
        UserScript script = entry.script;
        if (script.requires.isEmpty()) return script.code;
        File dir = scriptCacheDir(entry.id);
        if (!dir.isDirectory()) return script.code;
        JSONObject manifest = readManifest(dir);
        JSONObject reqs = manifest.optJSONObject("requires");
        if (reqs == null) return script.code;

        StringBuilder sb = new StringBuilder(script.code.length() + 4096);
        for (String url : script.requires) {
            JSONObject meta = reqs.optJSONObject(url);
            if (meta == null) continue;
            String fileName = meta.optString("file", "");
            if (fileName.isEmpty()) continue;
            File file = new File(dir, fileName);
            if (!file.isFile() || file.length() > MAX_DEP_BYTES) continue;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = UserScriptInput.readBytes(in, MAX_DEP_BYTES);
                String depCode = new String(bytes, StandardCharsets.UTF_8);
                if (depCode.startsWith("\ufeff")) depCode = depCode.substring(1);
                if (!depCode.trim().isEmpty()) {
                    sb.append(depCode).append("\n;\n");
                }
            } catch (IOException ignored) { }
        }
        sb.append(script.code);
        return sb.toString();
    }

    private JSONObject buildResourcesJson(Entry entry) {
        UserScript script = entry.script;
        JSONObject out = new JSONObject();
        if (script.resources.isEmpty() || !script.needsResources()) return out;
        File dir = scriptCacheDir(entry.id);
        if (!dir.isDirectory()) return out;
        JSONObject manifest = readManifest(dir);
        JSONObject resMap = manifest.optJSONObject("resources");
        if (resMap == null) return out;

        for (Map.Entry<String, String> item : script.resources.entrySet()) {
            String name = item.getKey();
            JSONObject meta = resMap.optJSONObject(name);
            if (meta == null) continue;
            String fileName = meta.optString("file", "");
            if (fileName.isEmpty()) continue;
            File file = new File(dir, fileName);
            if (!file.isFile() || file.length() > MAX_DEP_BYTES) continue;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = UserScriptInput.readBytes(in, MAX_DEP_BYTES);
                String mime = meta.optString("mime", inferMime("", item.getValue()));
                JSONObject obj = new JSONObject().put("mime", mime);
                if (isTextMime(mime)) {
                    obj.put("text", new String(bytes, StandardCharsets.UTF_8));
                } else {
                    obj.put("b64", Base64.getEncoder().encodeToString(bytes));
                }
                out.put(name, obj);
            } catch (IOException | JSONException ignored) { }
        }
        return out;
    }

    private static String inferMime(String headerCt, String url) {
        if (headerCt != null && !headerCt.trim().isEmpty()) {
            String base = headerCt.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!base.isEmpty() && !base.equals("application/octet-stream")) return base;
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private static boolean isTextMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        return m.startsWith("text/") || m.contains("json") || m.contains("javascript")
                || m.contains("xml") || m.contains("svg");
    }

    private static void invalidateEntryCache(Entry entry) {
        entry.program = null;
        entry.quotedCode = null;
        entry.cachedResources = null;
        entry.beforeValues = null;
        entry.afterValues = null;
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

    /** Preload saved scripts, values, dependency caches, and runtime.js off the UI thread. */
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
                        entry.quotedCode = JSONObject.quote(buildCombinedCode(entry));
                        if (!entry.script.resources.isEmpty() && entry.script.needsResources()) {
                            entry.cachedResources = buildResourcesJson(entry);
                        }
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

    public List<Entry> items() { load(); return new ArrayList<>(entries); }

    Entry duplicate(UserScript script) {
        load();
        for (Entry entry : entries) {
            if (entry.script.name.equals(script.name) && entry.script.namespace.equals(script.namespace)) return entry;
        }
        return null;
    }

    Entry save(UserScript script, Entry previous) {
        return installLocal(script, previous, false);
    }

    Entry installLocal(UserScript script, Entry previous, boolean enable) {
        load();
        int count = script.source.length();
        for (Entry item : entries) if (item != previous) count += item.script.source.length();
        if (count > MAX_SOURCES || (previous == null && entries.size() >= MAX_SCRIPTS))
            throw new IllegalArgumentException("最多保存 " + MAX_SCRIPTS + " 个脚本，源码总量不能超过约 4 MiB。");
        Entry collision = duplicate(script);
        if (collision != null && collision != previous)
            throw new IllegalArgumentException("已有同名、同命名空间的脚本。");
        flushValues();
        Entry next = new Entry(previous == null ? UUID.randomUUID().toString() : previous.id,
                script, enable, previous != null ? previous.sourceUrl : "");
        if (enable) {
            String incompat = compatibility(next);
            if (!incompat.isEmpty()) next.enabled = false;
        }
        if (previous != null && script.needsStorage() && previous.serializedValues != null) {
            next.serializedValues = previous.serializedValues;
        }
        if (previous == null) entries.add(next); else entries.set(entries.indexOf(previous), next);
        saveList();
        refreshViews();
        if (!script.requires.isEmpty() || !script.resources.isEmpty()) {
            prepareDependenciesAsync(next, () -> {
                invalidateEntryCache(next);
                refreshViews();
            });
        }
        return next;
    }

    void setEnabled(Entry entry, boolean enabled) {
        load();
        if (!entries.contains(entry)) return;
        entry.enabled = enabled;
        if (!enabled) {
            cancelActiveXhrsForScript(entry.id);
            clearMenuCommandsForScriptAllViews(entry.id);
        }
        saveList();
        refreshViews();
        if (enabled && (!entry.script.requires.isEmpty() || !entry.script.resources.isEmpty())) {
            prepareDependenciesAsync(entry, () -> {
                invalidateEntryCache(entry);
                refreshViews();
            });
        }
    }

    void delete(Entry entry) {
        load();
        if (!entries.remove(entry)) return;
        cancelActiveXhrsForScript(entry.id);
        clearMenuCommandsForScriptAllViews(entry.id);
        pending.remove(entry);
        dirtyBindings.remove(entry);
        values.remove(entry.id);
        for (Map<String, JavaScriptReplyProxy> map : replyProxies.values()) {
            map.remove(entry.id);
        }
        String key = "values-" + entry.id;
        valuePrefs().edit().remove(key).apply();
        SharedPreferences lp = listPrefs();
        if (lp.contains(key)) lp.edit().remove(key).apply();
        connectPrefs().edit().remove("approved-" + entry.id).apply();
        final String deletedId = entry.id;
        REMOTE_EXEC.execute(() -> deleteDirQuietly(scriptCacheDir(deletedId)));
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
        cancelActiveXhrsForView(view);
        views.remove(view);
        replyProxies.remove(view);
        menuRegistry.remove(view);
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
                if (entry.enabled && !script.needsBridge() && !script.runAt.equals("document-start")
                        && script.matches(address)) {
                    try {
                        view.evaluateJavascript(payload(entry, false), null);
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
                        if (binding.world != null) WebViewCompat.removeWebMessageListener(view, binding.world, binding.bridgeName);
                        else WebViewCompat.removeWebMessageListener(view, binding.bridgeName);
                        binding.registerBridge(view);
                    }
                    boolean ephemeral = binding.world == null && binding.bridge;
                    binding.register(view, payload(binding.entry, ephemeral), event(script));
                    if (!script.requiresIsolation && !script.needsBridge() && !script.runAt.equals("document-start")) {
                        view.evaluateJavascript(payload(binding.entry, ephemeral), null);
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
        if (isolatedWorlds() && !entry.script.pageWorld) return "";
        if (earlyScripts()) {
            if (entry.script.needsBridge() && !webMessageSupport()) {
                return "当前系统 WebView 缺少脚本桥接接口。更新系统 WebView 后可运行。";
            }
            return "";
        }
        if (entry.script.needsBridge()) return "当前系统 WebView 缺少脚本桥接支持。更新系统 WebView 后可运行。";
        if (entry.script.runAt.equals("document-start"))
            return "当前系统 WebView 不支持 document-start。更新系统 WebView 后可运行。";
        return "";
    }

    String status() {
        if (registrationFailed) return "部分脚本未注册成功，可在详情中检查兼容性";
        if (isolatedWorlds()) return "隔离世界模式 · Tampermonkey 完整接口可用";
        if (earlyScripts() && webMessageSupport()) return "兼容执行模式 · 支持 GM 存储 / XHR / 菜单";
        if (earlyScripts()) return "基础执行模式 · 仅支持无桥接脚本";
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
        replyProxies.clear();
        menuRegistry.clear();
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
        boolean early = earlyScripts();
        boolean canMessage = webMessageSupport();
        if (!worlds && !early) return;
        for (Entry entry : entries) {
            if (!entry.enabled) continue;
            // Tier 1: Chromium isolated world when available and script doesn't need page world (unsafeWindow / @sandbox raw).
            // Tier 2: Universal document-start script (supported on all Android 10+ WebViews) with ephemeral token-authenticated bridge.
            boolean useIsolated = worlds && !entry.script.pageWorld;
            if (!useIsolated && !early) continue;
            boolean needsBridge = entry.script.needsBridge();
            if (needsBridge && !useIsolated && !canMessage) continue;
            Binding binding = null;
            try {
                JavaScriptExecutionWorld world = useIsolated
                        ? WebViewCompat.getExecutionWorld(view, "aiko-" + entry.id) : null;
                boolean ephemeral = !useIsolated && needsBridge;
                String bridgeName = BRIDGE;
                String token = null;
                if (ephemeral) {
                    ensureSessionToken(entry);
                    bridgeName = entry.bridgeName;
                    token = entry.sessionToken;
                }
                final String expectedToken = token;
                WebViewCompat.WebMessageListener listener = needsBridge ? (source, message, origin, main, reply) -> {
                    if (main && source == view && views.containsKey(view)
                            && message.getType() == WebMessageCompat.TYPE_STRING) {
                        receive(view, entry, message.getData(), origin.toString(), reply, expectedToken);
                    }
                } : null;
                binding = new Binding(entry, world, bridgeName, expectedToken, needsBridge, !useIsolated, listener);
                if (needsBridge) binding.registerBridge(view);
                binding.register(view, payload(entry, ephemeral), event(entry.script));
                output.add(binding);
            } catch (IOException | RuntimeException | LinkageError error) {
                registrationFailed = true;
                if (binding != null) {
                    try { binding.remove(view); } catch (RuntimeException | LinkageError ignored) {}
                }
                Log.w("AikoScripts", "Could not register userscript: " + entry.script.name, error);
            }
        }
    }

    private static void ensureSessionToken(Entry entry) {
        if (entry.sessionToken != null && entry.bridgeName != null) return;
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        final char[] digits = "0123456789abcdef".toCharArray();
        for (byte b : bytes) {
            hex.append(digits[(b >>> 4) & 0xF]).append(digits[b & 0xF]);
        }
        entry.sessionToken = hex.toString();
        entry.bridgeName = "__aikoSv_" + entry.sessionToken.substring(0, 12);
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

    private boolean webMessageSupport() {
        if (webMessageSupport == null) webMessageSupport = supports(WebViewFeature.WEB_MESSAGE_LISTENER);
        return webMessageSupport;
    }

    private static boolean supports(String feature) {
        try { return WebViewFeature.isFeatureSupported(feature); }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    private static boolean verifyToken(JSONArray data, int baseLen, String expectedToken) {
        if (expectedToken != null) {
            return data.length() == baseLen + 1 && expectedToken.equals(data.optString(baseLen, ""));
        }
        return data.length() == baseLen;
    }

    private void storeReplyProxy(WebView sourceView, Entry entry, JavaScriptReplyProxy reply) {
        if (reply == null) return;
        if (entry.script.needsValueListener || entry.script.needsXhr() || entry.script.needsMenuCommand()) {
            replyProxies.computeIfAbsent(sourceView, k -> new HashMap<>()).put(entry.id, reply);
        }
    }

    private void receive(WebView sourceView, Entry entry, String message, String sourceOrigin,
                         JavaScriptReplyProxy reply, String expectedToken) {
        if (!entry.enabled || !entries.contains(entry) || message == null || message.length() > MAX_MESSAGE_CHARS) return;
        try {
            JSONArray data = new JSONArray(message);
            if (data.length() < 2 || data.length() > 6) return;
            URI address = UserScript.address(data.getString(0));
            if (!UserScript.sameOrigin(address, UserScript.address(sourceOrigin)) || !entry.script.matches(address)) return;
            String op = data.getString(1);

            if ("hello".equals(op)) {
                if (expectedToken != null) {
                    if (data.length() != 3 || !expectedToken.equals(data.optString(2, ""))) return;
                } else if (data.length() > 3) {
                    return;
                }
                storeReplyProxy(sourceView, entry, reply);
                clearMenuCommandsForScript(sourceView, entry.id);
                return;
            }

            if ("xhr".equals(op) && entry.script.needsXhr()) {
                if (!verifyToken(data, 3, expectedToken)) return;
                storeReplyProxy(sourceView, entry, reply);
                handleXhrMessage(sourceView, entry, address, data.getString(2), reply);
                return;
            }

            if ("xhr_abort".equals(op) && entry.script.needsXhr()) {
                if (!verifyToken(data, 3, expectedToken)) return;
                String reqId = data.getString(2);
                XhrTask task = activeXhrs.remove(xhrKey(sourceView, entry.id, reqId));
                if (task != null) task.cancel();
                return;
            }

            if ("menu_reg".equals(op) && entry.script.needsMenuCommand()) {
                if (!verifyToken(data, 4, expectedToken)) return;
                storeReplyProxy(sourceView, entry, reply);
                String localCmdId = data.getString(2);
                String caption = data.getString(3).trim();
                if (localCmdId.isEmpty() || localCmdId.length() > 64 || caption.isEmpty()) return;
                if (caption.length() > 120) caption = caption.substring(0, 120);
                LinkedHashMap<String, MenuEntry> map = menuRegistry.computeIfAbsent(sourceView, k -> new LinkedHashMap<>());
                int countForScript = 0;
                for (MenuEntry existing : map.values()) {
                    if (entry.id.equals(existing.command.scriptId)) countForScript++;
                }
                String fullCmdId = entry.id + ":" + localCmdId;
                if (!map.containsKey(fullCmdId) && countForScript >= MAX_MENU_COMMANDS_PER_SCRIPT) return;
                map.put(fullCmdId, new MenuEntry(
                        new MenuCommand(entry.id, entry.script.name, fullCmdId, caption), localCmdId));
                notifyMenuChanged(sourceView);
                return;
            }

            if ("menu_unreg".equals(op) && entry.script.needsMenuCommand()) {
                if (!verifyToken(data, 3, expectedToken)) return;
                String localCmdId = data.getString(2);
                LinkedHashMap<String, MenuEntry> map = menuRegistry.get(sourceView);
                if (map != null && map.remove(entry.id + ":" + localCmdId) != null) {
                    if (map.isEmpty()) menuRegistry.remove(sourceView);
                    notifyMenuChanged(sourceView);
                }
                return;
            }

            if ("download".equals(op) && entry.script.needsDownload()) {
                if (!verifyToken(data, 4, expectedToken)) return;
                String url = data.getString(2);
                String filename = sanitizeFilename(data.optString(3, ""));
                if (UserScriptInput.safeRemoteAddress(url) == null) return;
                HostActions actions = hostActions;
                if (actions != null) actions.downloadUrl(url, filename);
                return;
            }

            if (data.length() < 3) return;
            String key = data.getString(2);
            if (key.length() > 128) return;
            storeReplyProxy(sourceView, entry, reply);

            JSONObject current = values(entry);
            boolean hadOld = current.has(key);
            Object oldVal = hadOld ? current.opt(key) : JSONObject.NULL;
            Object newVal = JSONObject.NULL;
            boolean hasNew = false;
            String serialized;

            if ("set".equals(op) && (entry.script.permits("setValue") || entry.script.permits("setValues"))) {
                if (!verifyToken(data, 4, expectedToken)) return;
                String encoded = data.getString(3);
                if (encoded.length() > MAX_VALUE_CHARS) return;
                JSONTokener tokener = new JSONTokener(encoded);
                Object incoming = tokener.nextValue();
                if (tokener.nextClean() != 0) return;
                Object previous = hadOld ? oldVal : null;
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
                newVal = incoming;
                hasNew = true;
            } else if ("delete".equals(op) && (entry.script.permits("deleteValue") || entry.script.permits("deleteValues"))) {
                if (!verifyToken(data, 3, expectedToken)) return;
                if (current.remove(key) == null) return;
                serialized = current.toString();
            } else {
                return;
            }
            entry.serializedValues = serialized;
            entry.program = null;
            if (pending.isEmpty()) handler.postDelayed(flush, FLUSH_DELAY_MS);
            pending.add(entry);
            if (entry.script.needsValueListener) {
                broadcastValueChange(sourceView, entry.id, key, hadOld, oldVal, hasNew, newVal);
            }
        } catch (JSONException | RuntimeException invalid) {
            Log.d("AikoScripts", "Invalid userscript bridge message");
        }
    }

    private static String xhrKey(WebView view, String entryId, String reqId) {
        return System.identityHashCode(view) + ":" + entryId + ":" + reqId;
    }

    private void cancelActiveXhrsForView(WebView view) {
        for (Map.Entry<String, XhrTask> item : activeXhrs.entrySet()) {
            if (item.getValue().view == view) {
                activeXhrs.remove(item.getKey());
                item.getValue().cancel();
            }
        }
    }

    private void cancelActiveXhrsForScript(String entryId) {
        for (Map.Entry<String, XhrTask> item : activeXhrs.entrySet()) {
            if (entryId.equals(item.getValue().entryId)) {
                activeXhrs.remove(item.getKey());
                item.getValue().cancel();
            }
        }
    }

    private void handleXhrMessage(WebView sourceView, Entry entry, URI pageAddress,
                                  String reqJson, JavaScriptReplyProxy reply) {
        if (reply == null) return;
        JSONObject req;
        try {
            req = new JSONObject(reqJson);
        } catch (JSONException invalid) {
            return;
        }
        String reqId = req.optString("id", "");
        String rawUrl = req.optString("url", "");
        if (reqId.isEmpty() || reqId.length() > 64) return;
        URI targetUri = UserScriptInput.safeRemoteAddress(rawUrl);
        if (targetUri == null) {
            sendXhrFailure(sourceView, reply, "xhr_error", reqId, rawUrl, "Invalid HTTP(S) URL");
            return;
        }
        String targetHost = targetUri.getHost();
        String pageHost = pageAddress.getHost();
        Set<String> approved = approvedConnectHosts(entry.id);
        if (entry.script.isConnectAllowed(targetHost, pageHost, approved)) {
            startXhrTask(sourceView, entry, pageHost, targetUri, reqId, req, reply);
            return;
        }
        HostActions actions = hostActions;
        if (actions != null && entry.script.needsUserConnectApproval(targetHost, pageHost, approved)) {
            actions.requestConnectApproval(entry.id, entry.script.name, targetHost, granted -> handler.post(() -> {
                if (!Boolean.TRUE.equals(granted) || !views.containsKey(sourceView) || !entry.enabled) {
                    sendXhrFailure(sourceView, reply, "xhr_error", reqId, rawUrl,
                            "@connect denied for host: " + targetHost);
                    return;
                }
                approveConnectHost(entry.id, targetHost);
                startXhrTask(sourceView, entry, pageHost, targetUri, reqId, req, reply);
            }));
            return;
        }
        sendXhrFailure(sourceView, reply, "xhr_error", reqId, rawUrl,
                "@connect denied for host: " + targetHost);
    }

    private void startXhrTask(WebView sourceView, Entry entry, String pageHost, URI initialUri,
                              String reqId, JSONObject req, JavaScriptReplyProxy reply) {
        String method = req.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_XHR_METHODS.contains(method)) method = "GET";
        String responseType = req.optString("responseType", "").trim().toLowerCase(Locale.ROOT);
        String overrideMimeType = req.optString("overrideMimeType", "").trim();
        boolean anonymous = req.optBoolean("anonymous", false);
        int rawTimeout = req.optInt("timeout", 30_000);
        int timeoutMs = rawTimeout <= 0 ? 30_000 : Math.max(1_000, Math.min(120_000, rawTimeout));
        JSONObject headersObj = req.optJSONObject("headers");
        String bodyStr = req.isNull("data") ? null : req.optString("data", null);
        boolean dataIsBase64 = req.optBoolean("dataIsBase64", false);

        final String finalMethod = method;
        final String key = xhrKey(sourceView, entry.id, reqId);
        final XhrTask task = new XhrTask(sourceView, entry.id, reqId);
        XhrTask prev = activeXhrs.put(key, task);
        if (prev != null) prev.cancel();

        try {
            task.future = XHR_EXEC.submit(() -> {
                String currentUrlStr = initialUri.toASCIIString();
                try {
                    URI currentUri = initialUri;
                    URL url = initialUri.toURL();
                    String activeMethod = finalMethod;
                    boolean crossOriginRedirected = false;
                    byte[] bodyBytes = null;
                    if (bodyStr != null && ("POST".equals(activeMethod) || "PUT".equals(activeMethod)
                            || "PATCH".equals(activeMethod) || "DELETE".equals(activeMethod))) {
                        bodyBytes = dataIsBase64
                                ? Base64.getDecoder().decode(bodyStr)
                                : bodyStr.getBytes(StandardCharsets.UTF_8);
                    }

                    for (int redirects = 0; redirects <= 6; redirects++) {
                        if (task.aborted) return;
                        currentUrlStr = url.toExternalForm();
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        task.connection = conn;
                        conn.setRequestMethod(activeMethod);
                        conn.setConnectTimeout(timeoutMs);
                        conn.setReadTimeout(timeoutMs);
                        conn.setUseCaches(false);
                        conn.setInstanceFollowRedirects(false);

                        boolean hasUserAgent = false;
                        boolean hasCookie = false;
                        if (headersObj != null) {
                            Iterator<String> keys = headersObj.keys();
                            while (keys.hasNext()) {
                                String hk = keys.next();
                                if (hk == null) continue;
                                String trimmedKey = hk.trim();
                                String hv = headersObj.optString(hk, "");
                                if (trimmedKey.isEmpty() || trimmedKey.indexOf('\r') >= 0 || trimmedKey.indexOf('\n') >= 0
                                        || hv.indexOf('\r') >= 0 || hv.indexOf('\n') >= 0) {
                                    continue;
                                }
                                if ("host".equalsIgnoreCase(trimmedKey) || "content-length".equalsIgnoreCase(trimmedKey)) {
                                    continue;
                                }
                                if (crossOriginRedirected && ("authorization".equalsIgnoreCase(trimmedKey)
                                        || "proxy-authorization".equalsIgnoreCase(trimmedKey)
                                        || "cookie".equalsIgnoreCase(trimmedKey)
                                        || "cookie2".equalsIgnoreCase(trimmedKey))) {
                                    continue;
                                }
                                if ("user-agent".equalsIgnoreCase(trimmedKey)) hasUserAgent = true;
                                if ("cookie".equalsIgnoreCase(trimmedKey)) hasCookie = true;
                                conn.setRequestProperty(trimmedKey, hv);
                            }
                        }
                        if (!hasUserAgent) conn.setRequestProperty("User-Agent", XHR_UA);
                        if (!anonymous && !hasCookie) {
                            String cookie = safeWebViewCookie(currentUrlStr);
                            if (cookie != null && !cookie.isEmpty()) {
                                conn.setRequestProperty("Cookie", cookie);
                            }
                        }

                        try {
                            if (bodyBytes != null && !"GET".equals(activeMethod) && !"HEAD".equals(activeMethod)) {
                                conn.setDoOutput(true);
                                conn.setFixedLengthStreamingMode(bodyBytes.length);
                                try (OutputStream os = conn.getOutputStream()) {
                                    os.write(bodyBytes);
                                }
                            }
                            if (task.aborted) return;

                            int status = conn.getResponseCode();
                            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                                String loc = conn.getHeaderField("Location");
                                if (loc == null || loc.trim().isEmpty()) {
                                    throw new IOException("Invalid redirect");
                                }
                                URL nextUrl = new URL(url, loc.trim());
                                URI safeNext = UserScriptInput.safeRemoteAddress(nextUrl.toExternalForm());
                                if (safeNext == null) throw new IOException("Blocked redirect URL");
                                if (!entry.script.isConnectAllowed(safeNext.getHost(), pageHost,
                                        approvedConnectHosts(entry.id))) {
                                    throw new IOException("@connect denied redirect host: " + safeNext.getHost());
                                }
                                if (!UserScript.sameOrigin(currentUri, safeNext)) {
                                    crossOriginRedirected = true;
                                }
                                if (status == 303 || ((status == 301 || status == 302) && "POST".equals(activeMethod))) {
                                    activeMethod = "GET";
                                    bodyBytes = null;
                                }
                                currentUri = safeNext;
                                url = safeNext.toURL();
                                continue;
                            }

                            String statusText = conn.getResponseMessage();
                            if (statusText == null) statusText = "";
                            String respHeaders = formatResponseHeaders(conn);
                            String contentType = conn.getContentType();
                            if (contentType == null) contentType = "";

                            byte[] rawResp = new byte[0];
                            if (!"HEAD".equals(activeMethod)) {
                                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                                if (in == null && status < 400) in = conn.getErrorStream();
                                if (in != null) {
                                    try (InputStream stream = in) {
                                        rawResp = readCappedBytes(stream, MAX_XHR_RESPONSE_BYTES, task);
                                    }
                                }
                            }
                            if (task.aborted) return;

                            boolean binary = "arraybuffer".equals(responseType) || "blob".equals(responseType);
                            String bodyOut;
                            if (binary) {
                                bodyOut = Base64.getEncoder().encodeToString(rawResp);
                            } else {
                                Charset cs = resolveCharset(overrideMimeType, contentType);
                                bodyOut = new String(rawResp, cs);
                            }

                            JSONObject done = new JSONObject()
                                    .put("type", "xhr_done")
                                    .put("id", reqId)
                                    .put("status", status)
                                    .put("statusText", statusText)
                                    .put("finalUrl", currentUrlStr)
                                    .put("responseHeaders", respHeaders)
                                    .put("contentType", contentType)
                                    .put("binary", binary)
                                    .put("body", bodyOut);
                            postXhrReply(sourceView, reply, task, done.toString());
                            return;
                        } finally {
                            conn.disconnect();
                            task.connection = null;
                        }
                    }
                    sendXhrFailure(sourceView, reply, "xhr_error", reqId, currentUrlStr, "Too many redirects");
                } catch (SocketTimeoutException timeoutEx) {
                    if (!task.aborted) {
                        sendXhrFailure(sourceView, reply, "xhr_timeout", reqId, currentUrlStr, "Request timed out");
                    }
                } catch (IOException | JSONException | RuntimeException err) {
                    if (!task.aborted) {
                        String msg = err.getMessage();
                        sendXhrFailure(sourceView, reply, "xhr_error", reqId, currentUrlStr,
                                (msg == null || msg.isEmpty()) ? "Network error" : msg);
                    }
                } finally {
                    activeXhrs.remove(key, task);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            activeXhrs.remove(key, task);
            sendXhrFailure(sourceView, reply, "xhr_error", reqId, initialUri.toASCIIString(), "Too many concurrent XHR requests");
        }
    }

    private static byte[] readCappedBytes(InputStream stream, int maxBytes, XhrTask task) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        for (int n; (n = stream.read(buf)) != -1; ) {
            if (task.aborted) throw new IOException("Aborted");
            if (n > maxBytes - out.size()) {
                throw new IOException("Response exceeded 8 MiB limit");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String formatResponseHeaders(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return "";
        for (Map.Entry<String, List<String>> item : fields.entrySet()) {
            String k = item.getKey();
            if (k == null || item.getValue() == null) continue;
            for (String v : item.getValue()) {
                sb.append(k).append(": ").append(v == null ? "" : v).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static Charset resolveCharset(String overrideMime, String contentType) {
        Charset cs = parseCharset(overrideMime);
        if (cs != null) return cs;
        cs = parseCharset(contentType);
        return cs != null ? cs : StandardCharsets.UTF_8;
    }

    private static Charset parseCharset(String mime) {
        if (mime == null || mime.isEmpty()) return null;
        String lower = mime.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx < 0) return null;
        String part = mime.substring(idx + 8).trim();
        int semi = part.indexOf(';');
        if (semi >= 0) part = part.substring(0, semi).trim();
        if (part.length() >= 2 && ((part.startsWith("\"") && part.endsWith("\""))
                || (part.startsWith("'") && part.endsWith("'")))) {
            part = part.substring(1, part.length() - 1).trim();
        }
        try { return Charset.forName(part); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String safeWebViewCookie(String url) {
        try {
            CookieManager cm = CookieManager.getInstance();
            return cm != null ? cm.getCookie(url) : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "";
        String cleaned = name.replace('\\', '_').replace('/', '_').replace('\0', '_').trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private void postXhrReply(WebView sourceView, JavaScriptReplyProxy reply, XhrTask task, String payload) {
        handler.post(() -> {
            if (task.aborted || !views.containsKey(sourceView)) return;
            try {
                reply.postMessage(payload);
            } catch (RuntimeException | LinkageError ignored) { }
        });
    }

    private void sendXhrFailure(WebView sourceView, JavaScriptReplyProxy reply, String type,
                                String reqId, String finalUrl, String error) {
        handler.post(() -> {
            if (!views.containsKey(sourceView)) return;
            try {
                String payload = new JSONObject()
                        .put("type", type)
                        .put("id", reqId)
                        .put("status", 0)
                        .put("statusText", "")
                        .put("finalUrl", finalUrl == null ? "" : finalUrl)
                        .put("error", error == null ? "" : error)
                        .toString();
                reply.postMessage(payload);
            } catch (JSONException | RuntimeException | LinkageError ignored) { }
        });
    }

    private void broadcastValueChange(WebView sourceView, String entryId, String key,
                                      boolean hadOld, Object oldVal, boolean hasNew, Object newVal) {
        if (replyProxies.isEmpty()) return;
        String payload;
        try {
            payload = new JSONArray()
                    .put(key)
                    .put(hadOld)
                    .put(hadOld ? oldVal : JSONObject.NULL)
                    .put(hasNew)
                    .put(hasNew ? newVal : JSONObject.NULL)
                    .toString();
        } catch (RuntimeException ignored) {
            return;
        }
        for (Map.Entry<WebView, Map<String, JavaScriptReplyProxy>> item : replyProxies.entrySet()) {
            if (item.getKey() == sourceView) continue;
            Map<String, JavaScriptReplyProxy> map = item.getValue();
            JavaScriptReplyProxy proxy = map.get(entryId);
            if (proxy == null) continue;
            try {
                proxy.postMessage(payload);
            } catch (RuntimeException | LinkageError stale) {
                map.remove(entryId);
            }
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
                    boolean ephemeral = binding.world == null && binding.bridge;
                    binding.register(view, payload(binding.entry, ephemeral), event(binding.entry.script));
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

    private SharedPreferences connectPrefs() {
        SharedPreferences p = connectPrefs;
        if (p == null) {
            p = app.getSharedPreferences(PREFS_CONNECTS, Context.MODE_PRIVATE);
            connectPrefs = p;
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
            return UserScriptInput.read(stream, 131072);
        }
    }

    private String payload(Entry entry, boolean ephemeralBridge) throws IOException {
        if (entry.program != null && entry.cachedEphemeral == ephemeralBridge) return entry.program;
        if (entry.cachedEphemeral != ephemeralBridge) {
            entry.beforeValues = null;
            entry.afterValues = null;
            entry.program = null;
            entry.cachedEphemeral = ephemeralBridge;
        }
        if (runtime == null) runtime = readRuntimeAsset();
        UserScript script = entry.script;
        if (entry.quotedCode == null) entry.quotedCode = JSONObject.quote(buildCombinedCode(entry));
        try {
            if (entry.beforeValues == null) {
                JSONObject scriptMeta = new JSONObject()
                        .put("name", script.name)
                        .put("namespace", script.namespace)
                        .put("version", script.version)
                        .put("description", script.description)
                        .put("author", script.author)
                        .put("run-at", script.runAt)
                        .put("updateURL", script.updateUrl)
                        .put("downloadURL", script.downloadUrl)
                        .put("icon", script.iconUrl);
                JSONObject staticConfig = new JSONObject()
                        .put("id", entry.id).put("release", RELEASE).put("runAt", script.runAt)
                        .put("includes", patterns(script.includes)).put("excludes", patterns(script.excludes))
                        .put("grants", new JSONArray(script.grants))
                        .put("storage", script.needsStorage())
                        .put("bridgeEnabled", script.needsBridge())
                        .put("script", scriptMeta);
                if (!script.resources.isEmpty() && script.needsResources()) {
                    if (entry.cachedResources == null) entry.cachedResources = buildResourcesJson(entry);
                    staticConfig.put("resources", entry.cachedResources);
                }
                if (ephemeralBridge && entry.bridgeName != null && entry.sessionToken != null) {
                    staticConfig.put("bridge", entry.bridgeName)
                            .put("token", entry.sessionToken)
                            .put("ephemeralBridge", true);
                }
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
                    String srcUrl = item.optString("sourceUrl", "");
                    parsed.add(new Entry(id, UserScript.parse(item.getString("source")), item.optBoolean("enabled"), srcUrl));
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
                JSONObject item = new JSONObject()
                        .put("id", entry.id)
                        .put("source", entry.script.source)
                        .put("enabled", entry.enabled);
                if (entry.sourceUrl != null && !entry.sourceUrl.isEmpty()) {
                    item.put("sourceUrl", entry.sourceUrl);
                }
                list.put(item);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        listPrefs().edit().putString("list", list.toString()).apply();
    }
}
