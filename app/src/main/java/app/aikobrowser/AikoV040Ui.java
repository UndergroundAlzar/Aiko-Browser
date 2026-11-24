package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Clean Material Obsidian v0.4.0 UI helper housing customization, Arc-style Peek,
 * Media Sniffer, .aiko Backup, Read Aloud TTS, @connect & App-Jump Guard sheets,
 * Userscript Update Review, Site CSS Editor, and !Bang Manager so MainActivity stays crisp.
 */
final class AikoV040Ui {
    interface Host {
        Activity activity();
        float density();
        DisplayModeEngine displayMode();
        StartPageConfig startPageConfig();
        BangCatalog bangCatalog();
        SiteFilter siteFilter();
        SiteExtras siteExtras();
        UserScripts userScripts();
        void toast(String msg);
        void navigate(String url);
        void addTab(String url, boolean focusBox);
        void downloadUrl(String url, String filename, String ua);
        void copyText(String label, String text, String confirm);
        void onStartPageOrBarChanged();
        void onNightComfortChanged();
        void onTypographyOrPrivacyChanged();
        void pickCustomFont();
        void startBackupExport();
        void startBackupImport();
        void reloadCurrentTab();
        boolean effectiveDarkWeb(String host);
    }

    private AikoV040Ui() {}

    // =========================================================================
    // 1. Homepage, Bottom Bar, Night Comfort, Typography, App-Jump & Privacy
    // =========================================================================

    static void showCustomizationSheet(Host host) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        AikoTheme.applyTheme(act);
        float d = host.density();
        StartPageConfig cfg = host.startPageConfig();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "主页与高级定制", dialog::dismiss);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(580, Math.max(280, act.getResources().getConfiguration().screenHeightDp - 124));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout body = vStack(act);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // --- Section A: Homepage & New Tab (iTab / WeTab / MTab / Custom) ---
        body.addView(AikoTheme.sectionHeader(act, d, "主页与新标签页（支持 iTab / 云端起始页）"));

        LinearLayout homeCard = cardBox(act, d);
        body.addView(homeCard, mb(d, 8));

        String activeHomeUrl = cfg.resolveHomeUrl();
        String currentHomeDesc = activeHomeUrl == null ? "Aiko 原生极速页" : activeHomeUrl;
        TextView homeStatusTv = label(act, "当前主页：" + currentHomeDesc, 12, AikoTheme.ACCENT_CYAN, true);
        homeStatusTv.setSingleLine(true);
        homeStatusTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        homeCard.addView(homeStatusTv, mb(d, 8));

        FlowTagLayout presetFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
        homeCard.addView(presetFlow, mb(d, 10));

        EditText customHomeInput = styledInput(act, d, "自定义主页网址（如 https://go.itab.link/）",
                cfg.getHomeUrl(), false);
        customHomeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        List<StartPageConfig.Preset> presets = StartPageConfig.presets();
        TextView[] presetPills = new TextView[presets.size()];
        Runnable syncHomePills = () -> {
            String cur = cfg.resolveHomeUrl();
            homeStatusTv.setText("当前主页：" + (cur == null ? "Aiko 原生极速页" : cur));
            for (int i = 0; i < presets.size(); i++) {
                StartPageConfig.Preset p = presets.get(i);
                boolean sel = p.url.isEmpty() ? (cur == null) : p.url.equalsIgnoreCase(cur);
                stylePill(presetPills[i], d, sel);
            }
        };

        for (int i = 0; i < presets.size(); i++) {
            final StartPageConfig.Preset p = presets.get(i);
            TextView pill = pill(act, d, p.name, false);
            pill.setOnClickListener(v -> {
                cfg.applyHomePresetOrUrl(p.url);
                customHomeInput.setText(cfg.getHomeUrl());
                syncHomePills.run();
                host.onStartPageOrBarChanged();
                host.toast("已设为主页：" + p.name);
            });
            presetPills[i] = pill;
            presetFlow.addView(pill);
        }
        syncHomePills.run();

        LinearLayout homeInputRow = hRow(act);
        homeInputRow.setGravity(Gravity.CENTER_VERTICAL);
        homeCard.addView(homeInputRow, mb(d, 8));
        LinearLayout.LayoutParams hilp = new LinearLayout.LayoutParams(0, dp(d, 40), 1f);
        hilp.rightMargin = dp(d, 8);
        homeInputRow.addView(customHomeInput, hilp);

        TextView applyHomeBtn = accentBtn(act, d, "应用");
        applyHomeBtn.setPadding(dp(d, 14), 0, dp(d, 14), 0);
        applyHomeBtn.setOnClickListener(v -> {
            String raw = customHomeInput.getText().toString().trim();
            if (raw.isEmpty()) {
                cfg.setHome(StartPageConfig.PageMode.NATIVE, "");
                syncHomePills.run();
                host.onStartPageOrBarChanged();
                host.toast("已恢复 Aiko 原生极速主页");
                return;
            }
            if (!cfg.setHome(StartPageConfig.PageMode.CUSTOM_URL, raw)) {
                customHomeInput.setError("请输入有效的 HTTP/HTTPS 网址或 about:blank");
                return;
            }
            customHomeInput.setText(cfg.getHomeUrl());
            syncHomePills.run();
            host.onStartPageOrBarChanged();
            host.toast("已应用自定义主页");
        });
        homeInputRow.addView(applyHomeBtn, new LinearLayout.LayoutParams(-2, dp(d, 40)));

        // Separate New Tab URL row (visible when newTabFollowsHome is false)
        LinearLayout customNtRow = hRow(act);
        customNtRow.setGravity(Gravity.CENTER_VERTICAL);
        customNtRow.setVisibility(cfg.isNewTabFollowsHome() ? View.GONE : View.VISIBLE);
        EditText customNtInput = styledInput(act, d, "独立新标签网址（留空使用原生页）", cfg.getNewTabUrl(), false);
        customNtInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams ntlp = new LinearLayout.LayoutParams(0, dp(d, 40), 1f);
        ntlp.rightMargin = dp(d, 8);
        customNtRow.addView(customNtInput, ntlp);
        TextView applyNtBtn = secondaryBtn(act, d, "保存");
        applyNtBtn.setPadding(dp(d, 12), 0, dp(d, 12), 0);
        applyNtBtn.setOnClickListener(v -> {
            String raw = customNtInput.getText().toString().trim();
            if (raw.isEmpty()) {
                cfg.setNewTab(StartPageConfig.PageMode.NATIVE, "");
                host.onStartPageOrBarChanged();
                host.toast("新标签页已设为 Aiko 原生页");
            } else if (!cfg.setNewTab(StartPageConfig.PageMode.CUSTOM_URL, raw)) {
                customNtInput.setError("请输入有效网址");
            } else {
                customNtInput.setText(cfg.getNewTabUrl());
                host.onStartPageOrBarChanged();
                host.toast("已保存独立新标签页网址");
            }
        });
        customNtRow.addView(applyNtBtn, new LinearLayout.LayoutParams(-2, dp(d, 40)));

        homeCard.addView(switchRow(act, d, "新标签页跟随主页", "新建标签自动加载上方主页设置",
                cfg.isNewTabFollowsHome(), on -> {
                    cfg.setNewTabFollowsHome(on);
                    customNtRow.setVisibility(on ? View.GONE : View.VISIBLE);
                    host.onStartPageOrBarChanged();
                }), mb(d, 6));
        homeCard.addView(customNtRow, mb(d, 6));

        homeCard.addView(switchRow(act, d, "新标签页后台预加载", "预先温热 1 个隐藏页，新建标签零白屏瞬开（省电模式自动暂停）",
                cfg.isPreloadNewTab(), on -> {
                    cfg.setPreloadNewTab(on);
                    host.onStartPageOrBarChanged();
                }), new LinearLayout.LayoutParams(-1, -2));

        // --- Section B: Bottom Bar Layout & Gestures ---
        body.addView(AikoTheme.sectionHeader(act, d, "底栏样式与单手手势"));
        LinearLayout barCard = cardBox(act, d);
        body.addView(barCard, mb(d, 8));

        LinearLayout styleRow = hRow(act);
        barCard.addView(styleRow, mb(d, 8));
        TextView stdPill = pill(act, d, "标准贴底栏", cfg.getBarStyle() == StartPageConfig.BarStyle.STANDARD);
        TextView capPill = pill(act, d, "悬浮胶囊栏 (Capsule)", cfg.getBarStyle() == StartPageConfig.BarStyle.CAPSULE);
        stdPill.setOnClickListener(v -> {
            cfg.setBarStyle(StartPageConfig.BarStyle.STANDARD);
            stylePill(stdPill, d, true);
            stylePill(capPill, d, false);
            host.onStartPageOrBarChanged();
        });
        capPill.setOnClickListener(v -> {
            cfg.setBarStyle(StartPageConfig.BarStyle.CAPSULE);
            stylePill(stdPill, d, false);
            stylePill(capPill, d, true);
            host.onStartPageOrBarChanged();
        });
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, dp(d, 36), 1f);
        sp1.rightMargin = dp(d, 8);
        styleRow.addView(stdPill, sp1);
        styleRow.addView(capPill, new LinearLayout.LayoutParams(0, dp(d, 36), 1f));

        barCard.addView(switchRow(act, d, "底栏显示主页按钮", "一键直达自定义主页 / 原生起始页",
                cfg.isShowHomeButton(), on -> {
                    cfg.setShowHomeButton(on);
                    host.onStartPageOrBarChanged();
                }), mb(d, 6));

        barCard.addView(switchRow(act, d, "底栏显示巴别塔翻译按钮", "点按一键翻译网页，长按打开巴别塔面板",
                cfg.isShowBabelButton(), on -> {
                    cfg.setShowBabelButton(on);
                    host.onStartPageOrBarChanged();
                }), mb(d, 6));

        barCard.addView(switchRow(act, d, "底栏左右滑动切换标签", "单手在地址栏上左右轻划秒切相邻标签",
                cfg.isSwipeBarSwitchTabs(), on -> {
                    cfg.setSwipeBarSwitchTabs(on);
                    host.onStartPageOrBarChanged();
                }), mb(d, 6));

        barCard.addView(switchRow(act, d, "底栏上滑打开标签列表", "地址栏向上轻扫快速呼出标签切换器",
                cfg.isSwipeUpForTabs(), on -> {
                    cfg.setSwipeUpForTabs(on);
                    host.onStartPageOrBarChanged();
                }), new LinearLayout.LayoutParams(-1, -2));

        // --- Section C: AMOLED Night Comfort & Auto Day/Night Schedule ---
        body.addView(AikoTheme.sectionHeader(act, d, "AMOLED 极夜护眼与日夜定时"));
        LinearLayout nightCard = cardBox(act, d);
        body.addView(nightCard, mb(d, 8));

        nightCard.addView(switchRow(act, d, "开启极夜护眼滤镜", "全局低亮度压暗 + 暖色抗蓝光，不拦截任何网页触控",
                cfg.isNightFilterEnabled(), on -> {
                    cfg.setNightFilterEnabled(on);
                    host.onNightComfortChanged();
                }), mb(d, 8));

        TextView dimLabel = label(act, "屏幕压暗强度：" + cfg.getNightDimPercent() + "%", 12, AikoTheme.TEXT_MUTED, false);
        nightCard.addView(dimLabel);
        SeekBar dimSeek = themedSeek(act, 70, cfg.getNightDimPercent(), val -> {
            cfg.setNightDimPercent(val);
            dimLabel.setText("屏幕压暗强度：" + val + "%");
            if (!cfg.isNightFilterEnabled() && val > 0) cfg.setNightFilterEnabled(true);
            host.onNightComfortChanged();
        });
        nightCard.addView(dimSeek, mb(d, 6));

        TextView warmLabel = label(act, "暖色护眼色温：" + cfg.getNightWarmthPercent() + "%", 12, AikoTheme.TEXT_MUTED, false);
        nightCard.addView(warmLabel);
        SeekBar warmSeek = themedSeek(act, 60, cfg.getNightWarmthPercent(), val -> {
            cfg.setNightWarmthPercent(val);
            warmLabel.setText("暖色护眼色温：" + val + "%");
            if (!cfg.isNightFilterEnabled() && val > 0) cfg.setNightFilterEnabled(true);
            host.onNightComfortChanged();
        });
        nightCard.addView(warmSeek, mb(d, 8));

        nightCard.addView(switchRow(act, d, "按时间自动切换夜间护眼",
                String.format(Locale.ROOT, "当前夜间时段：%02d:00 – %02d:00", cfg.getNightStartHour(), cfg.getNightEndHour()),
                cfg.isAutoThemeByTime(), on -> {
                    cfg.setAutoThemeByTime(on);
                    host.onNightComfortChanged();
                }), mb(d, 8));

        int[][] schedules = {{22, 7}, {21, 7}, {23, 6}, {20, 8}};
        FlowTagLayout schedFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
        TextView[] schedPills = new TextView[schedules.length];
        Runnable syncSched = () -> {
            int s = cfg.getNightStartHour(), e = cfg.getNightEndHour();
            for (int i = 0; i < schedules.length; i++) {
                stylePill(schedPills[i], d, schedules[i][0] == s && schedules[i][1] == e);
            }
        };
        for (int i = 0; i < schedules.length; i++) {
            final int sh = schedules[i][0], eh = schedules[i][1];
            TextView p = pill(act, d, String.format(Locale.ROOT, "%02d:00–%02d:00", sh, eh), false);
            p.setOnClickListener(v -> {
                cfg.setNightSchedule(sh, eh);
                syncSched.run();
                host.onNightComfortChanged();
                host.toast(String.format(Locale.ROOT, "夜间时段已设为 %02d:00 – %02d:00", sh, eh));
            });
            schedPills[i] = p;
            schedFlow.addView(p);
        }
        syncSched.run();
        nightCard.addView(schedFlow, new LinearLayout.LayoutParams(-1, -2));

        // --- Section D: Typography & Force Zoom ---
        body.addView(AikoTheme.sectionHeader(act, d, "网页字体与缩放自由"));
        LinearLayout fontCard = cardBox(act, d);
        body.addView(fontCard, mb(d, 8));

        String fontName = cfg.getCustomFontName();
        TextView fontStatus = label(act,
                fontName.isEmpty() ? "当前网页字体：系统默认" : "已导入字体：" + fontName,
                12, fontName.isEmpty() ? AikoTheme.TEXT_MUTED : AikoTheme.ACCENT_CYAN, true);
        fontCard.addView(fontStatus, mb(d, 8));

        LinearLayout fontBtns = hRow(act);
        fontCard.addView(fontBtns, mb(d, 10));
        TextView importFontBtn = accentBtn(act, d, "导入本地 .ttf / .otf");
        importFontBtn.setOnClickListener(v -> {
            dialog.dismiss();
            host.pickCustomFont();
        });
        LinearLayout.LayoutParams iflp = new LinearLayout.LayoutParams(0, dp(d, 38), 1.4f);
        iflp.rightMargin = dp(d, 8);
        fontBtns.addView(importFontBtn, iflp);

        TextView clearFontBtn = secondaryBtn(act, d, "恢复默认");
        clearFontBtn.setEnabled(!fontName.isEmpty());
        clearFontBtn.setAlpha(fontName.isEmpty() ? 0.45f : 1f);
        clearFontBtn.setOnClickListener(v -> {
            cfg.setCustomFont("", "");
            fontStatus.setText("当前网页字体：系统默认");
            fontStatus.setTextColor(AikoTheme.TEXT_MUTED);
            clearFontBtn.setEnabled(false);
            clearFontBtn.setAlpha(0.45f);
            host.onTypographyOrPrivacyChanged();
            host.toast("已清除自定义网页字体");
        });
        fontBtns.addView(clearFontBtn, new LinearLayout.LayoutParams(0, dp(d, 38), 1f));

        TextView minFontTitle = label(act, "网页最小字号保底", 12, AikoTheme.TEXT_MUTED, false);
        fontCard.addView(minFontTitle, mb(d, 6));
        int[] minSizes = {0, 12, 14, 16, 18, 20};
        FlowTagLayout minFontFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
        TextView[] minFontPills = new TextView[minSizes.length];
        Runnable syncMinFont = () -> {
            int cur = cfg.getMinFontSizePx();
            for (int i = 0; i < minSizes.length; i++) stylePill(minFontPills[i], d, minSizes[i] == cur);
        };
        for (int i = 0; i < minSizes.length; i++) {
            final int sz = minSizes[i];
            TextView p = pill(act, d, sz == 0 ? "默认" : sz + "px", false);
            p.setOnClickListener(v -> {
                cfg.setMinFontSizePx(sz);
                syncMinFont.run();
                host.onTypographyOrPrivacyChanged();
            });
            minFontPills[i] = p;
            minFontFlow.addView(p);
        }
        syncMinFont.run();
        fontCard.addView(minFontFlow, mb(d, 10));

        fontCard.addView(switchRow(act, d, "全局强制双指缩放", "无视网页 user-scalable=no 限制，随时自由双指放大",
                cfg.isForcePinchZoom(), on -> {
                    cfg.setForcePinchZoom(on);
                    host.onTypographyOrPrivacyChanged();
                }), new LinearLayout.LayoutParams(-1, -2));

        // --- Section E: App-Jump Deep-Link Guard ---
        body.addView(AikoTheme.sectionHeader(act, d, "防跳外部 App 守卫"));
        LinearLayout jumpCard = cardBox(act, d);
        body.addView(jumpCard, mb(d, 8));

        StartPageConfig.AppJumpPolicy[] jumpPolicies = {
                StartPageConfig.AppJumpPolicy.ASK,
                StartPageConfig.AppJumpPolicy.BLOCK_ALL,
                StartPageConfig.AppJumpPolicy.ALLOW_ALL
        };
        String[] jumpNames = {"每次询问（推荐）", "默认拦截", "始终放行"};
        LinearLayout jumpRow = hRow(act);
        TextView[] jumpPills = new TextView[3];
        Runnable syncJump = () -> {
            StartPageConfig.AppJumpPolicy cur = cfg.getAppJumpPolicy();
            for (int i = 0; i < 3; i++) stylePill(jumpPills[i], d, jumpPolicies[i] == cur);
        };
        for (int i = 0; i < 3; i++) {
            final StartPageConfig.AppJumpPolicy pol = jumpPolicies[i];
            TextView p = pill(act, d, jumpNames[i], false);
            p.setOnClickListener(v -> {
                cfg.setAppJumpPolicy(pol);
                syncJump.run();
                host.toast("防跳 App 策略：" + jumpNames[pol == StartPageConfig.AppJumpPolicy.ASK ? 0
                        : (pol == StartPageConfig.AppJumpPolicy.BLOCK_ALL ? 1 : 2)]);
            });
            jumpPills[i] = p;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(d, 34), 1f);
            if (i < 2) lp.rightMargin = dp(d, 6);
            jumpRow.addView(p, lp);
        }
        syncJump.run();
        jumpCard.addView(jumpRow, mb(d, 8));

        Map<String, Integer> remembered = cfg.allHostAppJumpPolicies();
        TextView resetJumpBtn = secondaryBtn(act, d,
                remembered.isEmpty() ? "暂无已记住的站点跳转规则"
                        : "重置已记住的 " + remembered.size() + " 条站点跳转规则");
        resetJumpBtn.setEnabled(!remembered.isEmpty());
        resetJumpBtn.setAlpha(remembered.isEmpty() ? 0.45f : 1f);
        resetJumpBtn.setOnClickListener(v -> {
            cfg.clearHostAppJumpPolicies();
            resetJumpBtn.setText("已重置全部站点跳转规则");
            resetJumpBtn.setEnabled(false);
            resetJumpBtn.setAlpha(0.45f);
            host.toast("已清空站点防跳规则");
        });
        jumpCard.addView(resetJumpBtn, new LinearLayout.LayoutParams(-1, dp(d, 36)));

        // --- Section F: Privacy, DoH, Clipboard, Form Draft & Anti-Adblock ---
        body.addView(AikoTheme.sectionHeader(act, d, "隐私防护、DoH 与效率增强"));
        LinearLayout privCard = cardBox(act, d);
        body.addView(privCard, mb(d, 8));

        privCard.addView(switchRow(act, d, "剪贴板智能胶囊", "切回应用检测到新网址或 .user.js 链接时显示一键直达胶囊",
                cfg.isClipboardAssist(), cfg::setClipboardAssist), mb(d, 6));

        privCard.addView(switchRow(act, d, "表单草稿防丢失保护", "自动缓存未提交的输入框内容，刷新或内存回收后自动恢复",
                cfg.isFormDraftProtection(), on -> {
                    cfg.setFormDraftProtection(on);
                    host.onTypographyOrPrivacyChanged();
                }), mb(d, 6));

        privCard.addView(switchRow(act, d, "反反广告拦截 (Anti-Adblock)", "自动绕过常见网站的屏蔽广告检测弹窗与滚动锁定",
                cfg.isAntiAdblockScriptlets(), on -> {
                    cfg.setAntiAdblockScriptlets(on);
                    host.onTypographyOrPrivacyChanged();
                }), mb(d, 6));

        privCard.addView(switchRow(act, d, "防 WebRTC IP 泄漏", "禁用网页 RTCPeerConnection 本地 IP 探测",
                cfg.isBlockWebRtcLeak(), on -> {
                    cfg.setBlockWebRtcLeak(on);
                    host.onTypographyOrPrivacyChanged();
                }), mb(d, 10));

        TextView dohTitle = label(act, "加密 DNS (DoH) 配置", 12, AikoTheme.TEXT_MUTED, true);
        privCard.addView(dohTitle, mb(d, 6));
        String[] dohIds = {
                StartPageConfig.DOH_SYSTEM, StartPageConfig.DOH_ALIDNS, StartPageConfig.DOH_DNSPOD,
                StartPageConfig.DOH_CLOUDFLARE, StartPageConfig.DOH_GOOGLE, StartPageConfig.DOH_CUSTOM
        };
        String[] dohLabels = {"系统默认", "阿里 DNS", "DNSPod", "Cloudflare", "Google", "自定义"};
        FlowTagLayout dohFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
        TextView[] dohPills = new TextView[dohIds.length];

        LinearLayout customDohRow = hRow(act);
        customDohRow.setGravity(Gravity.CENTER_VERTICAL);
        customDohRow.setVisibility(StartPageConfig.DOH_CUSTOM.equals(cfg.getDohPreset()) ? View.VISIBLE : View.GONE);
        EditText customDohInput = styledInput(act, d, "https://dns.example.com/dns-query", cfg.getCustomDohUrl(), false);
        customDohInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(0, dp(d, 38), 1f);
        dhlp.rightMargin = dp(d, 8);
        customDohRow.addView(customDohInput, dhlp);
        TextView saveDohBtn = secondaryBtn(act, d, "保存");
        saveDohBtn.setPadding(dp(d, 12), 0, dp(d, 12), 0);
        saveDohBtn.setOnClickListener(v -> {
            String u = customDohInput.getText().toString().trim();
            if (!u.toLowerCase(Locale.ROOT).startsWith("https://") || !HttpUrlUtil.isHttp(u)) {
                customDohInput.setError("请输入有效的 HTTPS DoH 地址");
                return;
            }
            cfg.setDoh(StartPageConfig.DOH_CUSTOM, u);
            host.toast("已保存自定义 DoH：" + u);
        });
        customDohRow.addView(saveDohBtn, new LinearLayout.LayoutParams(-2, dp(d, 38)));

        Runnable syncDoh = () -> {
            String cur = cfg.getDohPreset();
            for (int i = 0; i < dohIds.length; i++) stylePill(dohPills[i], d, dohIds[i].equals(cur));
            customDohRow.setVisibility(StartPageConfig.DOH_CUSTOM.equals(cur) ? View.VISIBLE : View.GONE);
        };
        for (int i = 0; i < dohIds.length; i++) {
            final String id = dohIds[i];
            final String lbl = dohLabels[i];
            TextView p = pill(act, d, lbl, false);
            p.setOnClickListener(v -> {
                cfg.setDoh(id, cfg.getCustomDohUrl());
                syncDoh.run();
                if (!StartPageConfig.DOH_CUSTOM.equals(id)) {
                    String resolved = cfg.resolveDohUrl();
                    host.toast(resolved == null ? "已切回系统默认 DNS" : "DoH 偏好已设为 " + lbl + "（可配合系统私人 DNS 使用）");
                }
            });
            dohPills[i] = p;
            dohFlow.addView(p);
        }
        syncDoh.run();
        privCard.addView(dohFlow, mb(d, 8));
        privCard.addView(customDohRow, mb(d, 4));

        // --- Section G: !Bang Quick Search Manager ---
        body.addView(AikoTheme.sectionHeader(act, d, "地址栏 !Bang 与 /快捷指令"));
        TextView bangBtn = secondaryBtn(act, d, "管理 !Bang 快捷搜索与自定义指令（内置 20 个 + 自定义）›");
        bangBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showBangManagerSheet(host);
        });
        body.addView(bangBtn, new LinearLayout.LayoutParams(-1, dp(d, 42)));

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
    }

    // =========================================================================
    // 2. !Bang Custom Quick Search Manager
    // =========================================================================

    static void showBangManagerSheet(Host host) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        AikoTheme.applyTheme(act);
        float d = host.density();
        BangCatalog catalog = host.bangCatalog();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "!Bang 快捷指令管理", dialog::dismiss);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(540, Math.max(260, act.getResources().getConfiguration().screenHeightDp - 140));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout body = vStack(act);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView tip = label(act, "在地址栏输入 !gh 关键词 或 关键词 !b 即可直达站点搜索；输入 /babel /devtools /qr 等可直达工具。",
                12, AikoTheme.TEXT_MUTED, false);
        body.addView(tip, mb(d, 10));

        // Add custom bang card
        LinearLayout addCard = cardBox(act, d);
        body.addView(addCard, mb(d, 10));
        addCard.addView(label(act, "新增自定义 !Bang", 13, AikoTheme.TEXT, true), mb(d, 8));

        LinearLayout row1 = hRow(act);
        EditText keyIn = styledInput(act, d, "触发词，如 v2", "", false);
        EditText nameIn = styledInput(act, d, "名称，如 V2EX", "", false);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(d, 40), 1f);
        klp.rightMargin = dp(d, 8);
        row1.addView(keyIn, klp);
        row1.addView(nameIn, new LinearLayout.LayoutParams(0, dp(d, 40), 1.3f));
        addCard.addView(row1, mb(d, 8));

        EditText tplIn = styledInput(act, d, "搜索模板，如 https://example.com/search?q=%s", "", false);
        tplIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addCard.addView(tplIn, mb(d, 8));

        TextView addBtn = accentBtn(act, d, "+ 保存自定义 !Bang");
        addBtn.setOnClickListener(v -> {
            String k = keyIn.getText().toString().trim();
            String nm = nameIn.getText().toString().trim();
            String tpl = tplIn.getText().toString().trim();
            if (!catalog.addCustomBang(k, nm, tpl)) {
                tplIn.setError("触发词仅限字母/数字，模板需为有效 HTTP(S) 链接");
                return;
            }
            host.toast("已保存 !" + k.replace("!", ""));
            dialog.dismiss();
            showBangManagerSheet(host);
        });
        addCard.addView(addBtn, new LinearLayout.LayoutParams(-1, dp(d, 40)));

        // Custom bangs list
        List<BangCatalog.Entry> customs = catalog.listCustomBangs();
        if (!customs.isEmpty()) {
            body.addView(AikoTheme.sectionHeader(act, d, "我的自定义 !Bang（" + customs.size() + "）"));
            for (BangCatalog.Entry e : customs) {
                LinearLayout row = hRow(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(d, 12), dp(d, 9), dp(d, 8), dp(d, 9));
                row.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 13)));
                LinearLayout info = vStack(act);
                info.addView(label(act, "!" + e.bang + "  ·  " + e.label, 13, AikoTheme.ACCENT_CYAN, true));
                TextView sub = label(act, e.template, 11, AikoTheme.TEXT_DIM, false);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                info.addView(sub);
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView del = pill(act, d, "删除", false);
                del.setTextColor(AikoTheme.CORAL);
                del.setOnClickListener(v -> {
                    catalog.removeCustomBang(e.bang);
                    dialog.dismiss();
                    showBangManagerSheet(host);
                });
                row.addView(del, new LinearLayout.LayoutParams(-2, dp(d, 30)));
                body.addView(row, mb(d, 6));
            }
        }

        // Built-in bangs FlowTagLayout
        body.addView(AikoTheme.sectionHeader(act, d, "内置 20 个高频 !Bang（点按可直接使用）"));
        FlowTagLayout builtinFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
        for (BangCatalog.Entry e : BangCatalog.builtinBangs()) {
            TextView p = pill(act, d, "!" + e.bang + " " + e.label, false);
            p.setOnClickListener(v -> {
                dialog.dismiss();
                host.navigate(e.homeUrl);
            });
            builtinFlow.addView(p);
        }
        body.addView(builtinFlow, mb(d, 6));

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
    }

    // =========================================================================
    // 3. Arc-Style Half-Screen Peek Sheet
    // =========================================================================

    @SuppressWarnings("SetJavaScriptEnabled")
    static void showPeekSheet(Host host, String initialUrl) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed() || !HttpUrlUtil.isHttp(initialUrl)) return;
        AikoTheme.applyTheme(act);
        float d = host.density();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(AikoTheme.topRounded(AikoTheme.SURFACE, AikoTheme.STROKE, d, dp(d, 24)));
        panel.setElevation(dp(d, 16));

        // Drag handle
        View handle = new View(act);
        handle.setBackground(AikoTheme.rounded(AikoTheme.HANDLE, dp(d, 2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(d, 36), dp(d, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(d, 8);
        hlp.bottomMargin = dp(d, 6);
        panel.addView(handle, hlp);

        // Top Peek toolbar
        LinearLayout bar = hRow(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(d, 14), dp(d, 2), dp(d, 8), dp(d, 8));
        panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleCol = vStack(act);
        TextView titleTv = label(act, HttpUrlUtil.extractHost(initialUrl), 14, AikoTheme.TEXT, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        TextView urlTv = label(act, initialUrl, 11, AikoTheme.TEXT_MUTED, false);
        urlTv.setSingleLine(true);
        urlTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(titleTv);
        titleCol.addView(urlTv);
        bar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        final String[] liveUrl = {initialUrl};
        final SiteFilter.Page[] peekPage = {new SiteFilter.Page(initialUrl)};

        TextView expandBtn = AikoTheme.badge(act, d, "全屏打开",
                AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        expandBtn.setTextSize(12);
        expandBtn.setTypeface(AikoTheme.BOLD);
        expandBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(d, 14)));
        AikoTheme.attachPressFeedback(expandBtn, 0.94f);
        expandBtn.setOnClickListener(v -> {
            String target = HttpUrlUtil.isHttp(liveUrl[0]) ? liveUrl[0] : initialUrl;
            dialog.dismiss();
            host.addTab(target, false);
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, dp(d, 34));
        elp.leftMargin = dp(d, 8);
        bar.addView(expandBtn, elp);

        BrowserIcon closeBtn = new BrowserIcon(act, BrowserIcon.Kind.CLOSE);
        closeBtn.tint(AikoTheme.TEXT_MUTED);
        closeBtn.setContentDescription("关闭快览");
        closeBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(d, 18)));
        AikoTheme.attachPressFeedback(closeBtn);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(d, 40), dp(d, 40));
        clp.leftMargin = dp(d, 2);
        bar.addView(closeBtn, clp);

        ProgressBar progress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressDrawable(MainActivity.createLoadingDrawable(dp(d, 2)));
        panel.addView(progress, new LinearLayout.LayoutParams(-1, Math.max(2, Math.round(2.5f * d))));

        int peekH = Math.max(dp(d, 340), Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.72f));
        WebView peekWeb = new WebView(act);
        peekWeb.setBackgroundColor(AikoTheme.BG);
        WebSettings ws = peekWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(peekWeb, false);
        WebViewPerf.setAlgorithmicDarkening(peekWeb, host.effectiveDarkWeb(HttpUrlUtil.extractHost(initialUrl)));
        if (host.displayMode() != null) host.displayMode().attachView(peekWeb);

        SiteFilter filter = host.siteFilter();
        peekWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl() == null ? "" : request.getUrl().toString();
                if (HttpUrlUtil.isHttp(u)) {
                    liveUrl[0] = u;
                    peekPage[0] = new SiteFilter.Page(u);
                    urlTv.setText(u);
                    return false;
                }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return filter != null && filter.blocks(peekPage[0], request) ? SiteFilter.blocked() : null;
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                String h = HttpUrlUtil.extractHost(url);
                view.setBackgroundColor(host.effectiveDarkWeb(h) ? AikoTheme.BG : Color.WHITE);
                if (filter != null && !h.isEmpty()) filter.applyCss(view, h, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (HttpUrlUtil.isHttp(url)) {
                    liveUrl[0] = url;
                    urlTv.setText(url);
                    String h = HttpUrlUtil.extractHost(url);
                    if (filter != null && !h.isEmpty()) filter.applyCss(view, h, url);
                }
            }
        });
        peekWeb.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.trim().isEmpty()) titleTv.setText(title.trim());
            }
        });

        panel.addView(peekWeb, new LinearLayout.LayoutParams(-1, peekH));
        dialog.setOnDismissListener(d1 -> {
            try {
                peekWeb.stopLoading();
                if (peekWeb.getParent() instanceof ViewGroup) {
                    ((ViewGroup) peekWeb.getParent()).removeView(peekWeb);
                }
                peekWeb.destroy();
            } catch (RuntimeException ignored) { }
        });

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
        peekWeb.loadUrl(initialUrl);
    }

    // =========================================================================
    // 4. Media Sniffer Sheet (.mp4 / .m3u8 / .mp3 / .webm ...)
    // =========================================================================

    static void showMediaSnifferSheet(Host host, WebView web, String pageTitle, Consumer<Integer> onCountChanged) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if (web == null) {
            host.toast("请先打开包含视频或音频的网页");
            return;
        }
        AikoTheme.applyTheme(act);
        float d = host.density();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "媒体资源嗅探", dialog::dismiss);

        LinearLayout topRow = hRow(act);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(topRow, mb(d, 8));

        TextView statusTv = label(act, "正在扫描页面音视频与网络流…", 12, AikoTheme.ACCENT_CYAN, true);
        topRow.addView(statusTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView rescanBtn = pill(act, d, "重新嗅探", true);
        topRow.addView(rescanBtn, new LinearLayout.LayoutParams(-2, dp(d, 30)));

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(440, Math.max(220, act.getResources().getConfiguration().screenHeightDp - 190));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout list = vStack(act);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        Runnable runScan = () -> {
            statusTv.setText("正在扫描页面音视频与网络流…");
            web.evaluateJavascript(PageToolkit.sniffMediaScript(), raw -> {
                if (act.isDestroyed() || !dialog.isShowing()) return;
                renderSniffedMedia(host, dialog, list, statusTv, raw, pageTitle, onCountChanged);
            });
        };

        rescanBtn.setOnClickListener(v -> runScan.run());
        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
        runScan.run();
    }

    private static void renderSniffedMedia(Host host, Dialog dialog, LinearLayout list, TextView statusTv,
                                           String rawEval, String pageTitle, Consumer<Integer> onCountChanged) {
        Activity act = host.activity();
        float d = host.density();
        list.removeAllViews();

        JSONArray arr = null;
        try {
            if (rawEval != null && !"null".equals(rawEval)) {
                Object tok = new JSONTokener(rawEval).nextValue();
                if (tok instanceof String) arr = new JSONArray((String) tok);
                else if (tok instanceof JSONArray) arr = (JSONArray) tok;
            }
        } catch (Exception ignored) { }

        int count = arr == null ? 0 : arr.length();
        if (onCountChanged != null) onCountChanged.accept(count);

        if (count == 0) {
            statusTv.setText("未检测到直链媒体资源（建议先点击播放视频后再点「重新嗅探」）");
            statusTv.setTextColor(AikoTheme.TEXT_MUTED);
            TextView empty = label(act, "支持嗅探 .mp4 / .m3u8 / .webm / .mpd / .mp3 / .m4a / .flac / .ogg 网络请求与 HTML5 媒体标签。",
                    12, AikoTheme.TEXT_DIM, false);
            empty.setPadding(dp(d, 12), dp(d, 14), dp(d, 12), dp(d, 14));
            empty.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 14)));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        statusTv.setText("已嗅探到 " + count + " 条媒体流 · 支持 128 路高速下载");
        statusTv.setTextColor(AikoTheme.EMERALD);

        for (int i = 0; i < count; i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String mediaUrl = obj.optString("url", "").trim();
            if (!HttpUrlUtil.isHttp(mediaUrl)) continue;
            String type = obj.optString("type", "media").toUpperCase(Locale.ROOT);
            String title = obj.optString("title", "").trim();
            if (title.isEmpty()) title = (pageTitle == null || pageTitle.isEmpty()) ? URLUtil.guessFileName(mediaUrl, null, null) : pageTitle;
            long sizeHint = obj.optLong("sizeHint", 0L);

            LinearLayout card = cardBox(act, d);
            list.addView(card, mb(d, 8));

            LinearLayout head = hRow(act);
            head.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(head, mb(d, 6));

            int badgeColor = "M3U8".equals(type) || "MPD".equals(type) ? AikoTheme.AMBER : AikoTheme.ACCENT_CYAN;
            TextView typeBadge = AikoTheme.badge(act, d, type, badgeColor, AikoTheme.BG, AikoTheme.STROKE);
            LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(-2, -2);
            tblp.rightMargin = dp(d, 8);
            head.addView(typeBadge, tblp);

            TextView tTv = label(act, title, 13, AikoTheme.TEXT, true);
            tTv.setSingleLine(true);
            tTv.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(tTv, new LinearLayout.LayoutParams(0, -2, 1f));

            if (sizeHint > 0) {
                TextView szTv = label(act, formatBytes(sizeHint), 11, AikoTheme.TEXT_DIM, false);
                szTv.setTypeface(AikoTheme.MONO);
                head.addView(szTv);
            }

            TextView urlTv = label(act, mediaUrl, 11, AikoTheme.TEXT_MUTED, false);
            urlTv.setTypeface(AikoTheme.MONO);
            urlTv.setSingleLine(true);
            urlTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(urlTv, mb(d, 8));

            LinearLayout btnRow = hRow(act);
            card.addView(btnRow, new LinearLayout.LayoutParams(-1, dp(d, 34)));

            String suggestedName = URLUtil.guessFileName(mediaUrl, null, null);
            if (!suggestedName.contains(".") && !type.isEmpty()) {
                suggestedName = suggestedName + "." + type.toLowerCase(Locale.ROOT);
            }
            final String dlName = suggestedName;
            TextView dlBtn = accentBtn(act, d, "128 路下载");
            dlBtn.setTextSize(12);
            dlBtn.setOnClickListener(v -> {
                dialog.dismiss();
                host.downloadUrl(mediaUrl, dlName, null);
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(d, 34), 1.3f);
            dlp.rightMargin = dp(d, 6);
            btnRow.addView(dlBtn, dlp);

            TextView copyBtn = secondaryBtn(act, d, "复制链接");
            copyBtn.setTextSize(12);
            copyBtn.setOnClickListener(v -> host.copyText("媒体链接", mediaUrl, "已复制媒体链接"));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(d, 34), 1f);
            clp.rightMargin = dp(d, 6);
            btnRow.addView(copyBtn, clp);

            TextView openBtn = secondaryBtn(act, d, "打开");
            openBtn.setTextSize(12);
            openBtn.setOnClickListener(v -> {
                dialog.dismiss();
                host.addTab(mediaUrl, false);
            });
            btnRow.addView(openBtn, new LinearLayout.LayoutParams(0, dp(d, 34), 0.8f));
        }
    }

    // =========================================================================
    // 5. .aiko Full Backup & Restore Sheet
    // =========================================================================

    static void showBackupSheet(Host host) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        AikoTheme.applyTheme(act);
        float d = host.density();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "数据备份与恢复 (.aiko)", dialog::dismiss);

        LinearLayout infoCard = cardBox(act, d);
        infoCard.setBackground(AikoTheme.glowBanner(dp(d, 16)));
        panel.addView(infoCard, mb(d, 12));

        TextView head = label(act, "一键打包全站配置与油猴/巴别塔资产", 14, AikoTheme.TEXT, true);
        infoCard.addView(head, mb(d, 6));

        TextView desc = label(act,
                "• 完整包含：书签/历史/快捷拨号、主题色、性能模式、广告订阅与自定义规则、站点盾牌、油猴脚本、巴别塔 AI 配置、主页定制、自定义 !Bang 与导入字体。\n"
                        + "• 安全隐私：自动排除 WebView 缓存与登录 Cookie，导入前严格校验 manifest 与路径安全。",
                12, AikoTheme.TEXT_MUTED, false);
        desc.setLineSpacing(dp(d, 3), 1f);
        infoCard.addView(desc);

        TextView exportBtn = accentBtn(act, d, "导出完整备份 (.aiko)");
        exportBtn.setOnClickListener(v -> {
            dialog.dismiss();
            host.startBackupExport();
        });
        panel.addView(exportBtn, new LinearLayout.LayoutParams(-1, dp(d, 46)));

        TextView importBtn = secondaryBtn(act, d, "从 .aiko 备份文件恢复");
        importBtn.setOnClickListener(v -> {
            dialog.dismiss();
            host.startBackupImport();
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(d, 44));
        ilp.topMargin = dp(d, 8);
        panel.addView(importBtn, ilp);

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
    }

    // =========================================================================
    // 6. Userscript @connect Approval Bottom Sheet (Allow Once / Always / Deny)
    // =========================================================================

    /**
     * Presents a themed Material bottom sheet for undeclared GM_xmlhttpRequest @connect hosts.
     * Calls onChoice exactly once: 0 = deny, 1 = allow once, 2 = always allow host.
     */
    static void showConnectApprovalSheet(Activity act, DisplayModeEngine displayMode, float d,
                                         String scriptName, String targetHost, Consumer<Integer> onChoice) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            if (onChoice != null) onChoice.accept(0);
            return;
        }
        AikoTheme.applyTheme(act);
        final AtomicBoolean decided = new AtomicBoolean(false);
        Consumer<Integer> deliver = code -> {
            if (decided.compareAndSet(false, true) && onChoice != null) {
                onChoice.accept(code);
            }
        };

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "脚本跨域请求授权 (@connect)", () -> {
            deliver.accept(0);
            dialog.dismiss();
        });

        LinearLayout card = cardBox(act, d);
        panel.addView(card, mb(d, 12));

        TextView title = label(act, "脚本「" + (scriptName == null ? "未知脚本" : scriptName) + "」请求访问外部域名：",
                13, AikoTheme.TEXT, true);
        card.addView(title, mb(d, 6));

        TextView hostBadge = AikoTheme.badge(act, d, targetHost == null ? "" : targetHost,
                AikoTheme.AMBER, AikoTheme.BG, AikoTheme.STROKE_ACCENT);
        hostBadge.setTextSize(13);
        hostBadge.setTypeface(AikoTheme.MONO);
        card.addView(hostBadge, mb(d, 8));

        TextView warn = label(act, "该域名未在脚本 @connect 白名单中声明。请仅对信任的接口放行。",
                11, AikoTheme.TEXT_MUTED, false);
        card.addView(warn);

        TextView alwaysBtn = accentBtn(act, d, "始终允许此域名");
        alwaysBtn.setOnClickListener(v -> {
            deliver.accept(2);
            dialog.dismiss();
        });
        panel.addView(alwaysBtn, mb(d, 8));
        alwaysBtn.getLayoutParams().height = dp(d, 44);

        LinearLayout secRow = hRow(act);
        panel.addView(secRow, new LinearLayout.LayoutParams(-1, dp(d, 42)));

        TextView onceBtn = secondaryBtn(act, d, "仅本次允许");
        onceBtn.setTextColor(AikoTheme.ACCENT_CYAN);
        onceBtn.setOnClickListener(v -> {
            deliver.accept(1);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(d, 42), 1f);
        olp.rightMargin = dp(d, 8);
        secRow.addView(onceBtn, olp);

        TextView denyBtn = secondaryBtn(act, d, "拒绝请求");
        denyBtn.setTextColor(AikoTheme.CORAL);
        denyBtn.setOnClickListener(v -> {
            deliver.accept(0);
            dialog.dismiss();
        });
        secRow.addView(denyBtn, new LinearLayout.LayoutParams(0, dp(d, 42), 1f));

        dialog.setOnDismissListener(d1 -> deliver.accept(0));
        AikoTheme.presentSheet(dialog, panel, displayMode, d);
    }

    // =========================================================================
    // 7. App-Jump Deep-Link Guard Bottom Sheet
    // =========================================================================

    static void showAppJumpSheet(Activity act, DisplayModeEngine displayMode, float d,
                                 String pageHost, String appName, String scheme, String rawUrl,
                                 Runnable allowOnce, Runnable allowAlways, Runnable blockAlways) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        AikoTheme.applyTheme(act);

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "防跳外部 App 守卫", dialog::dismiss);

        LinearLayout card = cardBox(act, d);
        panel.addView(card, mb(d, 12));

        String hostLabel = (pageHost == null || pageHost.isEmpty()) ? "当前网页" : pageHost;
        TextView headline = label(act, hostLabel + " 试图拉起「" + appName + "」", 14, AikoTheme.TEXT, true);
        card.addView(headline, mb(d, 6));

        TextView schemeTv = label(act, (scheme != null ? scheme + "://  ·  " : "")
                + (rawUrl.length() > 72 ? rawUrl.substring(0, 72) + "…" : rawUrl), 11, AikoTheme.TEXT_MUTED, false);
        schemeTv.setTypeface(AikoTheme.MONO);
        card.addView(schemeTv);

        TextView onceBtn = accentBtn(act, d, "仅本次允许打开 " + appName);
        onceBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (allowOnce != null) allowOnce.run();
        });
        LinearLayout.LayoutParams olp = mb(d, 8);
        olp.height = dp(d, 44);
        panel.addView(onceBtn, olp);

        LinearLayout secRow = hRow(act);
        panel.addView(secRow, new LinearLayout.LayoutParams(-1, dp(d, 42)));

        TextView alwaysBtn = secondaryBtn(act, d, "始终允许此站");
        alwaysBtn.setTextColor(AikoTheme.EMERALD);
        alwaysBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (allowAlways != null) allowAlways.run();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(d, 42), 1f);
        alp.rightMargin = dp(d, 8);
        secRow.addView(alwaysBtn, alp);

        TextView blockBtn = secondaryBtn(act, d, "拦截并记住");
        blockBtn.setTextColor(AikoTheme.CORAL);
        blockBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (blockAlways != null) blockAlways.run();
        });
        secRow.addView(blockBtn, new LinearLayout.LayoutParams(0, dp(d, 42), 1f));

        AikoTheme.presentSheet(dialog, panel, displayMode, d);
    }

    // =========================================================================
    // 8. Userscript Updates Review Sheet
    // =========================================================================

    static void showScriptUpdatesSheet(Host host, List<UserScripts.UpdateResult> updates, Runnable onAllDone) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed() || updates == null || updates.isEmpty()) return;
        AikoTheme.applyTheme(act);
        float d = host.density();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "发现 " + updates.size() + " 个油猴脚本更新", dialog::dismiss);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        int maxH = Math.min(380, Math.max(180, act.getResources().getConfiguration().screenHeightDp - 220));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(d, maxH)));

        LinearLayout list = vStack(act);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        for (UserScripts.UpdateResult u : updates) {
            LinearLayout row = hRow(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(d, 12), dp(d, 10), dp(d, 10), dp(d, 10));
            row.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 14)));

            LinearLayout info = vStack(act);
            TextView name = label(act, u.name, 13, AikoTheme.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            String ver = (u.oldVersion == null || u.oldVersion.isEmpty() ? "?" : "v" + u.oldVersion)
                    + "  →  v" + u.newVersion;
            info.addView(label(act, ver, 11, AikoTheme.EMERALD, false));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView oneBtn = pill(act, d, "更新", true);
            oneBtn.setOnClickListener(v -> {
                oneBtn.setEnabled(false);
                oneBtn.setText("更新中…");
                host.userScripts().applyUpdate(u, err -> {
                    if (act.isDestroyed()) return;
                    if (err == null || err.isEmpty()) {
                        oneBtn.setText("✓ 已更新");
                        oneBtn.setTextColor(AikoTheme.EMERALD);
                        host.toast("已更新「" + u.name + "」");
                    } else {
                        oneBtn.setEnabled(true);
                        oneBtn.setText("重试");
                        host.toast("更新失败：" + err);
                    }
                });
            });
            row.addView(oneBtn, new LinearLayout.LayoutParams(-2, dp(d, 32)));
            list.addView(row, mb(d, 7));
        }

        TextView allBtn = accentBtn(act, d, "一键全部更新并缓存依赖");
        allBtn.setOnClickListener(v -> {
            allBtn.setEnabled(false);
            allBtn.setText("正在批量更新…");
            applyUpdatesRecursive(host, updates, 0, 0, dialog, onAllDone);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(d, 44));
        alp.topMargin = dp(d, 8);
        panel.addView(allBtn, alp);

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
    }

    private static void applyUpdatesRecursive(Host host, List<UserScripts.UpdateResult> updates, int idx, int ok,
                                              Dialog dialog, Runnable onAllDone) {
        if (idx >= updates.size()) {
            Activity act = host.activity();
            if (act != null && !act.isDestroyed()) {
                if (dialog.isShowing()) dialog.dismiss();
                host.toast("已更新 " + ok + "/" + updates.size() + " 个脚本，刷新网页生效");
                if (onAllDone != null) onAllDone.run();
            }
            return;
        }
        host.userScripts().applyUpdate(updates.get(idx), err ->
                applyUpdatesRecursive(host, updates, idx + 1, (err == null || err.isEmpty()) ? ok + 1 : ok, dialog, onAllDone));
    }

    // =========================================================================
    // 9. Per-Site Custom CSS Editor Sheet
    // =========================================================================

    static void showSiteCssSheet(Host host, String siteHost, Runnable onSaved) {
        Activity act = host.activity();
        if (act == null || act.isFinishing() || act.isDestroyed() || siteHost == null || siteHost.isEmpty()) return;
        AikoTheme.applyTheme(act);
        float d = host.density();

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = AikoTheme.sheetContainer(act, d, "站点自定义 CSS · " + siteHost, dialog::dismiss);

        EditText cssInput = styledInput(act, d,
                "/* 示例 */\n.banner-ad { display: none !important; }\nbody { line-height: 1.75 !important; }",
                host.siteExtras().customCss(siteHost), true);
        cssInput.setTypeface(AikoTheme.MONO);
        cssInput.setMinLines(6);
        cssInput.setMaxLines(10);
        panel.addView(cssInput, mb(d, 10));

        LinearLayout row = hRow(act);
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(d, 44)));

        TextView clearBtn = secondaryBtn(act, d, "清空");
        clearBtn.setOnClickListener(v -> {
            host.siteExtras().setCustomCss(siteHost, "");
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
            host.toast("已清空 " + siteHost + " 自定义 CSS");
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(d, 44), 1f);
        clp.rightMargin = dp(d, 8);
        row.addView(clearBtn, clp);

        TextView saveBtn = accentBtn(act, d, "保存并立即生效");
        saveBtn.setOnClickListener(v -> {
            host.siteExtras().setCustomCss(siteHost, cssInput.getText().toString());
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
            host.toast("已应用站点自定义 CSS");
        });
        row.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(d, 44), 1.6f));

        AikoTheme.presentSheet(dialog, panel, host.displayMode(), d);
    }

    // =========================================================================
    // 10. Read Aloud (Android TextToSpeech) Controller & Player Sheet
    // =========================================================================

    static final class ReadAloudController {
        private static final int MAX_CHUNK_CHARS = 520;
        private final Context appCtx;
        private final Consumer<String> toaster;
        private final Handler main = new Handler(Looper.getMainLooper());
        private TextToSpeech tts;
        private boolean ready;
        private boolean initializing;
        private boolean speaking;
        private boolean paused;
        private float speechRate = 1.0f;
        private final ArrayList<String> chunks = new ArrayList<>();
        private int chunkIndex;
        private String pendingSingleText;
        private String pendingSingleLang;
        private Runnable stateListener;

        ReadAloudController(Context ctx, Consumer<String> toaster) {
            this.appCtx = ctx.getApplicationContext();
            this.toaster = toaster;
        }

        private void ensureInit(Runnable onReady) {
            if (ready && tts != null) {
                if (onReady != null) onReady.run();
                return;
            }
            if (initializing) return;
            initializing = true;
            try {
                tts = new TextToSpeech(appCtx, status -> main.post(() -> {
                    initializing = false;
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        ready = true;
                        tts.setSpeechRate(speechRate);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String utteranceId) {}
                            @Override public void onDone(String utteranceId) {
                                if (utteranceId != null && utteranceId.startsWith("page_")) {
                                    main.post(() -> {
                                        if (speaking && !paused) {
                                            chunkIndex++;
                                            speakCurrentChunk();
                                        }
                                    });
                                }
                            }
                            @Override public void onError(String utteranceId) {
                                main.post(() -> {
                                    speaking = false;
                                    notifyListener();
                                });
                            }
                        });
                        if (pendingSingleText != null) {
                            String t = pendingSingleText, l = pendingSingleLang;
                            pendingSingleText = null;
                            pendingSingleLang = null;
                            speak(t, l);
                        }
                        if (onReady != null) onReady.run();
                    } else {
                        ready = false;
                        if (toaster != null) toaster.accept("系统语音朗读引擎不可用");
                    }
                }));
            } catch (RuntimeException e) {
                initializing = false;
                if (toaster != null) toaster.accept("无法初始化语音朗读引擎");
            }
        }

        void speak(String text, String langCode) {
            if (text == null || text.trim().isEmpty()) return;
            String clean = text.trim();
            if (clean.length() > 1200) clean = clean.substring(0, 1200);
            if (!ready || tts == null) {
                pendingSingleText = clean;
                pendingSingleLang = langCode;
                ensureInit(null);
                return;
            }
            try {
                Locale loc = resolveLocale(langCode, clean);
                int res = tts.setLanguage(loc);
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(speechRate);
                tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "single_" + System.currentTimeMillis());
            } catch (RuntimeException ignored) { }
        }

        void stop() {
            speaking = false;
            paused = false;
            chunks.clear();
            chunkIndex = 0;
            if (tts != null) {
                try { tts.stop(); } catch (RuntimeException ignored) { }
            }
            notifyListener();
        }

        void shutdown() {
            stop();
            stateListener = null;
            if (tts != null) {
                try { tts.shutdown(); } catch (RuntimeException ignored) { }
                tts = null;
                ready = false;
            }
        }

        void showReadAloudSheet(Activity act, WebView web, DisplayModeEngine displayMode, float d) {
            if (act == null || act.isFinishing() || act.isDestroyed()) return;
            if (web == null) {
                if (toaster != null) toaster.accept("请先打开网页后再使用朗读");
                return;
            }
            AikoTheme.applyTheme(act);
            Dialog dialog = new Dialog(act);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel = AikoTheme.sheetContainer(act, d, "网页朗读 (TTS)", dialog::dismiss);

            TextView statusTv = label(act, speaking ? "正在朗读当前网页…" : "正在提取网页正文…", 13, AikoTheme.ACCENT_CYAN, true);
            statusTv.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));
            statusTv.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 14)));
            panel.addView(statusTv, mb(d, 10));

            LinearLayout ctrlRow = hRow(act);
            panel.addView(ctrlRow, mb(d, 10));

            TextView playPauseBtn = accentBtn(act, d, speaking && !paused ? "暂停朗读" : "开始朗读");
            TextView stopBtn = secondaryBtn(act, d, "停止");
            LinearLayout.LayoutParams pplp = new LinearLayout.LayoutParams(0, dp(d, 44), 1.6f);
            pplp.rightMargin = dp(d, 8);
            ctrlRow.addView(playPauseBtn, pplp);
            ctrlRow.addView(stopBtn, new LinearLayout.LayoutParams(0, dp(d, 44), 1f));

            panel.addView(AikoTheme.sectionHeader(act, d, "朗读倍速（0.8x – 2.0x）"));
            float[] speeds = {0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
            FlowTagLayout speedFlow = new FlowTagLayout(act, dp(d, 6), dp(d, 6));
            TextView[] speedPills = new TextView[speeds.length];
            Runnable syncSpeeds = () -> {
                for (int i = 0; i < speeds.length; i++) {
                    stylePill(speedPills[i], d, Math.abs(speechRate - speeds[i]) < 0.05f);
                }
            };
            for (int i = 0; i < speeds.length; i++) {
                final float sp = speeds[i];
                TextView p = pill(act, d, sp + "x", false);
                p.setOnClickListener(v -> {
                    speechRate = sp;
                    if (tts != null) tts.setSpeechRate(sp);
                    syncSpeeds.run();
                    if (speaking && !paused) speakCurrentChunk();
                });
                speedPills[i] = p;
                speedFlow.addView(p);
            }
            syncSpeeds.run();
            panel.addView(speedFlow, new LinearLayout.LayoutParams(-1, -2));

            Runnable syncUi = () -> {
                if (!dialog.isShowing()) return;
                if (speaking && !paused) {
                    playPauseBtn.setText("暂停朗读");
                    statusTv.setText(String.format(Locale.ROOT, "朗读中 · 第 %d / %d 段 · %.2fx",
                            Math.min(chunkIndex + 1, Math.max(1, chunks.size())), Math.max(1, chunks.size()), speechRate));
                } else if (paused && !chunks.isEmpty()) {
                    playPauseBtn.setText("继续朗读");
                    statusTv.setText(String.format(Locale.ROOT, "已暂停 · 第 %d / %d 段",
                            Math.min(chunkIndex + 1, chunks.size()), chunks.size()));
                } else if (!chunks.isEmpty()) {
                    playPauseBtn.setText("开始朗读");
                    statusTv.setText("正文已就绪（共 " + chunks.size() + " 段），点击开始朗读");
                }
            };
            stateListener = syncUi;
            dialog.setOnDismissListener(d1 -> { if (stateListener == syncUi) stateListener = null; });

            playPauseBtn.setOnClickListener(v -> {
                if (chunks.isEmpty()) {
                    extractAndPrepare(web, () -> {
                        if (!chunks.isEmpty()) startOrResumePlayback();
                        else statusTv.setText("当前网页正文内容过少，无法朗读");
                        syncUi.run();
                    });
                    return;
                }
                if (speaking && !paused) {
                    paused = true;
                    if (tts != null) { try { tts.stop(); } catch (RuntimeException ignored) { } }
                    syncUi.run();
                } else {
                    startOrResumePlayback();
                    syncUi.run();
                }
            });

            stopBtn.setOnClickListener(v -> {
                stop();
                statusTv.setText("已停止朗读");
                playPauseBtn.setText("开始朗读");
            });

            AikoTheme.presentSheet(dialog, panel, displayMode, d);
            if (chunks.isEmpty()) {
                extractAndPrepare(web, () -> {
                    if (!dialog.isShowing()) return;
                    if (chunks.isEmpty()) {
                        statusTv.setText("当前网页未提取到可朗读的正文段落");
                        statusTv.setTextColor(AikoTheme.TEXT_MUTED);
                    } else {
                        startOrResumePlayback();
                        syncUi.run();
                    }
                });
            } else {
                syncUi.run();
            }
        }

        private void startOrResumePlayback() {
            ensureInit(() -> {
                if (chunks.isEmpty()) return;
                if (chunkIndex >= chunks.size()) chunkIndex = 0;
                speaking = true;
                paused = false;
                speakCurrentChunk();
                notifyListener();
            });
        }

        private void speakCurrentChunk() {
            if (!ready || tts == null || !speaking || paused) return;
            if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
                speaking = false;
                paused = false;
                chunkIndex = 0;
                notifyListener();
                return;
            }
            String text = chunks.get(chunkIndex);
            try {
                Locale loc = resolveLocale(null, text);
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(speechRate);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "page_" + chunkIndex);
            } catch (RuntimeException ignored) {
                speaking = false;
            }
            notifyListener();
        }

        private void extractAndPrepare(WebView web, Runnable onDone) {
            String extractJs = "(function(){try{"
                    + "var rr=document.getElementById('__aiko_reader_root');"
                    + "if(rr&&rr.shadowRoot){var a=rr.shadowRoot.querySelector('article');if(a&&a.innerText)return a.innerText;}"
                    + "var main=document.querySelector('article,main,[role=\"main\"]')||document.body;"
                    + "return main?(main.innerText||''):'';"
                    + "}catch(e){return '';}})();";
            web.evaluateJavascript(extractJs, raw -> {
                String text = "";
                try {
                    if (raw != null && !"null".equals(raw)) {
                        Object tok = new JSONTokener(raw).nextValue();
                        if (tok instanceof String) text = (String) tok;
                    }
                } catch (Exception ignored) { }
                chunks.clear();
                chunkIndex = 0;
                splitChunks(text, chunks);
                if (onDone != null) onDone.run();
            });
        }

        private static void splitChunks(String raw, List<String> out) {
            if (raw == null) return;
            String normalized = raw.replace('\r', '\n').trim();
            if (normalized.length() < 8) return;
            if (normalized.length() > 40_000) normalized = normalized.substring(0, 40_000);
            String[] paras = normalized.split("\\n+");
            StringBuilder cur = new StringBuilder();
            for (String p : paras) {
                String clean = p.trim().replaceAll("\\s+", " ");
                if (clean.length() < 2) continue;
                int offset = 0;
                while (offset < clean.length()) {
                    int take = Math.min(MAX_CHUNK_CHARS, clean.length() - offset);
                    String piece = clean.substring(offset, offset + take);
                    if (cur.length() + piece.length() + 1 > MAX_CHUNK_CHARS && cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(piece);
                    offset += take;
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }

        private static Locale resolveLocale(String langCode, String sample) {
            if (langCode != null && !langCode.trim().isEmpty()) {
                String low = langCode.trim().toLowerCase(Locale.ROOT);
                if (low.startsWith("zh-tw") || low.startsWith("zh-hk") || low.startsWith("zh-hant")) return Locale.TRADITIONAL_CHINESE;
                if (low.startsWith("zh")) return Locale.SIMPLIFIED_CHINESE;
                if (low.startsWith("en")) return Locale.US;
                if (low.startsWith("ja")) return Locale.JAPANESE;
                if (low.startsWith("ko")) return Locale.KOREAN;
                if (low.startsWith("fr")) return Locale.FRENCH;
                if (low.startsWith("de")) return Locale.GERMAN;
                if (low.startsWith("es")) return new Locale("es", "ES");
                if (low.startsWith("ru")) return new Locale("ru", "RU");
            }
            // Heuristic from sample text
            if (sample != null) {
                int cjk = 0, jp = 0, kr = 0, latin = 0;
                int n = Math.min(sample.length(), 160);
                for (int i = 0; i < n; i++) {
                    char c = sample.charAt(i);
                    if (c >= 0x3040 && c <= 0x30FF) jp++;
                    else if (c >= 0xAC00 && c <= 0xD7AF) kr++;
                    else if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
                    else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
                }
                if (jp > 2) return Locale.JAPANESE;
                if (kr > 2) return Locale.KOREAN;
                if (cjk >= 4) return Locale.SIMPLIFIED_CHINESE;
                if (latin > cjk * 3) return Locale.US;
            }
            return Locale.getDefault();
        }

        private void notifyListener() {
            Runnable r = stateListener;
            if (r != null) main.post(r);
        }
    }

    // =========================================================================
    // Shared Compact Material Builders
    // =========================================================================

    private static int dp(float d, int v) { return AikoTheme.dp(d, v); }

    private static LinearLayout vStack(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    private static LinearLayout hRow(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    private static LinearLayout cardBox(Context ctx, float d) {
        LinearLayout box = vStack(ctx);
        box.setPadding(dp(d, 13), dp(d, 11), dp(d, 13), dp(d, 11));
        box.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 15)));
        return box;
    }

    private static LinearLayout.LayoutParams mb(float d, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(d, bottomDp);
        return lp;
    }

    private static TextView label(Context ctx, String text, float sp, int color, boolean medium) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(AikoTheme.MEDIUM);
        return tv;
    }

    private static TextView pill(Context ctx, float d, String text, boolean selected) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(AikoTheme.MEDIUM);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(d, 11), dp(d, 6), dp(d, 11), dp(d, 6));
        tv.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 12)));
        AikoTheme.attachPressFeedback(tv, 0.95f);
        stylePill(tv, d, selected);
        return tv;
    }

    private static void stylePill(TextView tv, float d, boolean selected) {
        tv.setTextColor(selected ? AikoTheme.ACCENT : AikoTheme.TEXT_MUTED);
        tv.setBackground(AikoTheme.card(
                selected ? AikoTheme.HIGHLIGHT : AikoTheme.PILL,
                selected ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                d, dp(d, 12)));
    }

    private static TextView accentBtn(Context ctx, float d, String text) {
        TextView btn = AikoTheme.badge(ctx, d, text, AikoTheme.onAccentColor(), AikoTheme.ACCENT, 0);
        btn.setTextSize(13);
        btn.setTypeface(AikoTheme.BOLD);
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(d, 14)));
        AikoTheme.attachPressFeedback(btn, 0.96f);
        return btn;
    }

    private static TextView secondaryBtn(Context ctx, float d, String text) {
        TextView btn = AikoTheme.badge(ctx, d, text, AikoTheme.TEXT, AikoTheme.PILL, AikoTheme.STROKE);
        btn.setTextSize(13);
        btn.setTypeface(AikoTheme.MEDIUM);
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 14)));
        AikoTheme.attachPressFeedback(btn, 0.96f);
        return btn;
    }

    private static EditText styledInput(Context ctx, float d, String hint, String value, boolean multiline) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(AikoTheme.TEXT_DIM);
        et.setTextColor(AikoTheme.TEXT);
        et.setTextSize(13);
        et.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 12)));
        if (multiline) {
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setPadding(dp(d, 11), dp(d, 9), dp(d, 11), dp(d, 9));
        } else {
            et.setSingleLine(true);
            et.setGravity(Gravity.CENTER_VERTICAL);
            et.setPadding(dp(d, 11), 0, dp(d, 11), 0);
        }
        if (value != null) et.setText(value);
        return et;
    }

    private static SeekBar themedSeek(Context ctx, int max, int initial, Consumer<Integer> onChange) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(max);
        s.setProgress(Math.max(0, Math.min(max, initial)));
        s.setProgressTintList(ColorStateList.valueOf(AikoTheme.ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(AikoTheme.ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(AikoTheme.PILL));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && onChange != null) onChange.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return s;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String sub,
                                          boolean initial, Consumer<Boolean> onToggle) {
        final boolean[] state = {initial};
        LinearLayout row = hRow(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 10), dp(d, 8), dp(d, 8), dp(d, 8));
        row.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 12)));
        row.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 12)));
        AikoTheme.attachPressFeedback(row, 0.97f);

        LinearLayout col = vStack(ctx);
        col.addView(label(ctx, title, 13, AikoTheme.TEXT, true));
        if (sub != null && !sub.isEmpty()) {
            col.addView(label(ctx, sub, 11, AikoTheme.TEXT_MUTED, false));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.rightMargin = dp(d, 8);
        row.addView(col, clp);

        TextView badge = AikoTheme.badge(ctx, d, initial ? "开启" : "关闭",
                initial ? AikoTheme.onAccentColor() : AikoTheme.TEXT_MUTED,
                initial ? AikoTheme.ACCENT : AikoTheme.CARD,
                initial ? 0 : AikoTheme.STROKE);
        row.addView(badge, new LinearLayout.LayoutParams(dp(d, 52), dp(d, 26)));

        row.setOnClickListener(v -> {
            state[0] = !state[0];
            boolean on = state[0];
            badge.setText(on ? "开启" : "关闭");
            badge.setTextColor(on ? AikoTheme.onAccentColor() : AikoTheme.TEXT_MUTED);
            badge.setBackground(AikoTheme.card(
                    on ? AikoTheme.ACCENT : AikoTheme.CARD,
                    on ? 0 : AikoTheme.STROKE,
                    d, dp(d, 13)));
            if (onToggle != null) onToggle.accept(on);
        });
        return row;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
