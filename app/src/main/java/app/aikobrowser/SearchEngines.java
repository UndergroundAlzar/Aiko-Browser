package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

/** Search engine selector with fast in-memory cache and pure-Java catalog delegation. */
public final class SearchEngines {
    private static final String PREFS = "search_engines";
    private static final String KEY_ID = "engine_id";
    private static final String KEY_CUSTOM = "custom_template";

    private static volatile String cachedId;
    private static volatile String cachedCustom;

    private SearchEngines() {}

    public static String buildSearchUrl(String engineId, String customTemplate, String query) {
        return SearchEngineCatalog.buildSearchUrl(engineId, customTemplate, query);
    }

    public static String searchOrigin(String engineId, String customTemplate) {
        return SearchEngineCatalog.searchOrigin(engineId, customTemplate);
    }

    public static String engineName(String engineId) {
        return SearchEngineCatalog.engineName(engineId);
    }

    public static String[] engineIds() {
        return SearchEngineCatalog.engineIds();
    }

    public static String currentId(Context ctx) {
        String id = cachedId;
        if (id != null) return id;
        if (ctx == null) return SearchEngineCatalog.DEFAULT_ID;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        id = SearchEngineCatalog.normalizeId(prefs.getString(KEY_ID, SearchEngineCatalog.DEFAULT_ID));
        cachedId = id;
        if (cachedCustom == null) cachedCustom = prefs.getString(KEY_CUSTOM, "");
        return id;
    }

    public static String customTemplate(Context ctx) {
        String custom = cachedCustom;
        if (custom != null) return custom;
        if (ctx == null) return "";
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        custom = prefs.getString(KEY_CUSTOM, "");
        cachedCustom = custom;
        if (cachedId == null) {
            cachedId = SearchEngineCatalog.normalizeId(prefs.getString(KEY_ID, SearchEngineCatalog.DEFAULT_ID));
        }
        return custom;
    }

    public static void save(Context ctx, String id, String custom) {
        String normalized = SearchEngineCatalog.normalizeId(id);
        String cleanCustom = custom == null ? "" : custom.trim();
        cachedId = normalized;
        cachedCustom = cleanCustom;
        if (ctx == null) return;
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ID, normalized)
                .putString(KEY_CUSTOM, cleanCustom)
                .apply();
    }

    /** Convenience resolver for omnibox input using the currently saved search engine. */
    public static String resolveInput(Context ctx, String input) {
        return SearchEngineCatalog.resolveInput(currentId(ctx), customTemplate(ctx), input);
    }
}
