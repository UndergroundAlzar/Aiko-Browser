package app.aikobrowser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host JVM verification suite for BabelConfig and BabelEngine (pure Java + local loopback HTTP server).
 */
public final class BabelEngineCheck {
    public static void main(String[] args) throws Exception {
        checkConfigDefaultsClampingAndSerialization();
        checkPureParsersAndNormalizers();
        checkLocalHttpEnginesFallbackCacheAndConcurrency();
        System.out.println("PASS ALL BabelEngineCheck assertions");
    }

    private static void checkConfigDefaultsClampingAndSerialization() {
        BabelConfig cfg = new BabelConfig();
        check(cfg.getEngineKind() == BabelConfig.EngineKind.GOOGLE_FREE, "default engine GOOGLE_FREE");
        check(cfg.isAutoFallbackToCn(), "default autoFallbackToCn true");
        check("zh-CN".equals(cfg.getTargetLang()), "default targetLang zh-CN");
        check("en".equals(cfg.getInputBackTargetLang()), "default inputBackTargetLang en");
        check(cfg.getDisplayStyle() == BabelConfig.DisplayStyle.BILINGUAL_SOFT, "default displayStyle BILINGUAL_SOFT");
        check(cfg.isTripleSpaceInputTranslate(), "default tripleSpace true");
        check(cfg.isSelectionTranslate(), "default selectionTranslate true");
        check(cfg.isSkipCodeAndNav(), "default skipCodeAndNav true");
        check(cfg.isShowFloatingFab(), "default showFloatingFab true");
        check(BabelConfig.LANGUAGES.size() == 9, "9 supported languages");
        check(BabelConfig.PROMPT_TEMPLATES.size() == 5, "5 built-in prompt templates");

        // Parameter clamping & header sanitization
        BabelConfig.AiProfile low = new BabelConfig.AiProfile(
                "prof_low", "  My Relay\n\t", BabelConfig.ApiFormat.OPENAI_COMPAT,
                "", " sk-test-key ", "", Arrays.asList("m1", "m1", " ", "m2"),
                -5, 10, Float.NaN, 10, 0, 1,
                "", "{\"X-Custom\":\"ok\",\"Bad\\r\\nKey\":\"v\",\"Host\":\"evil.com\",\"X-Inject\":\"a\\nb\"}"
        );
        check("prof_low".equals(low.id), "profile id preserved");
        check("My Relay".equals(low.name), "profile name sanitized");
        check(BabelConfig.DEFAULT_OPENAI_BASE_URL.equals(low.baseUrl), "blank baseUrl defaults");
        check("sk-test-key".equals(low.apiKey), "apiKey trimmed");
        check(BabelConfig.DEFAULT_OPENAI_MODEL.equals(low.model), "blank model defaults");
        check(low.cachedModels.size() == 2, "cachedModels deduplicated & trimmed");
        check(low.maxConcurrency == BabelConfig.MIN_CONCURRENCY, "concurrency min clamp");
        check(low.maxTokens == BabelConfig.MIN_MAX_TOKENS, "maxTokens min clamp");
        check(Math.abs(low.temperature - BabelConfig.DEFAULT_TEMPERATURE) < 0.001f, "NaN temperature -> default");
        check(low.batchMaxChars == BabelConfig.MIN_BATCH_CHARS, "batchMaxChars min clamp");
        check(low.batchMaxItems == BabelConfig.MIN_BATCH_ITEMS, "batchMaxItems min clamp");
        check(low.timeoutSec == BabelConfig.MIN_TIMEOUT_SEC, "timeoutSec min clamp");
        check(BabelConfig.DEFAULT_SYSTEM_PROMPT.equals(low.systemPrompt), "blank prompt -> default");
        Map<String, String> headers = low.parseExtraHeaders();
        check("ok".equals(headers.get("X-Custom")), "valid extra header parsed");
        check(!headers.containsKey("Host") && !headers.containsKey("X-Inject") && headers.size() == 1,
                "unsafe/CR-LF headers rejected: " + headers);

        BabelConfig.AiProfile high = new BabelConfig.AiProfile(
                "prof_hi", "Gemini Relay", BabelConfig.ApiFormat.GEMINI,
                "https://relay.example.com", "AIza123", "gemini-2.5-pro",
                Collections.singletonList("gemini-2.5-pro"),
                99, 999999, 9.9f, 99999, 999, 999,
                "Custom prompt", "{\"X-Org\":\"aiko\"}"
        );
        check(high.maxConcurrency == BabelConfig.MAX_CONCURRENCY, "concurrency max clamp");
        check(high.maxTokens == BabelConfig.MAX_MAX_TOKENS, "maxTokens max clamp");
        check(Math.abs(high.temperature - BabelConfig.MAX_TEMPERATURE) < 0.001f, "temperature max clamp");
        check(high.batchMaxChars == BabelConfig.MAX_BATCH_CHARS, "batchMaxChars max clamp");
        check(high.batchMaxItems == BabelConfig.MAX_BATCH_ITEMS, "batchMaxItems max clamp");
        check(high.timeoutSec == BabelConfig.MAX_TIMEOUT_SEC, "timeoutSec max clamp");

        // Host policy mutual exclusivity & normalization
        cfg.setAutoTranslateHost("HTTPS://News.YCombinator.COM:443/front", true);
        check(cfg.isAutoTranslateHost("news.ycombinator.com"), "auto host normalized");
        cfg.setNeverTranslateHost("news.ycombinator.com", true);
        check(!cfg.isAutoTranslateHost("news.ycombinator.com") && cfg.isNeverTranslateHost("news.ycombinator.com"),
                "never overrides auto");
        cfg.setAutoTranslateHost("github.com", true);

        // Multiple AI profiles + JSON round-trip
        cfg.setEngineKind(BabelConfig.EngineKind.CUSTOM_AI);
        cfg.setDisplayStyle(BabelConfig.DisplayStyle.BILINGUAL_QUOTE);
        cfg.setTargetLang("ja");
        cfg.setInputBackTargetLang("fr");
        cfg.setAutoFallbackToCn(false);
        cfg.upsertProfile(low);
        cfg.upsertProfile(high);
        cfg.setActiveProfileId(high.id);

        String serialized = cfg.toJson();
        BabelConfig restored = BabelConfig.fromJson(serialized);
        check(restored.getEngineKind() == BabelConfig.EngineKind.CUSTOM_AI, "round-trip engineKind");
        check(restored.getDisplayStyle() == BabelConfig.DisplayStyle.BILINGUAL_QUOTE, "round-trip displayStyle");
        check("ja".equals(restored.getTargetLang()), "round-trip targetLang");
        check("fr".equals(restored.getInputBackTargetLang()), "round-trip inputBackTargetLang");
        check(!restored.isAutoFallbackToCn(), "round-trip autoFallbackToCn");
        check(restored.isAutoTranslateHost("github.com"), "round-trip autoTranslateHosts");
        check(restored.isNeverTranslateHost("news.ycombinator.com"), "round-trip neverTranslateHosts");
        check(restored.getAiProfiles().size() == 2, "round-trip profile count");
        check(restored.getActiveProfile() != null && "prof_hi".equals(restored.getActiveProfile().id),
                "round-trip activeProfileId");
        check(restored.resolveEffectiveConcurrency() == BabelConfig.MAX_CONCURRENCY, "effective concurrency from active AI");

        // Delete active profile reassigns active to remaining profile
        check(restored.deleteProfile("prof_hi"), "delete active profile");
        check(restored.getActiveProfile() != null && "prof_low".equals(restored.getActiveProfile().id),
                "fallback active profile after delete");

        // Malformed JSON resilience
        BabelConfig bad = BabelConfig.fromJson("{not-valid-json");
        check(bad.getEngineKind() == BabelConfig.EngineKind.GOOGLE_FREE, "malformed JSON retains defaults");

        System.out.println("PASS BabelConfig defaults, clamping, host policy, AI profile CRUD, and JSON round-trip");
    }

    private static void checkPureParsersAndNormalizers() throws Exception {
        // OpenAI URL normalization
        check("https://api.openai.com/v1/chat/completions".equals(
                BabelEngine.normalizeOpenAiChatUrl("https://api.openai.com")), "bare host -> /v1/chat/completions");
        check("https://api.openai.com/v1/chat/completions".equals(
                BabelEngine.normalizeOpenAiChatUrl("https://api.openai.com/v1/")), "/v1/ -> /v1/chat/completions");
        check("https://openrouter.ai/api/v1/chat/completions".equals(
                BabelEngine.normalizeOpenAiChatUrl("https://openrouter.ai/api/v1")), "/api/v1 -> /api/v1/chat/completions");
        check("https://relay.example.com/custom/chat/completions".equals(
                BabelEngine.normalizeOpenAiChatUrl("https://relay.example.com/custom/chat/completions")),
                "full chat/completions preserved");
        check("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions".equals(
                BabelEngine.normalizeOpenAiChatUrl("https://generativelanguage.googleapis.com/v1beta/openai")),
                "openai compat suffix preserved");

        check("https://api.openai.com/v1/models".equals(
                BabelEngine.normalizeOpenAiModelsUrl("https://api.openai.com")), "models bare host");
        check("https://relay.example.com/v1/models".equals(
                BabelEngine.normalizeOpenAiModelsUrl("https://relay.example.com/v1/chat/completions")),
                "models strips /chat/completions");

        // Gemini URL normalization
        check("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=K1".equals(
                BabelEngine.normalizeGeminiGenerateUrl("https://generativelanguage.googleapis.com", "models/gemini-2.5-flash", "K1")),
                "gemini generate url strips models/ prefix");
        check("https://my-gemini-relay.example/v1beta/models?key=K1".equals(
                BabelEngine.normalizeGeminiModelsUrl("https://my-gemini-relay.example/v1beta/", "K1")),
                "gemini models url avoids duplicate v1beta");

        // Model list parsers
        String openAiModelsJson = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"text-embedding-3-small\"},"
                + "{\"id\":\"deepseek-chat\"},"
                + "{\"id\":\"gpt-4o-mini\"},"
                + "{\"id\":\"gpt-4o-mini\"},"
                + "{\"id\":\"claude-3-7-sonnet\"}"
                + "]}";
        List<String> oModels = BabelEngine.parseOpenAiModels(openAiModelsJson);
        check(oModels.size() == 4, "OpenAI models deduplicated: " + oModels);
        check("text-embedding-3-small".equals(oModels.get(oModels.size() - 1)),
                "embedding model sorted to bottom: " + oModels);
        check(oModels.contains("gpt-4o-mini") && oModels.contains("deepseek-chat"), "chat models present");

        String geminiModelsJson = "{\"models\":["
                + "{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]},"
                + "{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":[\"generateContent\"]}"
                + "]}";
        List<String> gModels = BabelEngine.parseGeminiModels(geminiModelsJson);
        check(gModels.size() == 2, "Gemini filters out non-generateContent models: " + gModels);
        check(gModels.contains("gemini-2.5-flash") && gModels.contains("gemini-2.5-pro"),
                "Gemini strips models/ prefix: " + gModels);

        // AI response parser: <think> stripping, markdown fences, JSON array, object, numbered lines
        String thinkAndFence = "<think>Let me carefully translate these two lines.</think>\n```json\n[\"你好世界\", \"再见\"]\n```";
        List<String> parsed1 = BabelEngine.parseAiBatchResponse(thinkAndFence, 2);
        check(Arrays.asList("你好世界", "再见").equals(parsed1), "think + fenced JSON array: " + parsed1);

        String wrappedObj = "Here you go:\n{\"translations\": [\"第一条\", \"第二条\"]}";
        List<String> parsed2 = BabelEngine.parseAiBatchResponse(wrappedObj, 2);
        check(Arrays.asList("第一条", "第二条").equals(parsed2), "wrapped JSON object: " + parsed2);

        String numbered = "<reasoning>thinking</reasoning>\n1. 人工智能\n2. 浏览器引擎";
        List<String> parsed3 = BabelEngine.parseAiBatchResponse(numbered, 2);
        check(Arrays.asList("人工智能", "浏览器引擎").equals(parsed3), "numbered lines fallback: " + parsed3);

        // Google delimiter join/split + mangled detection
        List<String> src = Arrays.asList("Hello world", "Second line", "Third line");
        String joined = BabelEngine.joinWithGoogleDelimiters(src);
        check(joined.contains("⟦AIKO_B_1⟧") && joined.contains("⟦AIKO_B_2⟧"), "Google delimiter format");
        List<String> okSplit = BabelEngine.splitGoogleDelimited("你好世界\n[ AIKO_B_1 ]\n第二行\n⟦AIKO_B_2⟧\n第三行", 3);
        check(Arrays.asList("你好世界", "第二行", "第三行").equals(okSplit), "tolerant Google delimiter split: " + okSplit);
        check(BabelEngine.splitGoogleDelimited("mangled response without markers", 3) == null,
                "mangled delimiter returns null for fallback");

        // Batch partitioning respects maxChars & maxItems
        List<String> many = Arrays.asList(
                repeat('a', 300), repeat('b', 300), repeat('c', 300), repeat('d', 300));
        List<List<Integer>> parts = BabelEngine.partitionBatchIndices(many, 650, 3);
        check(parts.size() == 2 && parts.get(0).size() == 2 && parts.get(1).size() == 2,
                "partition respects maxChars: " + parts);

        // Cache key determinism & isolation
        String k1 = BabelEngine.cacheKey(BabelConfig.EngineKind.CUSTOM_AI, "gpt-4o", "zh-CN", "p1", "Hello");
        String k2 = BabelEngine.cacheKey(BabelConfig.EngineKind.CUSTOM_AI, "gpt-4o", "zh-CN", "p2", "Hello");
        String k3 = BabelEngine.cacheKey(BabelConfig.EngineKind.CUSTOM_AI, "gpt-4o", "zh-CN", "p1", "Hello");
        check(k1.equals(k3) && !k1.equals(k2), "cacheKey stable & sensitive to prompt hash");

        System.out.println("PASS BabelEngine pure parsers, URL normalizers, delimiter split, and batching");
    }

    private static void checkLocalHttpEnginesFallbackCacheAndConcurrency() throws Exception {
        AtomicBoolean failGoogle = new AtomicBoolean(false);
        AtomicBoolean mangleGoogle = new AtomicBoolean(false);
        AtomicInteger bingAuthCalls = new AtomicInteger(0);
        AtomicBoolean expireBingOnce = new AtomicBoolean(false);
        AtomicInteger slowActive = new AtomicInteger(0);
        AtomicInteger slowPeak = new AtomicInteger(0);
        AtomicInteger aiDelayMs = new AtomicInteger(0);
        AtomicReference<String> seenCustomHeader = new AtomicReference<>("");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        // 1. Mock Google Free
        server.createContext("/google", ex -> {
            if (failGoogle.get()) {
                respond(ex, 503, "Service Unavailable");
                return;
            }
            String q = extractQueryParam(ex, "q");
            String translated;
            if (mangleGoogle.get() && q.contains("AIKO_B_1")) {
                translated = "MANGLED_NO_MARKER";
            } else if (q.contains("⟦AIKO_B_1⟧")) {
                String[] segs = q.split("\n⟦AIKO_B_\\d+⟧\n");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < segs.length; i++) {
                    if (i > 0) sb.append("\n⟦AIKO_B_").append(i).append("⟧\n");
                    sb.append("G:").append(segs[i]);
                }
                translated = sb.toString();
            } else {
                translated = "G:" + q;
            }
            JSONArray seg = new JSONArray().put(translated).put(q);
            JSONArray root = new JSONArray().put(new JSONArray().put(seg));
            respond(ex, 200, root.toString());
        });

        // 2. Mock Bing Auth + Bing Translate
        server.createContext("/bing-auth", ex -> {
            int n = bingAuthCalls.incrementAndGet();
            respond(ex, 200, "mock-bing-jwt-" + n);
        });
        server.createContext("/bing-tr", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (expireBingOnce.compareAndSet(true, false)) {
                respond(ex, 401, "token expired");
                return;
            }
            if (auth == null || !auth.startsWith("Bearer mock-bing-jwt-")) {
                respond(ex, 401, "missing token");
                return;
            }
            String body = readBody(ex);
            JSONArray in = new JSONArray(body);
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                String txt = in.getJSONObject(i).getString("Text");
                JSONObject tr = new JSONObject().put("text", "BING:" + txt).put("to", "zh-Hans");
                out.put(new JSONObject().put("translations", new JSONArray().put(tr)));
            }
            respond(ex, 200, out.toString());
        });

        // 3. Mock Youdao CN
        server.createContext("/youdao", ex -> {
            String form = readBody(ex);
            String txt = extractFormParam(form, "i");
            JSONObject piece = new JSONObject().put("tgt", "YD:" + txt).put("src", txt);
            JSONObject root = new JSONObject()
                    .put("errorCode", 0)
                    .put("translateResult", new JSONArray().put(new JSONArray().put(piece)));
            respond(ex, 200, root.toString());
        });

        // 4. Mock OpenAI Compat (/v1/models & /v1/chat/completions)
        server.createContext("/openai/v1/models", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer sk-local".equals(auth)) {
                respond(ex, 401, "{\"error\":{\"message\":\"bad key\"}}");
                return;
            }
            respond(ex, 200, "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"deepseek-v3\"}]}");
        });
        server.createContext("/openai/v1/chat/completions", ex -> {
            int cur = slowActive.incrementAndGet();
            slowPeak.accumulateAndGet(cur, Math::max);
            try {
                seenCustomHeader.set(ex.getRequestHeaders().getFirst("X-Aiko-Test"));
                int delay = aiDelayMs.get();
                if (delay > 0) {
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { }
                }
                JSONObject req = new JSONObject(readBody(ex));
                JSONArray msgs = req.getJSONArray("messages");
                String userMsg = msgs.getJSONObject(1).getString("content");
                String replyText;
                int arrIdx = userMsg.indexOf('[');
                if (arrIdx >= 0) {
                    JSONArray src = new JSONArray(userMsg.substring(arrIdx));
                    JSONArray dst = new JSONArray();
                    for (int i = 0; i < src.length(); i++) dst.put("OAI:" + src.getString(i));
                    replyText = "<think>reasoning</think>\n```json\n" + dst.toString() + "\n```";
                } else {
                    replyText = "OAI:" + userMsg;
                }
                JSONObject resp = new JSONObject().put("choices", new JSONArray().put(
                        new JSONObject().put("message", new JSONObject().put("content", replyText))));
                respond(ex, 200, resp.toString());
            } finally {
                slowActive.decrementAndGet();
            }
        });

        // 5. Mock Gemini (/gemini/v1beta/models & :generateContent)
        server.createContext("/gemini/v1beta/models", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith(":generateContent")) {
                JSONObject req = new JSONObject(readBody(ex));
                String text = req.getJSONArray("contents").getJSONObject(0)
                        .getJSONArray("parts").getJSONObject(0).getString("text");
                String reply = "GEM:" + text;
                JSONArray parts = new JSONArray()
                        .put(new JSONObject().put("thought", true).put("text", "internal thought"))
                        .put(new JSONObject().put("text", reply));
                JSONObject resp = new JSONObject().put("candidates", new JSONArray().put(
                        new JSONObject().put("content", new JSONObject().put("parts", parts))));
                respond(ex, 200, resp.toString());
            } else {
                respond(ex, 200, "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                        + "{\"name\":\"models/embedding-001\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");
            }
        });

        java.util.concurrent.ExecutorService serverExec = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(serverExec);
        server.start();
        BabelConfig cfg = new BabelConfig();
        BabelEngine engine = new BabelEngine(cfg);
        engine.setEndpointsForTest(base + "/google", base + "/bing-auth", base + "/bing-tr", base + "/youdao");

        try {
            // A. Google Free batch + LRU cache verification
            BabelEngine.TranslationResult g1 = engine.translateBatch(Arrays.asList("Alpha", "Beta"), "zh-CN");
            check(g1.isSuccess() && !g1.fellBackToCn && g1.usedEngine == BabelConfig.EngineKind.GOOGLE_FREE,
                    "Google Free success");
            check(Arrays.asList("G:Alpha", "G:Beta").equals(g1.translations), "Google delimiter batch: " + g1.translations);
            check(g1.cacheHits == 0 && g1.networkItems == 2, "first call uncached");

            long netBeforeCache = engine.getStats().networkCalls;
            BabelEngine.TranslationResult g1Cached = engine.translateBatch(Arrays.asList("Alpha", "Beta"), "zh-CN");
            check(g1Cached.isSuccess() && g1Cached.cacheHits == 2 && g1Cached.networkItems == 0, "second call 100% cache hit");
            check(engine.getStats().networkCalls == netBeforeCache, "no network call on cache hit");

            // B. Google Free mangled delimiter -> safe single-item fallback
            mangleGoogle.set(true);
            BabelEngine.TranslationResult gFallback = engine.translateBatch(Arrays.asList("One", "Two"), "zh-CN");
            check(gFallback.isSuccess() && Arrays.asList("G:One", "G:Two").equals(gFallback.translations),
                    "mangled delimiter fell back to single-item cleanly: " + gFallback.translations);
            mangleGoogle.set(false);

            // C. Smart auto-fallback GOOGLE_FREE -> BING_CN + Bing token caching + 401 refresh
            failGoogle.set(true);
            BabelEngine.TranslationResult cnFallback = engine.translateBatch(Arrays.asList("China", "Direct"), "zh-CN");
            check(cnFallback.isSuccess() && cnFallback.fellBackToCn && cnFallback.usedEngine == BabelConfig.EngineKind.BING_CN,
                    "Google failure auto-fell back to Bing CN");
            check(Arrays.asList("BING:China", "BING:Direct").equals(cnFallback.translations),
                    "Bing CN fallback output: " + cnFallback.translations);
            check(bingAuthCalls.get() == 1, "Bing auth token fetched once");

            // Second Bing call reuses cached token
            cfg.setEngineKind(BabelConfig.EngineKind.BING_CN);
            BabelEngine.SingleResult b2 = engine.translateSingle("CachedToken", "zh-CN");
            check(b2.isSuccess() && "BING:CachedToken".equals(b2.translated), "Bing direct single");
            check(bingAuthCalls.get() == 1, "Bing auth token cached across calls");

            // Token 401 forces refresh seamlessly
            expireBingOnce.set(true);
            BabelEngine.SingleResult b3 = engine.translateSingle("RefreshToken", "zh-CN");
            check(b3.isSuccess() && "BING:RefreshToken".equals(b3.translated), "Bing auto-refreshes expired token");
            check(bingAuthCalls.get() == 2, "Bing auth refreshed once on 401");

            // D. Youdao CN
            cfg.setEngineKind(BabelConfig.EngineKind.YOUDAO_CN);
            BabelEngine.SingleResult yd = engine.translateSingle("Hello Youdao", "zh-CN");
            check(yd.isSuccess() && "YD:Hello Youdao".equals(yd.translated), "Youdao CN single: " + yd.translated);

            // E. Custom AI: OpenAI-compat batch + extra header + model fetch
            BabelConfig.AiProfile oaiProf = new BabelConfig.AiProfile(
                    "oai-local", "Local OpenAI", BabelConfig.ApiFormat.OPENAI_COMPAT,
                    base + "/openai", "sk-local", "gpt-4o-mini", Collections.emptyList(),
                    2, 1024, 0.3f, 2400, 8, 15,
                    BabelConfig.DEFAULT_SYSTEM_PROMPT, "{\"X-Aiko-Test\":\"v040\"}"
            );
            cfg.upsertProfile(oaiProf);
            cfg.setActiveProfileId(oaiProf.id);
            cfg.setEngineKind(BabelConfig.EngineKind.CUSTOM_AI);

            BabelEngine.ModelListResult oaiModels = engine.fetchModels(oaiProf);
            check(oaiModels.isSuccess() && oaiModels.models.size() == 2
                    && oaiModels.models.contains("deepseek-v3"), "fetchModels OpenAI: " + oaiModels.models);

            BabelEngine.TranslationResult oaiRes = engine.translateBatch(Arrays.asList("Hi AI", "Second"), "zh-CN");
            check(oaiRes.isSuccess() && Arrays.asList("OAI:Hi AI", "OAI:Second").equals(oaiRes.translations),
                    "OpenAI batch + <think>/fence strip: " + oaiRes.translations);
            check("v040".equals(seenCustomHeader.get()), "extraHeadersJson sent on request");

            // F. Custom AI: Gemini single + model fetch + thought filtering
            BabelConfig.AiProfile gemProf = new BabelConfig.AiProfile(
                    "gem-local", "Local Gemini", BabelConfig.ApiFormat.GEMINI,
                    base + "/gemini", "AIzaLocal", "gemini-2.5-flash", Collections.emptyList(),
                    3, 1024, 0.2f, 2400, 8, 15,
                    BabelConfig.DEFAULT_SYSTEM_PROMPT, ""
            );
            cfg.upsertProfile(gemProf);
            cfg.setActiveProfileId(gemProf.id);

            BabelEngine.ModelListResult gemModels = engine.fetchModels(gemProf);
            check(gemModels.isSuccess() && gemModels.models.size() == 1
                    && "gemini-2.5-flash".equals(gemModels.models.get(0)),
                    "fetchModels Gemini filtered: " + gemModels.models);

            BabelEngine.SingleResult gemRes = engine.translateSingle("Hi Gemini", "zh-CN");
            check(gemRes.isSuccess() && "GEM:Hi Gemini".equals(gemRes.translated),
                    "Gemini single + thought skip: " + gemRes.translated);

            // G. Concurrency gate + non-caller-thread queue burst verification (maxConcurrency = 2 with 28 parallel requests)
            cfg.setActiveProfileId(oaiProf.id);
            slowPeak.set(0);
            aiDelayMs.set(35);
            Thread callerThread = Thread.currentThread();
            AtomicBoolean ranOnCallerThread = new AtomicBoolean(false);
            CountDownLatch concLatch = new CountDownLatch(28);
            AtomicInteger concOk = new AtomicInteger(0);
            long genConc = engine.nextSessionGeneration("tab-conc");
            for (int i = 0; i < 28; i++) {
                String txt = "ConcItem-" + i;
                engine.translateSingleAsync("tab-conc", genConc, txt, "zh-CN", r -> {
                    if (Thread.currentThread() == callerThread) ranOnCallerThread.set(true);
                    if (r.isSuccess()) concOk.incrementAndGet();
                    concLatch.countDown();
                });
            }
            check(concLatch.await(15, TimeUnit.SECONDS), "concurrency burst finished");
            check(!ranOnCallerThread.get(), "async burst must never run on caller thread (no CallerRunsPolicy)");
            check(concOk.get() == 28, "all 28 concurrent items succeeded: " + concOk.get());
            check(slowPeak.get() <= 2 && slowPeak.get() >= 1,
                    "peak in-flight respected maxConcurrency=2, got " + slowPeak.get());

            // H. Session generation cancellation suppresses stale callbacks
            aiDelayMs.set(350);
            AtomicBoolean staleDelivered = new AtomicBoolean(false);
            long staleGen = engine.nextSessionGeneration("tab-cancel");
            engine.translateSingleAsync("tab-cancel", staleGen, "StaleText", "zh-CN", r -> staleDelivered.set(true));
            Thread.sleep(40);
            engine.cancelScope("tab-cancel");
            Thread.sleep(450);
            check(!staleDelivered.get(), "cancelled session generation must not deliver stale result");

            BabelEngine.Stats finalStats = engine.getStats();
            check(finalStats.cacheHits >= 2 && finalStats.networkCalls >= 8 && finalStats.cacheSize >= 6,
                    "live stats populated: hits=" + finalStats.cacheHits + ", net=" + finalStats.networkCalls
                            + ", cacheSize=" + finalStats.cacheSize);

            System.out.println("PASS BabelEngine HTTP engines, auto-fallback to Bing CN, token cache/refresh, AI model fetch, LRU cache, concurrency cap & cancellation");
        } finally {
            engine.shutdown();
            server.stop(0);
            serverExec.shutdownNow();
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String extractQueryParam(HttpExchange ex, String name) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = readBody(ex);
            String fromBody = extractFormParam(body, name);
            if (!fromBody.isEmpty()) return fromBody;
        }
        return extractFormParam(raw == null ? "" : raw, name);
    }

    private static String extractFormParam(String query, String name) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if (name.equals(k)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return "";
    }

    private static String repeat(char c, int count) {
        char[] arr = new char[count];
        Arrays.fill(arr, c);
        return new String(arr);
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError("FAIL: " + msg);
    }
}
