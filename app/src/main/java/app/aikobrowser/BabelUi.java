package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Babel (巴别塔) WebView coordinator & custom-themed Material bottom sheet UI.
 * Uses AikoTheme design tokens exclusively (zero default AlertDialogs).
 */
public final class BabelUi {
    private static final String ASSET_BABEL_JS = "babel/babel.js";
    private static final String WEB_MESSAGE_BRIDGE = "__aikoBabelNative";
    private static final String JS_INTERFACE_BRIDGE = "__aikoBabelJs";
    private static final int MAX_BRIDGE_MESSAGE_CHARS = 256 * 1024;

    public interface HostCallbacks {
        void toast(String msg);
        void speakText(String text, String lang);
        void onStateChanged();
    }

    private static final class TabState {
        final String scopeKey;
        long generation;
        boolean translating;
        boolean bridgeBound;
        boolean notifiedFallback;
        boolean notifiedError;
        String currentUrl = "";

        TabState(String scopeKey) {
            this.scopeKey = scopeKey;
        }
    }

    private final Activity activity;
    private final BabelConfig config;
    private final BabelEngine engine;
    private final HostCallbacks callbacks;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<WebView, TabState> tabs = new IdentityHashMap<>();
    private final AtomicInteger scopeSeq = new AtomicInteger(1);

    private DisplayModeEngine displayMode;
    private String cachedBabelScript;
    private Boolean webMessageSupported;

    public BabelUi(Activity activity, BabelConfig config, BabelEngine engine, HostCallbacks callbacks) {
        this.activity = activity;
        this.config = config;
        this.engine = engine;
        this.callbacks = callbacks;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    public void setDisplayModeEngine(DisplayModeEngine displayMode) {
        this.displayMode = displayMode;
    }

    // =========================================================================
    // Public Contract for MainActivity
    // =========================================================================

    public void attachWebView(WebView view) {
        bindWebView(view);
    }

    public void bindWebView(WebView view) {
        if (view == null) return;
        TabState state = tabs.get(view);
        if (state == null) {
            state = new TabState("tab-" + scopeSeq.getAndIncrement());
            tabs.put(view, state);
        }
        if (state.bridgeBound) return;
        state.bridgeBound = true;

        if (supportsWebMessageListener()) {
            try {
                WebViewCompat.addWebMessageListener(
                        view,
                        WEB_MESSAGE_BRIDGE,
                        Collections.singleton("*"),
                        (source, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            if (source == view && tabs.containsKey(view)
                                    && message.getType() == WebMessageCompat.TYPE_STRING) {
                                handleBridgeMessage(view, message.getData());
                            }
                        }
                );
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // Fall back to JavascriptInterface below
            }
        }
        try {
            view.addJavascriptInterface(new LegacyJsBridge(view), JS_INTERFACE_BRIDGE);
        } catch (RuntimeException ignored) { }
    }

    public void unbindWebView(WebView view) {
        if (view == null) return;
        TabState state = tabs.remove(view);
        if (state != null) {
            engine.cancelScope(state.scopeKey);
        }
    }

    public void onPageFinished(WebView view, String url) {
        if (view == null) return;
        bindWebView(view);
        TabState state = tabs.get(view);
        if (state == null) return;
        state.currentUrl = url == null ? "" : url;
        state.translating = false;
        state.notifiedFallback = false;
        state.notifiedError = false;
        state.generation = engine.nextSessionGeneration(state.scopeKey);
        notifyStateChanged();

        if (!HttpUrlUtil.isHttp(state.currentUrl)) return;
        String host = HttpUrlUtil.extractHost(state.currentUrl);
        boolean autoStart = config.isAutoTranslateHost(host) && !config.isNeverTranslateHost(host);
        boolean needsPassive = config.isSelectionTranslate() || config.isTripleSpaceInputTranslate();
        if (!autoStart && !needsPassive) return;

        injectAndConfigure(view, autoStart);
        if (autoStart) {
            state.translating = true;
            notifyStateChanged();
        }
    }

    public void toggleTranslate(WebView view, String url) {
        togglePageTranslation(view, url);
    }

    public void togglePageTranslation(WebView view, String url) {
        if (view == null) {
            toast("当前没有可翻译的网页");
            return;
        }
        bindWebView(view);
        TabState state = tabs.get(view);
        if (state == null) return;
        String resolvedUrl = (url == null || url.isEmpty()) ? safeUrl(view) : url;
        state.currentUrl = resolvedUrl;

        if (state.translating) {
            state.translating = false;
            state.generation = engine.nextSessionGeneration(state.scopeKey);
            evalJs(view, "window.__aikoBabel && window.__aikoBabel.stop();");
            notifyStateChanged();
            toast("已关闭巴别塔翻译，恢复原文");
        } else {
            if (!HttpUrlUtil.isHttp(resolvedUrl)) {
                toast("当前页面不支持网页翻译");
                return;
            }
            if (config.getEngineKind() == BabelConfig.EngineKind.CUSTOM_AI && config.getActiveProfile() == null) {
                toast("请先配置自定义 AI API");
                showSheet(view, resolvedUrl);
                return;
            }
            state.translating = true;
            state.notifiedFallback = false;
            state.notifiedError = false;
            state.generation = engine.nextSessionGeneration(state.scopeKey);
            injectAndConfigure(view, true);
            notifyStateChanged();
            toast("巴别塔已开启 · " + config.activeEngineDisplayName());
        }
    }

    public boolean isTranslating(WebView view) {
        if (view == null) return false;
        TabState state = tabs.get(view);
        return state != null && state.translating;
    }

    public void showSheet(WebView view, String url) {
        String resolvedUrl = (url == null || url.isEmpty()) ? safeUrl(view) : url;
        String host = HttpUrlUtil.extractHost(resolvedUrl);
        showBabelSheet(view, resolvedUrl, host);
    }

    public void showBabelSheet(WebView currentWebView, String currentUrl, String currentHost) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        String url = (currentUrl == null || currentUrl.isEmpty()) ? safeUrl(currentWebView) : currentUrl;
        String host = (currentHost == null || currentHost.isEmpty()) ? HttpUrlUtil.extractHost(url) : BabelConfig.normalizeHost(currentHost);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(activity, density, "巴别塔 · 沉浸式双语翻译", dialog::dismiss);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(600, Math.max(300, activity.getResources().getConfiguration().screenHeightDp - 120));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = verticalStack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Hero status & page toggle card
        LinearLayout hero = verticalStack();
        hero.setPadding(dp(14), dp(14), dp(14), dp(14));
        hero.setBackground(AikoTheme.glowBanner(dp(18)));
        body.addView(hero, marginBottom(dp(10)));

        LinearLayout heroTop = horizontalRow();
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(heroTop, marginBottom(dp(6)));

        BrowserIcon globe = new BrowserIcon(activity, BrowserIcon.Kind.TRANSLATE);
        globe.tint(AikoTheme.ACCENT);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(22), dp(22));
        glp.rightMargin = dp(8);
        heroTop.addView(globe, glp);

        TextView heroTitle = label("网页沉浸式翻译", 15, AikoTheme.TEXT, true);
        heroTop.addView(heroTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean activeNow = isTranslating(currentWebView);
        TextView stateBadge = AikoTheme.badge(
                activity, density,
                activeNow ? "翻译中" : "未开启",
                activeNow ? AikoTheme.onAccentColor() : AikoTheme.TEXT_MUTED,
                activeNow ? AikoTheme.EMERALD : AikoTheme.PILL,
                activeNow ? 0 : AikoTheme.STROKE);
        heroTop.addView(stateBadge, new LinearLayout.LayoutParams(-2, -2));

        TextView statsLine = label(formatStatsLine(), 11, AikoTheme.ACCENT_CYAN, false);
        statsLine.setSingleLine(true);
        statsLine.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(statsLine, marginBottom(dp(12)));

        TextView toggleBtn = primaryButton(activeNow ? "关闭当前页翻译（恢复原文）" : "开启当前页巴别塔翻译");
        toggleBtn.setOnClickListener(v -> {
            togglePageTranslation(currentWebView, url);
            dialog.dismiss();
        });
        hero.addView(toggleBtn, new LinearLayout.LayoutParams(-1, dp(44)));

        if (!host.isEmpty()) {
            LinearLayout hostRow = horizontalRow();
            hostRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = dp(10);
            hero.addView(hostRow, hlp);

            TextView autoChip = selectablePill("此站自动翻译 · " + truncateMiddle(host, 20), config.isAutoTranslateHost(host));
            TextView neverChip = selectablePill("永不翻译此站", config.isNeverTranslateHost(host));

            autoChip.setOnClickListener(v -> {
                boolean next = !config.isAutoTranslateHost(host);
                config.setAutoTranslateHost(host, next);
                stylePill(autoChip, config.isAutoTranslateHost(host));
                stylePill(neverChip, config.isNeverTranslateHost(host));
                toast(next ? ("已开启 " + host + " 自动翻译") : ("已取消 " + host + " 自动翻译"));
                if (next && !isTranslating(currentWebView) && currentWebView != null) {
                    togglePageTranslation(currentWebView, url);
                }
            });
            neverChip.setOnClickListener(v -> {
                boolean next = !config.isNeverTranslateHost(host);
                config.setNeverTranslateHost(host, next);
                stylePill(autoChip, config.isAutoTranslateHost(host));
                stylePill(neverChip, config.isNeverTranslateHost(host));
                toast(next ? ("已将 " + host + " 加入永不翻译") : "已移出永不翻译名单");
                if (next && isTranslating(currentWebView) && currentWebView != null) {
                    togglePageTranslation(currentWebView, url);
                }
            });

            LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(0, dp(36), 1.4f);
            acLp.rightMargin = dp(8);
            hostRow.addView(autoChip, acLp);
            hostRow.addView(neverChip, new LinearLayout.LayoutParams(0, dp(36), 1f));
        }

        // 2. Engine Selector (2x2 grid)
        body.addView(AikoTheme.sectionHeader(activity, density, "翻译引擎"));
        final BabelConfig.EngineKind[] engines = BabelConfig.EngineKind.values();
        final TextView[] engineCards = new TextView[engines.length];
        LinearLayout aiSectionHolder = verticalStack();

        Runnable syncEngineUi = () -> {
            BabelConfig.EngineKind sel = config.getEngineKind();
            for (int i = 0; i < engines.length; i++) {
                boolean active = engines[i] == sel;
                engineCards[i].setTextColor(active ? AikoTheme.ACCENT : AikoTheme.TEXT);
                engineCards[i].setBackground(AikoTheme.card(
                        active ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        active ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(14)));
            }
            statsLine.setText(formatStatsLine());
            aiSectionHolder.setAlpha(sel == BabelConfig.EngineKind.CUSTOM_AI ? 1f : 0.82f);
        };

        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout row = horizontalRow();
            body.addView(row, marginBottom(dp(8)));
            for (int col = 0; col < 2; col++) {
                int idx = rowIdx * 2 + col;
                BabelConfig.EngineKind kind = engines[idx];
                TextView card = new TextView(activity);
                card.setText(kind.label + "\n" + kind.subtitle);
                card.setTextSize(12);
                card.setTypeface(AikoTheme.MEDIUM);
                card.setLineSpacing(dp(2), 1f);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
                AikoTheme.attachPressFeedback(card, 0.96f);
                card.setOnClickListener(v -> {
                    config.setEngineKind(kind);
                    syncEngineUi.run();
                    if (kind == BabelConfig.EngineKind.CUSTOM_AI && config.getActiveProfile() == null) {
                        toast("请点击下方「+ 新增配置」添加您的 AI API");
                    } else {
                        toast("已切换引擎：" + kind.label);
                    }
                    syncActiveWebViewConfig(currentWebView);
                });
                engineCards[idx] = card;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (col == 0) clp.rightMargin = dp(8);
                row.addView(card, clp);
            }
        }

        // Smart CN Auto-Fallback switch
        body.addView(switchCard(
                "Google 不通自动切国内（微软必应）",
                "无 VPN 环境下 Google 超时或网络失败时，无缝回退至国内直连必应引擎",
                config.isAutoFallbackToCn(),
                next -> {
                    config.setAutoFallbackToCn(next);
                    toast(next ? "已开启国内智能容灾切换" : "已关闭自动切国内");
                }
        ), marginBottom(dp(6)));

        // 3. Bilingual Display Styles (Horizontal chips)
        body.addView(AikoTheme.sectionHeader(activity, density, "双语展示样式"));
        HorizontalScrollView styleScroll = new HorizontalScrollView(activity);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styleRow = horizontalRow();
        styleScroll.addView(styleRow, new HorizontalScrollView.LayoutParams(-2, -2));
        body.addView(styleScroll, marginBottom(dp(8)));

        BabelConfig.DisplayStyle[] styles = BabelConfig.DisplayStyle.values();
        TextView[] stylePills = new TextView[styles.length];
        for (int i = 0; i < styles.length; i++) {
            BabelConfig.DisplayStyle ds = styles[i];
            TextView pill = selectablePill(ds.label, config.getDisplayStyle() == ds);
            pill.setOnClickListener(v -> {
                config.setDisplayStyle(ds);
                for (int j = 0; j < styles.length; j++) {
                    stylePills[j].setSelected(styles[j] == ds);
                    stylePill(stylePills[j], styles[j] == ds);
                }
                if (currentWebView != null) {
                    evalJs(currentWebView, "window.__aikoBabel && window.__aikoBabel.setStyle(" + JSONObject.quote(ds.key) + ");");
                }
                toast("展示样式：" + ds.label);
            });
            stylePills[i] = pill;
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) plp.leftMargin = dp(8);
            styleRow.addView(pill, plp);
        }

        // 4. Target Language & Input Back-Translation Target
        body.addView(AikoTheme.sectionHeader(activity, density, "翻译目标语言"));
        LinearLayout langRow = horizontalRow();
        body.addView(langRow, marginBottom(dp(8)));

        TextView pageLangBtn = valueCard("网页译文语言", BabelConfig.langLabel(config.getTargetLang()));
        pageLangBtn.setOnClickListener(v -> showLanguagePickerSheet("选择网页译文目标语言", config.getTargetLang(), code -> {
            config.setTargetLang(code);
            pageLangBtn.setText("网页译文语言\n" + BabelConfig.langLabel(code));
            syncActiveWebViewConfig(currentWebView);
            toast("目标语言：" + BabelConfig.langLabel(code));
        }));

        TextView inputLangBtn = valueCard("三连空格反译目标", BabelConfig.langLabel(config.getInputBackTargetLang()));
        inputLangBtn.setOnClickListener(v -> showLanguagePickerSheet("选择输入框三连空格目标语言", config.getInputBackTargetLang(), code -> {
            config.setInputBackTargetLang(code);
            inputLangBtn.setText("三连空格反译目标\n" + BabelConfig.langLabel(code));
            syncActiveWebViewConfig(currentWebView);
            toast("输入框反译目标：" + BabelConfig.langLabel(code));
        }));

        LinearLayout.LayoutParams llp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        llp1.rightMargin = dp(8);
        langRow.addView(pageLangBtn, llp1);
        langRow.addView(inputLangBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        // 5. Custom AI API Manager & Parameter Panel
        body.addView(AikoTheme.sectionHeader(activity, density, "自定义 AI API 配置与调参"));
        body.addView(aiSectionHolder, marginBottom(dp(8)));
        rebuildAiManagerCard(aiSectionHolder, currentWebView, dialog, statsLine);

        // 6. Quick Toggles & Cache Maintenance
        body.addView(AikoTheme.sectionHeader(activity, density, "快捷能力与缓存"));
        body.addView(switchCard(
                "划词即译",
                "选中网页文本后在旁显示「译」浮标，点按弹出译文卡片与朗读",
                config.isSelectionTranslate(),
                next -> {
                    config.setSelectionTranslate(next);
                    syncActiveWebViewConfig(currentWebView);
                }
        ), marginBottom(dp(6)));

        body.addView(switchCard(
                "输入框三连空格反译",
                "在搜索框/评论框/富文本输入内容后快速按 3 次空格自动翻译替换",
                config.isTripleSpaceInputTranslate(),
                next -> {
                    config.setTripleSpaceInputTranslate(next);
                    syncActiveWebViewConfig(currentWebView);
                }
        ), marginBottom(dp(6)));

        body.addView(switchCard(
                "智能跳过代码与导航栏",
                "严格跳过 code / pre / kbd / svg / math / 表单与导航菜单",
                config.isSkipCodeAndNav(),
                next -> {
                    config.setSkipCodeAndNav(next);
                    syncActiveWebViewConfig(currentWebView);
                }
        ), marginBottom(dp(6)));

        body.addView(switchCard(
                "翻译时显示右下角轻浮球",
                "页面开启翻译后显示「译 · 双语」快速入口",
                config.isShowFloatingFab(),
                next -> {
                    config.setShowFloatingFab(next);
                    syncActiveWebViewConfig(currentWebView);
                }
        ), marginBottom(dp(10)));

        BabelEngine.Stats st = engine.getStats();
        TextView clearCacheBtn = secondaryButton("清空翻译缓存（当前 " + st.cacheSize + " 条 · 命中 " + st.cacheHits + " 次）");
        clearCacheBtn.setOnClickListener(v -> {
            engine.clearCache();
            statsLine.setText(formatStatsLine());
            clearCacheBtn.setText("清空翻译缓存（当前 0 条）");
            toast("已清空巴别塔会话缓存");
        });
        body.addView(clearCacheBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        syncEngineUi.run();
        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    public void showQuickSelectionResult(WebView view, String source, String translated) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(activity, density, "划词翻译结果", dialog::dismiss);

        LinearLayout box = verticalStack();
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
        panel.addView(box, marginBottom(dp(12)));

        TextView srcLabel = label("原文", 11, AikoTheme.TEXT_DIM, true);
        box.addView(srcLabel, marginBottom(dp(4)));
        TextView srcView = label(source == null ? "" : source, 13, AikoTheme.TEXT_MUTED, false);
        srcView.setMaxLines(4);
        srcView.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(srcView, marginBottom(dp(10)));

        View div = new View(activity);
        div.setBackgroundColor(AikoTheme.DIVIDER);
        box.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));

        TextView trLabel = label("译文 · " + BabelConfig.langLabel(config.getTargetLang()), 11, AikoTheme.ACCENT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        tlp.bottomMargin = dp(4);
        box.addView(trLabel, tlp);

        TextView trView = label(translated == null ? "" : translated, 15, AikoTheme.TEXT, false);
        trView.setTextIsSelectable(true);
        box.addView(trView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = horizontalRow();
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView copyBtn = secondaryButton("复制译文");
        copyBtn.setOnClickListener(v -> {
            copyToClipboard(translated == null ? "" : translated);
            toast("已复制译文");
        });
        TextView speakBtn = primaryButton("朗读译文");
        speakBtn.setOnClickListener(v -> {
            if (callbacks != null && translated != null && !translated.isEmpty()) {
                callbacks.speakText(translated, config.getTargetLang());
            }
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp1.rightMargin = dp(8);
        actions.addView(copyBtn, lp1);
        actions.addView(speakBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    // =========================================================================
    // Custom AI Profile Manager, Editor & Searchable Model Picker Sheets
    // =========================================================================

    private void rebuildAiManagerCard(
            LinearLayout holder,
            WebView currentWebView,
            Dialog parentSheet,
            TextView statsLine
    ) {
        holder.removeAllViews();

        LinearLayout card = verticalStack();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(16)));
        holder.addView(card, new LinearLayout.LayoutParams(-1, -2));

        List<BabelConfig.AiProfile> profiles = config.getAiProfiles();
        BabelConfig.AiProfile active = config.getActiveProfile();

        if (profiles.isEmpty()) {
            TextView empty = label(
                    "尚未添加自定义 AI API 配置。支持 OpenAI 兼容中转（DeepSeek / OpenRouter / New API / OneAPI）及 Google Gemini，支持一键拉取模型与全参数调优。",
                    12, AikoTheme.TEXT_MUTED, false);
            card.addView(empty, marginBottom(dp(10)));

            TextView addBtn = primaryButton("+ 新增 AI API 配置");
            addBtn.setOnClickListener(v -> showAiProfileEditorSheet(null, saved -> {
                config.setEngineKind(BabelConfig.EngineKind.CUSTOM_AI);
                rebuildAiManagerCard(holder, currentWebView, parentSheet, statsLine);
                statsLine.setText(formatStatsLine());
                syncActiveWebViewConfig(currentWebView);
            }));
            card.addView(addBtn, new LinearLayout.LayoutParams(-1, dp(42)));
            return;
        }

        // Profile selector strip
        HorizontalScrollView profScroll = new HorizontalScrollView(activity);
        profScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout profRow = horizontalRow();
        profScroll.addView(profRow, new HorizontalScrollView.LayoutParams(-2, -2));
        card.addView(profScroll, marginBottom(dp(10)));

        for (int i = 0; i < profiles.size(); i++) {
            BabelConfig.AiProfile p = profiles.get(i);
            boolean isActive = active != null && p.id.equals(active.id);
            TextView chip = selectablePill(p.name, isActive);
            chip.setOnClickListener(v -> {
                config.setActiveProfileId(p.id);
                config.setEngineKind(BabelConfig.EngineKind.CUSTOM_AI);
                rebuildAiManagerCard(holder, currentWebView, parentSheet, statsLine);
                statsLine.setText(formatStatsLine());
                syncActiveWebViewConfig(currentWebView);
                toast("已启用配置：" + p.name);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) lp.leftMargin = dp(8);
            profRow.addView(chip, lp);
        }

        if (active != null) {
            TextView summary = label(active.summaryLine(), 11, AikoTheme.ACCENT_CYAN, false);
            summary.setPadding(dp(10), dp(7), dp(10), dp(7));
            summary.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(10)));
            card.addView(summary, marginBottom(dp(10)));
        }

        LinearLayout btnRow = horizontalRow();
        card.addView(btnRow, new LinearLayout.LayoutParams(-1, dp(38)));

        TextView addBtn = compactActionBtn("+ 新增", true);
        addBtn.setOnClickListener(v -> showAiProfileEditorSheet(null, saved -> {
            config.setEngineKind(BabelConfig.EngineKind.CUSTOM_AI);
            rebuildAiManagerCard(holder, currentWebView, parentSheet, statsLine);
            statsLine.setText(formatStatsLine());
            syncActiveWebViewConfig(currentWebView);
        }));

        TextView editBtn = compactActionBtn("编辑/调参", false);
        editBtn.setOnClickListener(v -> {
            BabelConfig.AiProfile cur = config.getActiveProfile();
            if (cur == null) return;
            showAiProfileEditorSheet(cur, saved -> {
                rebuildAiManagerCard(holder, currentWebView, parentSheet, statsLine);
                statsLine.setText(formatStatsLine());
                syncActiveWebViewConfig(currentWebView);
            });
        });

        TextView testBtn = compactActionBtn("测试连通", false);
        testBtn.setOnClickListener(v -> {
            BabelConfig.AiProfile cur = config.getActiveProfile();
            if (cur == null) return;
            testBtn.setEnabled(false);
            testBtn.setText("测试中…");
            engine.testProfileAsync(cur, config.getTargetLang(), res -> main.post(() -> {
                if (isUiGone()) return;
                testBtn.setEnabled(true);
                testBtn.setText("测试连通");
                if (res.ok) {
                    toast("连通成功 (" + res.latencyMs + "ms)：" + truncateMiddle(res.sampleOutput, 42));
                } else {
                    toast("连通失败：" + res.error);
                }
            }));
        });

        TextView delBtn = compactActionBtn("删除", false);
        delBtn.setTextColor(AikoTheme.CORAL);
        delBtn.setOnClickListener(v -> {
            BabelConfig.AiProfile cur = config.getActiveProfile();
            if (cur == null) return;
            showConfirmSheet("删除 AI 配置？", "确认删除「" + cur.name + "」？此操作不可撤销。", "确认删除", true, () -> {
                config.deleteProfile(cur.id);
                rebuildAiManagerCard(holder, currentWebView, parentSheet, statsLine);
                statsLine.setText(formatStatsLine());
                toast("已删除配置：" + cur.name);
            });
        });

        addWeighted(btnRow, addBtn, 1f, dp(6));
        addWeighted(btnRow, editBtn, 1.15f, dp(6));
        addWeighted(btnRow, testBtn, 1.1f, dp(6));
        addWeighted(btnRow, delBtn, 0.85f, 0);
    }

    private interface ProfileSavedCallback {
        void onSaved(BabelConfig.AiProfile profile);
    }

    private void showAiProfileEditorSheet(BabelConfig.AiProfile editing, ProfileSavedCallback onSaved) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        BabelConfig.AiProfile base = editing != null ? editing : BabelConfig.AiProfile.defaultOpenAi("自定义 AI");
        final BabelConfig.ApiFormat[] formatHolder = {base.apiFormat};
        final List<String> cachedModels = new ArrayList<>(base.cachedModels);
        final int[] concurrency = {base.maxConcurrency};
        final float[] temperature = {base.temperature};
        final int[] batchChars = {base.batchMaxChars};
        final int[] batchItems = {base.batchMaxItems};
        final int[] timeoutSec = {base.timeoutSec};

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(
                activity, density,
                editing == null ? "新增 AI API 配置" : "编辑 AI 配置与参数",
                dialog::dismiss);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(560, Math.max(280, activity.getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout body = verticalStack();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Name
        body.addView(AikoTheme.sectionHeader(activity, density, "配置名称"));
        EditText nameInput = styledInput("例如：DeepSeek / OpenRouter / Gemini 中转", base.name, false);
        body.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(44)));

        // API Format pills
        body.addView(AikoTheme.sectionHeader(activity, density, "接口格式"));
        LinearLayout fmtRow = horizontalRow();
        body.addView(fmtRow, marginBottom(dp(6)));

        EditText baseUrlInput = styledInput("https://api.openai.com/v1", base.baseUrl, false);
        baseUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        EditText modelInput = styledInput("输入或拉取模型 ID，如 gpt-4o-mini", base.model, false);

        TextView openAiPill = selectablePill("OpenAI 兼容", formatHolder[0] == BabelConfig.ApiFormat.OPENAI_COMPAT);
        TextView geminiPill = selectablePill("Google Gemini", formatHolder[0] == BabelConfig.ApiFormat.GEMINI);
        Runnable syncFormat = () -> {
            stylePill(openAiPill, formatHolder[0] == BabelConfig.ApiFormat.OPENAI_COMPAT);
            stylePill(geminiPill, formatHolder[0] == BabelConfig.ApiFormat.GEMINI);
        };
        openAiPill.setOnClickListener(v -> {
            formatHolder[0] = BabelConfig.ApiFormat.OPENAI_COMPAT;
            if (BabelConfig.DEFAULT_GEMINI_BASE_URL.equals(baseUrlInput.getText().toString().trim())) {
                baseUrlInput.setText(BabelConfig.DEFAULT_OPENAI_BASE_URL);
            }
            if (BabelConfig.DEFAULT_GEMINI_MODEL.equals(modelInput.getText().toString().trim())) {
                modelInput.setText(BabelConfig.DEFAULT_OPENAI_MODEL);
            }
            syncFormat.run();
        });
        geminiPill.setOnClickListener(v -> {
            formatHolder[0] = BabelConfig.ApiFormat.GEMINI;
            if (BabelConfig.DEFAULT_OPENAI_BASE_URL.equals(baseUrlInput.getText().toString().trim())) {
                baseUrlInput.setText(BabelConfig.DEFAULT_GEMINI_BASE_URL);
            }
            if (BabelConfig.DEFAULT_OPENAI_MODEL.equals(modelInput.getText().toString().trim())) {
                modelInput.setText(BabelConfig.DEFAULT_GEMINI_MODEL);
            }
            syncFormat.run();
        });
        addWeighted(fmtRow, openAiPill, 1f, dp(8));
        addWeighted(fmtRow, geminiPill, 1f, 0);

        // Base URL
        body.addView(AikoTheme.sectionHeader(activity, density, "API Base URL（支持任意中转 / New API / OneAPI）"));
        body.addView(baseUrlInput, new LinearLayout.LayoutParams(-1, dp(44)));

        // API Key + mask toggle
        body.addView(AikoTheme.sectionHeader(activity, density, "API Key"));
        LinearLayout keyRow = horizontalRow();
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(keyRow, marginBottom(dp(4)));

        EditText keyInput = styledInput("sk-... 或 AIza...", base.apiKey, false);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean[] keyVisible = {false};
        TextView maskBtn = compactActionBtn("显示", false);
        maskBtn.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            int selPos = keyInput.getSelectionEnd();
            keyInput.setInputType(keyVisible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            keyInput.setTypeface(AikoTheme.MONO);
            if (selPos >= 0) keyInput.setSelection(Math.min(selPos, keyInput.getText().length()));
            maskBtn.setText(keyVisible[0] ? "隐藏" : "显示");
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        klp.rightMargin = dp(8);
        keyRow.addView(keyInput, klp);
        keyRow.addView(maskBtn, new LinearLayout.LayoutParams(dp(64), dp(44)));

        // Model free-text + Fetch Models button
        body.addView(AikoTheme.sectionHeader(activity, density, "模型 ID（可自由填写，或一键从服务端拉取）"));
        LinearLayout modelRow = horizontalRow();
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(modelRow, marginBottom(dp(4)));

        TextView fetchBtn = compactActionBtn("获取模型", true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        mlp.rightMargin = dp(8);
        modelRow.addView(modelInput, mlp);
        modelRow.addView(fetchBtn, new LinearLayout.LayoutParams(dp(86), dp(44)));

        if (!cachedModels.isEmpty()) {
            TextView pickCachedBtn = label("已缓存 " + cachedModels.size() + " 个模型 · 点此筛选选择 ▾", 12, AikoTheme.ACCENT_CYAN, true);
            pickCachedBtn.setPadding(dp(4), dp(4), dp(4), dp(6));
            pickCachedBtn.setOnClickListener(v -> showModelPickerSheet(cachedModels, modelInput.getText().toString().trim(), picked -> {
                modelInput.setText(picked);
            }));
            body.addView(pickCachedBtn);
        }

        // Max Tokens (quick chips + numeric EditText)
        body.addView(AikoTheme.sectionHeader(activity, density, "单次最大 Token（128–32768）"));
        EditText maxTokensInput = styledInput("2048", Integer.toString(base.maxTokens), false);
        maxTokensInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(maxTokensInput, marginBottom(dp(6)));

        HorizontalScrollView tokScroll = new HorizontalScrollView(activity);
        tokScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tokRow = horizontalRow();
        tokScroll.addView(tokRow, new HorizontalScrollView.LayoutParams(-2, -2));
        int[] tokPresets = {512, 1024, 2048, 4096, 8192, 16384};
        for (int i = 0; i < tokPresets.length; i++) {
            int preset = tokPresets[i];
            TextView chip = selectablePill(Integer.toString(preset), base.maxTokens == preset);
            chip.setOnClickListener(v -> maxTokensInput.setText(Integer.toString(preset)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            if (i > 0) lp.leftMargin = dp(6);
            tokRow.addView(chip, lp);
        }
        body.addView(tokScroll, marginBottom(dp(4)));

        // Concurrency slider (1..16)
        TextView concLabel = AikoTheme.sectionHeader(activity, density, "最大并发请求数：" + concurrency[0] + " 路");
        body.addView(concLabel);
        SeekBar concSeek = themedSeekBar(BabelConfig.MAX_CONCURRENCY - BabelConfig.MIN_CONCURRENCY,
                concurrency[0] - BabelConfig.MIN_CONCURRENCY, p -> {
                    concurrency[0] = BabelConfig.MIN_CONCURRENCY + p;
                    concLabel.setText("最大并发请求数：" + concurrency[0] + " 路");
                });
        body.addView(concSeek, new LinearLayout.LayoutParams(-1, -2));

        // Temperature slider (0.0..1.5, step 0.1)
        TextView tempLabel = AikoTheme.sectionHeader(activity, density,
                String.format(Locale.ROOT, "采样温度 Temperature：%.1f", temperature[0]));
        body.addView(tempLabel);
        SeekBar tempSeek = themedSeekBar(15, Math.round(temperature[0] * 10f), p -> {
            temperature[0] = p / 10f;
            tempLabel.setText(String.format(Locale.ROOT, "采样温度 Temperature：%.1f", temperature[0]));
        });
        body.addView(tempSeek, new LinearLayout.LayoutParams(-1, -2));

        // Batch Max Chars slider (400..12000, step 200)
        TextView charsLabel = AikoTheme.sectionHeader(activity, density, "单批最大字符数：" + batchChars[0] + " 字");
        body.addView(charsLabel);
        SeekBar charsSeek = themedSeekBar((BabelConfig.MAX_BATCH_CHARS - BabelConfig.MIN_BATCH_CHARS) / 200,
                (batchChars[0] - BabelConfig.MIN_BATCH_CHARS) / 200, p -> {
                    batchChars[0] = BabelConfig.MIN_BATCH_CHARS + p * 200;
                    charsLabel.setText("单批最大字符数：" + batchChars[0] + " 字");
                });
        body.addView(charsSeek, new LinearLayout.LayoutParams(-1, -2));

        // Batch Max Items slider (1..30)
        TextView itemsLabel = AikoTheme.sectionHeader(activity, density, "单批最大段落条数：" + batchItems[0] + " 条");
        body.addView(itemsLabel);
        SeekBar itemsSeek = themedSeekBar(BabelConfig.MAX_BATCH_ITEMS - BabelConfig.MIN_BATCH_ITEMS,
                batchItems[0] - BabelConfig.MIN_BATCH_ITEMS, p -> {
                    batchItems[0] = BabelConfig.MIN_BATCH_ITEMS + p;
                    itemsLabel.setText("单批最大段落条数：" + batchItems[0] + " 条");
                });
        body.addView(itemsSeek, new LinearLayout.LayoutParams(-1, -2));

        // Timeout slider (5..120, step 5)
        TextView timeoutLabel = AikoTheme.sectionHeader(activity, density, "请求超时秒数：" + timeoutSec[0] + " 秒");
        body.addView(timeoutLabel);
        SeekBar timeoutSeek = themedSeekBar((BabelConfig.MAX_TIMEOUT_SEC - BabelConfig.MIN_TIMEOUT_SEC) / 5,
                (timeoutSec[0] - BabelConfig.MIN_TIMEOUT_SEC) / 5, p -> {
                    timeoutSec[0] = BabelConfig.MIN_TIMEOUT_SEC + p * 5;
                    timeoutLabel.setText("请求超时秒数：" + timeoutSec[0] + " 秒");
                });
        body.addView(timeoutSeek, new LinearLayout.LayoutParams(-1, -2));

        // Prompt preset templates + custom prompt editor
        body.addView(AikoTheme.sectionHeader(activity, density, "系统提示词模板 (System Prompt)"));
        HorizontalScrollView tplScroll = new HorizontalScrollView(activity);
        tplScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tplRow = horizontalRow();
        tplScroll.addView(tplRow, new HorizontalScrollView.LayoutParams(-2, -2));
        body.addView(tplScroll, marginBottom(dp(8)));

        EditText promptInput = styledInput("自定义翻译系统提示词…", base.systemPrompt, true);
        for (int i = 0; i < BabelConfig.PROMPT_TEMPLATES.size(); i++) {
            BabelConfig.PromptTemplate tpl = BabelConfig.PROMPT_TEMPLATES.get(i);
            TextView chip = selectablePill(tpl.name, tpl.prompt.equals(base.systemPrompt));
            chip.setOnClickListener(v -> {
                promptInput.setText(tpl.prompt);
                toast("已应用模板：" + tpl.name);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) lp.leftMargin = dp(6);
            tplRow.addView(chip, lp);
        }
        body.addView(promptInput, new LinearLayout.LayoutParams(-1, dp(96)));

        // Optional Extra Headers JSON
        body.addView(AikoTheme.sectionHeader(activity, density, "自定义请求头 JSON（可选）"));
        EditText headersInput = styledInput("{\"HTTP-Referer\":\"https://aiko.app\"}", base.extraHeadersJson, false);
        headersInput.setTypeface(AikoTheme.MONO);
        body.addView(headersInput, new LinearLayout.LayoutParams(-1, dp(44)));

        // Helper to assemble draft profile
        final java.util.function.Supplier<BabelConfig.AiProfile> buildDraft = () -> {
            int parsedTok = BabelConfig.DEFAULT_MAX_TOKENS;
            try {
                parsedTok = Integer.parseInt(maxTokensInput.getText().toString().trim());
            } catch (NumberFormatException ignored) { }
            return new BabelConfig.AiProfile(
                    editing != null ? editing.id : null,
                    nameInput.getText().toString(),
                    formatHolder[0],
                    baseUrlInput.getText().toString(),
                    keyInput.getText().toString(),
                    modelInput.getText().toString(),
                    cachedModels,
                    concurrency[0],
                    parsedTok,
                    temperature[0],
                    batchChars[0],
                    batchItems[0],
                    timeoutSec[0],
                    promptInput.getText().toString(),
                    headersInput.getText().toString()
            );
        };

        // Wire Fetch Models button
        fetchBtn.setOnClickListener(v -> {
            BabelConfig.AiProfile draft = buildDraft.get();
            fetchBtn.setEnabled(false);
            fetchBtn.setText("拉取中…");
            engine.fetchModelsAsync(draft, res -> main.post(() -> {
                if (isUiGone()) return;
                fetchBtn.setEnabled(true);
                fetchBtn.setText("获取模型");
                if (res.isSuccess() && !res.models.isEmpty()) {
                    cachedModels.clear();
                    cachedModels.addAll(res.models);
                    if (editing != null) {
                        config.updateCachedModels(editing.id, res.models);
                    }
                    toast("已获取 " + res.models.size() + " 个模型 (" + res.latencyMs + "ms)");
                    showModelPickerSheet(res.models, modelInput.getText().toString().trim(), picked -> {
                        modelInput.setText(picked);
                    });
                } else {
                    toast(res.error == null ? "获取模型失败" : res.error);
                }
            }));
        });

        // Bottom bar: Test + Save
        LinearLayout footer = horizontalRow();
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(46));
        flp.topMargin = dp(10);
        panel.addView(footer, flp);

        TextView testBtn = secondaryButton("测试连通");
        testBtn.setOnClickListener(v -> {
            BabelConfig.AiProfile draft = buildDraft.get();
            testBtn.setEnabled(false);
            testBtn.setText("测试中…");
            engine.testProfileAsync(draft, config.getTargetLang(), res -> main.post(() -> {
                if (isUiGone()) return;
                testBtn.setEnabled(true);
                testBtn.setText("测试连通");
                if (res.ok) {
                    toast("测试成功 (" + res.latencyMs + "ms)：" + truncateMiddle(res.sampleOutput, 40));
                } else {
                    toast("测试失败：" + res.error);
                }
            }));
        });

        TextView saveBtn = primaryButton("保存并启用");
        saveBtn.setOnClickListener(v -> {
            String rawHeaders = headersInput.getText().toString().trim();
            if (!rawHeaders.isEmpty()) {
                try {
                    new JSONObject(rawHeaders);
                } catch (JSONException badJson) {
                    toast("自定义请求头不是合法 JSON 对象");
                    return;
                }
            }
            BabelConfig.AiProfile saved = config.upsertProfile(buildDraft.get());
            config.setActiveProfileId(saved.id);
            dialog.dismiss();
            if (onSaved != null) onSaved.onSaved(saved);
            toast("已保存配置：" + saved.name);
        });

        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        tLp.rightMargin = dp(8);
        footer.addView(testBtn, tLp);
        footer.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(46), 1.45f));

        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    private interface StringSelectedCallback {
        void onSelected(String value);
    }

    private void showModelPickerSheet(List<String> allModels, String currentModel, StringSelectedCallback onPicked) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(
                activity, density,
                "选择模型（共 " + allModels.size() + " 个）",
                dialog::dismiss);

        EditText searchInput = styledInput("搜索模型名或输入任意自定义 ID…", "", false);
        panel.addView(searchInput, marginBottom(dp(8)));

        TextView useCustomBtn = secondaryButton("使用输入内容作为自定义模型 ID");
        useCustomBtn.setVisibility(View.GONE);
        useCustomBtn.setOnClickListener(v -> {
            String typed = searchInput.getText().toString().trim();
            if (!typed.isEmpty()) {
                dialog.dismiss();
                onPicked.onSelected(typed);
            }
        });
        panel.addView(useCustomBtn, marginBottom(dp(8)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(380, Math.max(200, activity.getResources().getConfiguration().screenHeightDp - 240));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(maxH)));

        LinearLayout list = verticalStack();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        Runnable render = () -> {
            String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            list.removeAllViews();
            boolean exactMatch = false;
            int shown = 0;
            for (String m : allModels) {
                if (m.equalsIgnoreCase(query)) exactMatch = true;
                if (!query.isEmpty() && !m.toLowerCase(Locale.ROOT).contains(query)) continue;
                boolean selected = m.equals(currentModel);
                TextView item = new TextView(activity);
                item.setText(m);
                item.setTextSize(13);
                item.setTypeface(AikoTheme.MONO);
                item.setTextColor(selected ? AikoTheme.ACCENT : AikoTheme.TEXT);
                item.setPadding(dp(12), dp(11), dp(12), dp(11));
                item.setBackground(AikoTheme.card(
                        selected ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                        selected ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                        density, dp(12)));
                item.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
                AikoTheme.attachPressFeedback(item, 0.97f);
                item.setOnClickListener(v -> {
                    dialog.dismiss();
                    onPicked.onSelected(m);
                });
                list.addView(item, marginBottom(dp(6)));
                shown++;
                if (shown >= 120) break;
            }
            if (shown == 0) {
                TextView empty = label("未匹配到服务端模型，可直接点上方按钮使用自定义模型 ID", 12, AikoTheme.TEXT_MUTED, false);
                empty.setPadding(dp(6), dp(12), dp(6), dp(12));
                list.addView(empty);
            }
            useCustomBtn.setVisibility(!query.isEmpty() && !exactMatch ? View.VISIBLE : View.GONE);
            if (!query.isEmpty() && !exactMatch) {
                useCustomBtn.setText("使用自定义模型：「" + searchInput.getText().toString().trim() + "」");
            }
        };

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render.run(); }
        });

        render.run();
        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    private void showLanguagePickerSheet(String title, String currentCode, StringSelectedCallback onPicked) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(activity, density, title, dialog::dismiss);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(340)));

        LinearLayout list = verticalStack();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        for (BabelConfig.LangOption opt : BabelConfig.LANGUAGES) {
            boolean selected = opt.code.equalsIgnoreCase(currentCode);
            LinearLayout row = horizontalRow();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(AikoTheme.card(
                    selected ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    selected ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                    density, dp(14)));
            row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            AikoTheme.attachPressFeedback(row, 0.97f);

            TextView titleTxt = label(opt.label + "  (" + opt.code + ")", 14,
                    selected ? AikoTheme.ACCENT : AikoTheme.TEXT, selected);
            row.addView(titleTxt, new LinearLayout.LayoutParams(0, -2, 1f));

            if (selected) {
                BrowserIcon check = new BrowserIcon(activity, BrowserIcon.Kind.CHECK);
                check.tint(AikoTheme.ACCENT);
                row.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPicked.onSelected(opt.code);
            });
            list.addView(row, marginBottom(dp(6)));
        }

        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    private void showConfirmSheet(String title, String message, String confirmLabel, boolean danger, Runnable onConfirm) {
        if (isUiGone()) return;
        AikoTheme.applyTheme(activity);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(activity, density, title, dialog::dismiss);

        TextView msg = label(message, 13, AikoTheme.TEXT_MUTED, false);
        msg.setPadding(dp(4), dp(4), dp(4), dp(16));
        panel.addView(msg);

        LinearLayout actions = horizontalRow();
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView cancel = secondaryButton("取消");
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView ok = AikoTheme.badge(
                activity, density, confirmLabel,
                danger ? 0xFF0B0D12 : AikoTheme.onAccentColor(),
                danger ? AikoTheme.CORAL : AikoTheme.ACCENT, 0);
        ok.setTextSize(14);
        ok.setTypeface(AikoTheme.BOLD);
        ok.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(ok, 0.96f);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp1.rightMargin = dp(8);
        actions.addView(cancel, lp1);
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(44), 1f));

        AikoTheme.presentSheet(dialog, panel, displayMode, density);
    }

    // =========================================================================
    // JS Injection & Bridge Message Handling
    // =========================================================================

    private final class LegacyJsBridge {
        private final WebView view;

        LegacyJsBridge(WebView view) {
            this.view = view;
        }

        @JavascriptInterface
        public void post(String json) {
            main.post(() -> {
                if (tabs.containsKey(view)) {
                    handleBridgeMessage(view, json);
                }
            });
        }
    }

    private void handleBridgeMessage(WebView view, String rawJson) {
        if (view == null || rawJson == null || rawJson.length() > MAX_BRIDGE_MESSAGE_CHARS) return;
        TabState state = tabs.get(view);
        if (state == null) return;

        try {
            JSONObject msg = new JSONObject(rawJson);
            String type = msg.optString("type", "");
            if ("translate_batch".equals(type)) {
                handleTranslateBatch(view, state, msg);
            } else if ("translate_selection".equals(type)) {
                handleTranslateSelection(view, state, msg);
            } else if ("translate_input".equals(type)) {
                handleTranslateInput(view, state, msg);
            } else if ("speak".equals(type)) {
                String text = msg.optString("text", "").trim();
                String lang = msg.optString("lang", config.getTargetLang());
                if (!text.isEmpty() && callbacks != null) {
                    callbacks.speakText(text, lang);
                }
            } else if ("copy".equals(type)) {
                String text = msg.optString("text", "");
                if (!text.isEmpty()) {
                    copyToClipboard(text);
                    toast("已复制译文");
                }
            } else if ("open_sheet".equals(type)) {
                showSheet(view, state.currentUrl);
            } else if ("toggle_page".equals(type)) {
                togglePageTranslation(view, state.currentUrl);
            } else if ("state".equals(type)) {
                boolean active = msg.optBoolean("active", false);
                if (state.translating != active) {
                    state.translating = active;
                    notifyStateChanged();
                }
            }
        } catch (JSONException ignored) { }
    }

    private void handleTranslateBatch(WebView view, TabState state, JSONObject msg) {
        String reqId = msg.optString("reqId", "");
        JSONArray items = msg.optJSONArray("items");
        if (reqId.isEmpty() || items == null || items.length() == 0) return;

        int count = Math.min(items.length(), 40);
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JSONObject item = items.optJSONObject(i);
            texts.add(item == null ? "" : item.optString("text", ""));
        }
        String target = msg.optString("targetLang", config.getTargetLang());
        long gen = state.generation;

        engine.translateBatchAsync(state.scopeKey, gen, texts, target, res -> main.post(() -> {
            TabState cur = tabs.get(view);
            if (cur == null || cur.generation != gen || isUiGone()) return;
            if (res.isSuccess()) {
                if (res.fellBackToCn && !cur.notifiedFallback) {
                    cur.notifiedFallback = true;
                    toast("Google 暂不可达，已自动切换至 " + res.usedEngine.label);
                }
                JSONArray out = new JSONArray();
                for (String t : res.translations) out.put(t);
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applyBatchResult("
                        + JSONObject.quote(reqId) + "," + out.toString() + ");");
            } else {
                String errText = res.error == null ? "请检查网络或切换翻译引擎" : res.error;
                if (!cur.notifiedError && !"已取消".equals(errText)) {
                    cur.notifiedError = true;
                    toast("巴别塔翻译失败：" + errText);
                }
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applyBatchError("
                        + JSONObject.quote(reqId) + "," + JSONObject.quote(errText) + ");");
            }
        }));
    }

    private void handleTranslateSelection(WebView view, TabState state, JSONObject msg) {
        String reqId = msg.optString("reqId", "");
        String text = msg.optString("text", "").trim();
        if (reqId.isEmpty() || text.isEmpty()) return;
        String target = msg.optString("targetLang", config.getTargetLang());
        long gen = state.generation;

        engine.translateSingleAsync(state.scopeKey, gen, text, target, res -> main.post(() -> {
            TabState cur = tabs.get(view);
            if (cur == null || cur.generation != gen || isUiGone()) return;
            if (res.isSuccess()) {
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applySelectionResult("
                        + JSONObject.quote(reqId) + "," + JSONObject.quote(res.translated) + ");");
            } else {
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applySelectionError("
                        + JSONObject.quote(reqId) + "," + JSONObject.quote(res.error == null ? "翻译失败" : res.error) + ");");
            }
        }));
    }

    private void handleTranslateInput(WebView view, TabState state, JSONObject msg) {
        String reqId = msg.optString("reqId", "");
        String text = msg.optString("text", "").trim();
        if (reqId.isEmpty() || text.isEmpty()) return;
        String target = msg.optString("targetLang", config.getInputBackTargetLang());
        long gen = state.generation;

        engine.translateSingleAsync(state.scopeKey, gen, text, target, res -> main.post(() -> {
            TabState cur = tabs.get(view);
            if (cur == null || cur.generation != gen || isUiGone()) return;
            if (res.isSuccess()) {
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applyInputResult("
                        + JSONObject.quote(reqId) + "," + JSONObject.quote(res.translated) + ");");
            } else {
                evalJs(view, "window.__aikoBabel && window.__aikoBabel.applyInputError("
                        + JSONObject.quote(reqId) + "," + JSONObject.quote(res.error == null ? "翻译失败" : res.error) + ");");
                toast("输入框反译失败：" + (res.error == null ? "请检查网络" : res.error));
            }
        }));
    }

    private void injectAndConfigure(WebView view, boolean enablePageTranslation) {
        String script = loadBabelJs();
        if (script == null || script.isEmpty()) {
            toast("巴别塔脚本加载失败");
            return;
        }
        String cfgJson = buildJsConfigJson(enablePageTranslation);
        String payload = script + "\nwindow.__aikoBabel && window.__aikoBabel.configure(" + cfgJson + ");";
        evalJs(view, payload);
    }

    private void syncActiveWebViewConfig(WebView view) {
        if (view == null) return;
        TabState state = tabs.get(view);
        if (state == null) return;
        String cfgJson = buildJsConfigJson(state.translating);
        if (state.translating || config.isSelectionTranslate() || config.isTripleSpaceInputTranslate()) {
            if (HttpUrlUtil.isHttp(state.currentUrl)) {
                injectAndConfigure(view, state.translating);
                return;
            }
        }
        evalJs(view, "window.__aikoBabel && window.__aikoBabel.configure(" + cfgJson + ");");
    }

    private String buildJsConfigJson(boolean enablePageTranslation) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("enabled", enablePageTranslation);
            obj.put("style", config.getDisplayStyle().key);
            obj.put("targetLang", config.getTargetLang());
            obj.put("inputTargetLang", config.getInputBackTargetLang());
            obj.put("selectionTranslate", config.isSelectionTranslate());
            obj.put("tripleSpaceInputTranslate", config.isTripleSpaceInputTranslate());
            obj.put("skipCodeAndNav", config.isSkipCodeAndNav());
            obj.put("showFloatingFab", config.isShowFloatingFab());
            obj.put("batchMaxItems", config.resolveEffectiveBatchMaxItems());
            obj.put("batchMaxChars", config.resolveEffectiveBatchMaxChars());

            JSONObject theme = new JSONObject();
            theme.put("accent", AikoTheme.Palette.formatHex(AikoTheme.ACCENT));
            theme.put("accentCyan", AikoTheme.Palette.formatHex(AikoTheme.ACCENT_CYAN));
            theme.put("text", AikoTheme.Palette.formatHex(AikoTheme.TEXT));
            theme.put("muted", AikoTheme.Palette.formatHex(AikoTheme.TEXT_MUTED));
            theme.put("surface", AikoTheme.Palette.formatHex(AikoTheme.SURFACE));
            theme.put("card", AikoTheme.Palette.formatHex(AikoTheme.CARD));
            theme.put("stroke", "rgba(255,255,255,0.16)");
            theme.put("onAccent", AikoTheme.Palette.formatHex(AikoTheme.onAccentColor()));
            obj.put("theme", theme);
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private String loadBabelJs() {
        if (cachedBabelScript != null) return cachedBabelScript;
        try (InputStream in = activity.getAssets().open(ASSET_BABEL_JS)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            cachedBabelScript = bos.toString(StandardCharsets.UTF_8.name());
            return cachedBabelScript;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean supportsWebMessageListener() {
        if (webMessageSupported == null) {
            try {
                webMessageSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
            } catch (RuntimeException | LinkageError e) {
                webMessageSupported = false;
            }
        }
        return webMessageSupported;
    }

    private void evalJs(WebView view, String js) {
        if (view == null || js == null || isUiGone()) return;
        try {
            view.evaluateJavascript(js, null);
        } catch (RuntimeException ignored) { }
    }

    // =========================================================================
    // Material / AikoTheme View Builders
    // =========================================================================

    private int dp(int v) {
        return AikoTheme.dp(density, v);
    }

    private LinearLayout verticalStack() {
        LinearLayout ll = new LinearLayout(activity);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    private LinearLayout horizontalRow() {
        LinearLayout ll = new LinearLayout(activity);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px;
        return lp;
    }

    private void addWeighted(LinearLayout row, View child, float weight, int rightMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.rightMargin = rightMarginPx;
        row.addView(child, lp);
    }

    private TextView label(String text, float sp, int color, boolean medium) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(AikoTheme.MEDIUM);
        return tv;
    }

    private TextView primaryButton(String text) {
        TextView btn = AikoTheme.badge(activity, density, text, AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        btn.setTextSize(14);
        btn.setTypeface(AikoTheme.BOLD);
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(14)));
        AikoTheme.attachPressFeedback(btn, 0.96f);
        return btn;
    }

    private TextView secondaryButton(String text) {
        TextView btn = AikoTheme.badge(activity, density, text, AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        btn.setTextSize(13);
        btn.setTypeface(AikoTheme.MEDIUM);
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(btn, 0.96f);
        return btn;
    }

    private TextView compactActionBtn(String text, boolean accent) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTypeface(AikoTheme.MEDIUM);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setTextColor(accent ? AikoTheme.onAccentColor() : AikoTheme.TEXT);
        btn.setBackground(AikoTheme.card(
                accent ? AikoTheme.ACCENT : AikoTheme.PILL,
                accent ? 0 : AikoTheme.STROKE,
                density, dp(11)));
        btn.setForeground(AikoTheme.ripple(accent ? AikoTheme.RIPPLE_LIGHT : AikoTheme.RIPPLE_ACCENT, dp(11)));
        AikoTheme.attachPressFeedback(btn, 0.95f);
        return btn;
    }

    private TextView selectablePill(String text, boolean selected) {
        TextView pill = new TextView(activity);
        pill.setText(text);
        pill.setTextSize(12);
        pill.setTypeface(AikoTheme.MEDIUM);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(12)));
        AikoTheme.attachPressFeedback(pill, 0.95f);
        stylePill(pill, selected);
        return pill;
    }

    private void stylePill(TextView pill, boolean selected) {
        pill.setTextColor(selected ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED);
        pill.setBackground(AikoTheme.card(
                selected ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                selected ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                density, dp(12)));
    }

    private TextView valueCard(String title, String value) {
        TextView tv = new TextView(activity);
        tv.setText(title + "\n" + value);
        tv.setTextSize(12);
        tv.setTypeface(AikoTheme.MEDIUM);
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextColor(AikoTheme.TEXT);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        tv.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(tv, 0.96f);
        return tv;
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }

    private LinearLayout switchCard(String title, String subtitle, boolean initial, BooleanConsumer onChanged) {
        final boolean[] state = {initial};
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(10), dp(12), dp(10));
        row.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
        row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
        AikoTheme.attachPressFeedback(row, 0.97f);

        LinearLayout textCol = verticalStack();
        TextView t = label(title, 13, AikoTheme.TEXT, true);
        TextView sub = label(subtitle, 11, AikoTheme.TEXT_MUTED, false);
        textCol.addView(t);
        textCol.addView(sub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.rightMargin = dp(10);
        row.addView(textCol, tlp);

        TextView toggleBadge = AikoTheme.badge(
                activity, density,
                initial ? "开启" : "关闭",
                initial ? AikoTheme.onAccentColor() : AikoTheme.TEXT_MUTED,
                initial ? AikoTheme.ACCENT : AikoTheme.PILL,
                initial ? 0 : AikoTheme.STROKE);
        row.addView(toggleBadge, new LinearLayout.LayoutParams(dp(56), dp(28)));

        row.setOnClickListener(v -> {
            state[0] = !state[0];
            boolean on = state[0];
            toggleBadge.setText(on ? "开启" : "关闭");
            toggleBadge.setTextColor(on ? AikoTheme.onAccentColor() : AikoTheme.TEXT_MUTED);
            toggleBadge.setBackground(AikoTheme.card(
                    on ? AikoTheme.ACCENT : AikoTheme.PILL,
                    on ? 0 : AikoTheme.STROKE,
                    density, dp(14)));
            onChanged.accept(on);
        });
        return row;
    }

    private EditText styledInput(String hint, String value, boolean multiline) {
        EditText et = new EditText(activity);
        et.setHint(hint);
        et.setHintTextColor(AikoTheme.TEXT_DIM);
        et.setTextColor(AikoTheme.TEXT);
        et.setTextSize(13);
        et.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, density, dp(12)));
        if (multiline) {
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            et.setPadding(dp(12), dp(10), dp(12), dp(10));
        } else {
            et.setSingleLine(true);
            et.setGravity(Gravity.CENTER_VERTICAL);
            et.setPadding(dp(12), 0, dp(12), 0);
        }
        if (value != null) et.setText(value);
        return et;
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private SeekBar themedSeekBar(int max, int initial, IntConsumer onProgress) {
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max);
        seek.setProgress(Math.max(0, Math.min(max, initial)));
        seek.setProgressTintList(ColorStateList.valueOf(AikoTheme.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(AikoTheme.ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(AikoTheme.PILL));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onProgress.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return seek;
    }

    // =========================================================================
    // Misc Utilities
    // =========================================================================

    private String formatStatsLine() {
        BabelEngine.Stats s = engine.getStats();
        return String.format(
                Locale.ROOT,
                "%s · 缓存命中 %d · 网络 %d%s",
                s.lastActiveEngine.isEmpty() ? config.activeEngineDisplayName() : s.lastActiveEngine,
                s.cacheHits,
                s.networkCalls,
                s.lastLatencyMs > 0 ? (" · " + s.lastLatencyMs + "ms") : ""
        );
    }

    private String safeUrl(WebView view) {
        if (view == null) return "";
        try {
            String u = view.getUrl();
            return u == null ? "" : u;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Babel Translation", text));
            }
        } catch (RuntimeException ignored) { }
    }

    private void toast(String msg) {
        if (callbacks != null && msg != null) callbacks.toast(msg);
    }

    private void notifyStateChanged() {
        if (callbacks != null) callbacks.onStateChanged();
    }

    private boolean isUiGone() {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    private static String truncateMiddle(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        int half = Math.max(4, (max - 1) / 2);
        return s.substring(0, half) + "…" + s.substring(s.length() - (max - half - 1));
    }
}
