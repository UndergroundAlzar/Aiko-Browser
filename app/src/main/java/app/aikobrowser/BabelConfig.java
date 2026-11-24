package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent configuration for Babel (巴别塔) immersive bilingual translation engine.
 * Stored in SharedPreferences "babel_config" (backed up by BackupManager) with pure-Java
 * JSON serialization and host-JVM testable parameter clamping and host policy logic.
 */
public final class BabelConfig {
    public static final String PREFS_NAME = "babel_config";
    private static final String KEY_CONFIG_JSON = "config_json";
    private static final String KEY_SCHEMA_VERSION = "schema_version";
    private static final int SCHEMA_VERSION = 1;

    public static final int MIN_CONCURRENCY = 1;
    public static final int MAX_CONCURRENCY = 16;
    public static final int DEFAULT_CONCURRENCY = 4;

    public static final int MIN_MAX_TOKENS = 128;
    public static final int MAX_MAX_TOKENS = 32768;
    public static final int DEFAULT_MAX_TOKENS = 2048;

    public static final float MIN_TEMPERATURE = 0.0f;
    public static final float MAX_TEMPERATURE = 1.5f;
    public static final float DEFAULT_TEMPERATURE = 0.2f;

    public static final int MIN_BATCH_CHARS = 400;
    public static final int MAX_BATCH_CHARS = 12000;
    public static final int DEFAULT_BATCH_CHARS = 2400;

    public static final int MIN_BATCH_ITEMS = 1;
    public static final int MAX_BATCH_ITEMS = 30;
    public static final int DEFAULT_BATCH_ITEMS = 8;

    public static final int MIN_TIMEOUT_SEC = 5;
    public static final int MAX_TIMEOUT_SEC = 120;
    public static final int DEFAULT_TIMEOUT_SEC = 25;

    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";

    public static final int MAX_CACHED_MODELS = 500;
    public static final int MAX_PROFILES = 32;
    public static final int MAX_HOST_RULES = 512;

    public enum EngineKind {
        GOOGLE_FREE("google_free", "Google (默认)", "免费免 Key · 通用精准"),
        BING_CN("bing_cn", "微软必应 (国内直连)", "免 Key · 国内直连可用"),
        YOUDAO_CN("youdao_cn", "有道备用", "国内直连备用引擎"),
        CUSTOM_AI("custom_ai", "自定义 AI API", "OpenAI 兼容 / Gemini");

        public final String key;
        public final String label;
        public final String subtitle;

        EngineKind(String key, String label, String subtitle) {
            this.key = key;
            this.label = label;
            this.subtitle = subtitle;
        }

        public static EngineKind fromKey(String raw) {
            if (raw != null) {
                String trimmed = raw.trim();
                for (EngineKind k : values()) {
                    if (k.key.equalsIgnoreCase(trimmed) || k.name().equalsIgnoreCase(trimmed)) {
                        return k;
                    }
                }
            }
            return GOOGLE_FREE;
        }
    }

    public enum DisplayStyle {
        BILINGUAL_SOFT("soft", "柔和双语", "双语对照，译文柔和护眼"),
        BILINGUAL_QUOTE("quote", "左竖线引用", "左侧主题色引用条区分译文"),
        BILINGUAL_DASHED("dashed", "虚线下划线", "轻盈虚线标注译文"),
        BILINGUAL_BLUR("blur", "模糊学习", "译文默认模糊，点击/悬停显现"),
        REPLACE_ONLY("replace", "仅显示译文", "纯净沉浸，仅保留译文");

        public final String key;
        public final String label;
        public final String description;

        DisplayStyle(String key, String label, String description) {
            this.key = key;
            this.label = label;
            this.description = description;
        }

        public static DisplayStyle fromKey(String raw) {
            if (raw != null) {
                String trimmed = raw.trim();
                for (DisplayStyle s : values()) {
                    if (s.key.equalsIgnoreCase(trimmed) || s.name().equalsIgnoreCase(trimmed)) {
                        return s;
                    }
                }
            }
            return BILINGUAL_SOFT;
        }
    }

    public enum ApiFormat {
        OPENAI_COMPAT("openai_compat", "OpenAI 兼容"),
        GEMINI("gemini", "Google Gemini");

        public final String key;
        public final String label;

        ApiFormat(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static ApiFormat fromKey(String raw) {
            if (raw != null) {
                String trimmed = raw.trim();
                for (ApiFormat f : values()) {
                    if (f.key.equalsIgnoreCase(trimmed) || f.name().equalsIgnoreCase(trimmed)) {
                        return f;
                    }
                }
            }
            return OPENAI_COMPAT;
        }
    }

    public static final class LangOption {
        public final String code;
        public final String label;
        public final String englishName;

        public LangOption(String code, String label, String englishName) {
            this.code = code;
            this.label = label;
            this.englishName = englishName;
        }
    }

    public static final List<LangOption> LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            new LangOption("zh-CN", "简体中文", "Simplified Chinese"),
            new LangOption("zh-TW", "繁體中文", "Traditional Chinese"),
            new LangOption("en", "English", "English"),
            new LangOption("ja", "日本語", "Japanese"),
            new LangOption("ko", "한국어", "Korean"),
            new LangOption("fr", "Français", "French"),
            new LangOption("de", "Deutsch", "German"),
            new LangOption("es", "Español", "Spanish"),
            new LangOption("ru", "Русский", "Russian")
    ));

    public static final class PromptTemplate {
        public final String id;
        public final String name;
        public final String prompt;

        public PromptTemplate(String id, String name, String prompt) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
        }
    }

    public static final List<PromptTemplate> PROMPT_TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            new PromptTemplate(
                    "standard",
                    "标准精准",
                    "You are a professional translator. Translate accurately and faithfully into the target language, preserving meaning, tone, numbers, URLs, and inline formatting. Output only the requested translations without explanations."
            ),
            new PromptTemplate(
                    "tech",
                    "技术文档（保留术语与代码）",
                    "You are a senior technical translator. Translate technical documentation accurately into the target language. Keep code snippets, API identifiers, CLI commands, file paths, and widely used technical acronyms intact. Output only the translations."
            ),
            new PromptTemplate(
                    "natural_zh",
                    "自然中文润色",
                    "You are an editorial translator. Render the text into fluent, natural, idiomatic phrasing in the target language while staying faithful to the original meaning. Avoid stiff literal translationese. Output only the translations."
            ),
            new PromptTemplate(
                    "acg",
                    "ACG/角色扮演语境",
                    "You are a translator specializing in anime, games, light novels, and roleplay dialogues. Preserve character voice, emotional nuance, banter, and fandom terminology naturally in the target language. Output only the translations."
            ),
            new PromptTemplate(
                    "academic",
                    "学术论文",
                    "You are an academic translator. Translate into formal, precise, publication-grade scholarly prose using standard domain terminology while maintaining objective clarity. Output only the translations."
            )
    ));

    public static final String DEFAULT_SYSTEM_PROMPT = PROMPT_TEMPLATES.get(0).prompt;

    /**
     * Immutable, validated custom AI translation API profile.
     */
    public static final class AiProfile {
        public final String id;
        public final String name;
        public final ApiFormat apiFormat;
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final List<String> cachedModels;
        public final int maxConcurrency;
        public final int maxTokens;
        public final float temperature;
        public final int batchMaxChars;
        public final int batchMaxItems;
        public final int timeoutSec;
        public final String systemPrompt;
        public final String extraHeadersJson;

        public AiProfile(
                String id,
                String name,
                ApiFormat apiFormat,
                String baseUrl,
                String apiKey,
                String model,
                List<String> cachedModels,
                int maxConcurrency,
                int maxTokens,
                float temperature,
                int batchMaxChars,
                int batchMaxItems,
                int timeoutSec,
                String systemPrompt,
                String extraHeadersJson
        ) {
            ApiFormat fmt = apiFormat == null ? ApiFormat.OPENAI_COMPAT : apiFormat;
            String cleanId = cleanIdentifier(id);
            this.id = cleanId.isEmpty() ? ("ai-" + UUID.randomUUID().toString().substring(0, 8)) : cleanId;
            String cleanName = sanitizeSingleLine(name, 40);
            this.name = cleanName.isEmpty() ? (fmt == ApiFormat.GEMINI ? "Gemini 配置" : "自定义 AI") : cleanName;
            this.apiFormat = fmt;
            String cleanBase = sanitizeSingleLine(baseUrl, 512);
            this.baseUrl = cleanBase.isEmpty()
                    ? (fmt == ApiFormat.GEMINI ? DEFAULT_GEMINI_BASE_URL : DEFAULT_OPENAI_BASE_URL)
                    : cleanBase;
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            String cleanModel = sanitizeSingleLine(model, 160);
            this.model = cleanModel.isEmpty()
                    ? (fmt == ApiFormat.GEMINI ? DEFAULT_GEMINI_MODEL : DEFAULT_OPENAI_MODEL)
                    : cleanModel;
            this.cachedModels = sanitizeModelList(cachedModels);
            this.maxConcurrency = clampConcurrency(maxConcurrency);
            this.maxTokens = clampMaxTokens(maxTokens);
            this.temperature = clampTemperature(temperature);
            this.batchMaxChars = clampBatchMaxChars(batchMaxChars);
            this.batchMaxItems = clampBatchMaxItems(batchMaxItems);
            this.timeoutSec = clampTimeoutSec(timeoutSec);
            String cleanPrompt = systemPrompt == null ? "" : systemPrompt.trim();
            if (cleanPrompt.length() > 4000) cleanPrompt = cleanPrompt.substring(0, 4000);
            this.systemPrompt = cleanPrompt.isEmpty() ? DEFAULT_SYSTEM_PROMPT : cleanPrompt;
            String cleanHeaders = extraHeadersJson == null ? "" : extraHeadersJson.trim();
            if (cleanHeaders.length() > 4000) cleanHeaders = cleanHeaders.substring(0, 4000);
            this.extraHeadersJson = cleanHeaders;
        }

        public static AiProfile defaultOpenAi(String name) {
            return new AiProfile(
                    null, name, ApiFormat.OPENAI_COMPAT,
                    DEFAULT_OPENAI_BASE_URL, "", DEFAULT_OPENAI_MODEL,
                    Collections.emptyList(),
                    DEFAULT_CONCURRENCY, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE,
                    DEFAULT_BATCH_CHARS, DEFAULT_BATCH_ITEMS, DEFAULT_TIMEOUT_SEC,
                    DEFAULT_SYSTEM_PROMPT, ""
            );
        }

        public static AiProfile defaultGemini(String name) {
            return new AiProfile(
                    null, name, ApiFormat.GEMINI,
                    DEFAULT_GEMINI_BASE_URL, "", DEFAULT_GEMINI_MODEL,
                    Collections.emptyList(),
                    DEFAULT_CONCURRENCY, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE,
                    DEFAULT_BATCH_CHARS, DEFAULT_BATCH_ITEMS, DEFAULT_TIMEOUT_SEC,
                    DEFAULT_SYSTEM_PROMPT, ""
            );
        }

        public AiProfile withCachedModels(List<String> models) {
            return new AiProfile(
                    id, name, apiFormat, baseUrl, apiKey, model, models,
                    maxConcurrency, maxTokens, temperature,
                    batchMaxChars, batchMaxItems, timeoutSec,
                    systemPrompt, extraHeadersJson
            );
        }

        public AiProfile withModel(String newModel) {
            return new AiProfile(
                    id, name, apiFormat, baseUrl, apiKey, newModel, cachedModels,
                    maxConcurrency, maxTokens, temperature,
                    batchMaxChars, batchMaxItems, timeoutSec,
                    systemPrompt, extraHeadersJson
            );
        }

        /**
         * Parses optional extra headers JSON object safely, rejecting CR/LF control chars.
         */
        public Map<String, String> parseExtraHeaders() {
            return parseHeadersJson(extraHeadersJson);
        }

        public String summaryLine() {
            return String.format(
                    Locale.ROOT,
                    "%s · %s · 并发 %d · %d tok · T=%.1f · %d字/%d条 · %ds",
                    apiFormat.label, model, maxConcurrency, maxTokens,
                    temperature, batchMaxChars, batchMaxItems, timeoutSec
            );
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("apiFormat", apiFormat.key);
            obj.put("baseUrl", baseUrl);
            obj.put("apiKey", apiKey);
            obj.put("model", model);
            JSONArray modelsArr = new JSONArray();
            for (String m : cachedModels) modelsArr.put(m);
            obj.put("cachedModels", modelsArr);
            obj.put("maxConcurrency", maxConcurrency);
            obj.put("maxTokens", maxTokens);
            obj.put("temperature", Math.round(temperature * 100.0) / 100.0);
            obj.put("batchMaxChars", batchMaxChars);
            obj.put("batchMaxItems", batchMaxItems);
            obj.put("timeoutSec", timeoutSec);
            obj.put("systemPrompt", systemPrompt);
            obj.put("extraHeadersJson", extraHeadersJson);
            return obj;
        }

        public static AiProfile fromJson(JSONObject obj) {
            if (obj == null) return null;
            List<String> models = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("cachedModels");
            if (arr != null) {
                for (int i = 0; i < arr.length() && models.size() < MAX_CACHED_MODELS; i++) {
                    String m = arr.optString(i, "");
                    if (!m.trim().isEmpty()) models.add(m.trim());
                }
            }
            float temp = DEFAULT_TEMPERATURE;
            if (obj.has("temperature")) {
                double d = obj.optDouble("temperature", DEFAULT_TEMPERATURE);
                if (!Double.isNaN(d) && !Double.isInfinite(d)) temp = (float) d;
            }
            return new AiProfile(
                    obj.optString("id", ""),
                    obj.optString("name", ""),
                    ApiFormat.fromKey(obj.optString("apiFormat", "")),
                    obj.optString("baseUrl", ""),
                    obj.optString("apiKey", ""),
                    obj.optString("model", ""),
                    models,
                    obj.optInt("maxConcurrency", DEFAULT_CONCURRENCY),
                    obj.optInt("maxTokens", DEFAULT_MAX_TOKENS),
                    temp,
                    obj.optInt("batchMaxChars", DEFAULT_BATCH_CHARS),
                    obj.optInt("batchMaxItems", DEFAULT_BATCH_ITEMS),
                    obj.optInt("timeoutSec", DEFAULT_TIMEOUT_SEC),
                    obj.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT),
                    obj.optString("extraHeadersJson", "")
            );
        }
    }

    private final SharedPreferences prefs;

    private EngineKind engineKind = EngineKind.GOOGLE_FREE;
    private boolean autoFallbackToCn = true;
    private String targetLang = "zh-CN";
    private String inputBackTargetLang = "en";
    private DisplayStyle displayStyle = DisplayStyle.BILINGUAL_SOFT;
    private boolean tripleSpaceInputTranslate = true;
    private boolean selectionTranslate = true;
    private boolean skipCodeAndNav = true;
    private boolean showFloatingFab = true;
    private final LinkedHashSet<String> autoTranslateHosts = new LinkedHashSet<>();
    private final LinkedHashSet<String> neverTranslateHosts = new LinkedHashSet<>();
    private final ArrayList<AiProfile> aiProfiles = new ArrayList<>();
    private String activeProfileId = "";

    /** Pure-Java in-memory constructor (for host JVM unit tests and deserialization). */
    public BabelConfig() {
        this.prefs = null;
    }

    /** Android persistent constructor backed by SharedPreferences "babel_config". */
    public BabelConfig(Context context) {
        this.prefs = context == null
                ? null
                : context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (this.prefs != null) {
            String raw = this.prefs.getString(KEY_CONFIG_JSON, null);
            if (raw != null && !raw.trim().isEmpty()) {
                applyFromJsonInternal(raw, false);
            }
        }
    }

    // ---- Clamping & Validation Static Helpers ----

    public static int clampConcurrency(int v) {
        return Math.max(MIN_CONCURRENCY, Math.min(MAX_CONCURRENCY, v));
    }

    public static int clampMaxTokens(int v) {
        return Math.max(MIN_MAX_TOKENS, Math.min(MAX_MAX_TOKENS, v));
    }

    public static float clampTemperature(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return DEFAULT_TEMPERATURE;
        float clamped = Math.max(MIN_TEMPERATURE, Math.min(MAX_TEMPERATURE, v));
        return Math.round(clamped * 100f) / 100f;
    }

    public static int clampBatchMaxChars(int v) {
        return Math.max(MIN_BATCH_CHARS, Math.min(MAX_BATCH_CHARS, v));
    }

    public static int clampBatchMaxItems(int v) {
        return Math.max(MIN_BATCH_ITEMS, Math.min(MAX_BATCH_ITEMS, v));
    }

    public static int clampTimeoutSec(int v) {
        return Math.max(MIN_TIMEOUT_SEC, Math.min(MAX_TIMEOUT_SEC, v));
    }

    public static String normalizeLang(String code, String fallback) {
        if (code != null) {
            String trimmed = code.trim();
            for (LangOption opt : LANGUAGES) {
                if (opt.code.equalsIgnoreCase(trimmed)) return opt.code;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if ("zh".equals(lower) || "zh-hans".equals(lower) || "zh_cn".equals(lower)) return "zh-CN";
            if ("zh-hant".equals(lower) || "zh_tw".equals(lower) || "zh-hk".equals(lower)) return "zh-TW";
        }
        return fallback;
    }

    public static String langLabel(String code) {
        String norm = normalizeLang(code, "zh-CN");
        for (LangOption opt : LANGUAGES) {
            if (opt.code.equals(norm)) return opt.label;
        }
        return norm;
    }

    public static String langEnglishName(String code) {
        String norm = normalizeLang(code, "zh-CN");
        for (LangOption opt : LANGUAGES) {
            if (opt.code.equals(norm)) return opt.englishName;
        }
        return norm;
    }

    public static String normalizeHost(String hostOrUrl) {
        if (hostOrUrl == null) return "";
        String trimmed = hostOrUrl.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.contains("://")) {
            return HttpUrlUtil.extractHost(trimmed);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        while (lower.endsWith(".")) lower = lower.substring(0, lower.length() - 1);
        int slash = lower.indexOf('/');
        if (slash >= 0) lower = lower.substring(0, slash);
        int colon = lower.indexOf(':');
        if (colon >= 0 && !lower.startsWith("[")) lower = lower.substring(0, colon);
        if (lower.isEmpty() || lower.length() > 253) return "";
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c <= 0x20 || c == 0x7F) return "";
        }
        return lower;
    }

    public static Map<String, String> parseHeadersJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject obj = new JSONObject(raw.trim());
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k == null) continue;
                String cleanKey = k.trim();
                if (cleanKey.isEmpty() || hasHeaderControlChars(cleanKey)) continue;
                if (cleanKey.equalsIgnoreCase("Host") || cleanKey.equalsIgnoreCase("Content-Length")) continue;
                Object valObj = obj.opt(k);
                if (valObj == null || valObj == JSONObject.NULL) continue;
                String val = String.valueOf(valObj).trim();
                if (hasHeaderControlChars(val)) continue;
                out.put(cleanKey, val);
            }
        } catch (JSONException ignored) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean hasHeaderControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0' || (c < 0x20 && c != '\t') || c == 0x7F) return true;
        }
        return false;
    }

    private static String sanitizeSingleLine(String s, int maxLen) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxLen));
        for (int i = 0; i < s.length() && sb.length() < maxLen; i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') sb.append(' ');
            else if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String cleanIdentifier(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 48; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> sanitizeModelList(List<String> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String item : input) {
            String clean = sanitizeSingleLine(item, 160);
            if (!clean.isEmpty()) {
                set.add(clean);
                if (set.size() >= MAX_CACHED_MODELS) break;
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }

    // ---- Synchronized Getters & Setters ----

    public synchronized EngineKind getEngineKind() { return engineKind; }

    public synchronized void setEngineKind(EngineKind kind) {
        this.engineKind = kind == null ? EngineKind.GOOGLE_FREE : kind;
        persist();
    }

    public synchronized boolean isAutoFallbackToCn() { return autoFallbackToCn; }

    public synchronized void setAutoFallbackToCn(boolean enabled) {
        this.autoFallbackToCn = enabled;
        persist();
    }

    public synchronized String getTargetLang() { return targetLang; }

    public synchronized void setTargetLang(String lang) {
        this.targetLang = normalizeLang(lang, "zh-CN");
        persist();
    }

    public synchronized String getInputBackTargetLang() { return inputBackTargetLang; }

    public synchronized void setInputBackTargetLang(String lang) {
        this.inputBackTargetLang = normalizeLang(lang, "en");
        persist();
    }

    public synchronized DisplayStyle getDisplayStyle() { return displayStyle; }

    public synchronized void setDisplayStyle(DisplayStyle style) {
        this.displayStyle = style == null ? DisplayStyle.BILINGUAL_SOFT : style;
        persist();
    }

    public synchronized boolean isTripleSpaceInputTranslate() { return tripleSpaceInputTranslate; }

    public synchronized void setTripleSpaceInputTranslate(boolean enabled) {
        this.tripleSpaceInputTranslate = enabled;
        persist();
    }

    public synchronized boolean isSelectionTranslate() { return selectionTranslate; }

    public synchronized void setSelectionTranslate(boolean enabled) {
        this.selectionTranslate = enabled;
        persist();
    }

    public synchronized boolean isSkipCodeAndNav() { return skipCodeAndNav; }

    public synchronized void setSkipCodeAndNav(boolean enabled) {
        this.skipCodeAndNav = enabled;
        persist();
    }

    public synchronized boolean isShowFloatingFab() { return showFloatingFab; }

    public synchronized void setShowFloatingFab(boolean enabled) {
        this.showFloatingFab = enabled;
        persist();
    }

    // ---- Host Policy ----

    public synchronized boolean isAutoTranslateHost(String hostOrUrl) {
        String h = normalizeHost(hostOrUrl);
        return !h.isEmpty() && autoTranslateHosts.contains(h) && !neverTranslateHosts.contains(h);
    }

    public synchronized void setAutoTranslateHost(String hostOrUrl, boolean enabled) {
        String h = normalizeHost(hostOrUrl);
        if (h.isEmpty()) return;
        if (enabled) {
            neverTranslateHosts.remove(h);
            if (autoTranslateHosts.size() >= MAX_HOST_RULES) {
                Iterator<String> it = autoTranslateHosts.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            autoTranslateHosts.add(h);
        } else {
            autoTranslateHosts.remove(h);
        }
        persist();
    }

    public synchronized boolean isNeverTranslateHost(String hostOrUrl) {
        String h = normalizeHost(hostOrUrl);
        return !h.isEmpty() && neverTranslateHosts.contains(h);
    }

    public synchronized void setNeverTranslateHost(String hostOrUrl, boolean enabled) {
        String h = normalizeHost(hostOrUrl);
        if (h.isEmpty()) return;
        if (enabled) {
            autoTranslateHosts.remove(h);
            if (neverTranslateHosts.size() >= MAX_HOST_RULES) {
                Iterator<String> it = neverTranslateHosts.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            neverTranslateHosts.add(h);
        } else {
            neverTranslateHosts.remove(h);
        }
        persist();
    }

    public synchronized Set<String> getAutoTranslateHosts() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(autoTranslateHosts));
    }

    public synchronized Set<String> getNeverTranslateHosts() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(neverTranslateHosts));
    }

    // ---- Custom AI Profiles ----

    public synchronized List<AiProfile> getAiProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(aiProfiles));
    }

    public synchronized String getActiveProfileId() {
        return activeProfileId;
    }

    public synchronized AiProfile getActiveProfile() {
        if (aiProfiles.isEmpty()) return null;
        if (activeProfileId != null && !activeProfileId.isEmpty()) {
            for (AiProfile p : aiProfiles) {
                if (p.id.equals(activeProfileId)) return p;
            }
        }
        return aiProfiles.get(0);
    }

    public synchronized AiProfile findProfileById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (AiProfile p : aiProfiles) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public synchronized boolean setActiveProfileId(String id) {
        if (id == null) return false;
        for (AiProfile p : aiProfiles) {
            if (p.id.equals(id)) {
                activeProfileId = p.id;
                persist();
                return true;
            }
        }
        return false;
    }

    public synchronized AiProfile upsertProfile(AiProfile profile) {
        if (profile == null) return null;
        for (int i = 0; i < aiProfiles.size(); i++) {
            if (aiProfiles.get(i).id.equals(profile.id)) {
                aiProfiles.set(i, profile);
                if (activeProfileId == null || activeProfileId.isEmpty()) {
                    activeProfileId = profile.id;
                }
                persist();
                return profile;
            }
        }
        if (aiProfiles.size() >= MAX_PROFILES) {
            aiProfiles.remove(0);
        }
        aiProfiles.add(profile);
        if (activeProfileId == null || activeProfileId.isEmpty()) {
            activeProfileId = profile.id;
        }
        persist();
        return profile;
    }

    public synchronized boolean deleteProfile(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean removed = false;
        for (int i = 0; i < aiProfiles.size(); i++) {
            if (aiProfiles.get(i).id.equals(id)) {
                aiProfiles.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            if (id.equals(activeProfileId)) {
                activeProfileId = aiProfiles.isEmpty() ? "" : aiProfiles.get(0).id;
            }
            persist();
        }
        return removed;
    }

    public synchronized void updateCachedModels(String profileId, List<String> models) {
        if (profileId == null || profileId.isEmpty()) return;
        for (int i = 0; i < aiProfiles.size(); i++) {
            AiProfile existing = aiProfiles.get(i);
            if (existing.id.equals(profileId)) {
                aiProfiles.set(i, existing.withCachedModels(models));
                persist();
                return;
            }
        }
    }

    // ---- Effective Runtime Tuning ----

    public synchronized int resolveEffectiveConcurrency() {
        if (engineKind == EngineKind.CUSTOM_AI) {
            AiProfile p = getActiveProfile();
            if (p != null) return p.maxConcurrency;
        }
        return DEFAULT_CONCURRENCY;
    }

    public synchronized int resolveEffectiveBatchMaxChars() {
        if (engineKind == EngineKind.CUSTOM_AI) {
            AiProfile p = getActiveProfile();
            if (p != null) return p.batchMaxChars;
        }
        return engineKind == EngineKind.YOUDAO_CN ? 1200 : DEFAULT_BATCH_CHARS;
    }

    public synchronized int resolveEffectiveBatchMaxItems() {
        if (engineKind == EngineKind.CUSTOM_AI) {
            AiProfile p = getActiveProfile();
            if (p != null) return p.batchMaxItems;
        }
        return engineKind == EngineKind.YOUDAO_CN ? 4 : DEFAULT_BATCH_ITEMS;
    }

    public synchronized int resolveEffectiveTimeoutSec() {
        if (engineKind == EngineKind.CUSTOM_AI) {
            AiProfile p = getActiveProfile();
            if (p != null) return p.timeoutSec;
        }
        return 12;
    }

    public synchronized String activeEngineDisplayName() {
        if (engineKind == EngineKind.CUSTOM_AI) {
            AiProfile p = getActiveProfile();
            if (p != null) return p.name + " (" + p.model + ")";
            return "自定义 AI (未配置)";
        }
        return engineKind.label;
    }

    // ---- Pure-Java JSON Serialization ----

    public synchronized String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("engineKind", engineKind.key);
            root.put("autoFallbackToCn", autoFallbackToCn);
            root.put("targetLang", targetLang);
            root.put("inputBackTargetLang", inputBackTargetLang);
            root.put("displayStyle", displayStyle.key);
            root.put("tripleSpaceInputTranslate", tripleSpaceInputTranslate);
            root.put("selectionTranslate", selectionTranslate);
            root.put("skipCodeAndNav", skipCodeAndNav);
            root.put("showFloatingFab", showFloatingFab);

            JSONArray autoArr = new JSONArray();
            for (String h : autoTranslateHosts) autoArr.put(h);
            root.put("autoTranslateHosts", autoArr);

            JSONArray neverArr = new JSONArray();
            for (String h : neverTranslateHosts) neverArr.put(h);
            root.put("neverTranslateHosts", neverArr);

            JSONArray profArr = new JSONArray();
            for (AiProfile p : aiProfiles) profArr.put(p.toJson());
            root.put("aiProfiles", profArr);
            root.put("activeProfileId", activeProfileId == null ? "" : activeProfileId);
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to serialize BabelConfig", e);
        }
    }

    public static BabelConfig fromJson(String json) {
        BabelConfig cfg = new BabelConfig();
        cfg.applyFromJsonInternal(json, false);
        return cfg;
    }

    public synchronized void applyFromJson(String json) {
        applyFromJsonInternal(json, true);
    }

    private synchronized void applyFromJsonInternal(String json, boolean save) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            engineKind = EngineKind.fromKey(root.optString("engineKind", EngineKind.GOOGLE_FREE.key));
            autoFallbackToCn = root.optBoolean("autoFallbackToCn", true);
            targetLang = normalizeLang(root.optString("targetLang", "zh-CN"), "zh-CN");
            inputBackTargetLang = normalizeLang(root.optString("inputBackTargetLang", "en"), "en");
            displayStyle = DisplayStyle.fromKey(root.optString("displayStyle", DisplayStyle.BILINGUAL_SOFT.key));
            tripleSpaceInputTranslate = root.optBoolean("tripleSpaceInputTranslate", true);
            selectionTranslate = root.optBoolean("selectionTranslate", true);
            skipCodeAndNav = root.optBoolean("skipCodeAndNav", true);
            showFloatingFab = root.optBoolean("showFloatingFab", true);

            autoTranslateHosts.clear();
            JSONArray autoArr = root.optJSONArray("autoTranslateHosts");
            if (autoArr != null) {
                for (int i = 0; i < autoArr.length() && autoTranslateHosts.size() < MAX_HOST_RULES; i++) {
                    String h = normalizeHost(autoArr.optString(i, ""));
                    if (!h.isEmpty()) autoTranslateHosts.add(h);
                }
            }

            neverTranslateHosts.clear();
            JSONArray neverArr = root.optJSONArray("neverTranslateHosts");
            if (neverArr != null) {
                for (int i = 0; i < neverArr.length() && neverTranslateHosts.size() < MAX_HOST_RULES; i++) {
                    String h = normalizeHost(neverArr.optString(i, ""));
                    if (!h.isEmpty()) {
                        autoTranslateHosts.remove(h);
                        neverTranslateHosts.add(h);
                    }
                }
            }

            aiProfiles.clear();
            JSONArray profArr = root.optJSONArray("aiProfiles");
            if (profArr != null) {
                LinkedHashSet<String> seenIds = new LinkedHashSet<>();
                for (int i = 0; i < profArr.length() && aiProfiles.size() < MAX_PROFILES; i++) {
                    AiProfile p = AiProfile.fromJson(profArr.optJSONObject(i));
                    if (p != null && seenIds.add(p.id)) {
                        aiProfiles.add(p);
                    }
                }
            }

            String savedActive = root.optString("activeProfileId", "");
            activeProfileId = "";
            for (AiProfile p : aiProfiles) {
                if (p.id.equals(savedActive)) {
                    activeProfileId = p.id;
                    break;
                }
            }
            if (activeProfileId.isEmpty() && !aiProfiles.isEmpty()) {
                activeProfileId = aiProfiles.get(0).id;
            }
            if (save) persist();
        } catch (JSONException ignored) {
            // Retain defaults or prior valid state on malformed JSON
        }
    }

    private void persist() {
        if (prefs == null) return;
        String serialized = toJson();
        prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_CONFIG_JSON, serialized)
                .apply();
    }
}
