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
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings, subscriptions, and background rule loading. A failed refresh keeps the existing snapshot. */
public final class SiteFilter {
    private static final String[] TRACKERS = {
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "googlesyndication.com", "scorecardresearch.com", "analytics.tiktok.com",
        "bat.bing.com", "static.ads-twitter.com", "analytics.twitter.com"
    };

    public static final class BuiltinList {
        public final String id, title, desc, region, url;
        public final boolean defaultEnabled;

        public BuiltinList(String id, String title, String desc, String region, String url, boolean defaultEnabled) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.region = region;
            this.url = url;
            this.defaultEnabled = defaultEnabled;
        }
    }

    public static final class Subscription {
        public final String id, name, url;
        public final boolean enabled;
        public final int ruleCount;
        public final long updatedAt;
        public final String lastError;

        public Subscription(String id, String name, String url, boolean enabled,
                            int ruleCount, long updatedAt, String lastError) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.enabled = enabled;
            this.ruleCount = ruleCount;
            this.updatedAt = updatedAt;
            this.lastError = lastError;
        }

        Subscription withEnabled(boolean nextEnabled) {
            return new Subscription(id, name, url, nextEnabled, ruleCount, updatedAt, lastError);
        }

        Subscription withRefresh(int nextCount, long nextUpdatedAt, String nextError) {
            return new Subscription(id, name, url, enabled, nextCount, nextUpdatedAt, nextError);
        }
    }

    public static final class RecommendedSub {
        public final String name, desc, region, url;

        public RecommendedSub(String name, String desc, String region, String url) {
            this.name = name;
            this.desc = desc;
            this.region = region;
            this.url = url;
        }
    }

    private static final BuiltinList[] BUILTINS = {
        new BuiltinList(
            "adguard-cn",
            "中文网站广告过滤",
            "整合 EasyList China 与 AdGuard 中文规则，强力拦截国内网站广告",
            "中国",
            "https://filters.adtidy.org/extension/chromium/filters/224.txt",
            true),
        new BuiltinList(
            "cjx-annoyance",
            "中文弹窗与引流净化",
            "去除国内网页的「打开 App」诱导条、底部悬浮推广与反屏蔽遮罩",
            "中国",
            "https://raw.githubusercontent.com/cjx82630/cjxlist/master/cjx-annoyance.txt",
            true),
        new BuiltinList(
            "adguard-mobile",
            "移动端广告联盟拦截",
            "专治手机网页与移动广告联盟的横幅、插屏与信息流推广",
            "移动",
            "https://filters.adtidy.org/extension/chromium/filters/11_optimized.txt",
            true),
        new BuiltinList(
            "adguard-mobile-app",
            "「打开 App」横幅净化",
            "隐藏全球手机网页顶部与底部的「在 App 中打开」引流条",
            "移动",
            "https://filters.adtidy.org/extension/chromium/filters/20.txt",
            true),
        new BuiltinList(
            "easylist",
            "EasyList 全球基础广告",
            "拦截全球主流网站的横幅、弹窗、贴片与信息流广告",
            "全球",
            "https://easylist.to/easylist/easylist.txt",
            true),
        new BuiltinList(
            "easyprivacy",
            "EasyPrivacy 隐私追踪防护",
            "拦截跨站统计脚本、埋点像素与第三方行为追踪",
            "全球",
            "https://easylist.to/easylist/easyprivacy.txt",
            true),
        new BuiltinList(
            "adguard-cookies",
            "Cookie 隐私弹窗净化",
            "自动隐藏海外网站的 Cookie 同意横幅与全屏隐私遮罩",
            "全球",
            "https://filters.adtidy.org/extension/chromium/filters/18_optimized.txt",
            true)
    };

    private static final int[] DEFAULT_BUILTIN_COUNTS = {
        18204, // adguard-cn
        1764,  // cjx-annoyance
        6241,  // adguard-mobile
        3289,  // adguard-mobile-app
        59430, // easylist
        56514, // easyprivacy
        6317   // adguard-cookies
    };

    private static final List<BuiltinList> BUILTIN_LIST = Collections.unmodifiableList(Arrays.asList(BUILTINS));

    private static final List<RecommendedSub> RECOMMENDED_SUBS = Collections.unmodifiableList(Arrays.asList(
        new RecommendedSub(
            "anti-AD 中文广告强化",
            "中文区高命中率广告与日志域名库（约 10 万条），适合深度净化国内站点",
            "中国",
            "https://anti-ad.net/adguard.txt"),
        new RecommendedSub(
            "AdRules 中文综合规则",
            "国内高频更新聚合规则，强化屏蔽视频广告、追踪与 PCDN 流量劫持",
            "中国",
            "https://adrules.top/adblock.txt"),
        new RecommendedSub(
            "EasyList China 纯净版",
            "经典 EasyList 中文官方分支，适合需要轻量独立中文规则的用户",
            "中国",
            "https://easylist-downloads.adblockplus.org/easylistchina.txt"),
        new RecommendedSub(
            "Peter Lowe's Ad Servers",
            "老牌极简全球广告与追踪域名列表（仅 70 KB，100% 规则识别、极低误杀）",
            "全球",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"),
        new RecommendedSub(
            "HaGeZi Multi LIGHT",
            "全球热门高质量广告、追踪与遥测域名库，兼顾强拦截与高兼容性",
            "全球",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt"),
        new RecommendedSub(
            "AdGuard 烦人元素全家桶",
            "一次性净化弹窗、悬浮客服、社交挂件、订阅提醒与 Cookie 横幅",
            "全球",
            "https://filters.adtidy.org/extension/chromium/filters/14.txt"),
        new RecommendedSub(
            "uBlock Origin 补充广告规则",
            "uBlock Origin 官方补充广告规则，与 EasyList 互补拦截新型推广",
            "全球",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"),
        new RecommendedSub(
            "uBlock Origin 隐私强化规则",
            "uBlock Origin 官方隐私强化规则，额外拦截隐蔽遥测与追踪接口",
            "全球",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt")
    ));

    private static final int DEFAULT_LISTS_MASK = defaultListsMask();
    private static final int MAX_SUB_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SUBSCRIPTIONS = 32;
    private static final int MAX_SUB_NAME_CHARS = 60;
    private static final int FETCH_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int GZIP_BUFFER = 32 * 1024;
    private static final int IO_BUFFER = 64 * 1024;
    private static final int CACHE_MAGIC = 0x41494B43; // "AIKC"
    private static final int CACHE_VERSION = 3;
    private static final String CACHE_FILE = "filter-rules-v1.bin";
    private static final int CSS_CACHE_LIMIT = 16;

    private static final String PREF_SUBS_JSON = "subs_json";
    private static final String PREF_LAST_UPDATED = "last_updated_at";
    private static final String PREF_BUILTIN_COUNTS = "builtin_counts";

    private static int defaultListsMask() {
        int mask = 0;
        for (int i = 0; i < BUILTINS.length; i++) {
            if (BUILTINS[i].defaultEnabled) mask |= (1 << i);
        }
        return mask;
    }

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

    private static final class FetchedList {
        final byte[] bytes;
        final int ruleCount;
        final String headerTitle;

        FetchedList(byte[] bytes, int ruleCount, String headerTitle) {
            this.bytes = bytes;
            this.ruleCount = ruleCount;
            this.headerTitle = headerTitle;
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
    private final Object customFileLock = new Object();
    private final List<Runnable> readyWaiters = new ArrayList<>(2);
    private final LinkedHashMap<String, CssEntry> cssCache = new LinkedHashMap<String, CssEntry>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CssEntry> eldest) {
            return size() > CSS_CACHE_LIMIT;
        }
    };
    private final ConcurrentHashMap<String, Integer> builtinCounts = new ConcurrentHashMap<>(16);
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private FilterRules cssCacheRules = FilterRules.EMPTY;
    private volatile boolean prefsLoaded;
    private volatile Set<String> exclusions = Collections.emptySet();
    private volatile int listMask = DEFAULT_LISTS_MASK;
    private volatile List<Subscription> subs = Collections.emptyList();
    private volatile long lastUpdatedAt;
    volatile FilterRules rules = FilterRules.EMPTY;
    private boolean started, loadCompleted;

    SiteFilter(Context context) {
        app = context.getApplicationContext();
        storage = app.getSharedPreferences("site_filter", Context.MODE_PRIVATE);
        for (int i = 0; i < BUILTINS.length; i++) {
            builtinCounts.put(BUILTINS[i].id, DEFAULT_BUILTIN_COUNTS[i]);
        }
    }

    private void ensurePrefsLoaded() {
        if (prefsLoaded) return;
        synchronized (prefsLock) {
            if (prefsLoaded) return;
            exclusions = new HashSet<>(storage.getStringSet("allow", Collections.emptySet()));
            int mask = 0;
            for (int i = 0; i < BUILTINS.length; i++) {
                BuiltinList b = BUILTINS[i];
                if (storage.getBoolean("list_" + b.id, b.defaultEnabled)) mask |= (1 << i);
            }
            listMask = mask;
            subs = parseSubscriptions(storage.getString(PREF_SUBS_JSON, null));
            lastUpdatedAt = storage.getLong(PREF_LAST_UPDATED, 0L);
            loadSavedBuiltinCounts(storage.getString(PREF_BUILTIN_COUNTS, null));
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
                List<Subscription> activeSubs = subs;
                byte[] fp = fingerprint(custom, enabledMask, activeSubs);
                loaded = readBinaryCache(fp);
                if (loaded == null) {
                    loaded = compile(custom, enabledMask, activeSubs);
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

    // --- Built-in catalog & metadata ---

    public List<BuiltinList> builtinLists() {
        return BUILTIN_LIST;
    }

    public int builtinRuleCount(String id) {
        if (id == null || listIndex(id) < 0) return 0;
        ensurePrefsLoaded();
        Integer count = builtinCounts.get(id);
        return count == null ? 0 : count;
    }

    public long lastUpdatedAt() {
        ensurePrefsLoaded();
        return lastUpdatedAt;
    }

    public List<RecommendedSub> recommendedSubscriptions() {
        return RECOMMENDED_SUBS;
    }

    public List<Subscription> subscriptions() {
        ensurePrefsLoaded();
        return subs;
    }

    // --- Custom Online Subscriptions ---

    public void addSubscription(String name, String url, Consumer<String> onResult) {
        String cleanUrl = url == null ? "" : url.trim();
        if (!HttpUrlUtil.isHttp(cleanUrl)) {
            postConsumer(onResult, "请输入有效的 http:// 或 https:// 订阅链接");
            return;
        }
        String cleanName = sanitizeName(name);
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                ensurePrefsLoaded();
                List<Subscription> current = subs;
                if (current.size() >= MAX_SUBSCRIPTIONS) {
                    postConsumer(onResult, "最多支持添加 " + MAX_SUBSCRIPTIONS + " 个在线订阅");
                    return;
                }
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).url.equalsIgnoreCase(cleanUrl)) {
                        postConsumer(onResult, "该订阅链接已添加");
                        return;
                    }
                }

                FetchedList fetched;
                try {
                    fetched = fetchValidated(cleanUrl, MAX_SUB_BYTES, false);
                } catch (IOException e) {
                    postConsumer(onResult, readableFetchError(e));
                    return;
                }

                String resolvedName = cleanName;
                if (resolvedName.isEmpty()) resolvedName = sanitizeName(fetched.headerTitle);
                if (resolvedName.isEmpty()) resolvedName = HttpUrlUtil.extractHost(cleanUrl);
                if (resolvedName.isEmpty()) resolvedName = "自定义订阅";

                String id = generateSubId(cleanUrl);
                AtomicFile dest = subFile(id);
                try {
                    writeAtomic(dest, fetched.bytes);
                } catch (IOException e) {
                    postConsumer(onResult, "保存订阅规则文件失败");
                    return;
                }

                long now = System.currentTimeMillis();
                Subscription added = new Subscription(id, resolvedName, cleanUrl, true, fetched.ruleCount, now, null);
                List<Subscription> nextSubs;
                synchronized (prefsLock) {
                    List<Subscription> latest = subs;
                    // Re-check duplicate & cap under lock in case two adds raced
                    if (latest.size() >= MAX_SUBSCRIPTIONS) {
                        dest.delete();
                        postConsumer(onResult, "最多支持添加 " + MAX_SUBSCRIPTIONS + " 个在线订阅");
                        return;
                    }
                    for (int i = 0; i < latest.size(); i++) {
                        if (latest.get(i).url.equalsIgnoreCase(cleanUrl)) {
                            dest.delete();
                            postConsumer(onResult, "该订阅链接已添加");
                            return;
                        }
                    }
                    ArrayList<Subscription> copy = new ArrayList<>(latest.size() + 1);
                    copy.addAll(latest);
                    copy.add(added);
                    nextSubs = Collections.unmodifiableList(copy);
                    subs = nextSubs;
                    lastUpdatedAt = now;
                }

                try {
                    String custom = loadCustom();
                    int enabledMask = listMask;
                    FilterRules next = compile(custom, enabledMask, nextSubs);
                    saveSubscriptionsPref(nextSubs, now);
                    publishRules(next);
                    writeBinaryCache(next, fingerprint(custom, enabledMask, nextSubs));
                    postConsumer(onResult, null);
                } catch (IOException | RuntimeException failure) {
                    android.util.Log.w("AikoFilter", "Failed to compile after adding subscription", failure);
                    synchronized (prefsLock) {
                        ArrayList<Subscription> rollback = new ArrayList<>(subs.size());
                        for (int i = 0; i < subs.size(); i++) {
                            Subscription s = subs.get(i);
                            if (!s.id.equals(added.id)) rollback.add(s);
                        }
                        subs = rollback.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(rollback);
                    }
                    dest.delete();
                    postConsumer(onResult, "规则编译失败，已取消添加");
                }
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            }
        });
    }

    public void toggleSubscription(String id, boolean enabled, Runnable onDone) {
        if (!isSafeSubId(id)) {
            postRunnable(onDone);
            return;
        }
        ensurePrefsLoaded();
        List<Subscription> updated;
        synchronized (prefsLock) {
            List<Subscription> current = subs;
            int idx = findSubIndex(current, id);
            if (idx < 0 || current.get(idx).enabled == enabled) {
                postRunnable(onDone);
                return;
            }
            ArrayList<Subscription> copy = new ArrayList<>(current);
            copy.set(idx, current.get(idx).withEnabled(enabled));
            updated = Collections.unmodifiableList(copy);
            subs = updated;
        }
        saveSubscriptionsPref(updated, -1L);
        worker.execute(() -> {
            try {
                recompileFromCurrentState(true);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not apply subscription toggle", e);
            }
            postRunnable(onDone);
        });
    }

    public void removeSubscription(String id, Runnable onDone) {
        if (!isSafeSubId(id)) {
            postRunnable(onDone);
            return;
        }
        ensurePrefsLoaded();
        List<Subscription> updated;
        synchronized (prefsLock) {
            List<Subscription> current = subs;
            int idx = findSubIndex(current, id);
            if (idx < 0) {
                postRunnable(onDone);
                return;
            }
            ArrayList<Subscription> copy = new ArrayList<>(current);
            copy.remove(idx);
            updated = Collections.unmodifiableList(copy);
            subs = updated;
        }
        saveSubscriptionsPref(updated, -1L);
        worker.execute(() -> {
            try {
                subFile(id).delete();
                recompileFromCurrentState(true);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not remove subscription", e);
            }
            postRunnable(onDone);
        });
    }

    public void updateAll(Consumer<String> onSummary) {
        if (!updating.compareAndSet(false, true)) {
            postConsumer(onSummary, "规则正在更新中，请稍候…");
            return;
        }
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                ensurePrefsLoaded();
                int enabledMask = listMask;
                List<Subscription> subSnapshot = subs;
                int targetCount = 0;
                int refreshed = 0;
                int failed = 0;

                // 1. Update all enabled built-in lists
                for (int i = 0; i < BUILTINS.length; i++) {
                    if ((enabledMask & (1 << i)) == 0) continue;
                    targetCount++;
                    BuiltinList b = BUILTINS[i];
                    try {
                        FetchedList fetched = fetchValidated(b.url, MAX_SUB_BYTES, true);
                        writeAtomic(cache(i), fetched.bytes);
                        refreshed++;
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        android.util.Log.w("AikoFilter", "Kept existing built-in list " + b.id, e);
                    }
                }

                // 2. Update all enabled custom subscriptions
                Map<String, Subscription> subUpdates = new HashMap<>(4);
                long now = System.currentTimeMillis();
                for (int i = 0; i < subSnapshot.size(); i++) {
                    Subscription sub = subSnapshot.get(i);
                    if (!sub.enabled) continue;
                    targetCount++;
                    try {
                        FetchedList fetched = fetchValidated(sub.url, MAX_SUB_BYTES, false);
                        writeAtomic(subFile(sub.id), fetched.bytes);
                        subUpdates.put(sub.id, sub.withRefresh(fetched.ruleCount, now, null));
                        refreshed++;
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        String err = e instanceof IOException ? readableFetchError((IOException) e) : "更新失败";
                        subUpdates.put(sub.id, sub.withRefresh(sub.ruleCount, sub.updatedAt, err));
                        android.util.Log.w("AikoFilter", "Kept existing subscription " + sub.id, e);
                    }
                }

                // Merge subscription metadata updates safely without clobbering concurrent toggles/removals
                List<Subscription> mergedSubs = null;
                if (!subUpdates.isEmpty()) {
                    synchronized (prefsLock) {
                        ArrayList<Subscription> copy = new ArrayList<>(subs.size());
                        for (int i = 0; i < subs.size(); i++) {
                            Subscription cur = subs.get(i);
                            Subscription upd = subUpdates.get(cur.id);
                            if (upd != null) {
                                copy.add(new Subscription(cur.id, cur.name, cur.url, cur.enabled,
                                        upd.ruleCount, upd.updatedAt, upd.lastError));
                            } else {
                                copy.add(cur);
                            }
                        }
                        mergedSubs = Collections.unmodifiableList(copy);
                        subs = mergedSubs;
                    }
                }

                if (refreshed > 0) {
                    long ts = System.currentTimeMillis();
                    synchronized (prefsLock) {
                        lastUpdatedAt = ts;
                    }
                    if (mergedSubs != null) {
                        saveSubscriptionsPref(mergedSubs, ts);
                    } else {
                        storage.edit().putLong(PREF_LAST_UPDATED, ts).apply();
                    }
                    try {
                        recompileFromCurrentState(false);
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("AikoFilter", "Kept existing compiled rules after updateAll", e);
                    }
                } else if (mergedSubs != null) {
                    saveSubscriptionsPref(mergedSubs, -1L);
                }

                FilterRules snap = rules;
                int totalRules = snap.networkCount + snap.cosmeticCount;
                String summary;
                if (targetCount == 0) {
                    summary = "当前没有已启用的规则列表";
                } else if (refreshed == 0) {
                    summary = "更新失败，请检查网络后重试（已保留原有规则）";
                } else if (failed == 0) {
                    summary = String.format(Locale.ROOT, "已更新 %d 份规则（共 %,d 条）", refreshed, totalRules);
                } else {
                    summary = String.format(Locale.ROOT, "已更新 %d 份规则，%d 份失败（共 %,d 条）",
                            refreshed, failed, totalRules);
                }
                postConsumer(onSummary, summary);
            } finally {
                updating.set(false);
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            }
        });
    }

    public void addDomainBlockRule(String host, Consumer<Boolean> onDone) {
        String norm = CustomFilterList.normalizeDomainForRule(host);
        if (norm == null) {
            postConsumer(onDone, Boolean.FALSE);
            return;
        }
        appendCustomRule("||" + norm + "^", onDone);
    }

    public void addDomainAllowRule(String host, Consumer<Boolean> onDone) {
        String norm = CustomFilterList.normalizeDomainForRule(host);
        if (norm == null) {
            postConsumer(onDone, Boolean.FALSE);
            return;
        }
        appendCustomRule("@@||" + norm + "^", onDone);
    }

    // --- Legacy Refresh API (preserved for full backward compatibility) ---

    interface RefreshResult { void done(int refreshed, boolean applied); }

    boolean update(RefreshResult callback) {
        if (!updating.compareAndSet(false, true)) return false;
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                ensurePrefsLoaded();
                int enabledMask = listMask;
                int refreshed = 0;
                for (int i = 0; i < BUILTINS.length; i++) {
                    if ((enabledMask & (1 << i)) == 0) continue;
                    BuiltinList b = BUILTINS[i];
                    try {
                        FetchedList fetched = fetchValidated(b.url, MAX_SUB_BYTES, true);
                        writeAtomic(cache(i), fetched.bytes);
                        refreshed++;
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("AikoFilter", "Kept existing list " + b.id, e);
                    }
                }
                boolean compiled = false;
                if (refreshed > 0) {
                    long ts = System.currentTimeMillis();
                    synchronized (prefsLock) {
                        lastUpdatedAt = ts;
                    }
                    storage.edit().putLong(PREF_LAST_UPDATED, ts).apply();
                    try {
                        recompileFromCurrentState(false);
                        compiled = true;
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("AikoFilter", "Kept existing compiled rules", e);
                    }
                }
                final int count = refreshed;
                final boolean applied = compiled;
                if (callback != null) {
                    main.post(() -> callback.done(count, applied));
                }
            } finally {
                updating.set(false);
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
            postRunnable(onDone);
            return;
        }
        ensurePrefsLoaded();
        synchronized (prefsLock) {
            int nextMask = enabled ? (listMask | (1 << idx)) : (listMask & ~(1 << idx));
            if (nextMask == listMask) {
                postRunnable(onDone);
                return;
            }
            listMask = nextMask;
        }
        storage.edit().putBoolean("list_" + id, enabled).apply();
        worker.execute(() -> {
            try {
                recompileFromCurrentState(true);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("AikoFilter", "Could not apply filter list toggle", e);
            }
            postRunnable(onDone);
        });
    }

    public void appendCustomRule(String rule, Runnable onDone) {
        appendCustomRule(rule, (Boolean ok) -> {
            if (onDone != null) onDone.run();
        });
    }

    public void appendCustomRule(String rule, Consumer<Boolean> onDone) {
        String trimmed = rule == null ? "" : rule.trim();
        if (trimmed.isEmpty()) {
            postConsumer(onDone, Boolean.FALSE);
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
                String nextSource;
                CustomFilterList list;
                synchronized (customFileLock) {
                    try { existing = customSource(); }
                    catch (IOException ignored) { existing = ""; }
                    if (containsExactLine(existing, trimmed)) {
                        saved = true;
                        nextSource = null;
                        list = null;
                    } else {
                        nextSource = existing.isEmpty() ? trimmed
                                : (existing.endsWith("\n") || existing.endsWith("\r")
                                        ? existing + trimmed : existing + "\n" + trimmed);
                        list = CustomFilterList.parse(nextSource);
                        writeAtomic(customFile(), nextSource.getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (list != null) {
                    int enabledMask = listMask;
                    List<Subscription> activeSubs = subs;
                    FilterRules next = compile(list.rules, enabledMask, activeSubs);
                    publishRules(next);
                    writeBinaryCache(next, fingerprint(list.rules, enabledMask, activeSubs));
                    saved = true;
                }
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("AikoFilter", "Could not append custom filter rule", failure);
            }
            postConsumer(onDone, saved);
        });
    }

    /** Returns exact trimmed rule lines in filters-user.txt scoped to the given host (for Site Shield undo). */
    public List<String> userRulesForHost(String host) {
        String normHost = normal(host);
        if (normHost == null) return Collections.emptyList();
        try {
            return filterUserRulesForHost(customSource(), normHost);
        } catch (IOException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    /** Pure-Java helper matching exact custom filter lines in source scoped to host. */
    public static List<String> filterUserRulesForHost(String source, String host) {
        String normHost = normal(host);
        if (normHost == null || source == null || source.isEmpty()) return Collections.emptyList();
        ArrayList<String> matches = new ArrayList<>();
        int len = source.length(), pos = 0;
        while (pos < len) {
            int end = pos;
            while (end < len && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            String line = source.substring(pos, end).trim();
            if (!line.isEmpty() && !line.startsWith("!") && !line.equalsIgnoreCase("[Adblock Plus 2.0]")) {
                if (ruleLineAppliesToHost(line, normHost)) {
                    matches.add(line);
                }
            }
            pos = end + 1;
            if (end < len && source.charAt(end) == '\r' && pos < len && source.charAt(pos) == '\n') pos++;
        }
        return matches;
    }

    private static boolean ruleLineAppliesToHost(String line, String normHost) {
        int cosmeticIdx = line.indexOf("##");
        int excCosmeticIdx = line.indexOf("#@#");
        int sepIdx = cosmeticIdx >= 0 && excCosmeticIdx >= 0
                ? Math.min(cosmeticIdx, excCosmeticIdx)
                : Math.max(cosmeticIdx, excCosmeticIdx);
        if (sepIdx > 0) {
            String domainsPart = line.substring(0, sepIdx);
            String[] domains = domainsPart.split(",");
            for (String rawDom : domains) {
                String d = rawDom.trim();
                if (d.isEmpty() || d.startsWith("~")) continue;
                String nd = normal(d);
                if (nd != null && (normHost.equals(nd) || atDomain(normHost, nd))) return true;
            }
            return false;
        }
        // Network rules: ||domain^ or @@||domain^ or $domain=...
        String net = line.startsWith("@@") ? line.substring(2) : line;
        if (net.startsWith("||")) {
            int end = 2;
            while (end < net.length()) {
                char c = net.charAt(end);
                if (c == '^' || c == '/' || c == '$' || c == '*' || c == ':') break;
                end++;
            }
            if (end > 2) {
                String nd = normal(net.substring(2, end));
                if (nd != null && (normHost.equals(nd) || atDomain(normHost, nd))) return true;
            }
        }
        int dollar = line.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < line.length()) {
            String opts = line.substring(dollar + 1);
            for (String opt : opts.split(",")) {
                String o = opt.trim();
                if (o.startsWith("domain=")) {
                    String list = o.substring("domain=".length());
                    for (String rawDom : list.split("\\|")) {
                        String d = rawDom.trim();
                        if (d.isEmpty() || d.startsWith("~")) continue;
                        String nd = normal(d);
                        if (nd != null && (normHost.equals(nd) || atDomain(normHost, nd))) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Removes an exact rule line from filters-user.txt atomically (serialized with worker file I/O)
     * and schedules a clean recompile. Returns true if the line existed and was removed.
     */
    public boolean removeUserRule(String exactRuleLine) {
        String target = exactRuleLine == null ? "" : exactRuleLine.trim();
        if (target.isEmpty()) return false;
        try {
            synchronized (customFileLock) {
                String existing = customSource();
                String updated = removeExactRuleLine(existing, target);
                if (updated == null) return false;
                writeAtomic(customFile(), updated.getBytes(StandardCharsets.UTF_8));
            }
            worker.execute(() -> {
                ensurePrefsLoaded();
                try {
                    recompileFromCurrentState(true);
                } catch (IOException | RuntimeException failure) {
                    android.util.Log.w("AikoFilter", "Could not recompile after removing user rule", failure);
                }
            });
            return true;
        } catch (IOException | RuntimeException failure) {
            android.util.Log.w("AikoFilter", "Could not remove custom filter rule", failure);
            return false;
        }
    }

    /**
     * Off-UI-thread variant: removes an exact rule line from filters-user.txt on the filter worker,
     * recompiles and publishes updated rules, and delivers completion status on the main thread.
     */
    public void removeUserRule(String exactRuleLine, Consumer<Boolean> onDone) {
        String target = exactRuleLine == null ? "" : exactRuleLine.trim();
        if (target.isEmpty()) {
            postConsumer(onDone, Boolean.FALSE);
            return;
        }
        worker.execute(() -> {
            ensurePrefsLoaded();
            boolean removed = false;
            try {
                String updated;
                synchronized (customFileLock) {
                    String existing = customSource();
                    updated = removeExactRuleLine(existing, target);
                    if (updated != null) {
                        writeAtomic(customFile(), updated.getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (updated != null) {
                    recompileFromCurrentState(true);
                    removed = true;
                }
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("AikoFilter", "Could not remove custom filter rule asynchronously", failure);
            }
            postConsumer(onDone, removed);
        });
    }

    /** Pure-Java helper removing exact trimmed line from rule source; returns null if not present. */
    public static String removeExactRuleLine(String source, String exactRuleLine) {
        if (source == null || source.isEmpty() || exactRuleLine == null) return null;
        String target = exactRuleLine.trim();
        if (target.isEmpty()) return null;
        StringBuilder out = new StringBuilder(source.length());
        boolean removed = false;
        int len = source.length(), pos = 0;
        while (pos < len) {
            int end = pos;
            while (end < len && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            String rawLine = source.substring(pos, end);
            String trimmed = rawLine.trim();
            if (!removed && trimmed.equals(target)) {
                removed = true;
            } else {
                if (out.length() > 0) out.append('\n');
                out.append(rawLine);
            }
            pos = end + 1;
            if (end < len && source.charAt(end) == '\r' && pos < len && source.charAt(pos) == '\n') pos++;
        }
        return removed ? out.toString() : null;
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
        synchronized (customFileLock) {
            try {
                byte[] bytes = Files.readAllBytes(customFile().getBaseFile().toPath());
                if (bytes.length > 4 * CustomFilterList.MAX_CHARS) throw new IOException("Custom rules too large");
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (NoSuchFileException missing) {
                return "";
            }
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
                List<Subscription> activeSubs = subs;
                FilterRules next = compile(list.rules, enabledMask, activeSubs);
                synchronized (customFileLock) {
                    writeAtomic(customFile(), source.getBytes(StandardCharsets.UTF_8));
                }
                publishRules(next);
                writeBinaryCache(next, fingerprint(list.rules, enabledMask, activeSubs));
                count = list.count;
            } catch (IllegalArgumentException invalid) {
                error = invalid.getMessage();
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("AikoFilter", "Could not save custom filters", failure);
                error = "保存失败，继续使用原有规则。";
            }
            final int saved = count;
            final String detail = error;
            if (callback != null) main.post(() -> callback.done(saved, detail));
        });
    }

    void close() { worker.shutdown(); }

    private void recompileFromCurrentState(boolean allowCacheHit) throws IOException {
        String custom = loadCustom();
        int enabledMask = listMask;
        List<Subscription> activeSubs = subs;
        byte[] fp = fingerprint(custom, enabledMask, activeSubs);
        FilterRules next = allowCacheHit ? readBinaryCache(fp) : null;
        boolean fromCache = next != null;
        if (!fromCache) next = compile(custom, enabledMask, activeSubs);
        publishRules(next);
        if (!fromCache) writeBinaryCache(next, fp);
    }

    private String loadCustom() {
        try {
            return CustomFilterList.parse(customSource()).rules;
        } catch (IOException | IllegalArgumentException invalid) {
            android.util.Log.w("AikoFilter", "Ignoring invalid custom filter data");
            return "";
        }
    }

    private FilterRules compile(String customRules, int enabledMask, List<Subscription> activeSubs) throws IOException {
        FilterRules.Builder builder = new FilterRules.Builder();
        builder.suffixes(new InputStreamReader(
                new GZIPInputStream(app.getAssets().open("filters/psl.txt.gz"), GZIP_BUFFER),
                StandardCharsets.UTF_8));
        if (!customRules.isEmpty()) {
            builder.read(new StringReader(customRules), "自定义规则");
        }
        Map<String, Integer> measuredCounts = new HashMap<>(BUILTINS.length * 2);
        for (int i = 0; i < BUILTINS.length; i++) {
            if ((enabledMask & (1 << i)) == 0) continue;
            BuiltinList b = BUILTINS[i];
            AtomicFile cached = cache(i);
            InputStream source;
            try {
                source = cached.openRead();
            } catch (FileNotFoundException e) {
                source = new GZIPInputStream(app.getAssets().open("filters/" + b.id + ".txt.gz"), GZIP_BUFFER);
            }
            int before = builder.networkCount + builder.cosmeticCount;
            builder.read(new InputStreamReader(source, StandardCharsets.UTF_8), b.title);
            int added = (builder.networkCount + builder.cosmeticCount) - before;
            measuredCounts.put(b.id, added);
        }
        for (int i = 0; i < activeSubs.size(); i++) {
            Subscription sub = activeSubs.get(i);
            if (!sub.enabled || !isSafeSubId(sub.id)) continue;
            try {
                InputStream source = subFile(sub.id).openRead();
                builder.read(new InputStreamReader(source, StandardCharsets.UTF_8), sub.name);
            } catch (FileNotFoundException missing) {
                android.util.Log.w("AikoFilter", "Missing cached subscription file for " + sub.id);
            }
        }
        FilterRules compiled = builder.build();
        if (!measuredCounts.isEmpty()) {
            builtinCounts.putAll(measuredCounts);
            saveBuiltinCountsPref();
        }
        return compiled;
    }

    private byte[] fingerprint(String customRules, int enabledMask, List<Subscription> activeSubs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(384);
            sb.append("v").append(CACHE_VERSION).append(':').append(enabledMask).append(':');
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                sb.append(info.getLongVersionCode()).append(':').append(info.lastUpdateTime).append(':');
            } catch (Exception ignored) {
                sb.append("nopkg:");
            }
            for (int i = 0; i < BUILTINS.length; i++) {
                if ((enabledMask & (1 << i)) == 0) continue;
                BuiltinList b = BUILTINS[i];
                File f = cache(i).getBaseFile();
                sb.append(b.id).append('=');
                if (f.isFile()) sb.append('f').append(f.length()).append('@').append(f.lastModified());
                else sb.append('a');
                sb.append(';');
            }
            sb.append("subs:");
            for (int i = 0; i < activeSubs.size(); i++) {
                Subscription sub = activeSubs.get(i);
                if (!sub.enabled || !isSafeSubId(sub.id)) continue;
                File f = subFile(sub.id).getBaseFile();
                sb.append(sub.id).append('=').append(sub.name).append('|');
                if (f.isFile()) sb.append('f').append(f.length()).append('@').append(f.lastModified());
                else sb.append('m');
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
        for (int i = 0; i < BUILTINS.length; i++) if (BUILTINS[i].id.equals(id)) return i;
        return -1;
    }

    private static int findSubIndex(List<Subscription> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private AtomicFile cache(int i) {
        return new AtomicFile(new File(app.getFilesDir(), "filter-" + BUILTINS[i].id + ".txt"));
    }

    private AtomicFile subFile(String id) {
        return new AtomicFile(new File(app.getFilesDir(), "sub-" + id + ".txt"));
    }

    private AtomicFile customFile() {
        return new AtomicFile(new File(app.getFilesDir(), "filters-user.txt"));
    }

    private static void writeAtomic(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
    }

    private void saveSubscriptionsPref(List<Subscription> list, long updatedTimestamp) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Subscription s = list.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("enabled", s.enabled);
                o.put("ruleCount", s.ruleCount);
                o.put("updatedAt", s.updatedAt);
                if (s.lastError != null && !s.lastError.isEmpty()) o.put("lastError", s.lastError);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        SharedPreferences.Editor ed = storage.edit().putString(PREF_SUBS_JSON, arr.toString());
        if (updatedTimestamp > 0L) ed.putLong(PREF_LAST_UPDATED, updatedTimestamp);
        ed.apply();
    }

    private static List<Subscription> parseSubscriptions(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        try {
            JSONArray arr = new JSONArray(raw);
            ArrayList<Subscription> out = new ArrayList<>(Math.min(arr.length(), MAX_SUBSCRIPTIONS));
            Set<String> seenIds = new HashSet<>(16);
            for (int i = 0; i < arr.length() && out.size() < MAX_SUBSCRIPTIONS; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String url = o.optString("url", "").trim();
                if (!isSafeSubId(id) || !seenIds.add(id) || !HttpUrlUtil.isHttp(url)) continue;
                String name = sanitizeName(o.optString("name", ""));
                if (name.isEmpty()) name = HttpUrlUtil.extractHost(url);
                if (name.isEmpty()) name = "自定义订阅";
                boolean enabled = o.optBoolean("enabled", true);
                int ruleCount = Math.max(0, o.optInt("ruleCount", 0));
                long updatedAt = Math.max(0L, o.optLong("updatedAt", 0L));
                String err = o.optString("lastError", "");
                out.add(new Subscription(id, name, url, enabled, ruleCount, updatedAt, err.isEmpty() ? null : err));
            }
            return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
        } catch (JSONException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private void loadSavedBuiltinCounts(String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (listIndex(k) >= 0) {
                    int v = obj.optInt(k, -1);
                    if (v >= 0) builtinCounts.put(k, v);
                }
            }
        } catch (JSONException | RuntimeException ignored) {
        }
    }

    private void saveBuiltinCountsPref() {
        JSONObject obj = new JSONObject();
        for (int i = 0; i < BUILTINS.length; i++) {
            String id = BUILTINS[i].id;
            Integer v = builtinCounts.get(id);
            if (v != null && v >= 0) {
                try { obj.put(id, v.intValue()); } catch (JSONException ignored) { }
            }
        }
        storage.edit().putString(PREF_BUILTIN_COUNTS, obj.toString()).apply();
    }

    private static boolean isSafeSubId(String id) {
        if (id == null || id.isEmpty() || id.length() > 32) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static String generateSubId(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((url + ":" + System.nanoTime() + ":" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            byte[] dig = md.digest();
            char[] hex = new char[12];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 6; i++) {
                int v = dig[i] & 0xFF;
                hex[i * 2] = digits[v >>> 4];
                hex[i * 2 + 1] = digits[v & 0x0F];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            return Long.toHexString(System.nanoTime());
        }
    }

    private static String sanitizeName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.length() > MAX_SUB_NAME_CHARS) {
            cleaned = cleaned.substring(0, MAX_SUB_NAME_CHARS).trim();
        }
        return cleaned;
    }

    private static FetchedList fetchValidated(String initialUrl, int maxBytes, boolean builtinStrict) throws IOException {
        byte[] bytes = downloadBytes(initialUrl, maxBytes);
        if (bytes.length == 0) throw new IOException("EMPTY_BODY");
        if (builtinStrict && bytes.length < 2000) throw new IOException("INVALID_RULES");

        // Reject binary payloads
        int probeLen = Math.min(bytes.length, 512);
        for (int i = 0; i < probeLen; i++) {
            if (bytes[i] == 0) throw new IOException("INVALID_RULES");
        }

        // Reject HTML/XML error pages or captive portals
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        String trimmedHead = head.startsWith("\ufeff") ? head.substring(1).trim() : head.trim();
        String lowerHead = trimmedHead.length() > 32
                ? trimmedHead.substring(0, 32).toLowerCase(Locale.ROOT)
                : trimmedHead.toLowerCase(Locale.ROOT);
        if (lowerHead.startsWith("<!doctype") || lowerHead.startsWith("<html")
                || lowerHead.startsWith("<head") || lowerHead.startsWith("<body")
                || lowerHead.startsWith("<?xml")) {
            throw new IOException("HTML_PAGE");
        }

        String headerTitle = extractHeaderTitle(head);
        FilterRules.Builder validator = new FilterRules.Builder();
        validator.read(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8), "validate");
        int parsed = validator.networkCount + validator.cosmeticCount;
        int minRequired = builtinStrict ? 100 : 1;
        if (parsed < minRequired) {
            throw new IOException("INVALID_RULES");
        }
        return new FetchedList(bytes, parsed, headerTitle);
    }

    private static byte[] downloadBytes(String initialUrl, int maxBytes) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!HttpUrlUtil.isHttp(current)) throw new IOException("INVALID_URL");
            URL urlObj = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
            conn.setConnectTimeout(FETCH_TIMEOUT_MS);
            conn.setReadTimeout(FETCH_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "AikoBrowser/0.3.1");
            conn.setRequestProperty("Accept", "text/plain, */*;q=0.5");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hop == MAX_REDIRECTS) throw new IOException("TOO_MANY_REDIRECTS");
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("HTTP_" + code);
                    URL nextUrl = new URL(urlObj, location.trim());
                    // Refuse HTTPS -> plain HTTP downgrade on redirect
                    if ("https".equalsIgnoreCase(urlObj.getProtocol())
                            && !"https".equalsIgnoreCase(nextUrl.getProtocol())) {
                        throw new IOException("INSECURE_REDIRECT");
                    }
                    current = nextUrl.toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP_" + code);

                String encoding = conn.getContentEncoding();
                boolean isGzip = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
                if (!isGzip) {
                    long cl = conn.getContentLengthLong();
                    if (cl > maxBytes) throw new IOException("TOO_LARGE");
                }
                try (InputStream raw = conn.getInputStream();
                     InputStream in = isGzip ? new GZIPInputStream(raw, 16384) : raw;
                     ByteArrayOutputStream result = new ByteArrayOutputStream(16384)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (result.size() + n > maxBytes) throw new IOException("TOO_LARGE");
                        result.write(buf, 0, n);
                    }
                    return result.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("TOO_MANY_REDIRECTS");
    }

    private static String extractHeaderTitle(String head) {
        if (head == null || head.isEmpty()) return "";
        int pos = head.startsWith("\ufeff") ? 1 : 0;
        int len = head.length();
        int lines = 0;
        while (pos < len && lines < 40) {
            int end = pos;
            while (end < len && head.charAt(end) != '\n' && head.charAt(end) != '\r') end++;
            String line = head.substring(pos, end).trim();
            if (line.startsWith("!")) {
                String body = line.substring(1).trim();
                if (body.length() > 6 && body.regionMatches(true, 0, "Title:", 0, 6)) {
                    return body.substring(6).trim();
                }
            }
            lines++;
            pos = end + 1;
            if (end < len && head.charAt(end) == '\r' && pos < len && head.charAt(pos) == '\n') pos++;
        }
        return "";
    }

    private static String readableFetchError(IOException e) {
        if (e instanceof SocketTimeoutException) return "连接超时，请检查网络后重试";
        if (e instanceof UnknownHostException) return "无法解析域名，请检查网络连接";
        if (e instanceof SSLException) return "安全证书校验失败，无法建立加密连接";
        String msg = e.getMessage();
        if (msg == null) return "下载规则失败，请稍后重试";
        if ("TOO_LARGE".equals(msg)) return "规则文件超过 8 MB 上限";
        if ("HTML_PAGE".equals(msg)) return "链接返回的是网页而非广告过滤规则";
        if ("INVALID_RULES".equals(msg) || "EMPTY_BODY".equals(msg)) return "未识别到有效的 ABP/EasyList 过滤规则";
        if ("INVALID_URL".equals(msg) || "INSECURE_REDIRECT".equals(msg)) return "订阅链接无效或包含不安全跳转";
        if ("TOO_MANY_REDIRECTS".equals(msg)) return "链接跳转次数过多，请使用直链地址";
        if (msg.startsWith("HTTP_")) return "服务器返回错误（" + msg.replace('_', ' ') + "）";
        return "下载规则失败，请检查网络或链接";
    }

    private void postRunnable(Runnable r) {
        if (r != null) main.post(r);
    }

    private <T> void postConsumer(Consumer<T> c, T value) {
        if (c != null) main.post(() -> c.accept(value));
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
        if (page == null || !enabled(page.host) || request.isForMainFrame()) return false;
        String method = request.getMethod();
        if (!"GET".equals(method) && !"POST".equals(method) && !"HEAD".equals(method)) return false;
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
                + ")return;var css=" + entry.quoted + ";var s=document.getElementById('aiko-filter-style');"
                + "if(s&&s.textContent===css)return;"
                + "if(!s){s=document.createElement('style');s.id='aiko-filter-style';"
                + "var root=document.head||document.documentElement;if(!root)return;root.appendChild(s);}"
                + "s.textContent=css;})();", null);
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
