package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Customizable 1st-tier quick menu configuration (persisted in "menu_config.xml").
 * Lets users choose exactly which actions live on the clean 1st-tier bottom sheet
 * and their order, while keeping everything else cleanly organized in "More Tools"
 * and the dedicated 2nd-tier Settings Center.
 */
public final class MenuConfig {
    public static final String PREFS_NAME = "menu_config";
    private static final String KEY_PRIMARY_IDS = "primary_ids_json";

    public enum ActionId {
        BABEL("babel", "巴别塔翻译", "沉浸双语 / AI 翻译", BrowserIcon.Kind.TRANSLATE, false),
        USERSCRIPTS("userscripts", "油猴脚本", "脚本管理 / 实时菜单", BrowserIcon.Kind.CODE, false),
        ADBLOCK("adblock", "广告过滤", "7合1规则 / 订阅中心", BrowserIcon.Kind.SHIELD, false),
        BOOKMARKS("bookmarks", "书签收藏", "收藏夹 / 导入导出", BrowserIcon.Kind.STAR, false),
        HISTORY("history", "浏览历史", "历史记录 / 搜索", BrowserIcon.Kind.HISTORY, false),
        READER("reader", "阅读模式", "沉浸去干扰排版", BrowserIcon.Kind.READER, true),

        BOOKMARK_TOGGLE("bookmark_toggle", "收藏当前页", "一键收藏 / 取消", BrowserIcon.Kind.STAR, true),
        READING_SHELF("reading_shelf", "稍后读 · 0MB", "保存位置 / 离线快照", BrowserIcon.Kind.PIN, true),
        GALLERY("gallery", "沉浸看图", "185Hz 原图相册/批量存", BrowserIcon.Kind.GALLERY, true),
        LONGSHOT("longshot", "网页长截图", "全页拼接含分享码", BrowserIcon.Kind.IMAGE, true),
        VIDEO_BOOSTER("video_booster", "视频倍速/后台", "倍速 / 锁屏听声 / PiP", BrowserIcon.Kind.VIDEO, true),
        MEDIA_SNIFF("media_sniff", "媒体嗅探", "MP4 / M3U8 / 音频直链", BrowserIcon.Kind.SNIFF, true),
        READ_ALOUD("read_aloud", "网页朗读", "TTS 语音听书 0.8–2x", BrowserIcon.Kind.SPEAKER, true),
        DEVTOOLS("devtools", "网页调试台", "离线 Console / Network", BrowserIcon.Kind.DEVTOOLS, true),
        PWA_PIN("pwa_pin", "添加到桌面", "独立全屏 PWA 应用", BrowserIcon.Kind.PWA, true),
        TAB_MUTE("tab_mute", "标签静音", "切换当前页声音", BrowserIcon.Kind.MUTE, true),
        DARK_WEB("dark_web", "网页暗黑", "全局深色网页开关", BrowserIcon.Kind.DARK, false),
        PERF_ENGINE("perf_engine", "性能引擎", "狂暴 / 均衡 / 185Hz", BrowserIcon.Kind.BOLT, false),
        THEME_STUDIO("theme_studio", "外观主题", "17款高级色 / 调色台", BrowserIcon.Kind.PALETTE, false),
        CUSTOM_HOME("custom_home", "主页与定制", "iTab / 底栏 / 护眼字体", BrowserIcon.Kind.SUN_MOON, false),
        BACKUP("backup", "备份与恢复", "一键 .aiko 全量迁移", BrowserIcon.Kind.BACKUP, false),
        CLEAN_CACHE("clean_cache", "极速清理", "清缓存不掉登录态", BrowserIcon.Kind.TRASH, false);

        public final String id;
        public final String label;
        public final String defaultSub;
        public final BrowserIcon.Kind icon;
        public final boolean requiresPage;

        ActionId(String id, String label, String defaultSub, BrowserIcon.Kind icon, boolean requiresPage) {
            this.id = id;
            this.label = label;
            this.defaultSub = defaultSub;
            this.icon = icon;
            this.requiresPage = requiresPage;
        }

        public static ActionId fromId(String id) {
            if (id == null) return null;
            for (ActionId a : values()) {
                if (a.id.equals(id)) return a;
            }
            return null;
        }
    }

    /** Crisp default 1st-tier quick menu: 6 essential high-frequency items (no clutter!). */
    public static final List<ActionId> DEFAULT_PRIMARY = Collections.unmodifiableList(Arrays.asList(
            ActionId.BABEL,
            ActionId.USERSCRIPTS,
            ActionId.ADBLOCK,
            ActionId.READER,
            ActionId.BOOKMARKS,
            ActionId.HISTORY
    ));

    public static final int MAX_PRIMARY = 12;

    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final List<ActionId> primary = new ArrayList<>();

    public MenuConfig(Context ctx) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public MenuConfig(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        synchronized (lock) {
            primary.clear();
            String raw = prefs == null ? null : prefs.getString(KEY_PRIMARY_IDS, null);
            List<ActionId> parsed = decodeIds(raw);
            if (parsed.isEmpty()) {
                primary.addAll(DEFAULT_PRIMARY);
            } else {
                primary.addAll(parsed);
            }
        }
    }

    public List<ActionId> getPrimaryActions() {
        synchronized (lock) {
            return new ArrayList<>(primary);
        }
    }

    public List<ActionId> getSecondaryActions() {
        synchronized (lock) {
            Set<ActionId> set = new LinkedHashSet<>(primary);
            List<ActionId> out = new ArrayList<>();
            for (ActionId a : ActionId.values()) {
                if (!set.contains(a)) out.add(a);
            }
            return out;
        }
    }

    public boolean isPrimary(ActionId action) {
        if (action == null) return false;
        synchronized (lock) {
            return primary.contains(action);
        }
    }

    public boolean togglePrimary(ActionId action) {
        if (action == null) return false;
        boolean added;
        synchronized (lock) {
            if (primary.contains(action)) {
                if (primary.size() <= 2) return false; // Always keep at least 2 quick actions
                primary.remove(action);
                added = false;
            } else {
                if (primary.size() >= MAX_PRIMARY) return false;
                primary.add(action);
                added = true;
            }
            persistLocked();
        }
        return added;
    }

    public boolean movePrimary(ActionId action, int delta) {
        if (action == null || delta == 0) return false;
        synchronized (lock) {
            int idx = primary.indexOf(action);
            if (idx < 0) return false;
            int next = idx + delta;
            if (next < 0 || next >= primary.size()) return false;
            primary.remove(idx);
            primary.add(next, action);
            persistLocked();
            return true;
        }
    }

    public void resetDefaults() {
        synchronized (lock) {
            primary.clear();
            primary.addAll(DEFAULT_PRIMARY);
            persistLocked();
        }
    }

    private void persistLocked() {
        if (prefs == null) return;
        prefs.edit().putString(KEY_PRIMARY_IDS, encodeIds(primary)).apply();
    }

    public static String encodeIds(List<ActionId> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (ActionId a : list) {
                if (a != null) arr.put(a.id);
            }
        }
        return arr.toString();
    }

    public static List<ActionId> decodeIds(String json) {
        List<ActionId> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            Set<ActionId> seen = new LinkedHashSet<>();
            for (int i = 0; i < arr.length() && seen.size() < MAX_PRIMARY; i++) {
                ActionId a = ActionId.fromId(arr.optString(i, ""));
                if (a != null && seen.add(a)) out.add(a);
            }
        } catch (JSONException ignored) { }
        return out;
    }
}
