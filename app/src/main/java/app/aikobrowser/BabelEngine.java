package app.aikobrowser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Babel (巴别塔) concurrent translation engine.
 * Supports Google Free (default), Bing CN direct (zero-key, China-friendly), Youdao CN fallback,
 * Custom OpenAI-compatible & Gemini AI APIs, auto model-list fetching, smart Google->Bing CN
 * failover, dynamic concurrency throttling, cancellable per-tab session generations, and a
 * 4096-entry session LRU hash cache.
 *
 * Pure Java with zero external libraries beyond JDK + org.json (host JVM testable).
 */
public final class BabelEngine {
    public static final int MAX_CACHE_ENTRIES = 4096;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final long BING_TOKEN_TTL_MS = 8L * 60L * 1000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final String DEFAULT_GOOGLE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String DEFAULT_BING_AUTH_ENDPOINT = "https://edge.microsoft.com/translate/auth";
    private static final String DEFAULT_BING_TRANSLATE_ENDPOINT = "https://api-edge.cognitive.microsofttranslator.com/translate";
    private static final String DEFAULT_YOUDAO_ENDPOINT = "https://fanyi.youdao.com/translate";

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile(
            "(?is)<(?:think|thinking|reasoning)>.*?</(?:think|thinking|reasoning)>");
    private static final Pattern UNCLOSED_THINK_PATTERN = Pattern.compile(
            "(?is)^\\s*<(?:think|thinking|reasoning)>.*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "(?s)^```[a-zA-Z0-9_-]*\\s*\\n?(.*?)\\n?```\\s*$");
    private static final Pattern NUMBERED_LINE_PATTERN = Pattern.compile(
            "^\\s*(?:\\[?(\\d{1,3})\\]?[.):\\]]\\s*)(.*)$");

    public static final class TranslationResult {
        public final List<String> translations;
        public final BabelConfig.EngineKind usedEngine;
        public final boolean fellBackToCn;
        public final int cacheHits;
        public final int networkItems;
        public final long latencyMs;
        public final String error;

        public TranslationResult(
                List<String> translations,
                BabelConfig.EngineKind usedEngine,
                boolean fellBackToCn,
                int cacheHits,
                int networkItems,
                long latencyMs,
                String error
        ) {
            this.translations = translations == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(translations));
            this.usedEngine = usedEngine == null ? BabelConfig.EngineKind.GOOGLE_FREE : usedEngine;
            this.fellBackToCn = fellBackToCn;
            this.cacheHits = cacheHits;
            this.networkItems = networkItems;
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    public static final class SingleResult {
        public final String translated;
        public final BabelConfig.EngineKind usedEngine;
        public final boolean fellBackToCn;
        public final boolean fromCache;
        public final long latencyMs;
        public final String error;

        public SingleResult(
                String translated,
                BabelConfig.EngineKind usedEngine,
                boolean fellBackToCn,
                boolean fromCache,
                long latencyMs,
                String error
        ) {
            this.translated = translated == null ? "" : translated;
            this.usedEngine = usedEngine == null ? BabelConfig.EngineKind.GOOGLE_FREE : usedEngine;
            this.fellBackToCn = fellBackToCn;
            this.fromCache = fromCache;
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    public static final class ModelListResult {
        public final List<String> models;
        public final long latencyMs;
        public final String error;

        public ModelListResult(List<String> models, long latencyMs, String error) {
            this.models = models == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(models));
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    public static final class ConnectionTestResult {
        public final boolean ok;
        public final String sampleOutput;
        public final long latencyMs;
        public final String error;

        public ConnectionTestResult(boolean ok, String sampleOutput, long latencyMs, String error) {
            this.ok = ok;
            this.sampleOutput = sampleOutput == null ? "" : sampleOutput;
            this.latencyMs = latencyMs;
            this.error = error;
        }
    }

    public static final class Stats {
        public final long cacheHits;
        public final long networkCalls;
        public final long estimatedPromptChars;
        public final long estimatedOutputChars;
        public final long lastLatencyMs;
        public final String lastActiveEngine;
        public final int cacheSize;

        public Stats(
                long cacheHits,
                long networkCalls,
                long estimatedPromptChars,
                long estimatedOutputChars,
                long lastLatencyMs,
                String lastActiveEngine,
                int cacheSize
        ) {
            this.cacheHits = cacheHits;
            this.networkCalls = networkCalls;
            this.estimatedPromptChars = estimatedPromptChars;
            this.estimatedOutputChars = estimatedOutputChars;
            this.lastLatencyMs = lastLatencyMs;
            this.lastActiveEngine = lastActiveEngine == null ? "" : lastActiveEngine;
            this.cacheSize = cacheSize;
        }
    }

    public interface BatchCallback {
        void onResult(TranslationResult result);
    }

    public interface SingleCallback {
        void onResult(SingleResult result);
    }

    public interface ModelsCallback {
        void onResult(ModelListResult result);
    }

    public interface TestCallback {
        void onResult(ConnectionTestResult result);
    }

    private static final class ActiveRequest {
        final String scope;
        final long generation;
        volatile HttpURLConnection conn;

        ActiveRequest(String scope, long generation) {
            this.scope = scope;
            this.generation = generation;
        }

        void abort() {
            HttpURLConnection c = conn;
            if (c != null) {
                try { c.disconnect(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * Dynamic concurrency gate respecting live changes to maxConcurrency (1..16)
     * while waking promptly on scope cancellation.
     */
    private static final class ConcurrencyGate {
        private int inFlight = 0;

        boolean acquire(int maxConcurrency, CancellationCheck cancelled) throws InterruptedException {
            int limit = BabelConfig.clampConcurrency(maxConcurrency);
            synchronized (this) {
                while (inFlight >= limit) {
                    if (cancelled != null && cancelled.isCancelled()) return false;
                    wait(40L);
                }
                if (cancelled != null && cancelled.isCancelled()) return false;
                inFlight++;
                return true;
            }
        }

        void release() {
            synchronized (this) {
                if (inFlight > 0) inFlight--;
                notifyAll();
            }
        }

        void wakeWaiters() {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    @FunctionalInterface
    private interface CancellationCheck {
        boolean isCancelled();
    }

    private static final class CancelledException extends IOException {
        CancelledException() { super("Translation request cancelled"); }
    }

    private static final class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private final BabelConfig config;
    private final ExecutorService executor;
    private final ConcurrencyGate gate = new ConcurrencyGate();

    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private final ConcurrentHashMap<String, AtomicLong> scopeGenerations = new ConcurrentHashMap<>();
    private final Set<ActiveRequest> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final AtomicLong statCacheHits = new AtomicLong();
    private final AtomicLong statNetworkCalls = new AtomicLong();
    private final AtomicLong statPromptChars = new AtomicLong();
    private final AtomicLong statOutputChars = new AtomicLong();
    private final AtomicLong statLastLatencyMs = new AtomicLong();
    private volatile String statLastActiveEngine = "";

    private final Object bingTokenLock = new Object();
    private volatile String bingToken = null;
    private volatile long bingTokenExpiresAt = 0L;

    // Configurable endpoints (production defaults; overridable in host unit tests)
    private volatile String googleEndpoint = DEFAULT_GOOGLE_ENDPOINT;
    private volatile String bingAuthEndpoint = DEFAULT_BING_AUTH_ENDPOINT;
    private volatile String bingTranslateEndpoint = DEFAULT_BING_TRANSLATE_ENDPOINT;
    private volatile String youdaoEndpoint = DEFAULT_YOUDAO_ENDPOINT;

    public BabelEngine(BabelConfig config) {
        this.config = config == null ? new BabelConfig() : config;
        AtomicInteger threadId = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "aiko-babel-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                BabelConfig.MAX_CONCURRENCY,
                BabelConfig.MAX_CONCURRENCY,
                45L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                factory);
        pool.allowCoreThreadTimeOut(true);
        this.executor = pool;
    }

    /** Test-only endpoint overrides for offline loopback HTTP verification. */
    void setEndpointsForTest(String googleUrl, String bingAuthUrl, String bingTranslateUrl, String youdaoUrl) {
        if (googleUrl != null) this.googleEndpoint = googleUrl;
        if (bingAuthUrl != null) this.bingAuthEndpoint = bingAuthUrl;
        if (bingTranslateUrl != null) this.bingTranslateEndpoint = bingTranslateUrl;
        if (youdaoUrl != null) this.youdaoEndpoint = youdaoUrl;
        clearBingTokenCache();
    }

    void clearBingTokenCache() {
        synchronized (bingTokenLock) {
            bingToken = null;
            bingTokenExpiresAt = 0L;
        }
    }

    public BabelConfig getConfig() {
        return config;
    }

    // ---- Session Generation & Cancellation ----

    public long nextSessionGeneration(String scopeKey) {
        String key = scopeKey == null ? "default" : scopeKey;
        long next = scopeGenerations.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        abortStaleRequests(key, next);
        gate.wakeWaiters();
        return next;
    }

    public long currentSessionGeneration(String scopeKey) {
        String key = scopeKey == null ? "default" : scopeKey;
        AtomicLong gen = scopeGenerations.get(key);
        return gen == null ? 0L : gen.get();
    }

    public void cancelScope(String scopeKey) {
        nextSessionGeneration(scopeKey);
    }

    public boolean isGenerationActive(String scopeKey, long generation) {
        if (scopeKey == null || generation <= 0L) return true;
        AtomicLong gen = scopeGenerations.get(scopeKey);
        return gen == null || gen.get() == generation;
    }

    private void abortStaleRequests(String scopeKey, long currentGen) {
        for (ActiveRequest req : activeRequests) {
            if (scopeKey.equals(req.scope) && req.generation > 0L && req.generation < currentGen) {
                req.abort();
            }
        }
    }

    // ---- Cache & Live Stats ----

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public void resetStats() {
        statCacheHits.set(0);
        statNetworkCalls.set(0);
        statPromptChars.set(0);
        statOutputChars.set(0);
        statLastLatencyMs.set(0);
        statLastActiveEngine = "";
    }

    public Stats getStats() {
        int size;
        synchronized (cache) {
            size = cache.size();
        }
        return new Stats(
                statCacheHits.get(),
                statNetworkCalls.get(),
                statPromptChars.get(),
                statOutputChars.get(),
                statLastLatencyMs.get(),
                statLastActiveEngine.isEmpty() ? config.activeEngineDisplayName() : statLastActiveEngine,
                size
        );
    }

    public void shutdown() {
        for (ActiveRequest req : activeRequests) req.abort();
        executor.shutdownNow();
    }

    // ---- Public Translation APIs ----

    public void translateBatchAsync(
            String scopeKey,
            long generation,
            List<String> texts,
            String targetLang,
            BatchCallback callback
    ) {
        if (callback == null) return;
        final List<String> snapshot = texts == null ? Collections.emptyList() : new ArrayList<>(texts);
        executor.execute(() -> {
            TranslationResult res = translateBatchInternal(scopeKey, generation, snapshot, targetLang);
            if (isGenerationActive(scopeKey, generation)) {
                callback.onResult(res);
            }
        });
    }

    public TranslationResult translateBatch(List<String> texts, String targetLang) {
        return translateBatchInternal(null, 0L, texts == null ? Collections.emptyList() : texts, targetLang);
    }

    public void translateSingleAsync(
            String scopeKey,
            long generation,
            String text,
            String targetLang,
            SingleCallback callback
    ) {
        if (callback == null) return;
        executor.execute(() -> {
            SingleResult res = translateSingleInternal(scopeKey, generation, text, targetLang);
            if (isGenerationActive(scopeKey, generation)) {
                callback.onResult(res);
            }
        });
    }

    public SingleResult translateSingle(String text, String targetLang) {
        return translateSingleInternal(null, 0L, text, targetLang);
    }

    public void fetchModelsAsync(BabelConfig.AiProfile profile, ModelsCallback callback) {
        if (callback == null) return;
        executor.execute(() -> callback.onResult(fetchModels(profile)));
    }

    public void testProfileAsync(BabelConfig.AiProfile profile, String targetLang, TestCallback callback) {
        if (callback == null) return;
        executor.execute(() -> callback.onResult(testProfile(profile, targetLang)));
    }

    // ---- Core Batch Execution ----

    private SingleResult translateSingleInternal(String scopeKey, long generation, String text, String targetLang) {
        if (text == null || text.trim().isEmpty()) {
            return new SingleResult("", config.getEngineKind(), false, true, 0L, null);
        }
        TranslationResult batch = translateBatchInternal(
                scopeKey, generation, Collections.singletonList(text), targetLang);
        if (!batch.isSuccess()) {
            return new SingleResult("", batch.usedEngine, batch.fellBackToCn, false, batch.latencyMs, batch.error);
        }
        String out = batch.translations.isEmpty() ? "" : batch.translations.get(0);
        return new SingleResult(
                out, batch.usedEngine, batch.fellBackToCn,
                batch.networkItems == 0 && batch.cacheHits > 0,
                batch.latencyMs, null
        );
    }

    private TranslationResult translateBatchInternal(
            String scopeKey,
            long generation,
            List<String> texts,
            String requestedTarget
    ) {
        long startNs = System.nanoTime();
        if (texts == null || texts.isEmpty()) {
            return new TranslationResult(Collections.emptyList(), config.getEngineKind(), false, 0, 0, 0L, null);
        }

        BabelConfig.EngineKind configuredEngine = config.getEngineKind();
        BabelConfig.AiProfile activeProfile = configuredEngine == BabelConfig.EngineKind.CUSTOM_AI
                ? config.getActiveProfile() : null;
        if (configuredEngine == BabelConfig.EngineKind.CUSTOM_AI && activeProfile == null) {
            return new TranslationResult(
                    Collections.emptyList(), configuredEngine, false, 0, 0, 0L,
                    "请先新增并选择一个自定义 AI API 配置"
            );
        }

        String target = BabelConfig.normalizeLang(
                requestedTarget == null || requestedTarget.trim().isEmpty() ? config.getTargetLang() : requestedTarget,
                "zh-CN");
        String modelId = activeProfile != null ? activeProfile.model : "";
        String promptHashKey = activeProfile != null ? activeProfile.systemPrompt : "";

        int n = texts.size();
        String[] results = new String[n];
        List<Integer> missIndices = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        String[] cacheKeys = new String[n];
        int localCacheHits = 0;

        for (int i = 0; i < n; i++) {
            String raw = texts.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                results[i] = "";
                continue;
            }
            String key = cacheKey(configuredEngine, modelId, target, promptHashKey, raw);
            cacheKeys[i] = key;
            String cached;
            synchronized (cache) {
                cached = cache.get(key);
            }
            if (cached != null) {
                results[i] = cached;
                localCacheHits++;
            } else {
                missIndices.add(i);
                missTexts.add(raw);
            }
        }

        if (localCacheHits > 0) {
            statCacheHits.addAndGet(localCacheHits);
        }

        if (missIndices.isEmpty()) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            return new TranslationResult(
                    asList(results), configuredEngine, false, localCacheHits, 0, elapsedMs, null);
        }

        CancellationCheck cancelled = () -> !isGenerationActive(scopeKey, generation);
        if (cancelled.isCancelled()) {
            return new TranslationResult(
                    Collections.emptyList(), configuredEngine, false, localCacheHits, 0, 0L, "已取消");
        }

        int maxConcurrency = config.resolveEffectiveConcurrency();
        int batchMaxChars = config.resolveEffectiveBatchMaxChars();
        int batchMaxItems = config.resolveEffectiveBatchMaxItems();
        int timeoutMs = config.resolveEffectiveTimeoutSec() * 1000;

        List<List<Integer>> subBatches = partitionBatchIndices(missTexts, batchMaxChars, batchMaxItems);
        BabelConfig.EngineKind usedEngine = configuredEngine;
        boolean fellBack = false;

        try {
            for (List<Integer> sub : subBatches) {
                if (cancelled.isCancelled()) throw new CancelledException();
                List<String> subInput = new ArrayList<>(sub.size());
                for (int idx : sub) subInput.add(missTexts.get(idx));

                boolean acquired = false;
                try {
                    acquired = gate.acquire(maxConcurrency, cancelled);
                    if (!acquired || cancelled.isCancelled()) throw new CancelledException();

                    BatchNetworkOutcome outcome = executeWithFallback(
                            scopeKey, generation, configuredEngine, activeProfile,
                            subInput, target, timeoutMs, cancelled);

                    usedEngine = outcome.usedEngine;
                    if (outcome.fellBackToCn) fellBack = true;

                    for (int k = 0; k < sub.size(); k++) {
                        int origIdx = missIndices.get(sub.get(k));
                        String translated = k < outcome.translations.size() ? outcome.translations.get(k) : "";
                        results[origIdx] = translated;
                        if (translated != null && !translated.isEmpty()) {
                            synchronized (cache) {
                                cache.put(cacheKeys[origIdx], translated);
                                if (outcome.usedEngine != configuredEngine) {
                                    String altKey = cacheKey(outcome.usedEngine, "", target, "", texts.get(origIdx));
                                    cache.put(altKey, translated);
                                }
                            }
                        }
                    }
                } finally {
                    if (acquired) gate.release();
                }
            }
        } catch (CancelledException ce) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            return new TranslationResult(
                    Collections.emptyList(), usedEngine, fellBack, localCacheHits, 0, elapsedMs, "已取消");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            return new TranslationResult(
                    Collections.emptyList(), usedEngine, fellBack, localCacheHits, 0, elapsedMs, "翻译被中断");
        } catch (IOException | RuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
            return new TranslationResult(
                    Collections.emptyList(), usedEngine, fellBack, localCacheHits, 0, elapsedMs, msg);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        statLastLatencyMs.set(elapsedMs);
        statLastActiveEngine = fellBack
                ? "微软必应 (自动切国内)"
                : (usedEngine == BabelConfig.EngineKind.CUSTOM_AI && activeProfile != null
                        ? activeProfile.name + " · " + activeProfile.model
                        : usedEngine.label);

        return new TranslationResult(
                asList(results), usedEngine, fellBack, localCacheHits, missIndices.size(), elapsedMs, null);
    }

    private static final class BatchNetworkOutcome {
        final List<String> translations;
        final BabelConfig.EngineKind usedEngine;
        final boolean fellBackToCn;

        BatchNetworkOutcome(List<String> translations, BabelConfig.EngineKind usedEngine, boolean fellBackToCn) {
            this.translations = translations;
            this.usedEngine = usedEngine;
            this.fellBackToCn = fellBackToCn;
        }
    }

    private BatchNetworkOutcome executeWithFallback(
            String scopeKey,
            long generation,
            BabelConfig.EngineKind engine,
            BabelConfig.AiProfile profile,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        long promptChars = 0;
        for (String s : items) promptChars += s.length();
        statPromptChars.addAndGet(promptChars);

        try {
            List<String> out = dispatchEngine(scopeKey, generation, engine, profile, items, targetLang, timeoutMs, cancelled);
            recordOutputChars(out);
            return new BatchNetworkOutcome(out, engine, false);
        } catch (CancelledException ce) {
            throw ce;
        } catch (IOException ioe) {
            if (engine == BabelConfig.EngineKind.GOOGLE_FREE && config.isAutoFallbackToCn() && !cancelled.isCancelled()) {
                List<String> cnOut = translateViaBingCn(scopeKey, generation, items, targetLang, timeoutMs, cancelled);
                recordOutputChars(cnOut);
                return new BatchNetworkOutcome(cnOut, BabelConfig.EngineKind.BING_CN, true);
            }
            throw ioe;
        }
    }

    private void recordOutputChars(List<String> out) {
        long chars = 0;
        for (String s : out) if (s != null) chars += s.length();
        statOutputChars.addAndGet(chars);
    }

    private List<String> dispatchEngine(
            String scopeKey,
            long generation,
            BabelConfig.EngineKind engine,
            BabelConfig.AiProfile profile,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        switch (engine) {
            case GOOGLE_FREE:
                return translateViaGoogleFree(scopeKey, generation, items, targetLang, timeoutMs, cancelled);
            case BING_CN:
                return translateViaBingCn(scopeKey, generation, items, targetLang, timeoutMs, cancelled);
            case YOUDAO_CN:
                return translateViaYoudaoCn(scopeKey, generation, items, targetLang, timeoutMs, cancelled);
            case CUSTOM_AI:
                if (profile == null) throw new IOException("自定义 AI 配置为空");
                return translateViaCustomAi(scopeKey, generation, profile, items, targetLang, timeoutMs, cancelled);
            default:
                return translateViaGoogleFree(scopeKey, generation, items, targetLang, timeoutMs, cancelled);
        }
    }

    // ---- Engine 1: Google Free Translate ----

    private List<String> translateViaGoogleFree(
            String scopeKey,
            long generation,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        String gl = mapGoogleLang(targetLang);
        if (items.size() == 1) {
            String single = callGoogleOnce(scopeKey, generation, items.get(0), gl, timeoutMs, cancelled);
            return Collections.singletonList(single);
        }
        String joined = joinWithGoogleDelimiters(items);
        String combined = callGoogleOnce(scopeKey, generation, joined, gl, timeoutMs, cancelled);
        List<String> split = splitGoogleDelimited(combined, items.size());
        if (split != null && split.size() == items.size()) {
            return split;
        }
        // Delimiter mangled -> safe single-item fallback so alignment never breaks
        List<String> fallback = new ArrayList<>(items.size());
        for (String item : items) {
            if (cancelled.isCancelled()) throw new CancelledException();
            fallback.add(callGoogleOnce(scopeKey, generation, item, gl, timeoutMs, cancelled));
        }
        return fallback;
    }

    private String callGoogleOnce(
            String scopeKey,
            long generation,
            String text,
            String googleLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        statNetworkCalls.incrementAndGet();
        String base = googleEndpoint;
        String sep = base.contains("?") ? "&" : "?";
        String queryMeta = "client=gtx&sl=auto&tl=" + urlEncode(googleLang) + "&dt=t";
        String encodedQ = urlEncode(text);
        HttpResponse resp;
        if (encodedQ.length() <= 1600) {
            String url = base + sep + queryMeta + "&q=" + encodedQ;
            resp = executeHttp(scopeKey, generation, "GET", url, null, null, timeoutMs, cancelled);
        } else {
            String url = base + sep + queryMeta;
            Map<String, String> headers = Collections.singletonMap(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            resp = executeHttp(scopeKey, generation, "POST", url, headers, "q=" + encodedQ, timeoutMs, cancelled);
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new IOException("Google 翻译返回 HTTP " + resp.code);
        }
        try {
            return parseGoogleTranslateJson(resp.body);
        } catch (JSONException je) {
            throw new IOException("Google 翻译响应解析失败", je);
        }
    }

    // ---- Engine 2: Bing CN Direct (Edge Free Token) ----

    private List<String> translateViaBingCn(
            String scopeKey,
            long generation,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        String token = obtainBingToken(scopeKey, generation, timeoutMs, cancelled, false);
        try {
            return callBingTranslate(scopeKey, generation, items, targetLang, token, timeoutMs, cancelled);
        } catch (BingAuthExpiredException expired) {
            String refreshed = obtainBingToken(scopeKey, generation, timeoutMs, cancelled, true);
            return callBingTranslate(scopeKey, generation, items, targetLang, refreshed, timeoutMs, cancelled);
        }
    }

    private static final class BingAuthExpiredException extends IOException {
        BingAuthExpiredException(String msg) { super(msg); }
    }

    private String obtainBingToken(
            String scopeKey,
            long generation,
            int timeoutMs,
            CancellationCheck cancelled,
            boolean forceRefresh
    ) throws IOException {
        long now = System.currentTimeMillis();
        if (!forceRefresh && bingToken != null && now < bingTokenExpiresAt) {
            return bingToken;
        }
        synchronized (bingTokenLock) {
            now = System.currentTimeMillis();
            if (!forceRefresh && bingToken != null && now < bingTokenExpiresAt) {
                return bingToken;
            }
            HttpResponse resp = executeHttp(
                    scopeKey, generation, "GET", bingAuthEndpoint, null, null, timeoutMs, cancelled);
            if (resp.code < 200 || resp.code >= 300 || resp.body.trim().isEmpty()) {
                throw new IOException("微软必应鉴权失败 HTTP " + resp.code);
            }
            bingToken = resp.body.trim();
            bingTokenExpiresAt = System.currentTimeMillis() + BING_TOKEN_TTL_MS;
            return bingToken;
        }
    }

    private List<String> callBingTranslate(
            String scopeKey,
            long generation,
            List<String> items,
            String targetLang,
            String token,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        statNetworkCalls.incrementAndGet();
        String bl = mapBingLang(targetLang);
        String sep = bingTranslateEndpoint.contains("?") ? "&" : "?";
        String url = bingTranslateEndpoint + sep + "from=&to=" + urlEncode(bl) + "&api-version=3.0";
        JSONArray bodyArr = new JSONArray();
        try {
            for (String s : items) {
                JSONObject o = new JSONObject();
                o.put("Text", s);
                bodyArr.put(o);
            }
        } catch (JSONException je) {
            throw new IOException("Bing 请求构建失败", je);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        HttpResponse resp = executeHttp(
                scopeKey, generation, "POST", url, headers, bodyArr.toString(), timeoutMs, cancelled);
        if (resp.code == 401 || resp.code == 403) {
            throw new BingAuthExpiredException("Bing token expired: HTTP " + resp.code);
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new IOException("微软必应翻译返回 HTTP " + resp.code);
        }
        try {
            return parseBingTranslateJson(resp.body, items.size());
        } catch (JSONException je) {
            throw new IOException("微软必应响应解析失败", je);
        }
    }

    // ---- Engine 3: Youdao CN Direct Backup ----

    private List<String> translateViaYoudaoCn(
            String scopeKey,
            long generation,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        String ydType = mapYoudaoType(targetLang);
        List<String> out = new ArrayList<>(items.size());
        for (String item : items) {
            if (cancelled.isCancelled()) throw new CancelledException();
            statNetworkCalls.incrementAndGet();
            String sep = youdaoEndpoint.contains("?") ? "&" : "?";
            String url = youdaoEndpoint + sep + "doctype=json&type=" + urlEncode(ydType);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            headers.put("Referer", "https://fanyi.youdao.com/");
            String form = "i=" + urlEncode(item);
            HttpResponse resp = executeHttp(scopeKey, generation, "POST", url, headers, form, timeoutMs, cancelled);
            if (resp.code < 200 || resp.code >= 300) {
                throw new IOException("有道翻译返回 HTTP " + resp.code);
            }
            try {
                String parsed = parseYoudaoJson(resp.body);
                out.add(parsed);
            } catch (JSONException je) {
                throw new IOException("有道翻译响应解析失败", je);
            }
        }
        return out;
    }

    // ---- Engine 4: Custom AI API (OpenAI-Compatible & Gemini) ----

    private List<String> translateViaCustomAi(
            String scopeKey,
            long generation,
            BabelConfig.AiProfile profile,
            List<String> items,
            String targetLang,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        String sysPrompt = buildAiSystemPrompt(profile.systemPrompt, targetLang, items.size());
        String userPrompt = buildAiUserPrompt(items, targetLang);
        String rawText;
        if (profile.apiFormat == BabelConfig.ApiFormat.GEMINI) {
            rawText = callGeminiChat(scopeKey, generation, profile, sysPrompt, userPrompt, timeoutMs, cancelled);
        } else {
            rawText = callOpenAiChat(scopeKey, generation, profile, sysPrompt, userPrompt, timeoutMs, cancelled);
        }
        List<String> parsed = parseAiBatchResponse(rawText, items.size());
        if (parsed.size() == items.size()) {
            return parsed;
        }
        if (items.size() == 1) {
            String cleaned = stripThinkingAndFences(rawText).trim();
            return Collections.singletonList(cleaned);
        }
        // Safe per-item fallback if model returned mismatched array size
        List<String> fallback = new ArrayList<>(items.size());
        for (String single : items) {
            if (cancelled.isCancelled()) throw new CancelledException();
            String sSys = buildAiSystemPrompt(profile.systemPrompt, targetLang, 1);
            String sUser = buildAiUserPrompt(Collections.singletonList(single), targetLang);
            String sRaw = profile.apiFormat == BabelConfig.ApiFormat.GEMINI
                    ? callGeminiChat(scopeKey, generation, profile, sSys, sUser, timeoutMs, cancelled)
                    : callOpenAiChat(scopeKey, generation, profile, sSys, sUser, timeoutMs, cancelled);
            List<String> one = parseAiBatchResponse(sRaw, 1);
            fallback.add(one.isEmpty() ? stripThinkingAndFences(sRaw).trim() : one.get(0));
        }
        return fallback;
    }

    private String callOpenAiChat(
            String scopeKey,
            long generation,
            BabelConfig.AiProfile profile,
            String sysPrompt,
            String userPrompt,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        statNetworkCalls.incrementAndGet();
        String url = normalizeOpenAiChatUrl(profile.baseUrl);
        if (!HttpUrlUtil.isHttp(url)) throw new IOException("无效的 OpenAI API 地址：" + profile.baseUrl);

        JSONObject body = new JSONObject();
        try {
            body.put("model", profile.model);
            body.put("temperature", Math.round(profile.temperature * 100.0) / 100.0);
            body.put("max_tokens", profile.maxTokens);
            body.put("stream", false);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", sysPrompt));
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            body.put("messages", messages);
        } catch (JSONException je) {
            throw new IOException("OpenAI 请求构造失败", je);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        if (!profile.apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + profile.apiKey);
        }
        headers.putAll(profile.parseExtraHeaders());

        HttpResponse resp = executeHttp(
                scopeKey, generation, "POST", url, headers, body.toString(), timeoutMs, cancelled);
        if (resp.code < 200 || resp.code >= 300) {
            throw new IOException("AI API HTTP " + resp.code + extractApiErrorSnippet(resp.body));
        }
        try {
            return parseOpenAiChatResponse(resp.body);
        } catch (JSONException je) {
            throw new IOException("OpenAI 响应解析失败", je);
        }
    }

    private String callGeminiChat(
            String scopeKey,
            long generation,
            BabelConfig.AiProfile profile,
            String sysPrompt,
            String userPrompt,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        statNetworkCalls.incrementAndGet();
        String url = normalizeGeminiGenerateUrl(profile.baseUrl, profile.model, profile.apiKey);
        if (!HttpUrlUtil.isHttp(url)) throw new IOException("无效的 Gemini API 地址：" + profile.baseUrl);

        JSONObject body = new JSONObject();
        try {
            JSONObject sysInst = new JSONObject();
            sysInst.put("parts", new JSONArray().put(new JSONObject().put("text", sysPrompt)));
            body.put("systemInstruction", sysInst);

            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            userContent.put("parts", new JSONArray().put(new JSONObject().put("text", userPrompt)));
            body.put("contents", new JSONArray().put(userContent));

            JSONObject genCfg = new JSONObject();
            genCfg.put("temperature", Math.round(profile.temperature * 100.0) / 100.0);
            genCfg.put("maxOutputTokens", profile.maxTokens);
            body.put("generationConfig", genCfg);
        } catch (JSONException je) {
            throw new IOException("Gemini 请求构造失败", je);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        if (!profile.apiKey.isEmpty()) {
            headers.put("x-goog-api-key", profile.apiKey);
        }
        headers.putAll(profile.parseExtraHeaders());

        HttpResponse resp = executeHttp(
                scopeKey, generation, "POST", url, headers, body.toString(), timeoutMs, cancelled);
        if (resp.code < 200 || resp.code >= 300) {
            throw new IOException("Gemini API HTTP " + resp.code + extractApiErrorSnippet(resp.body));
        }
        try {
            return parseGeminiResponse(resp.body);
        } catch (JSONException je) {
            throw new IOException("Gemini 响应解析失败", je);
        }
    }

    // ---- Auto-Fetch Model List & Connection Test ----

    public ModelListResult fetchModels(BabelConfig.AiProfile profile) {
        long startNs = System.nanoTime();
        if (profile == null) {
            return new ModelListResult(Collections.emptyList(), 0L, "配置为空");
        }
        int timeoutMs = profile.timeoutSec * 1000;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            String url;
            if (profile.apiFormat == BabelConfig.ApiFormat.GEMINI) {
                url = normalizeGeminiModelsUrl(profile.baseUrl, profile.apiKey);
                if (!profile.apiKey.isEmpty()) headers.put("x-goog-api-key", profile.apiKey);
            } else {
                url = normalizeOpenAiModelsUrl(profile.baseUrl);
                if (!profile.apiKey.isEmpty()) headers.put("Authorization", "Bearer " + profile.apiKey);
            }
            if (!HttpUrlUtil.isHttp(url)) {
                return new ModelListResult(Collections.emptyList(), 0L, "无效的 API 地址：" + profile.baseUrl);
            }
            headers.putAll(profile.parseExtraHeaders());

            HttpResponse resp = executeHttp(null, 0L, "GET", url, headers, null, timeoutMs, () -> false);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            if (resp.code < 200 || resp.code >= 300) {
                return new ModelListResult(
                        Collections.emptyList(), elapsedMs,
                        "获取模型失败 HTTP " + resp.code + extractApiErrorSnippet(resp.body));
            }
            List<String> models = profile.apiFormat == BabelConfig.ApiFormat.GEMINI
                    ? parseGeminiModels(resp.body)
                    : parseOpenAiModels(resp.body);
            if (models.isEmpty()) {
                return new ModelListResult(Collections.emptyList(), elapsedMs, "接口未返回可用模型列表，可手动输入模型 ID");
            }
            return new ModelListResult(models, elapsedMs, null);
        } catch (IOException | RuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String msg = e.getMessage();
            return new ModelListResult(
                    Collections.emptyList(), elapsedMs,
                    msg == null || msg.trim().isEmpty() ? "网络请求失败" : msg);
        }
    }

    public ConnectionTestResult testProfile(BabelConfig.AiProfile profile, String targetLang) {
        long startNs = System.nanoTime();
        if (profile == null) {
            return new ConnectionTestResult(false, "", 0L, "配置为空");
        }
        String target = BabelConfig.normalizeLang(targetLang, config.getTargetLang());
        String sample = "en".equals(target) ? "你好，巴别塔翻译引擎运行正常。" : "Hello, Babel translation engine is ready.";
        try {
            List<String> res = translateViaCustomAi(
                    null, 0L, profile, Collections.singletonList(sample),
                    target, profile.timeoutSec * 1000, () -> false);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String out = res.isEmpty() ? "" : res.get(0);
            if (out == null || out.trim().isEmpty()) {
                return new ConnectionTestResult(false, "", elapsedMs, "模型返回空内容");
            }
            return new ConnectionTestResult(true, out.trim(), elapsedMs, null);
        } catch (IOException | RuntimeException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String msg = e.getMessage();
            return new ConnectionTestResult(false, "", elapsedMs,
                    msg == null || msg.trim().isEmpty() ? "连接失败" : msg);
        }
    }

    // ---- Low-level HTTP Transport ----

    private HttpResponse executeHttp(
            String scopeKey,
            long generation,
            String method,
            String urlStr,
            Map<String, String> headers,
            String body,
            int timeoutMs,
            CancellationCheck cancelled
    ) throws IOException {
        if (cancelled != null && cancelled.isCancelled()) throw new CancelledException();
        ActiveRequest tracker = new ActiveRequest(scopeKey, generation);
        activeRequests.add(tracker);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            tracker.conn = conn;
            if (cancelled != null && cancelled.isCancelled()) throw new CancelledException();

            conn.setRequestMethod(method);
            conn.setConnectTimeout(Math.max(3000, Math.min(timeoutMs, 20000)));
            conn.setReadTimeout(Math.max(4000, timeoutMs));
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            if (cancelled != null && cancelled.isCancelled()) throw new CancelledException();
            InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = stream == null ? "" : readBoundedUtf8(stream, MAX_RESPONSE_BYTES);
            return new HttpResponse(code, respBody);
        } catch (IOException ioe) {
            if (cancelled != null && cancelled.isCancelled()) throw new CancelledException();
            throw ioe;
        } finally {
            activeRequests.remove(tracker);
            if (conn != null) {
                try { conn.disconnect(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static String readBoundedUtf8(InputStream in, int maxBytes) throws IOException {
        try (InputStream bin = new BufferedInputStream(in)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = bin.read(buf)) >= 0) {
                int allowed = Math.min(n, maxBytes - total);
                if (allowed > 0) {
                    bos.write(buf, 0, allowed);
                    total += allowed;
                }
                if (total >= maxBytes) break;
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String extractApiErrorSnippet(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject err = obj.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "").trim();
                if (!msg.isEmpty()) return " · " + truncate(msg, 120);
            }
            String msg = obj.optString("message", "").trim();
            if (!msg.isEmpty()) return " · " + truncate(msg, 120);
        } catch (JSONException ignored) { }
        return " · " + truncate(body.replaceAll("\\s+", " ").trim(), 100);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static List<String> asList(String[] arr) {
        List<String> list = new ArrayList<>(arr.length);
        for (String s : arr) list.add(s == null ? "" : s);
        return list;
    }

    // =========================================================================
    // Pure-Java Static Parsers, URL Normalizers, Batching & Hashing (Host-Testable)
    // =========================================================================

    public static String cacheKey(
            BabelConfig.EngineKind engine,
            String model,
            String targetLang,
            String stylePrompt,
            String text
    ) {
        String e = engine == null ? "google_free" : engine.key;
        String m = model == null ? "" : model;
        String t = targetLang == null ? "zh-CN" : targetLang;
        String pHash = (stylePrompt == null || stylePrompt.isEmpty()) ? "0" : sha256Short(stylePrompt, 12);
        String txtHash = sha256Short(text == null ? "" : text, 24);
        return e + "|" + m + "|" + t + "|" + pHash + "|" + txtHash;
    }

    public static String sha256Short(String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            int len = Math.min(hexChars, digest.length * 2);
            char[] out = new char[len];
            for (int i = 0; i < len; i++) {
                int b = digest[i / 2] & 0xFF;
                out[i] = (i % 2 == 0) ? hex[b >>> 4] : hex[b & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input == null ? 0 : input.hashCode());
        }
    }

    /** Partitions item indices into batches respecting both character and item count limits. */
    public static List<List<Integer>> partitionBatchIndices(List<String> texts, int maxChars, int maxItems) {
        int charCap = BabelConfig.clampBatchMaxChars(maxChars);
        int itemCap = BabelConfig.clampBatchMaxItems(maxItems);
        List<List<Integer>> batches = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return batches;
        List<Integer> current = new ArrayList<>();
        int currentChars = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            int len = t == null ? 0 : t.length();
            if (!current.isEmpty() && (current.size() >= itemCap || currentChars + len > charCap)) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(i);
            currentChars += len;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    public static String mapGoogleLang(String targetLang) {
        String norm = BabelConfig.normalizeLang(targetLang, "zh-CN");
        return norm;
    }

    public static String mapBingLang(String targetLang) {
        String norm = BabelConfig.normalizeLang(targetLang, "zh-CN");
        if ("zh-CN".equals(norm)) return "zh-Hans";
        if ("zh-TW".equals(norm)) return "zh-Hant";
        return norm;
    }

    public static String mapYoudaoType(String targetLang) {
        String norm = BabelConfig.normalizeLang(targetLang, "zh-CN");
        if ("en".equals(norm)) return "ZH_CN2EN";
        if ("ja".equals(norm)) return "ZH_CN2JA";
        if ("ko".equals(norm)) return "ZH_CN2KR";
        if ("fr".equals(norm)) return "ZH_CN2FR";
        if ("es".equals(norm)) return "ZH_CN2SP";
        if ("ru".equals(norm)) return "ZH_CN2RU";
        return "AUTO";
    }

    public static String joinWithGoogleDelimiters(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("\n⟦AIKO_B_").append(i).append("⟧\n");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    public static List<String> splitGoogleDelimited(String combined, int expectedCount) {
        if (expectedCount <= 0) return Collections.emptyList();
        if (expectedCount == 1) return Collections.singletonList(combined == null ? "" : combined.trim());
        if (combined == null) return null;
        List<String> out = new ArrayList<>(expectedCount);
        int cursor = 0;
        for (int i = 1; i < expectedCount; i++) {
            Pattern marker = Pattern.compile("\\s*[⟦\\[]\\s*AIKO_B_" + i + "\\s*[⟧\\]]\\s*", Pattern.CASE_INSENSITIVE);
            Matcher m = marker.matcher(combined);
            if (!m.find(cursor)) {
                return null;
            }
            out.add(combined.substring(cursor, m.start()).trim());
            cursor = m.end();
        }
        out.add(combined.substring(cursor).trim());
        return out.size() == expectedCount ? out : null;
    }

    public static String parseGoogleTranslateJson(String json) throws JSONException {
        JSONArray root = new JSONArray(json);
        JSONArray segments = root.optJSONArray(0);
        if (segments == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length(); i++) {
            JSONArray seg = segments.optJSONArray(i);
            if (seg != null && seg.length() > 0 && !seg.isNull(0)) {
                sb.append(seg.optString(0, ""));
            }
        }
        return sb.toString();
    }

    public static List<String> parseBingTranslateJson(String json, int expectedCount) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<String> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            String translated = "";
            if (item != null) {
                JSONArray trs = item.optJSONArray("translations");
                if (trs != null && trs.length() > 0) {
                    JSONObject first = trs.optJSONObject(0);
                    if (first != null) translated = first.optString("text", "");
                }
            }
            out.add(translated);
        }
        while (out.size() < expectedCount) out.add("");
        return out;
    }

    public static String parseYoudaoJson(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        int errCode = obj.optInt("errorCode", 0);
        if (errCode != 0) {
            throw new JSONException("Youdao errorCode=" + errCode);
        }
        JSONArray outer = obj.optJSONArray("translateResult");
        if (outer == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outer.length(); i++) {
            if (i > 0) sb.append('\n');
            JSONArray line = outer.optJSONArray(i);
            if (line == null) continue;
            for (int j = 0; j < line.length(); j++) {
                JSONObject piece = line.optJSONObject(j);
                if (piece != null) sb.append(piece.optString("tgt", ""));
            }
        }
        return sb.toString().trim();
    }

    /**
     * Normalizes user-supplied OpenAI-compatible baseUrl into a clean chat/completions URL.
     * Supports bare hosts ("https://api.openai.com"), versioned bases ("https://host/v1",
     * "https://host/api/v1", "https://host/v1beta/openai"), or full "/chat/completions" paths.
     */
    public static String normalizeOpenAiChatUrl(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        if (raw.isEmpty()) raw = BabelConfig.DEFAULT_OPENAI_BASE_URL;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions") || lower.endsWith("/completions")) {
            return raw;
        }
        if (hasApiVersionSuffix(lower)) {
            return raw + "/chat/completions";
        }
        return raw + "/v1/chat/completions";
    }

    /**
     * Normalizes user-supplied OpenAI-compatible baseUrl into a clean GET /models URL.
     */
    public static String normalizeOpenAiModelsUrl(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        if (raw.isEmpty()) raw = BabelConfig.DEFAULT_OPENAI_BASE_URL;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            raw = raw.substring(0, raw.length() - "/chat/completions".length());
            return raw + "/models";
        }
        if (lower.endsWith("/completions")) {
            raw = raw.substring(0, raw.length() - "/completions".length());
            return raw + "/models";
        }
        if (lower.endsWith("/models")) {
            return raw;
        }
        if (hasApiVersionSuffix(lower)) {
            return raw + "/models";
        }
        return raw + "/v1/models";
    }

    private static boolean hasApiVersionSuffix(String lowerUrl) {
        return lowerUrl.endsWith("/v1")
                || lowerUrl.endsWith("/v2")
                || lowerUrl.endsWith("/v3")
                || lowerUrl.endsWith("/v4")
                || lowerUrl.endsWith("/v1beta")
                || lowerUrl.endsWith("/v1beta/openai")
                || lowerUrl.endsWith("/v1/openai")
                || lowerUrl.endsWith("/compatible-mode/v1")
                || lowerUrl.matches(".*(/v\\d+(?:beta\\d*)?)$");
    }

    /**
     * Normalizes user-supplied Gemini baseUrl + model into a :generateContent endpoint.
     */
    public static String normalizeGeminiGenerateUrl(String baseUrl, String model, String apiKey) {
        String base = normalizeGeminiApiRoot(baseUrl);
        String cleanModel = model == null ? BabelConfig.DEFAULT_GEMINI_MODEL : model.trim();
        if (cleanModel.startsWith("models/")) cleanModel = cleanModel.substring("models/".length());
        if (cleanModel.isEmpty()) cleanModel = BabelConfig.DEFAULT_GEMINI_MODEL;
        String endpoint = base + "/models/" + urlEncode(cleanModel) + ":generateContent";
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            endpoint += "?key=" + urlEncode(apiKey.trim());
        }
        return endpoint;
    }

    /**
     * Normalizes user-supplied Gemini baseUrl into a GET /v1beta/models endpoint.
     */
    public static String normalizeGeminiModelsUrl(String baseUrl, String apiKey) {
        String base = normalizeGeminiApiRoot(baseUrl);
        String endpoint = base + "/models";
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            endpoint += "?key=" + urlEncode(apiKey.trim());
        }
        return endpoint;
    }

    private static String normalizeGeminiApiRoot(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        if (raw.isEmpty()) raw = BabelConfig.DEFAULT_GEMINI_BASE_URL;
        int q = raw.indexOf('?');
        if (q >= 0) raw = raw.substring(0, q);
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        String lower = raw.toLowerCase(Locale.ROOT);
        int modelsIdx = lower.indexOf("/models/");
        if (modelsIdx > 0) {
            raw = raw.substring(0, modelsIdx);
            lower = raw.toLowerCase(Locale.ROOT);
        } else if (lower.endsWith("/models")) {
            raw = raw.substring(0, raw.length() - "/models".length());
            lower = raw.toLowerCase(Locale.ROOT);
        }
        if (lower.endsWith("/v1beta") || lower.endsWith("/v1")) {
            return raw;
        }
        return raw + "/v1beta";
    }

    public static String buildAiSystemPrompt(String customSystemPrompt, String targetLang, int itemCount) {
        String base = (customSystemPrompt == null || customSystemPrompt.trim().isEmpty())
                ? BabelConfig.DEFAULT_SYSTEM_PROMPT : customSystemPrompt.trim();
        String targetName = BabelConfig.langEnglishName(targetLang) + " (" + targetLang + ")";
        if (itemCount <= 1) {
            return base + "\nTarget language: " + targetName + ". Return ONLY the translated text.";
        }
        return base + "\nTarget language: " + targetName
                + ". Return ONLY a valid JSON array of exactly " + itemCount
                + " translated strings in the exact same order. No markdown fences, no commentary.";
    }

    public static String buildAiUserPrompt(List<String> items, String targetLang) {
        if (items.size() == 1) {
            return items.get(0);
        }
        JSONArray arr = new JSONArray();
        for (String s : items) arr.put(s);
        return "Translate each string in this JSON array into " + BabelConfig.langEnglishName(targetLang)
                + " and return a JSON array of the exact same length (" + items.size() + "):\n" + arr.toString();
    }

    public static String parseOpenAiChatResponse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("Missing choices in OpenAI response");
        }
        JSONObject first = choices.getJSONObject(0);
        JSONObject msg = first.optJSONObject("message");
        if (msg != null) {
            Object content = msg.opt("content");
            if (content instanceof String) {
                return (String) content;
            }
            if (content instanceof JSONArray) {
                JSONArray parts = (JSONArray) content;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null && !"thinking".equalsIgnoreCase(part.optString("type", ""))) {
                        sb.append(part.optString("text", ""));
                    }
                }
                return sb.toString();
            }
        }
        String text = first.optString("text", "");
        if (!text.isEmpty()) return text;
        throw new JSONException("Empty content in OpenAI response");
    }

    public static String parseGeminiResponse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new JSONException("Missing candidates in Gemini response");
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");
        if (content == null) throw new JSONException("Missing content in Gemini candidate");
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) throw new JSONException("Missing parts in Gemini candidate");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            if (part.optBoolean("thought", false)) continue;
            String t = part.optString("text", "");
            if (!t.isEmpty()) sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Strips reasoning/thinking tags (<think>...</think>) and markdown code fences cleanly.
     */
    public static String stripThinkingAndFences(String raw) {
        if (raw == null) return "";
        String s = THINK_BLOCK_PATTERN.matcher(raw).replaceAll("");
        s = UNCLOSED_THINK_PATTERN.matcher(s).replaceAll("");
        s = s.trim();
        Matcher fence = CODE_FENCE_PATTERN.matcher(s);
        if (fence.matches()) {
            s = fence.group(1).trim();
        }
        return s;
    }

    /**
     * Robustly parses batch AI translation outputs: strips <think> blocks and markdown fences,
     * accepts JSON arrays, wrapped JSON objects (translations/items/indexed keys), or numbered lines.
     */
    public static List<String> parseAiBatchResponse(String rawContent, int expectedCount) {
        if (expectedCount <= 0) return Collections.emptyList();
        String cleaned = stripThinkingAndFences(rawContent);
        if (cleaned.isEmpty()) return Collections.emptyList();

        // 1. Try direct or embedded JSON array
        List<String> fromArr = tryParseJsonStringArray(cleaned, expectedCount);
        if (fromArr != null) return fromArr;

        // 2. Try JSON object {"translations": [...]} or {"0": "...", "1": "..."}
        List<String> fromObj = tryParseJsonObject(cleaned, expectedCount);
        if (fromObj != null) return fromObj;

        // 3. Single item fallback returns cleaned text directly
        if (expectedCount == 1) {
            return Collections.singletonList(cleaned);
        }

        // 4. Numbered lines fallback (e.g. "1. xxx\n2. yyy" or "[1] xxx")
        List<String> numbered = tryParseNumberedLines(cleaned, expectedCount);
        if (numbered != null) return numbered;

        // 5. Exact line-count split fallback
        String[] lines = cleaned.split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) nonEmpty.add(t);
        }
        if (nonEmpty.size() == expectedCount) {
            return nonEmpty;
        }
        return Collections.emptyList();
    }

    private static List<String> tryParseJsonStringArray(String text, int expectedCount) {
        String candidate = text;
        if (!candidate.startsWith("[")) {
            int first = candidate.indexOf('[');
            int last = candidate.lastIndexOf(']');
            if (first >= 0 && last > first) {
                candidate = candidate.substring(first, last + 1);
            } else {
                return null;
            }
        }
        try {
            JSONArray arr = new JSONArray(candidate);
            if (arr.length() != expectedCount) return null;
            List<String> out = new ArrayList<>(expectedCount);
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.opt(i);
                if (el instanceof JSONObject) {
                    JSONObject o = (JSONObject) el;
                    String v = o.optString("translation", o.optString("text", o.optString("translated", "")));
                    out.add(v);
                } else {
                    out.add(el == null || el == JSONObject.NULL ? "" : String.valueOf(el));
                }
            }
            return out;
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static List<String> tryParseJsonObject(String text, int expectedCount) {
        String candidate = text;
        if (!candidate.startsWith("{")) {
            int first = candidate.indexOf('{');
            int last = candidate.lastIndexOf('}');
            if (first >= 0 && last > first) {
                candidate = candidate.substring(first, last + 1);
            } else {
                return null;
            }
        }
        try {
            JSONObject obj = new JSONObject(candidate);
            for (String key : new String[]{"translations", "items", "results", "data"}) {
                JSONArray arr = obj.optJSONArray(key);
                if (arr != null && arr.length() == expectedCount) {
                    List<String> out = new ArrayList<>(expectedCount);
                    for (int i = 0; i < arr.length(); i++) {
                        out.add(arr.optString(i, ""));
                    }
                    return out;
                }
            }
            // Indexed keys: "0".."n-1" or "1".."n"
            boolean zeroBased = obj.has("0");
            List<String> indexed = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                String k = Integer.toString(zeroBased ? i : (i + 1));
                if (!obj.has(k)) return null;
                indexed.add(obj.optString(k, ""));
            }
            return indexed;
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static List<String> tryParseNumberedLines(String text, int expectedCount) {
        String[] lines = text.split("\\r?\\n");
        List<String> items = new ArrayList<>();
        StringBuilder current = null;
        for (String line : lines) {
            Matcher m = NUMBERED_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                if (current != null) items.add(current.toString().trim());
                current = new StringBuilder(m.group(2));
            } else if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) items.add(current.toString().trim());
        return items.size() == expectedCount ? items : null;
    }

    /**
     * Pure static parser for OpenAI-compatible GET /models responses (supports standard data[].id
     * as well as relay models[].id fallback).
     */
    public static List<String> parseOpenAiModels(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            String trimmed = json.trim();
            JSONArray arr;
            if (trimmed.startsWith("[")) {
                arr = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                arr = root.optJSONArray("data");
                if (arr == null) arr = root.optJSONArray("models");
            }
            if (arr != null) {
                for (int i = 0; i < arr.length() && set.size() < BabelConfig.MAX_CACHED_MODELS; i++) {
                    Object el = arr.opt(i);
                    String id = "";
                    if (el instanceof JSONObject) {
                        JSONObject o = (JSONObject) el;
                        id = o.optString("id", o.optString("name", "")).trim();
                    } else if (el instanceof String) {
                        id = ((String) el).trim();
                    }
                    if (!id.isEmpty()) set.add(id);
                }
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>(set);
        sortModelsNicely(list);
        return list;
    }

    /**
     * Pure static parser for Gemini GET /v1beta/models responses.
     * Strips the "models/" prefix and filters to generateContent-capable models when capability
     * metadata is provided.
     */
    public static List<String> parseGeminiModels(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            JSONObject root = new JSONObject(json.trim());
            JSONArray arr = root.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length() && set.size() < BabelConfig.MAX_CACHED_MODELS; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "").trim();
                    if (name.startsWith("models/")) name = name.substring("models/".length());
                    if (name.isEmpty()) continue;

                    JSONArray methods = o.optJSONArray("supportedGenerationMethods");
                    if (methods != null && methods.length() > 0) {
                        boolean supportsGenerate = false;
                        for (int j = 0; j < methods.length(); j++) {
                            if ("generateContent".equalsIgnoreCase(methods.optString(j, ""))) {
                                supportsGenerate = true;
                                break;
                            }
                        }
                        if (!supportsGenerate) continue;
                    } else {
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.contains("embedding") || lower.contains("aqa") || lower.contains("imagen")) {
                            continue;
                        }
                    }
                    set.add(name);
                }
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>(set);
        sortModelsNicely(list);
        return list;
    }

    private static void sortModelsNicely(List<String> models) {
        models.sort(Comparator.comparingInt(BabelEngine::modelPriority)
                .thenComparing(String::compareToIgnoreCase));
    }

    private static int modelPriority(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("embedding") || lower.contains("whisper")
                || lower.contains("tts") || lower.contains("dall-e")
                || lower.contains("moderation") || lower.contains("rerank")) {
            return 3;
        }
        if (lower.contains("gemini-2.5") || lower.contains("gemini-2.0")
                || lower.contains("gpt-4o") || lower.contains("gpt-4.1")
                || lower.contains("gpt-5") || lower.contains("deepseek")
                || lower.contains("claude") || lower.contains("qwen")) {
            return 0;
        }
        return 1;
    }
}
