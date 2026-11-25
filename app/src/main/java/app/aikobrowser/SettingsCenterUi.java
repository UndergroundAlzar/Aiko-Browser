package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated 2nd-tier Settings Center (设置中心), 1st-tier Menu Customizer (自定义一级菜单),
 * More Tools drawer (更多工具), and Fast Storage Cleaner (极速存储清理) — keeping the 1st-tier
 * quick menu ultra-clean, scannable, and customizable.
 */
final class SettingsCenterUi {

    interface Host {
        Activity activity();
        float density();
        DisplayModeEngine displayMode();
        MenuConfig menuConfig();
        StartPageConfig startPageConfig();
        PerfProfiles.Profile activePerfProfile();
        String appVersionName();
        String babelSummary();
        String searchEngineName();
        String themeSummary();
        String filterSummary();
        boolean isGlobalDarkWeb();
        boolean isCleanLinks();
        boolean isPageOpen();
        void toast(String msg);

        void openThemeStudio();
        void openPerformanceCenter();
        void openPerformanceDiagnostics();
        void openCustomizationSheet();
        void openGestureSettings();
        void openBabelSheet();
        void openFilterRules();
        void openSearchEnginePicker();
        void openUserScripts();
        void openBangManager();
        void openBackupSheet();
        void openAboutSheet();
        void toggleGlobalDarkWeb();
        void toggleCleanLinks();
        void clearWebHttpCache(Runnable onDone);
        void clearAllHistory();
        void executeMenuAction(MenuConfig.ActionId action);
        String actionDynamicSubtitle(MenuConfig.ActionId action);
        boolean actionIsActive(MenuConfig.ActionId action);
        int actionAccentColor(MenuConfig.ActionId action);
    }

    private SettingsCenterUi() {}

    // =========================================================================
    // 1. Dedicated 2nd-Tier Settings Center (⚙️ 设置中心)
    // =========================================================================

    static void showSettingsCenter(Host host) {
        Activity act = host.activity();
        float d = host.density();
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "设置中心", dialog::dismiss);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(620, Math.max(300, act.getResources().getConfiguration().screenHeightDp - 110));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout body = vStack(act);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Top Flagship Identity Banner
        LinearLayout banner = vStack(act);
        banner.setPadding(dp(d, 15), dp(d, 13), dp(d, 15), dp(d, 13));
        banner.setBackground(AikoTheme.glowBanner(dp(d, 18)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(d, 2);
        blp.bottomMargin = dp(d, 8);
        body.addView(banner, blp);

        LinearLayout bHead = hRow(act);
        bHead.setGravity(Gravity.CENTER_VERTICAL);
        banner.addView(bHead);

        TextView bTitle = txt(act, "Aiko Browser v" + host.appVersionName(), 15, AikoTheme.TEXT, true);
        bHead.addView(bTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        DisplayModeEngine dme = host.displayMode();
        float hz = dme != null ? dme.activeRefreshRate() : 0f;
        String hzTag = hz > 0 ? String.format(Locale.ROOT, "%.0fHz", hz) : "高刷";
        TextView hzPill = AikoTheme.badge(act, d, host.activePerfProfile().name + " · " + hzTag,
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        bHead.addView(hzPill, new LinearLayout.LayoutParams(-2, -2));

        String engineSummary = WebViewPerf.flagshipFeatureSummary();
        TextView bSub = txt(act, "主题：" + host.themeSummary() + "  ·  " + engineSummary, 11, AikoTheme.TEXT_MUTED, false);
        bSub.setSingleLine(true);
        bSub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(-1, -2);
        bslp.topMargin = dp(d, 5);
        banner.addView(bSub, bslp);

        // Section 1: 性能与核心引擎 (Flagship Chip First!)
        body.addView(AikoTheme.sectionHeader(act, d, "性能与硬件调度"));
        addSettingRow(body, act, d, BrowserIcon.Kind.BOLT, AikoTheme.ACCENT_CYAN,
                "性能引擎", host.activePerfProfile().name + " · " + hzTag + " · ADPF / QUIC / 预加载",
                () -> { dialog.dismiss(); host.openPerformanceCenter(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.SPEED, AikoTheme.EMERALD,
                "硬件诊断与引擎状态", "刷新率、HTTP/3 QUIC、Service Worker 与内存详情",
                () -> { dialog.dismiss(); host.openPerformanceDiagnostics(); });

        // Section 2: 外观与界面定制
        body.addView(AikoTheme.sectionHeader(act, d, "外观与界面定制"));
        addSettingRow(body, act, d, BrowserIcon.Kind.PALETTE, AikoTheme.ACCENT,
                "外观主题工坊", host.themeSummary() + " · 17 款精选色（含初音绿 / 高级色）",
                () -> { dialog.dismiss(); host.openThemeStudio(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.SUN_MOON, AikoTheme.ACCENT_CYAN,
                "主页、底栏与护眼字体",
                (host.startPageConfig().resolveHomeUrl() == null ? "原生主页" : "自定义主页")
                        + " · 胶囊底栏 · 深夜护眼 · 本地字体",
                () -> { dialog.dismiss(); host.openCustomizationSheet(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.EDIT, AikoTheme.EMERALD,
                "自定义一级快捷菜单", "当前置顶 " + host.menuConfig().getPrimaryActions().size() + " 项 · 自由增删排序",
                () -> { dialog.dismiss(); showMenuCustomizer(host, () -> showSettingsCenter(host)); });
        addSettingRow(body, act, d, BrowserIcon.Kind.GESTURE, AikoTheme.ACCENT,
                "手势与自动隐栏", "底栏滑切标签、上滑网格、边缘手势与隐栏",
                () -> { dialog.dismiss(); host.openGestureSettings(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.DARK, AikoTheme.ACCENT,
                "全局网页深色模式", host.isGlobalDarkWeb() ? "已开启 · 自动适配暗黑网页" : "已关闭 · 点按切换",
                () -> { dialog.dismiss(); host.toggleGlobalDarkWeb(); });

        // Section 3: 巴别塔翻译、搜索与扩展
        body.addView(AikoTheme.sectionHeader(act, d, "翻译、搜索与扩展"));
        addSettingRow(body, act, d, BrowserIcon.Kind.TRANSLATE, AikoTheme.ACCENT,
                "巴别塔沉浸式翻译", host.babelSummary() + " · 三级降级 / 自定义 AI API",
                () -> { dialog.dismiss(); host.openBabelSheet(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.CODE, AikoTheme.ACCENT_CYAN,
                "油猴脚本管理", "Greasy Fork 一键装 · GM_xmlhttpRequest · 查更新",
                () -> { dialog.dismiss(); host.openUserScripts(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.ENGINE, AikoTheme.EMERALD,
                "搜索引擎", "当前：" + host.searchEngineName(),
                () -> { dialog.dismiss(); host.openSearchEnginePicker(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.FLASH_CMD, AikoTheme.AMBER,
                "!Bang 瞬搜与 /命令", "自定义快捷搜索词（!gh / !b / !gf 等）",
                () -> { dialog.dismiss(); host.openBangManager(); });

        // Section 4: 广告拦截、隐私与存储
        body.addView(AikoTheme.sectionHeader(act, d, "广告过滤、隐私与存储"));
        addSettingRow(body, act, d, BrowserIcon.Kind.SHIELD, AikoTheme.EMERALD,
                "广告过滤与订阅中心", host.filterSummary() + " · 含 Service Worker 拦截",
                () -> { dialog.dismiss(); host.openFilterRules(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.CHECK, AikoTheme.ACCENT_CYAN,
                "分享/复制链接自动净化", host.isCleanLinks() ? "已开启 · 自动剥离追踪参数" : "已关闭 · 点按开启",
                () -> { dialog.dismiss(); host.toggleCleanLinks(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.TRASH, AikoTheme.AMBER,
                "极速存储与缓存清理", "一键清理 HTTP 缓存（保留登录态）或清理 Cookie/历史",
                () -> { dialog.dismiss(); showStorageCleaner(host); });

        // Section 5: 备份与关于
        body.addView(AikoTheme.sectionHeader(act, d, "迁移与关于"));
        addSettingRow(body, act, d, BrowserIcon.Kind.BACKUP, AikoTheme.ACCENT,
                "全量备份与恢复 (.aiko)", "一键打包主题、规则、脚本数据、巴别塔与菜单布局",
                () -> { dialog.dismiss(); host.openBackupSheet(); });
        addSettingRow(body, act, d, BrowserIcon.Kind.INFO, AikoTheme.TEXT_MUTED,
                "关于 Aiko Browser", "v" + host.appVersionName() + " · 作者 Alzar · GitHub 开源仓库",
                () -> { dialog.dismiss(); host.openAboutSheet(); });

        presentSheet(act, dialog, panel, host.displayMode());
    }

    // =========================================================================
    // 2. More Tools 2nd-Tier Drawer (更多工具 — holds non-pinned actions cleanly)
    // =========================================================================

    static void showMoreTools(Host host) {
        Activity act = host.activity();
        float d = host.density();
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "更多工具", dialog::dismiss);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, act.getResources().getConfiguration().screenHeightDp - 130));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout body = vStack(act);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Top tip bar with 1-tap Customize 1st-tier menu button
        LinearLayout tipBar = hRow(act);
        tipBar.setGravity(Gravity.CENTER_VERTICAL);
        tipBar.setPadding(dp(d, 12), dp(d, 9), dp(d, 10), dp(d, 9));
        tipBar.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 14)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = dp(d, 10);
        body.addView(tipBar, tlp);

        TextView tipText = txt(act, "这里收纳了未置顶的工具，可随时调到一级菜单", 11, AikoTheme.TEXT_MUTED, false);
        tipBar.addView(tipText, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView editPill = AikoTheme.badge(act, d, "自定义菜单", AikoTheme.ACCENT, AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT);
        editPill.setOnClickListener(v -> {
            dialog.dismiss();
            showMenuCustomizer(host, () -> showMoreTools(host));
        });
        tipBar.addView(editPill, new LinearLayout.LayoutParams(-2, dp(d, 28)));

        List<MenuConfig.ActionId> rest = host.menuConfig().getSecondaryActions();
        if (rest.isEmpty()) {
            TextView empty = txt(act, "全部常用工具均已置顶到一级菜单", 13, AikoTheme.TEXT_MUTED, false);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(0, dp(d, 24), 0, dp(d, 24));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            boolean pageOpen = host.isPageOpen();
            for (int i = 0; i < rest.size(); i += 2) {
                MenuConfig.ActionId left = rest.get(i);
                MenuConfig.ActionId right = (i + 1 < rest.size()) ? rest.get(i + 1) : null;
                LinearLayout row = hRow(act);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(d, 9);
                body.addView(row, rlp);

                View lv = buildMenuTile(dialog, host, left, pageOpen);
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
                if (right != null) lp1.rightMargin = dp(d, 9);
                row.addView(lv, lp1);

                if (right != null) {
                    View rv = buildMenuTile(dialog, host, right, pageOpen);
                    row.addView(rv, new LinearLayout.LayoutParams(0, -2, 1f));
                } else {
                    row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
        }

        presentSheet(act, dialog, panel, host.displayMode());
    }

    static View buildMenuTile(Dialog dialog, Host host, MenuConfig.ActionId action, boolean pageOpen) {
        Activity act = host.activity();
        float d = host.density();
        boolean enabled = !action.requiresPage || pageOpen;
        String sub = host.actionDynamicSubtitle(action);
        if (sub == null || sub.isEmpty()) sub = action.defaultSub;
        boolean active = host.actionIsActive(action);
        int color = host.actionAccentColor(action);
        return AikoTheme.toggleTile(act, d, action.icon, action.label, sub, active, enabled, color, () -> {
            dialog.dismiss();
            host.executeMenuAction(action);
        });
    }

    // =========================================================================
    // 3. 1st-Tier Menu Customizer (自定义一级快捷菜单)
    // =========================================================================

    static void showMenuCustomizer(Host host, Runnable onDone) {
        Activity act = host.activity();
        float d = host.density();
        MenuConfig cfg = host.menuConfig();
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "自定义一级菜单", () -> {
            dialog.dismiss();
            if (onDone != null) onDone.run();
        });

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(560, Math.max(280, act.getResources().getConfiguration().screenHeightDp - 130));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout body = vStack(act);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            body.removeAllViews();

            // Header bar with Reset
            LinearLayout top = hRow(act);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams toplp = new LinearLayout.LayoutParams(-1, -2);
            toplp.bottomMargin = dp(d, 8);
            body.addView(top, toplp);

            List<MenuConfig.ActionId> prim = cfg.getPrimaryActions();
            TextView countTv = txt(act,
                    "已置顶一级菜单（" + prim.size() + "/" + MenuConfig.MAX_PRIMARY + "，可排序）",
                    12, AikoTheme.ACCENT, true);
            top.addView(countTv, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView resetBtn = AikoTheme.badge(act, d, "恢复默认", AikoTheme.TEXT_MUTED, AikoTheme.PILL, AikoTheme.STROKE);
            resetBtn.setOnClickListener(v -> {
                cfg.resetDefaults();
                host.toast("已恢复清爽默认一级菜单");
                rebuild[0].run();
            });
            top.addView(resetBtn, new LinearLayout.LayoutParams(-2, dp(d, 28)));

            // Primary pinned rows (with Up / Down / Remove)
            for (int i = 0; i < prim.size(); i++) {
                final MenuConfig.ActionId a = prim.get(i);
                final int idx = i;
                LinearLayout card = hRow(act);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(d, 11), dp(d, 9), dp(d, 9), dp(d, 9));
                card.setBackground(AikoTheme.card(AikoTheme.HIGHLIGHT, AikoTheme.STROKE_ACCENT, d, dp(d, 13)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.bottomMargin = dp(d, 6);
                body.addView(card, clp);

                BrowserIcon ic = new BrowserIcon(act, a.icon);
                ic.tint(AikoTheme.ACCENT);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20));
                ilp.rightMargin = dp(d, 10);
                card.addView(ic, ilp);

                LinearLayout col = vStack(act);
                col.addView(txt(act, a.label, 13, AikoTheme.TEXT, true));
                col.addView(txt(act, a.defaultSub, 10, AikoTheme.TEXT_MUTED, false));
                card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView upBtn = miniBtn(act, d, "↑", idx > 0);
                upBtn.setOnClickListener(v -> {
                    if (cfg.movePrimary(a, -1)) rebuild[0].run();
                });
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(d, 30), dp(d, 28));
                ulp.rightMargin = dp(d, 5);
                card.addView(upBtn, ulp);

                TextView dnBtn = miniBtn(act, d, "↓", idx < prim.size() - 1);
                dnBtn.setOnClickListener(v -> {
                    if (cfg.movePrimary(a, 1)) rebuild[0].run();
                });
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(d, 30), dp(d, 28));
                dlp.rightMargin = dp(d, 6);
                card.addView(dnBtn, dlp);

                TextView rmBtn = AikoTheme.badge(act, d, "移出", AikoTheme.CORAL, AikoTheme.CARD, AikoTheme.STROKE);
                rmBtn.setOnClickListener(v -> {
                    if (prim.size() <= 2) {
                        host.toast("一级菜单至少保留 2 项");
                        return;
                    }
                    cfg.togglePrimary(a);
                    rebuild[0].run();
                });
                card.addView(rmBtn, new LinearLayout.LayoutParams(-2, dp(d, 28)));
            }

            // Secondary pool (in More Tools — tap to pin into 1st-tier)
            body.addView(AikoTheme.sectionHeader(act, d, "更多工具池（点按「置顶」加入一级菜单）"));
            List<MenuConfig.ActionId> sec = cfg.getSecondaryActions();
            for (MenuConfig.ActionId a : sec) {
                LinearLayout card = hRow(act);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(d, 11), dp(d, 9), dp(d, 9), dp(d, 9));
                card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 13)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.bottomMargin = dp(d, 6);
                body.addView(card, clp);

                BrowserIcon ic = new BrowserIcon(act, a.icon);
                ic.tint(AikoTheme.TEXT_MUTED);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20));
                ilp.rightMargin = dp(d, 10);
                card.addView(ic, ilp);

                LinearLayout col = vStack(act);
                col.addView(txt(act, a.label, 13, AikoTheme.TEXT, true));
                col.addView(txt(act, a.defaultSub, 10, AikoTheme.TEXT_DIM, false));
                card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView addBtn = AikoTheme.badge(act, d, "+ 置顶", AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
                addBtn.setOnClickListener(v -> {
                    if (cfg.getPrimaryActions().size() >= MenuConfig.MAX_PRIMARY) {
                        host.toast("一级菜单最多置顶 " + MenuConfig.MAX_PRIMARY + " 项");
                        return;
                    }
                    cfg.togglePrimary(a);
                    rebuild[0].run();
                });
                card.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(d, 28)));
            }
        };
        rebuild[0].run();

        presentSheet(act, dialog, panel, host.displayMode());
    }

    // =========================================================================
    // 4. Fast Storage & Cache Cleaner (极速存储清理 — keep logins!)
    // =========================================================================

    static void showStorageCleaner(Host host) {
        Activity act = host.activity();
        float d = host.density();
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "极速存储与缓存清理", dialog::dismiss);

        LinearLayout body = vStack(act);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));

        long webCacheBytes = dirSize(new File(act.getCacheDir(), "WebView"));
        long filterBinBytes = fileSize(new File(act.getCacheDir(), "filter-rules-v1.bin"));
        long shelfBytes = dirSize(new File(act.getFilesDir(), "reading_shelf"));

        LinearLayout summary = vStack(act);
        summary.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));
        summary.setBackground(AikoTheme.glowBanner(dp(d, 16)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(d, 10);
        body.addView(summary, slp);

        summary.addView(txt(act, "当前存储占用估算", 12, AikoTheme.ACCENT_CYAN, true));
        TextView details = txt(act,
                "WebView 缓存：" + fmtBytes(webCacheBytes)
                        + "  ·  过滤二进制索引：" + fmtBytes(filterBinBytes)
                        + "  ·  离线稍后读：" + fmtBytes(shelfBytes),
                12, AikoTheme.TEXT, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(d, 4);
        summary.addView(details, dlp);

        addSettingRow(body, act, d, BrowserIcon.Kind.BOLT, AikoTheme.EMERALD,
                "一键清理 HTTP 网页缓存（推荐）", "释放磁盘与内存，完全保留网站登录态与 Cookie",
                () -> {
                    dialog.dismiss();
                    host.clearWebHttpCache(() -> host.toast("已清理网页 HTTP 缓存，登录态已完整保留"));
                });

        addSettingRow(body, act, d, BrowserIcon.Kind.HISTORY, AikoTheme.AMBER,
                "清空浏览历史记录", "仅清空访问历史，不影响书签与已登录网站",
                () -> {
                    dialog.dismiss();
                    host.clearAllHistory();
                });

        addSettingRow(body, act, d, BrowserIcon.Kind.TRASH, AikoTheme.CORAL,
                "清空全部站点 Cookie 与本地存储", "将退出所有已登录网站，请谨慎操作",
                () -> {
                    dialog.dismiss();
                    showConfirmSheet(act, d, host.displayMode(),
                            "清空全部站点登录态？",
                            "此操作会清除所有网站的 Cookie、LocalStorage 与 IndexedDB，并退出全部已登录账号。",
                            "确认清空", true, () -> {
                                try {
                                    CookieManager.getInstance().removeAllCookies(null);
                                    CookieManager.getInstance().flush();
                                    WebStorage.getInstance().deleteAllData();
                                } catch (RuntimeException ignored) { }
                                host.clearWebHttpCache(() -> host.toast("已清空全部站点 Cookie 与存储"));
                            });
                });

        presentSheet(act, dialog, panel, host.displayMode());
    }

    // =========================================================================
    // 5. Reusable Themed Material Confirm & Choice Sheets (Replacing AlertDialog)
    // =========================================================================

    static void showConfirmSheet(Activity act, float d, DisplayModeEngine displayMode,
                                 String title, String message, String confirmLabel,
                                 boolean destructive, Runnable onConfirm) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, title, dialog::dismiss);

        TextView msgTv = txt(act, message, 13, AikoTheme.TEXT_MUTED, false);
        msgTv.setLineSpacing(dp(d, 3), 1f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(d, 4);
        mlp.bottomMargin = dp(d, 16);
        panel.addView(msgTv, mlp);

        LinearLayout btnRow = hRow(act);
        TextView cancel = AikoTheme.badge(act, d, "取消", AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        cancel.setTextSize(13);
        cancel.setTypeface(AikoTheme.MEDIUM);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(d, 44), 1f);
        clp.rightMargin = dp(d, 8);
        btnRow.addView(cancel, clp);

        int bg = destructive ? AikoTheme.CORAL : AikoTheme.ACCENT;
        int fg = AikoTheme.Palette.onAccentInk(bg);
        TextView ok = AikoTheme.badge(act, d, confirmLabel, fg, bg, 0);
        ok.setTextSize(13);
        ok.setTypeface(AikoTheme.BOLD);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0, dp(d, 44), 1.2f));
        panel.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        presentSheet(act, dialog, panel, displayMode);
    }

    static void showTextReportSheet(Activity act, float d, DisplayModeEngine displayMode,
                                    String title, String monospaceReport,
                                    String actionLabel, Runnable onAction) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, title, dialog::dismiss);

        int maxH = Math.min(440, Math.max(200, act.getResources().getConfiguration().screenHeightDp - 200));
        ScrollView scroll = AikoTheme.maxHeightScrollView(act, dp(d, maxH));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(d, 12);
        panel.addView(scroll, slp);

        TextView report = new TextView(act);
        report.setText(monospaceReport);
        report.setTextSize(12);
        report.setTextColor(AikoTheme.TEXT);
        report.setTypeface(AikoTheme.MONO);
        report.setLineSpacing(dp(d, 3), 1f);
        report.setTextIsSelectable(true);
        report.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12));
        report.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 14)));
        scroll.addView(report, new ScrollView.LayoutParams(-1, -2));

        LinearLayout row = hRow(act);
        if (actionLabel != null && onAction != null) {
            TextView actBtn = AikoTheme.badge(act, d, actionLabel, AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
            actBtn.setTextSize(13);
            actBtn.setTypeface(AikoTheme.BOLD);
            actBtn.setOnClickListener(v -> {
                dialog.dismiss();
                onAction.run();
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(d, 42), 1.2f);
            alp.rightMargin = dp(d, 8);
            row.addView(actBtn, alp);
        }
        TextView close = AikoTheme.badge(act, d, "关闭", AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        close.setTextSize(13);
        close.setTypeface(AikoTheme.MEDIUM);
        close.setOnClickListener(v -> dialog.dismiss());
        row.addView(close, new LinearLayout.LayoutParams(0, dp(d, 42), 1f));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));

        presentSheet(act, dialog, panel, displayMode);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void addSettingRow(LinearLayout parent, Activity act, float d,
                                      BrowserIcon.Kind kind, int tint,
                                      String title, String subtitle, Runnable onClick) {
        LinearLayout row = hRow(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 13), dp(d, 11), dp(d, 12), dp(d, 11));
        row.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 15)));
        row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 15)));
        AikoTheme.attachPressFeedback(row, 0.97f);

        LinearLayout iconBox = new LinearLayout(act);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(AikoTheme.rounded((tint & 0x00FFFFFF) | 0x22000000, dp(d, 11)));
        BrowserIcon ic = new BrowserIcon(act, kind);
        ic.tint(tint);
        iconBox.addView(ic, new LinearLayout.LayoutParams(dp(d, 19), dp(d, 19)));
        LinearLayout.LayoutParams iblp = new LinearLayout.LayoutParams(dp(d, 36), dp(d, 36));
        iblp.rightMargin = dp(d, 11);
        row.addView(iconBox, iblp);

        LinearLayout textCol = vStack(act);
        TextView t = txt(act, title, 14, AikoTheme.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = txt(act, subtitle, 11, AikoTheme.TEXT_MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(t);
        textCol.addView(sub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.rightMargin = dp(d, 6);
        row.addView(textCol, tlp);

        BrowserIcon chev = new BrowserIcon(act, BrowserIcon.Kind.FORWARD);
        chev.tint(AikoTheme.TEXT_DIM);
        row.addView(chev, new LinearLayout.LayoutParams(dp(d, 16), dp(d, 16)));

        row.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(d, 7);
        parent.addView(row, rlp);
    }

    private static TextView miniBtn(Activity act, float d, String text, boolean enabled) {
        TextView tv = AikoTheme.badge(act, d, text,
                enabled ? AikoTheme.TEXT : AikoTheme.TEXT_DIM,
                AikoTheme.CARD, AikoTheme.STROKE);
        tv.setAlpha(enabled ? 1f : 0.45f);
        tv.setEnabled(enabled);
        return tv;
    }

    private static void presentSheet(Activity act, Dialog dialog, View panel, DisplayModeEngine displayMode) {
        float d = act.getResources().getDisplayMetrics().density;
        AikoTheme.presentSheet(dialog, panel, displayMode, d);
    }

    private static LinearLayout vStack(Activity act) {
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    private static LinearLayout hRow(Activity act) {
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    private static TextView txt(Activity act, String s, float sp, int color, boolean medium) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(AikoTheme.MEDIUM);
        return tv;
    }

    private static int dp(float d, int v) {
        return AikoTheme.dp(d, v);
    }

    private static long fileSize(File f) {
        return (f != null && f.isFile()) ? f.length() : 0L;
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        for (File c : children) {
            sum += c.isDirectory() ? dirSize(c) : c.length();
        }
        return sum;
    }

    private static String fmtBytes(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
    }
}
