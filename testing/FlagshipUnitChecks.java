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
 * Host JVM unit checks for v0.2.0 flagship modules:
 * - HttpUrlUtil (validation, host/origin extraction, security boundaries)
 * - SearchEngineCatalog (built-in engines, custom templates, omnibox resolution)
 * - DisplayModeEngine.selectModeId (resolution lock, HDR preservation, seamless tie-breaker, policies)
 * - FilterRules binary serialization round-trip and match/CSS equivalence
 * - BrowserLibrary Netscape HTML export/import, search, Speed Dial pins, and legacy prefs compatibility
 */
public final class FlagshipUnitChecks {
    public static void main(String[] args) throws Exception {
        checkHttpUrlUtil();
        checkSearchEngineCatalog();
        checkDisplayModeSelection();
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

    private static void checkFilterRulesBinaryRoundTrip(Path filtersDir) throws Exception {
        FilterRules.Builder b = new FilterRules.Builder();
        if (filtersDir != null && Files.isDirectory(filtersDir)) {
            b.suffixes(gzip(filtersDir, "psl"));
            b.read(gzip(filtersDir, "easylist"), "EasyList");
            b.read(gzip(filtersDir, "easyprivacy"), "EasyPrivacy");
            b.read(gzip(filtersDir, "adguard-cn"), "AdGuard 中文");
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
