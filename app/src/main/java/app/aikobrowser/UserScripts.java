package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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

/** Owns the normal profile's locally installed scripts and WebView registrations. */
final class UserScripts {
    private static final String BRIDGE = "__aikoScriptValues";
    private static final int MAX_SCRIPTS = 24, MAX_SOURCES = 1024 * 1024, MAX_VALUE_CHARS = 32768;
    private final SharedPreferences prefs;
    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<WebView, List<Binding>> views = new IdentityHashMap<>();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<String, JSONObject> values = new HashMap<>();
    private final Set<Entry> pending = new HashSet<>();
    private final Runnable flush = this::flushValues;
    private boolean loaded, registrationFailed;
    private String runtime;
    private Boolean isolatedWorlds, earlyScripts;

    static final class Entry {
        final String id;
        final UserScript script;
        boolean enabled;
        String program;
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
        ScriptHandler handle;

        Binding(Entry entry, JavaScriptExecutionWorld world, boolean bridge, boolean legacyStart) {
            this.entry = entry;
            this.world = world;
            this.bridge = bridge;
            this.legacyStart = legacyStart;
        }

        void register(WebView view, String javascript, int event) {
            if (handle != null) {
                handle.remove();
                handle = null;
            }
            handle = legacyStart ? WebViewCompat.addDocumentStartJavaScript(view, javascript, entry.script.origins)
                    : WebViewCompat.addJavaScriptOnEvent(view, javascript, event, entry.script.origins, world);
        }

        void remove(WebView view) {
            try {
                if (handle != null) handle.remove();
            } finally {
                if (bridge) WebViewCompat.removeWebMessageListener(view, world, BRIDGE);
            }
        }
    }

    UserScripts(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("userscripts", Context.MODE_PRIVATE);
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
            throw new IllegalArgumentException("最多保存 24 个脚本，源码总量不能超过约 1 MiB。");
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
        if (!entries.contains(entry)) return;
        entry.enabled = enabled;
        saveList();
        refreshViews();
    }

    void delete(Entry entry) {
        if (!entries.remove(entry)) return;
        pending.remove(entry);
        values.remove(entry.id);
        prefs.edit().remove("values-" + entry.id).apply();
        saveList();
        refreshViews();
    }

    void attach(WebView view) {
        List<Binding> bindings = new ArrayList<>();
        views.put(view, bindings);
        // Empty installs never query the provider's script capabilities.
        if (hasEnabled()) register(view, bindings);
    }

    void detach(WebView view) { views.remove(view); }

    void pageFinished(WebView view, String url) {
        if (!hasEnabled() || !views.containsKey(view) || !Objects.equals(url, view.getUrl())
                || isolatedWorlds() || earlyScripts())
            return;
        URI address = UserScript.address(url);
        if (address == null) return;
        for (Entry entry : entries) {
            UserScript script = entry.script;
            if (entry.enabled && !script.requiresIsolation && !script.runAt.equals("document-start") && script.matches(address)) {
                try {
                    view.evaluateJavascript(payload(entry), null);
                } catch (IOException | RuntimeException error) {
                    registrationFailed = true;
                    Log.w("AikoScripts", "Could not inject end-of-load userscript");
                }
            }
        }
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
                binding = new Binding(entry, world, bridge, legacyStart);
                if (bridge) {
                    WebViewCompat.addWebMessageListener(view, BRIDGE, entry.script.origins, world,
                            (source, message, origin, main, reply) -> {
                                if (main && source == view && views.containsKey(view)
                                        && message.getType() == WebMessageCompat.TYPE_STRING)
                                    receive(entry, message.getData(), origin.toString());
                            });
                }
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
            if (op.equals("set") && data.length() == 4 && entry.script.permits("setValue")) {
                String encoded = data.getString(3);
                if (encoded.length() > MAX_VALUE_CHARS) return;
                JSONTokener tokener = new JSONTokener(encoded);
                Object incoming = tokener.nextValue();
                if (tokener.nextClean() != 0) return;
                Object previous = current.opt(key);
                current.put(key, incoming);
                if (current.length() > 128 || current.toString().length() > MAX_VALUE_CHARS) {
                    if (previous == null) current.remove(key); else current.put(key, previous);
                    return;
                }
            } else if (op.equals("delete") && data.length() == 3 && entry.script.permits("deleteValue")) {
                if (current.remove(key) == null) return;
            } else {
                return;
            }
            if (pending.isEmpty()) handler.postDelayed(flush, 150);
            pending.add(entry);
        } catch (JSONException | RuntimeException invalid) {
            Log.d("AikoScripts", "Invalid userscript storage message");
        }
    }

    void flushValues() {
        handler.removeCallbacks(flush);
        if (pending.isEmpty()) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (Entry entry : pending) {
            edit.putString("values-" + entry.id, values(entry).toString());
            entry.program = null;
        }
        edit.apply();
        // New documents receive fresh synchronous GM_getValue snapshots. No script reruns in an open document.
        for (Map.Entry<WebView, List<Binding>> item : views.entrySet()) {
            for (Binding binding : item.getValue()) {
                if (!binding.bridge || !pending.contains(binding.entry)) continue;
                try { binding.register(item.getKey(), payload(binding.entry), event(binding.entry.script)); }
                catch (IOException | RuntimeException | LinkageError error) {
                    registrationFailed = true;
                    Log.w("AikoScripts", "Could not refresh userscript storage snapshot");
                }
            }
        }
        pending.clear();
    }

    private JSONObject values(Entry entry) {
        JSONObject existing = values.get(entry.id);
        if (existing != null) return existing;
        try { existing = new JSONObject(prefs.getString("values-" + entry.id, "{}")); }
        catch (JSONException ignored) { existing = new JSONObject(); }
        values.put(entry.id, existing);
        return existing;
    }

    private String payload(Entry entry) throws IOException {
        if (entry.program != null) return entry.program;
        if (runtime == null) {
            try (InputStream stream = app.getAssets().open("userscripts/runtime.js")) {
                runtime = UserScriptInput.read(stream, 65536);
            }
        }
        UserScript script = entry.script;
        try {
            JSONObject config = new JSONObject()
                    .put("id", entry.id).put("release", "0.1.9").put("runAt", script.runAt)
                    .put("includes", patterns(script.includes)).put("excludes", patterns(script.excludes))
                    .put("grants", new JSONArray(script.grants)).put("storage", script.needsStorage())
                    .put("values", script.needsStorage() ? values(entry) : new JSONObject())
                    .put("script", new JSONObject().put("name", script.name).put("namespace", script.namespace)
                            .put("version", script.version));
            entry.program = "(" + runtime + ")(JSON.parse(" + JSONObject.quote(config.toString()) + "),"
                    + JSONObject.quote(script.code) + ");";
            return entry.program;
        } catch (JSONException malformed) {
            throw new IOException("Invalid userscript metadata", malformed);
        }
    }

    private static JSONArray patterns(List<UserScript.UrlPattern> rules) {
        JSONArray result = new JSONArray();
        for (UserScript.UrlPattern rule : rules) {
            result.put(new JSONArray().put(rule.scheme).put(rule.host).put(rule.subdomains).put(rule.port).put(rule.path));
        }
        return result;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray list = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < Math.min(list.length(), MAX_SCRIPTS); i++) {
                try {
                    JSONObject item = list.getJSONObject(i);
                    String id = UUID.fromString(item.getString("id")).toString();
                    entries.add(new Entry(id, UserScript.parse(item.getString("source")), item.optBoolean("enabled")));
                } catch (JSONException | IllegalArgumentException invalid) {
                    Log.w("AikoScripts", "Ignored invalid saved userscript");
                }
            }
        } catch (JSONException invalid) {
            Log.w("AikoScripts", "Ignored invalid saved userscript list");
        }
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
        prefs.edit().putString("list", list.toString()).apply();
    }
}
