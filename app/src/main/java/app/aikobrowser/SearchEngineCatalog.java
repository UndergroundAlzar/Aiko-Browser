package app.aikobrowser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/** Pure-Java search engine catalog and URL builder (zero android.* dependencies). */
public final class SearchEngineCatalog {
    public static final String DEFAULT_ID = "google";
    public static final String CUSTOM_ID = "custom";

    private static final String[] IDS = {
            "google", "bing", "baidu", "ddg", "startpage", "kagi", "github", CUSTOM_ID
    };
    private static final String[] NAMES = {
            "Google", "Bing", "百度", "DuckDuckGo", "Startpage", "Kagi", "GitHub", "自定义"
    };
    private static final String[] TEMPLATES = {
            "https://www.google.com/search?q=%s",
            "https://www.bing.com/search?q=%s",
            "https://www.baidu.com/s?wd=%s",
            "https://duckduckgo.com/?q=%s",
            "https://www.startpage.com/sp/search?query=%s",
            "https://kagi.com/search?q=%s",
            "https://github.com/search?q=%s",
            ""
    };

    private SearchEngineCatalog() {}

    public static String[] engineIds() {
        return IDS.clone();
    }

    public static boolean isKnownId(String engineId) {
        return indexOf(engineId) >= 0;
    }

    public static String normalizeId(String engineId) {
        return indexOf(engineId) >= 0 ? engineId.toLowerCase(Locale.ROOT) : DEFAULT_ID;
    }

    public static String engineName(String engineId) {
        int idx = indexOf(engineId);
        return idx >= 0 ? NAMES[idx] : NAMES[0];
    }

    public static String buildSearchUrl(String engineId, String customTemplate, String query) {
        String encoded = encodeQuery(query);
        String template = resolveTemplate(engineId, customTemplate);
        if (template.contains("%s")) return template.replace("%s", encoded);
        if (template.contains("{searchTerms}")) return template.replace("{searchTerms}", encoded);
        return template + encoded;
    }

    public static String searchOrigin(String engineId, String customTemplate) {
        String sample = buildSearchUrl(engineId, customTemplate, "a");
        return HttpUrlUtil.extractOrigin(sample);
    }

    /** Resolves user omnibox input into a valid HTTP(S) URL or search engine query URL. */
    public static String resolveInput(String engineId, String customTemplate, String rawInput) {
        if (rawInput == null) return "";
        String input = rawInput.trim();
        if (input.isEmpty()) return "";
        if (HttpUrlUtil.isHttp(input)) return input;
        if (!containsWhitespace(input) && !input.contains("://") && looksLikeHostOrDomain(input)) {
            String candidate = "https://" + input;
            if (HttpUrlUtil.isHttp(candidate)) return candidate;
        }
        return buildSearchUrl(engineId, customTemplate, input);
    }

    private static String resolveTemplate(String engineId, String customTemplate) {
        int idx = indexOf(engineId);
        if (idx >= 0 && !CUSTOM_ID.equals(IDS[idx])) return TEMPLATES[idx];
        if (CUSTOM_ID.equalsIgnoreCase(engineId) && customTemplate != null) {
            String trimmed = customTemplate.trim();
            if (!trimmed.isEmpty()) {
                String probe = trimmed.contains("%s") ? trimmed.replace("%s", "test")
                        : (trimmed.contains("{searchTerms}") ? trimmed.replace("{searchTerms}", "test")
                        : trimmed + "test");
                if (HttpUrlUtil.isHttp(probe)) return trimmed;
            }
        }
        return TEMPLATES[0];
    }

    private static int indexOf(String engineId) {
        if (engineId == null || engineId.isEmpty()) return -1;
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equalsIgnoreCase(engineId)) return i;
        }
        return -1;
    }

    private static String encodeQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        try {
            return URLEncoder.encode(query.trim(), "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean looksLikeHostOrDomain(String input) {
        int slash = input.indexOf('/');
        String hostPort = slash >= 0 ? input.substring(0, slash) : input;
        if (hostPort.isEmpty() || hostPort.startsWith(".") || hostPort.endsWith(".")) return false;
        if (hostPort.equalsIgnoreCase("localhost") || hostPort.toLowerCase(Locale.ROOT).startsWith("localhost:")) {
            return true;
        }
        return hostPort.indexOf('.') > 0;
    }
}
