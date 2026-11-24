package app.aikobrowser;

import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Host JVM unit checks for flagship modules:
 * - HttpUrlUtil (validation, host/origin extraction, security boundaries)
 * - UrlCleaner (global + CN tracking parameter stripping, functional param & fragment preservation)
 * - PerfProfiles (Rampage/Balanced/Eco presets, clamping, built-in protection, summary formatting)
 * - UserScript / UserScriptInput (Greasy Fork .user.js URL & filename detection, safeRemoteAddress encoding)
 * - AikoTheme.Palette (curated presets, 360-deg hue derivation, WCAG AA+ contrast, OLED & hex parsing)
 * - CustomFilterList.normalizeDomainForRule & VideoBooster rate clamping
 * - SearchEngineCatalog (built-in engines, custom templates, omnibox resolution)
 * - DisplayModeEngine.selectModeId (resolution lock, HDR preservation, seamless tie-breaker, policies)
 * - GestureChrome.computeChromeLayout & canAutoHideForScroll (viewport bottom-inset, auto-hide, IME, fullscreen)
 * - FilterRules binary serialization round-trip and match/CSS equivalence (3-list & 7-list v0.3.0)
 * - BrowserLibrary Netscape HTML export/import, search, Speed Dial pins, and legacy prefs compatibility
 */
public final class FlagshipUnitChecks {
    public static void main(String[] args) throws Exception {
        checkHttpUrlUtil();
        checkUrlCleaner();
        checkPerfProfiles();
        checkUserScriptUrls();
        checkAikoThemePalette();
        checkCustomDomainAndVideoBooster();
        checkSearchEngineCatalog();
        checkDisplayModeSelection();
        checkChromeLayoutEngine();
        checkFilterRulesBinaryRoundTrip(args.length > 0 ? Path.of(args[0]) : null);
        checkBrowserLibrary();
    }

    private static void checkHttpUrlUtil() {
        String[] valid = {
                "http://example.com",
                "https://example.com/",
                "HTTPS://WWW.Example.COM:8443/path?a=1#frag",
                "http://127.0.0.1:18769/artifact.bin",
                "http://localhost:8080/test",
                "https://[2001:db8::1]:443/v6",
                "https://sub_1.example-site.co.uk/a/b",
                "https://example.com./trailing-dot"
        };
        for (String u : valid) {
            check(HttpUrlUtil.isHttp(u), "HttpUrlUtil should accept: " + u);
        }

        String[] invalid = {
                null,
                "",
                "http://",
                "https://",
                "http://./",
                "https://../",
                "javascript:alert(1)",
                "about:blank",
                "file:///etc/passwd",
                "mailto:user@example.com",
                "intent://scan/#Intent;end",
                "https://user:pass@example.com/secret",
                "https://user@example.com/",
                "https://example.com:65536/badport",
                "https://example.com:abc/badport",
                "https://example.com/\nnewline",
                "https://example.com/\rcr",
                "https://example.com/\0nul",
                "https://bad host.com/",
                "aiko browser search"
        };
        for (String u : invalid) {
            check(!HttpUrlUtil.isHttp(u), "HttpUrlUtil should reject: " + u);
        }

        check("www.example.com".equals(HttpUrlUtil.extractHost("HTTPS://WWW.Example.COM:8443/p")), "extractHost lowercase + strip port");
        check("example.com".equals(HttpUrlUtil.extractHost("https://example.com./x")), "extractHost strips trailing dot");
        check("[2001:db8::1]".equals(HttpUrlUtil.extractHost("https://[2001:db8::1]:443/a")), "extractHost IPv6");
        check("".equals(HttpUrlUtil.extractHost("javascript:void(0)")), "extractHost invalid -> empty");

        check("https://example.com".equals(HttpUrlUtil.extractOrigin("https://Example.COM:443/path?q=1")), "extractOrigin omits :443");
        check("https://example.com:8443".equals(HttpUrlUtil.extractOrigin("https://example.com:8443/path")), "extractOrigin preserves non-default port");
        check("".equals(HttpUrlUtil.extractOrigin("http://example.com/plain")), "extractOrigin rejects plain http");
        check("".equals(HttpUrlUtil.extractOrigin("https://user@example.com/")), "extractOrigin rejects userinfo");
        System.out.println("PASS HttpUrlUtil validation, host normalization, and HTTPS-only origin extraction");
    }

    private static void checkUrlCleaner() {
        // Global tracking params stripped while preserving functional params & fragment
        String yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123XYZ&t=42s&utm_source=share#comments";
        String ytClean = UrlCleaner.clean(yt);
        check("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s#comments".equals(ytClean),
                "YouTube preserves v & t & fragment, strips si & utm_source: " + ytClean);
        check(UrlCleaner.wasCleaned(yt, ytClean), "wasCleaned true when stripped");

        // Chinese ecosystem tracking params stripped (Bilibili, Taobao, Xiaohongshu, Baidu)
        String bili = "https://www.bilibili.com/video/BV1xx411c7mD/?p=2&spm_id_from=333.1007&vd_source=deadbeef&share_source=copy_web&t=90#reply";
        String biliClean = UrlCleaner.clean(bili);
        check("https://www.bilibili.com/video/BV1xx411c7mD/?p=2&t=90#reply".equals(biliClean),
                "Bilibili preserves p & t & fragment, strips spm_id_from/vd_source/share_source: " + biliClean);

        String tb = "https://item.taobao.com/item.htm?id=123456&spm=a21wu.1&ali_trackid=2:mm&ts=1700000";
        String tbClean = UrlCleaner.clean(tb);
        check("https://item.taobao.com/item.htm?id=123456&ts=1700000".equals(tbClean),
                "Taobao preserves id & ts, strips spm & ali_trackid: " + tbClean);

        // Case-insensitive keys, valueless tracking keys, all-tracking query -> drops '?'
        String allTrack = "https://example.com/article?UTM_SOURCE=x&FBCLID=123&gclid&bd_vid=9#top";
        check("https://example.com/article#top".equals(UrlCleaner.clean(allTrack)),
                "All tracking params dropped cleanly leaving fragment");

        // Leading/middle/trailing tracking params + raw percent-encoding preservation
        String enc = "https://example.com/search?fbclid=drop&q=%E4%B8%AD%E6%96%87+a%26b&utm_campaign=c&s=1&ref=nav";
        check("https://example.com/search?q=%E4%B8%AD%E6%96%87+a%26b&s=1&ref=nav".equals(UrlCleaner.clean(enc)),
                "Preserves raw percent-encoding, q, s, ref");

        // Tracking param name in VALUE or FRAGMENT must NOT be touched; unchanged URL returns exact ref
        String safeVal = "https://example.com/doc?param=utm_source&id=7#?utm_medium=1&fbclid=2";
        String safeValOut = UrlCleaner.clean(safeVal);
        check(safeVal == safeValOut && !UrlCleaner.wasCleaned(safeVal, safeValOut),
                "Unchanged URL preserves exact reference and fragment");

        // Non-HTTP(S) / null / malformed URLs untouched
        check(UrlCleaner.clean(null) == null, "null safe");
        check("javascript:alert(1)?utm_source=x".equals(UrlCleaner.clean("javascript:alert(1)?utm_source=x")),
                "non-HTTP untouched");
        check("file:///sdcard/a.html?utm_source=1".equals(UrlCleaner.clean("file:///sdcard/a.html?utm_source=1")),
                "file:// untouched");
        System.out.println("PASS UrlCleaner global + CN tracking stripping, functional param/encoding/fragment preservation");
    }

    private static void checkPerfProfiles() {
        PerfProfiles.Profile rampage = PerfProfiles.preset(PerfProfiles.ID_RAMPAGE);
        PerfProfiles.Profile balanced = PerfProfiles.preset(PerfProfiles.ID_BALANCED);
        PerfProfiles.Profile eco = PerfProfiles.preset(PerfProfiles.ID_ECO);
        PerfProfiles.Profile fallback = PerfProfiles.preset("unknown-mode");

        check(rampage.builtin && PerfProfiles.ID_RAMPAGE.equals(rampage.id), "rampage preset id/builtin");
        check(rampage.refreshPolicy == DisplayModeEngine.POLICY_MAX && rampage.adpfBoost
                && rampage.maxHotTabs == 10 && rampage.bfCachePages == 5
                && rampage.preloadLevel == PerfProfiles.PRELOAD_AGGRESSIVE
                && rampage.activePreRaster && rampage.downloadLanes == 128 && !rampage.globalUltraScroll,
                "rampage preset hardware levers");

        check(balanced.builtin && PerfProfiles.ID_BALANCED.equals(balanced.id), "balanced preset id/builtin");
        check(balanced.refreshPolicy == DisplayModeEngine.POLICY_MAX && !balanced.adpfBoost
                && balanced.maxHotTabs == 6 && balanced.bfCachePages == 3
                && balanced.preloadLevel == PerfProfiles.PRELOAD_PRECONNECT
                && !balanced.activePreRaster && balanced.downloadLanes == 64,
                "balanced preset hardware levers");

        check(eco.builtin && PerfProfiles.ID_ECO.equals(eco.id), "eco preset id/builtin");
        check(eco.refreshPolicy == DisplayModeEngine.POLICY_POWER && !eco.adpfBoost
                && eco.maxHotTabs == 2 && eco.bfCachePages == 1
                && eco.preloadLevel == PerfProfiles.PRELOAD_OFF
                && !eco.activePreRaster && eco.downloadLanes == 16,
                "eco preset hardware levers");

        check(fallback == balanced, "unknown preset falls back to Balanced");
        check(PerfProfiles.isBuiltinId("rampage") && PerfProfiles.isBuiltinId("balanced")
                && PerfProfiles.isBuiltinId("eco") && !PerfProfiles.isBuiltinId("custom-1"),
                "isBuiltinId exact check");

        // Clamping & sanitization (extremes, control chars, built-in ID collision protection)
        PerfProfiles.Profile low = PerfProfiles.sanitized(
                "rampage", "\u0000\t  ", false, 99, true, -5, -10, -3, true, 0, true);
        check(!low.id.equals("rampage") && low.id.startsWith("custom-"), "non-builtin cannot steal builtin id");
        check("自定义模式".equals(low.name), "blank/control name falls back to default");
        check(low.refreshPolicy == DisplayModeEngine.POLICY_MAX, "unknown policy clamps to POLICY_MAX");
        check(low.maxHotTabs == PerfProfiles.MIN_HOT_TABS, "hotTabs min clamp");
        check(low.bfCachePages == PerfProfiles.MIN_BFCACHE_PAGES, "bfCache min clamp");
        check(low.preloadLevel == PerfProfiles.PRELOAD_OFF, "preload min clamp");
        check(low.downloadLanes == PerfProfiles.MIN_DOWNLOAD_LANES, "lanes min clamp");
        check(low.globalUltraScroll && low.adpfBoost && low.activePreRaster, "booleans preserved");

        PerfProfiles.Profile high = PerfProfiles.sanitized(
                "custom-hi", "123456789012345678901234567890\nX", false,
                DisplayModeEngine.POLICY_POWER, false, 999, 99, 99, false, 9999, false);
        check("custom-hi".equals(high.id), "valid custom id kept");
        check(high.name.length() == 24, "name truncated to 24 chars without control chars");
        check(high.refreshPolicy == DisplayModeEngine.POLICY_POWER, "POLICY_POWER kept");
        check(high.maxHotTabs == PerfProfiles.MAX_HOT_TABS, "hotTabs max clamp");
        check(high.bfCachePages == PerfProfiles.MAX_BFCACHE_PAGES, "bfCache max clamp");
        check(high.preloadLevel == PerfProfiles.PRELOAD_AGGRESSIVE, "preload max clamp");
        check(high.downloadLanes == PerfProfiles.MAX_DOWNLOAD_LANES, "lanes max clamp");

        check(PerfProfiles.sanitized((PerfProfiles.Profile) null) == balanced, "null sanitized -> balanced");
        String sumR = PerfProfiles.summaryLine(rampage);
        check(sumR.contains("最高刷") && sumR.contains("ADPF") && sumR.contains("10热标签")
                && sumR.contains("5页BFCache") && sumR.contains("128路"), "rampage summaryLine: " + sumR);
        String sumLow = PerfProfiles.summaryLine(low);
        check(sumLow.contains("无BFCache") && sumLow.contains("全局抗卡盾"), "low summaryLine: " + sumLow);
        System.out.println("PASS PerfProfiles presets (Rampage/Balanced/Eco), clamping, built-in protection, and summaries");
    }

    private static void checkUserScriptUrls() {
        String[] accepted = {
                "https://update.greasyfork.org/scripts/12345/Demo%20Script.user.js",
                "https://greasyfork.org/scripts/12345-demo/code/Demo.user.js?version=999",
                "https://sleazyfork.org/scripts/1/code/Test.user.js#install",
                "https://openuserjs.org/install/user/Demo.user.js",
                "https://scriptcat.org/scripts/code/42/Cat.user.js",
                "https://raw.githubusercontent.com/user/repo/main/helper.user.js",
                "https://github.com/user/repo/raw/main/helper.user.js",
                "https://cdn.example.com/a.USER.JS",
                "https://update.greasyfork.org/scripts/42/测试脚本 [v2].user.js",
                "http://127.0.0.1:8080/local.user.js"
        };
        for (String u : accepted) {
            check(UserScript.isUserScriptUrl(u), "isUserScriptUrl should accept: " + u);
            check(UserScriptInput.safeRemoteAddress(u) != null, "safeRemoteAddress parity for: " + u);
        }

        String[] rejected = {
                null,
                "",
                "https://greasyfork.org/zh-CN/scripts/12345-demo",
                "https://greasyfork.org/zh-CN/scripts/12345-demo/code",
                "https://github.com/user/repo/blob/main/helper.user.js",
                "https://github.com/user/repo/tree/main/helper.user.js",
                "https://gitlab.com/user/repo/-/blob/main/helper.user.js",
                "https://gitee.com/user/repo/blob/master/helper.user.js",
                "https://codeberg.org/user/repo/blame/branch/main/helper.user.js",
                "https://example.com/.user.js",
                "https://example.com/app.js",
                "https://example.com/demo.user.js.html",
                "https://example.com/demo.user.js/view",
                "https://example.com/download?file=demo.user.js",
                "javascript:alert('a.user.js')",
                "file:///sdcard/Download/demo.user.js",
                "https://user:pass@example.com/demo.user.js"
        };
        for (String u : rejected) {
            check(!UserScript.isUserScriptUrl(u), "isUserScriptUrl should reject: " + u);
        }

        // Filename detection (Content-Disposition / guessed filename)
        check(UserScript.isUserScriptFilename("demo.user.js"), "plain filename");
        check(UserScript.isUserScriptFilename("\"My Script.USER.JS\""), "quoted uppercase filename");
        check(!UserScript.isUserScriptFilename(".user.js"), "empty basename rejected");
        check(!UserScript.isUserScriptFilename("bundle.js"), "normal js rejected");
        check(!UserScript.isUserScriptFilename(null), "null filename rejected");
        System.out.println("PASS UserScript.isUserScriptUrl, isUserScriptFilename, and safeRemoteAddress parity");
    }

    private static void checkAikoThemePalette() {
        // Verify all built-in curated presets & OLED mode
        String[] presets = {
                AikoTheme.MODE_OBSIDIAN, AikoTheme.MODE_EMERALD, AikoTheme.MODE_SAKURA,
                AikoTheme.MODE_AMBER, AikoTheme.MODE_VIOLET, AikoTheme.MODE_OLED
        };
        for (String mode : presets) {
            AikoTheme.Palette p = AikoTheme.Palette.preset(mode);
            verifyPaletteContrast(p, "preset:" + mode);
        }
        check(AikoTheme.Palette.OLED.bg == 0xFF000000, "OLED preset has true #000000 canvas");

        // Full 360-degree hue sweep across multiple surface tints (including extreme 0.0 and 1.5)
        float[] tints = {0.0f, 0.5f, 1.0f, 1.5f, Float.NaN, -1.0f, 5.0f};
        for (int h = 0; h < 360; h += 15) {
            int seed = AikoTheme.Palette.hsvToRgb(h, 0.85f, 0.90f);
            for (float tint : tints) {
                AikoTheme.Palette p = AikoTheme.Palette.derive(seed, tint, false);
                verifyPaletteContrast(p, "hue=" + h + ",tint=" + tint);
            }
        }

        // Extreme seeds: pure black, pure white, deep navy, neon yellow, pure red, neutral gray
        int[] extremes = {0xFF000000, 0xFFFFFFFF, 0xFF050A30, 0xFFFFFF00, 0xFFFF0000, 0xFF808080, 0xFF003300};
        for (int seed : extremes) {
            AikoTheme.Palette p = AikoTheme.Palette.derive(seed, 1.0f, false);
            verifyPaletteContrast(p, "extreme:" + AikoTheme.Palette.formatHex(seed));
        }

        // Hex parsing & formatting (including #FFFFFF sentinel check!)
        check(AikoTheme.Palette.parseHex("#FFFFFF", 0) == 0xFFFFFFFF, "#FFFFFF parses cleanly with 0 sentinel");
        check(AikoTheme.Palette.parseHex("#7C9BFF", 0) == 0xFF7C9BFF, "#7C9BFF parse");
        check(AikoTheme.Palette.parseHex("34d399", 0) == 0xFF34D399, "bare lowercase hex parse");
        check(AikoTheme.Palette.parseHex("invalid", 0) == 0, "invalid hex returns fallback");
        check("#7C9BFF".equals(AikoTheme.Palette.formatHex(0xFF7C9BFF)), "formatHex round-trip");
        System.out.println("PASS AikoTheme.Palette presets, 360-deg WCAG AA+ contrast safety, OLED, and hex parsing");
    }

    private static void verifyPaletteContrast(AikoTheme.Palette p, String label) {
        check((p.bg >>> 24) == 0xFF && (p.surface >>> 24) == 0xFF && (p.card >>> 24) == 0xFF
                && (p.accent >>> 24) == 0xFF && (p.accentCyan >>> 24) == 0xFF, label + " opaque ARGB");
        check(AikoTheme.Palette.contrastRatio(AikoTheme.TEXT, p.bg) >= 12.0, label + " TEXT on BG >= 12:1");
        check(AikoTheme.Palette.contrastRatio(AikoTheme.TEXT, p.highlight) >= 7.0, label + " TEXT on HIGHLIGHT >= 7:1");
        check(AikoTheme.Palette.contrastRatio(AikoTheme.TEXT_MUTED, p.card) >= 4.5, label + " TEXT_MUTED on CARD >= 4.5:1");
        check(AikoTheme.Palette.contrastRatio(p.accent, p.card) >= 5.0, label + " accent on CARD >= 5.0:1");
        check(AikoTheme.Palette.contrastRatio(p.accentCyan, p.card) >= 5.0, label + " accentCyan on CARD >= 5.0:1");
        int ink = AikoTheme.Palette.onAccentInk(p.accent);
        check(AikoTheme.Palette.contrastRatio(ink, p.accent) >= 4.5, label + " onAccentInk >= 4.5:1");
    }

    private static void checkCustomDomainAndVideoBooster() {
        check("ads.example.com".equals(CustomFilterList.normalizeDomainForRule("https://Ads.Example.com:8443/x")), "normalize URL");
        check("example.com".equals(CustomFilterList.normalizeDomainForRule("||example.com^")), "normalize ABP block");
        check("example.com".equals(CustomFilterList.normalizeDomainForRule("@@||example.com^")), "normalize ABP allow");
        check("example.com".equals(CustomFilterList.normalizeDomainForRule("*.example.com")), "normalize wildcard");
        check(CustomFilterList.normalizeDomainForRule("javascript:alert(1)") == null, "reject javascript:");
        check(CustomFilterList.normalizeDomainForRule("user:pass@example.com") == null, "reject userinfo");
        check(CustomFilterList.normalizeDomainForRule("localhost") == null, "reject single label");
        check(CustomFilterList.normalizeDomainForRule("-bad.com") == null, "reject leading hyphen");

        check(VideoBooster.clampRate(Float.NaN) == 1.0f, "VideoBooster NaN -> 1.0");
        check(VideoBooster.clampRate(0.05f) == VideoBooster.MIN_RATE, "VideoBooster min clamp");
        check(VideoBooster.clampRate(20.0f) == VideoBooster.MAX_RATE, "VideoBooster max clamp");
        check(VideoBooster.setRateJs(2.0f).contains("2.00") && VideoBooster.setRateJs(1.0f).contains("active=false"),
                "VideoBooster JS active/idle teardown");
        System.out.println("PASS CustomFilterList.normalizeDomainForRule and VideoBooster clamping/teardown");
    }

    private static void checkSearchEngineCatalog() {
        String[] ids = SearchEngineCatalog.engineIds();
        check(ids.length == 8, "8 built-in search engine entries");
        for (String id : ids) {
            check(SearchEngineCatalog.isKnownId(id), "known id: " + id);
            check(!SearchEngineCatalog.engineName(id).isEmpty(), "non-empty name: " + id);
            if (!SearchEngineCatalog.CUSTOM_ID.equals(id)) {
                String built = SearchEngineCatalog.buildSearchUrl(id, null, "aiko 185hz");
                check(HttpUrlUtil.isHttp(built) && built.contains("aiko+185hz"), "valid query URL for " + id + ": " + built);
                check(SearchEngineCatalog.searchOrigin(id, null).startsWith("https://"), "https origin for " + id);
            }
        }
        String customPct = SearchEngineCatalog.buildSearchUrl("custom", "https://search.example/q?term=%s&lang=zh", "hello world");
        check("https://search.example/q?term=hello+world&lang=zh".equals(customPct), "custom %s template");
        String customBraces = SearchEngineCatalog.buildSearchUrl("custom", "https://search.example/?q={searchTerms}", "a+b");
        check("https://search.example/?q=a%2Bb".equals(customBraces), "custom {searchTerms} template");
        String badCustom = SearchEngineCatalog.buildSearchUrl("custom", "javascript:alert(%s)", "x");
        check(badCustom.startsWith("https://www.google.com/search?q="), "unsafe custom template falls back to Google");

        check("https://linux.do/latest".equals(SearchEngineCatalog.resolveInput("bing", null, "https://linux.do/latest")), "direct https URL");
        check("https://linux.do/t/1".equals(SearchEngineCatalog.resolveInput("bing", null, "linux.do/t/1")), "bare domain promoted to https");
        check("https://localhost:8080/a".equals(SearchEngineCatalog.resolveInput("bing", null, "localhost:8080/a")), "localhost promoted to https");
        check(SearchEngineCatalog.resolveInput("bing", null, "android 185hz webview").startsWith("https://www.bing.com/search?q="), "multi-word input searches");
        System.out.println("PASS SearchEngineCatalog all engines, custom templates, fallback, and omnibox resolution");
    }

    private static void checkDisplayModeSelection() {
        // 1. Flagship 185Hz phone with 2 resolutions (1440x3200 @ 60/120/165/185Hz, plus 1080x2400 @ 240Hz)
        int[] ids = {1, 2, 3, 4, 5, 6};
        int[] ws = {1440, 1440, 1440, 1440, 1080, 1440};
        int[] hs = {3200, 3200, 3200, 3200, 2400, 3200};
        float[] hz = {60f, 120f, 165f, 185f, 240f, 185f};
        float[] alt = {60f, 120f, 165f, 185f};
        int[] hdr = {3, 3, 3, 2, 3, 3}; // mode 4 has lower HDR count than curHdr=3; mode 6 preserves HDR=3

        // Resolution lock + HDR preservation: must pick mode 6 (1440x3200 @ 185Hz, HDR=3), NOT mode 5 (1080p 240Hz) or mode 4 (HDR=2)
        int pickedMax = DisplayModeEngine.selectModeId(1, 1440, 3200, 60f, ids, ws, hs, hz, alt, hdr, 3, DisplayModeEngine.POLICY_MAX);
        check(pickedMax == 6, "POLICY_MAX preserves resolution & HDR, expected 6 got " + pickedMax);

        // POLICY_AUTO always returns 0 (defer to OS LTPO / ARR)
        int pickedAuto = DisplayModeEngine.selectModeId(1, 1440, 3200, 60f, ids, ws, hs, hz, alt, hdr, 3, DisplayModeEngine.POLICY_AUTO);
        check(pickedAuto == 0, "POLICY_AUTO returns 0, got " + pickedAuto);

        // POLICY_POWER picks lowest >= 60Hz (ignoring 48Hz)
        int[] pIds = {10, 11, 12};
        int[] pW = {1440, 1440, 1440};
        int[] pH = {3200, 3200, 3200};
        float[] pHz = {48f, 60f, 120f};
        int pickedPower = DisplayModeEngine.selectModeId(12, 1440, 3200, 120f, pIds, pW, pH, pHz, null, null, 0, DisplayModeEngine.POLICY_POWER);
        check(pickedPower == 11, "POLICY_POWER picks lowest >= 60Hz (11), got " + pickedPower);

        // Seamless tie-breaker: two modes at 120Hz, mode 21 is in altRates (120.0f) while mode 20 is non-seamless
        int[] sIds = {20, 21};
        int[] sW = {1080, 1080};
        int[] sH = {2400, 2400};
        float[] sHz = {119.995f, 120.00f};
        float[] sAlt = {120.00f};
        int pickedSeamless = DisplayModeEngine.selectModeId(1, 1080, 2400, 60f, sIds, sW, sH, sHz, sAlt, null, 0, DisplayModeEngine.POLICY_MAX);
        check(pickedSeamless == 21, "Seamless altRates tie-breaker expected 21 got " + pickedSeamless);

        // Safety on null/empty inputs
        check(DisplayModeEngine.selectModeId(1, 0, 0, 60f, null, null, null, null, null, null, 0, DisplayModeEngine.POLICY_MAX) == 0,
                "invalid dimensions/arrays safely return 0");
        System.out.println("PASS DisplayModeEngine.selectModeId resolution lock, HDR preservation, seamless tie-breaker, policies");
    }

    private static void checkChromeLayoutEngine() {
        final int nav = 144;    // 3-button nav bar @ 3x (48dp)
        final int deckH = 171;  // 56dp controls + 1dp hairline @ 3x
        final int findH = 327;  // controls + 52dp findBar @ 3x
        final int ime = 900;    // 300dp soft keyboard @ 3x
        final int gap = 18;     // 6dp omnibox overlay gap @ 3x

        // 1. Docked webpage: viewport ends strictly at top edge of bottomDeck (ZERO overlap)
        GestureChrome.ChromeLayout docked = GestureChrome.computeChromeLayout(
                false, false, false, false, false, false, false, nav, 0, deckH, gap);
        assertLayout(docked, true, nav, 0, deckH + nav, 0, "docked webpage (3-button nav)");

        // Docked webpage on API 29 (fitsSystemWindows on root -> insetBottom=0)
        GestureChrome.ChromeLayout dockedApi29 = GestureChrome.computeChromeLayout(
                false, false, false, false, false, false, false, 0, 0, deckH, gap);
        assertLayout(dockedApi29, true, 0, 0, deckH, 0, "docked webpage (API 29)");

        // 2. Auto-hidden webpage: deck slid fully offscreen, viewport expands to nav inset (never behind 3-button nav)
        GestureChrome.ChromeLayout hidden = GestureChrome.computeChromeLayout(
                false, false, false, false, true, false, false, nav, 0, deckH, gap);
        assertLayout(hidden, true, nav, deckH + nav, nav, 0, "auto-hidden webpage");

        // 3. Auto-hide settling reveal: keeps viewport at nav during 160ms GPU slide-in (zero mid-fling relayout)
        GestureChrome.ChromeLayout settling = GestureChrome.computeChromeLayout(
                false, false, false, false, false, true, false, nav, 0, deckH, gap);
        assertLayout(settling, true, nav, 0, nav, 0, "auto-hide settling show");

        // 4. WebView IME open (API 30+): hides deck and sizes viewport strictly above IME
        GestureChrome.ChromeLayout webIme30 = GestureChrome.computeChromeLayout(
                false, false, false, false, true, false, false, nav, ime, deckH, gap);
        assertLayout(webIme30, false, 0, 0, ime, 0, "WebView IME open (API 30+)");

        // WebView IME open (API 29 legacy adjustResize): hides deck, margin=0 (root already resized above IME)
        GestureChrome.ChromeLayout webIme29 = GestureChrome.computeChromeLayout(
                false, false, false, false, false, false, true, 0, 0, deckH, gap);
        assertLayout(webIme29, false, 0, 0, 0, 0, "WebView IME open (API 29 legacy)");

        // imeBottom == insetBottom (IME closed, only nav bar reported) must stay docked
        GestureChrome.ChromeLayout imeEqualNav = GestureChrome.computeChromeLayout(
                false, false, false, false, false, false, false, nav, nav, deckH, gap);
        assertLayout(imeEqualNav, true, nav, 0, deckH + nav, 0, "imeBottom == insetBottom stays docked");

        // 5. Omnibox editing with IME: floats deck above IME, keeps viewport steady (zero WebView reflow), overlay above deck
        GestureChrome.ChromeLayout omniIme = GestureChrome.computeChromeLayout(
                false, false, true, true, true, false, true, nav, ime, deckH, gap);
        assertLayout(omniIme, true, 0, -ime, deckH + nav, ime + deckH + gap, "omnibox editing with IME");

        // Omnibox editing without IME (hardware keyboard / IME still opening)
        GestureChrome.ChromeLayout omniNoIme = GestureChrome.computeChromeLayout(
                false, true, true, false, false, false, false, nav, 0, deckH, gap);
        assertLayout(omniNoIme, true, nav, 0, deckH + nav, nav + deckH + gap, "omnibox editing without IME");

        // 6. Find-in-Page open (with & without IME): viewport strictly above expanded deck + IME
        GestureChrome.ChromeLayout findIme = GestureChrome.computeChromeLayout(
                false, false, false, true, true, false, false, nav, ime, findH, gap);
        assertLayout(findIme, true, 0, -ime, ime + findH, 0, "find-in-page with IME");

        GestureChrome.ChromeLayout findNoIme = GestureChrome.computeChromeLayout(
                false, false, false, true, false, false, false, nav, 0, findH, gap);
        assertLayout(findNoIme, true, nav, 0, nav + findH, 0, "find-in-page without IME");

        // 7. Home Start Page: deck visible, never auto-hidden, viewport clears visible deck (or IME if larger)
        GestureChrome.ChromeLayout home = GestureChrome.computeChromeLayout(
                false, true, false, false, true, false, true, nav, 0, deckH, gap);
        assertLayout(home, true, nav, 0, deckH + nav, 0, "Home start page");

        GestureChrome.ChromeLayout homeIme = GestureChrome.computeChromeLayout(
                false, true, false, false, false, false, false, nav, ime, deckH, gap);
        assertLayout(homeIme, true, nav, 0, ime, 0, "Home start page with IME");

        // 8. Fullscreen video overrides all other states -> true edge-to-edge (margin=0, deck hidden)
        GestureChrome.ChromeLayout fs = GestureChrome.computeChromeLayout(
                true, true, true, true, true, true, true, nav, ime, deckH, gap);
        assertLayout(fs, false, 0, 0, 0, 0, "fullscreen video highest priority");

        // 9. Negative inputs safely clamped to zero
        GestureChrome.ChromeLayout neg = GestureChrome.computeChromeLayout(
                false, false, false, false, false, false, false, -10, -50, -20, -5);
        assertLayout(neg, true, 0, 0, 0, 0, "negative dimensions clamped safely");

        // 10. Short-page auto-hide oscillation guard
        int minExtra = 288; // 96dp @ 3x
        check(!GestureChrome.canAutoHideForScroll(0, deckH, minExtra), "non-scrollable page cannot auto-hide");
        check(!GestureChrome.canAutoHideForScroll(deckH + minExtra, deckH, minExtra), "exact boundary cannot auto-hide");
        check(GestureChrome.canAutoHideForScroll(deckH + minExtra + 1, deckH, minExtra), "tall page allows auto-hide");
        check(!GestureChrome.canAutoHideForScroll(0, -10, -10), "negative guard inputs safely return false");

        System.out.println("PASS GestureChrome.computeChromeLayout (8 states, IME/nav/fullscreen/Home/clamping) & canAutoHideForScroll");
    }

    private static void assertLayout(GestureChrome.ChromeLayout actual, boolean deckVisible, int deckPad,
                                     int deckTransY, int vpMargin, int omniMargin, String label) {
        check(actual.deckVisible == deckVisible, label + ": deckVisible expected " + deckVisible + " got " + actual.deckVisible);
        check(actual.deckPaddingBottom == deckPad, label + ": deckPaddingBottom expected " + deckPad + " got " + actual.deckPaddingBottom);
        check(actual.deckTranslationY == deckTransY, label + ": deckTranslationY expected " + deckTransY + " got " + actual.deckTranslationY);
        check(actual.viewportBottomMargin == vpMargin, label + ": viewportBottomMargin expected " + vpMargin + " got " + actual.viewportBottomMargin);
        check(actual.omniboxBottomMargin == omniMargin, label + ": omniboxBottomMargin expected " + omniMargin + " got " + actual.omniboxBottomMargin);
    }

    private static void checkFilterRulesBinaryRoundTrip(Path filtersDir) throws Exception {
        FilterRules.Builder b = new FilterRules.Builder();
        if (filtersDir != null && Files.isDirectory(filtersDir)) {
            b.suffixes(gzip(filtersDir, "psl"));
            String[] allLists = {
                    "adguard-cn", "cjx-annoyance", "adguard-mobile", "adguard-mobile-app",
                    "easylist", "easyprivacy", "adguard-cookies"
            };
            for (String id : allLists) {
                if (Files.isRegularFile(filtersDir.resolve(id + ".txt.gz"))) {
                    b.read(gzip(filtersDir, id), id);
                }
            }
        } else {
            b.suffixes(new StringReader("com\ncn\ncom.cn\n*.ck\n!www.ck\n"));
            b.read(new StringReader("||ads.example^\n@@||ads.example/keep.js$script\nexample.com##.ad\n"), "Custom");
        }
        b.read(new StringReader("||custom-ad.example^\nexample.com##.custom-banner\n"), "自定义规则");
        FilterRules orig = b.build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 1024);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(baos, 32768))) {
            orig.writeBinary(out);
        }
        byte[] blob = baos.toByteArray();
        FilterRules loaded;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(blob), 32768))) {
            loaded = FilterRules.readBinary(in);
        }

        check(loaded.networkCount == orig.networkCount && loaded.cosmeticCount == orig.cosmeticCount,
                "binary round-trip counts match");
        String[][] probes = {
                {"janitorai.com", "ad.doubleclick.net", "https://ad.doubleclick.net/ddm/a.js", "1"},
                {"janitorai.com", "www.google-analytics.com", "https://www.google-analytics.com/analytics.js", "1"},
                {"janitorai.com", "janitorai.com", "https://janitorai.com/api/chats", "8"},
                {"linux.do", "qzs.gdtimg.com", "https://qzs.gdtimg.com/sdk.js", "1"},
                {"example.com", "custom-ad.example", "https://custom-ad.example/x.js", "1"},
                {"www.youtube.com", "r1.googlevideo.com", "https://r1.googlevideo.com/videoplayback", "16"}
        };
        for (String[] p : probes) {
            String keyOrig = orig.siteKey(p[0]);
            String keyLoaded = loaded.siteKey(p[0]);
            check(keyOrig.equals(keyLoaded), "siteKey mismatch for " + p[0]);
            FilterRules.Rule r1 = orig.blockingRule(p[0], keyOrig, p[1], p[2], Integer.parseInt(p[3]), "cross-site");
            FilterRules.Rule r2 = loaded.blockingRule(p[0], keyLoaded, p[1], p[2], Integer.parseInt(p[3]), "cross-site");
            check((r1 == null) == (r2 == null), "block verdict mismatch for " + p[2]);
            if (r1 != null) {
                check(r1.source.equals(r2.source) && orig.description(r1).equals(loaded.description(r2)),
                        "source/rule attribution mismatch for " + p[2]);
            }
        }
        check(orig.css("www.youtube.com", "https://www.youtube.com/").equals(
                loaded.css("www.youtube.com", "https://www.youtube.com/")), "YouTube CSS identical after binary round-trip");
        check(orig.css("example.com", "https://example.com/").equals(
                loaded.css("example.com", "https://example.com/")), "Custom CSS identical after binary round-trip");

        // Corrupt magic must throw IOException cleanly (never crash with OOM/NPE)
        boolean rejectedCorrupt = false;
        try (DataInputStream bad = new DataInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}))) {
            FilterRules.readBinary(bad);
        } catch (java.io.IOException expected) {
            rejectedCorrupt = true;
        }
        check(rejectedCorrupt, "corrupt binary cache safely rejected with IOException");
        System.out.printf("PASS FilterRules binary cache round-trip (%,d bytes), equivalence, and corruption guard%n", blob.length);
    }

    private static void checkBrowserLibrary() throws Exception {
        Map<String, String> store = new ConcurrentHashMap<>();
        // Seed legacy v0.1.9 bookmarks & history JSON schema to verify 100% upgrade compatibility
        store.put("bookmarks", "[[\"https://linux.do/\",\"Linux.do & Community\"]]");
        store.put("history", "[[\"https://github.com/UndergroundAlzar/Aiko-Browser\",\"Aiko Browser Repo\"]]");

        AtomicReference<CountDownLatch> applyLatch = new AtomicReference<>(new CountDownLatch(0));
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, values) -> {
                    String name = method.getName();
                    if ("getString".equals(name)) return store.getOrDefault(values[0], (String) values[1]);
                    if ("contains".equals(name)) return store.containsKey(values[0]);
                    if ("edit".equals(name)) {
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editor, action, params) -> {
                                    String op = action.getName();
                                    if ("putString".equals(op)) {
                                        store.put((String) params[0], (String) params[1]);
                                        return editor;
                                    }
                                    if ("remove".equals(op)) {
                                        store.remove(params[0]);
                                        return editor;
                                    }
                                    if ("apply".equals(op)) {
                                        applyLatch.get().countDown();
                                        return null;
                                    }
                                    if ("commit".equals(op)) {
                                        applyLatch.get().countDown();
                                        return true;
                                    }
                                    return editor;
                                });
                    }
                    throw new AssertionError("Unexpected SharedPreferences call: " + name);
                });

        BrowserLibrary lib = new BrowserLibrary(prefs);
        check(lib.isBookmarked("https://linux.do/"), "legacy v0.1.9 bookmark loaded");
        check(lib.pages(false).size() == 1, "legacy v0.1.9 history loaded");

        // Search across bookmarks + history (multi-term)
        List<BrowserLibrary.Entry> hits = lib.search("aiko repo", 10);
        check(hits.size() == 1 && hits.get(0).url.contains("Aiko-Browser"), "multi-term history search");

        // Export -> Import Netscape HTML round-trip + entity unescaping + javascript: rejection + dedup
        String exported = lib.exportBookmarksHtml();
        check(exported.contains("NETSCAPE-Bookmark-file-1") && exported.contains("Linux.do &amp; Community"),
                "Netscape HTML export escapes entities");

        CountDownLatch importLatch = new CountDownLatch(1);
        applyLatch.set(importLatch);
        String importSample = exported
                + "<DT><A HREF=\"https://janitorai.com/?a=1&amp;b=2\"><b>Janitor</b> &quot;AI&quot;</A>\n"
                + "<DT><A HREF=\"javascript:alert(1)\">XSS</A>\n";
        int added = lib.importBookmarksHtml(importSample);
        check(added == 1, "imported exactly 1 new valid bookmark (deduped existing, rejected javascript:), got " + added);
        check(importLatch.await(3, TimeUnit.SECONDS), "async bookmark save flushed");

        // Speed Dial pins
        CountDownLatch pinLatch = new CountDownLatch(1);
        applyLatch.set(pinLatch);
        boolean pinned = lib.togglePin("https://janitorai.com/?a=1&b=2", "Janitor AI");
        check(pinned && lib.isPinned("https://janitorai.com/?a=1&b=2"), "Speed Dial pin added");
        check(pinLatch.await(3, TimeUnit.SECONDS), "async pin save flushed");

        // Reopen fresh instance from persisted map to confirm round-trip
        BrowserLibrary reopened = new BrowserLibrary(prefs);
        check(reopened.isBookmarked("https://janitorai.com/?a=1&b=2"), "reopened preserves imported bookmark");
        List<BrowserLibrary.Entry> bm = reopened.pages(true);
        boolean foundTitle = bm.stream().anyMatch(e -> "Janitor \"AI\"".equals(e.title));
        check(foundTitle, "HTML tags stripped & entities unescaped in imported title");
        check(reopened.isPinned("https://janitorai.com/?a=1&b=2"), "reopened preserves Speed Dial pin");
        System.out.println("PASS BrowserLibrary legacy compat, search, Speed Dial pins, and Netscape HTML import/export");
    }

    private static Reader gzip(Path folder, String id) throws Exception {
        return new InputStreamReader(new GZIPInputStream(Files.newInputStream(folder.resolve(id + ".txt.gz"))),
                StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("FAILED: " + label);
    }
}
