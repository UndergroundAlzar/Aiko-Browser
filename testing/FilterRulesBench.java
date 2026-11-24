package app.aikobrowser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Repeatable pure-Java microbenchmark for Aiko Browser's filter engine, CSS lookup,
 * and URL validation hot paths. Runs identically on v0.1.9 baseline and v0.2.0+.
 */
public final class FilterRulesBench {
    private static volatile Object RETAINED;
    private static volatile long SINK;

    private static final class RequestSample {
        final String label;
        final String topHost;
        final String host;
        final String url;
        final int type;
        final String fetchSite;

        RequestSample(String label, String topHost, String host, String url, int type, String fetchSite) {
            this.label = label;
            this.topHost = topHost;
            this.host = host;
            this.url = url;
            this.type = type;
            this.fetchSite = fetchSite;
        }
    }

    // 20-URL realistic workload exercising hot suffixes/tokens (.min.js, player.js,
    // desktop_polymer.js, .com/, .gif?, .php?), blocked trackers/ads, and clean assets.
    private static final RequestSample[] WORKLOAD = new RequestSample[]{
            new RequestSample("clean:.min.js", "github.com", "cdn.jsdelivr.net",
                    "https://cdn.jsdelivr.net/npm/react@18/umd/react.production.min.js", 1, "cross-site"),
            new RequestSample("clean:player.js", "www.youtube.com", "www.youtube.com",
                    "https://www.youtube.com/s/player/a1b2c3d4/player.js", 1, "same-origin"),
            new RequestSample("clean:desktop_polymer.js", "www.youtube.com", "www.youtube.com",
                    "https://www.youtube.com/s/desktop/12345678/jsbin/desktop_polymer.vflset/desktop_polymer.js", 1, "same-origin"),
            new RequestSample("clean:.com/repo", "github.com", "github.com",
                    "https://github.com/UndergroundAlzar/Aiko-Browser", 0, "same-origin"),
            new RequestSample("clean:.com/api", "janitorai.com", "janitorai.com",
                    "https://janitorai.com/api/chats?limit=20", 8, "same-origin"),
            new RequestSample("clean:.com/cdn-js", "linux.do", "cdn.example.com",
                    "https://cdn.example.com/assets/discourse-bundle.js", 1, "cross-site"),
            new RequestSample("block:.gif?-ga", "linux.do", "www.google-analytics.com",
                    "https://www.google-analytics.com/__utm.gif?utmwv=5&utms=1", 2, "cross-site"),
            new RequestSample("clean:.gif?-spinner", "www.reddit.com", "www.redditstatic.com",
                    "https://www.redditstatic.com/assets/spinner.gif?v=2", 2, "same-site"),
            new RequestSample("block:.php?-ads", "news.example.com", "pagead2.googlesyndication.com",
                    "https://pagead2.googlesyndication.com/pagead/ads.php?client=ca-pub-12345", 1, "cross-site"),
            new RequestSample("clean:.php?-forum", "forum.example.com", "forum.example.com",
                    "https://forum.example.com/index.php?topic=12345&page=2", 0, "same-origin"),
            new RequestSample("block:doubleclick", "janitorai.com", "ad.doubleclick.net",
                    "https://ad.doubleclick.net/ddm/activity/src=12345;type=page", 1, "cross-site"),
            new RequestSample("block:ga-js", "janitorai.com", "www.google-analytics.com",
                    "https://www.google-analytics.com/analytics.js", 1, "cross-site"),
            new RequestSample("block:gtm-js", "x.com", "www.googletagmanager.com",
                    "https://www.googletagmanager.com/gtm.js?id=GTM-ABC123", 1, "cross-site"),
            new RequestSample("block:adsbygoogle", "news.example.com", "pagead2.googlesyndication.com",
                    "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", 1, "cross-site"),
            new RequestSample("block:gdt-cn", "linux.do", "qzs.gdtimg.com",
                    "https://qzs.gdtimg.com/qzone/biz/gdt/lib/sdk.js", 1, "cross-site"),
            new RequestSample("block:baidu-hm", "m.weibo.cn", "hm.baidu.com",
                    "https://hm.baidu.com/hm.js?1234567890abcdef", 1, "cross-site"),
            new RequestSample("block:fb-events", "shop.example.com", "connect.facebook.net",
                    "https://connect.facebook.net/en_US/fbevents.js", 1, "cross-site"),
            new RequestSample("clean:yt-video", "www.youtube.com", "r1.googlevideo.com",
                    "https://r1.googlevideo.com/videoplayback?expire=1700000000&id=o-abc", 16, "cross-site"),
            new RequestSample("clean:yt-thumb", "www.youtube.com", "i.ytimg.com",
                    "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 2, "cross-site"),
            new RequestSample("clean:font-woff2", "github.com", "fonts.gstatic.com",
                    "https://fonts.gstatic.com/s/inter/v13/inter-latin.woff2", 4, "cross-site")
    };

    private static final String[][] CSS_HOSTS = new String[][]{
            {"www.youtube.com", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"},
            {"linux.do", "https://linux.do/t/topic/12345"},
            {"janitorai.com", "https://janitorai.com/"},
            {"x.com", "https://x.com/home"},
            {"github.com", "https://github.com/UndergroundAlzar/Aiko-Browser"},
            {"m.weibo.cn", "https://m.weibo.cn/"}
    };

    private static final String[] VALID_URLS = new String[]{
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://linux.do/t/topic/12345?page=2#post_10",
            "http://127.0.0.1:18769/artifact.bin",
            "https://janitorai.com/api/chats",
            "https://github.com/UndergroundAlzar/Aiko-Browser",
            "http://example.org/path/to/index.php?a=1&b=2",
            "https://sub.domain.co.uk:8443/wiki/Main_Page",
            "https://[2001:db8::1]:443/test"
    };

    private static final String[] INVALID_URLS = new String[]{
            "javascript:void(0)",
            "about:blank",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "mailto:user@example.com",
            "file:///sdcard/Download/test.html",
            "https://user:pass@example.com/secret",
            "aiko browser 185hz flagship",
            ""
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FilterRulesBench <assets/filters dir>");
            System.exit(2);
        }
        Path filtersDir = Path.of(args[0]);

        // 1. Cold compile + retained heap
        long heapBefore = usedHeapAfterGc();
        CompileTiming cold = compileOnce(filtersDir);
        RETAINED = cold.rules;
        long heapAfter = usedHeapAfterGc();
        double retainedMb = Math.max(0L, heapAfter - heapBefore) / (1024.0 * 1024.0);

        System.out.printf(Locale.ROOT,
                "COMPILE_COLD psl=%.2fms read=%.2fms build=%.2fms total=%.2fms retained_heap=%.2fMB network=%d cosmetic=%d skipped=%d%n",
                cold.pslMs, cold.readMs, cold.buildMs, cold.totalMs, retainedMb,
                cold.rules.networkCount, cold.rules.cosmeticCount, cold.rules.skipped);

        // 2. Warm compile (2 warmup + 5 timed)
        for (int i = 0; i < 2; i++) RETAINED = compileOnce(filtersDir).rules;
        double[] wPsl = new double[5], wRead = new double[5], wBuild = new double[5], wTot = new double[5];
        for (int i = 0; i < 5; i++) {
            CompileTiming t = compileOnce(filtersDir);
            RETAINED = t.rules;
            wPsl[i] = t.pslMs;
            wRead[i] = t.readMs;
            wBuild[i] = t.buildMs;
            wTot[i] = t.totalMs;
        }
        System.out.printf(Locale.ROOT,
                "COMPILE_WARM_MEDIAN psl=%.2fms read=%.2fms build=%.2fms total=%.2fms%n",
                median(wPsl), median(wRead), median(wBuild), median(wTot));

        FilterRules live = (FilterRules) RETAINED;
        String[] topKeys = new String[WORKLOAD.length];
        for (int i = 0; i < WORKLOAD.length; i++) topKeys[i] = live.siteKey(WORKLOAD[i].topHost);

        // Print deterministic match table for differential verification
        int blockedCount = 0;
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < WORKLOAD.length; i++) {
            RequestSample s = WORKLOAD[i];
            FilterRules.Rule hit = live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite);
            if (hit != null) blockedCount++;
            String info = hit == null ? "ALLOW" : ("BLOCK[" + hit.source + "] " + live.description(hit));
            sig.append(i).append(':').append(info).append('\n');
        }
        System.out.printf(Locale.ROOT, "WORKLOAD_MATCHES blocked=%d/%d sigHash=0x%08x%n",
                blockedCount, WORKLOAD.length, sig.toString().hashCode());

        // 3. blockingRule() latency benchmark
        final int warmupSweeps = 1_500;
        long acc = 0;
        for (int w = 0; w < warmupSweeps; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                if (live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite) != null) acc++;
            }
        }
        // Batch throughput (no inner nanoTime overhead)
        final int batchSweeps = 5_000;
        long bStart = System.nanoTime();
        for (int w = 0; w < batchSweeps; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                if (live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite) != null) acc++;
            }
        }
        long bElapsed = System.nanoTime() - bStart;
        double batchMeanUs = (bElapsed / 1000.0) / (batchSweeps * (double) WORKLOAD.length);

        // Per-request latency distribution (20 URLs x 2,000 sweeps = 40,000 samples)
        final int sampleSweeps = 2_000;
        long[] allSamples = new long[sampleSweeps * WORKLOAD.length];
        long[][] perUrl = new long[WORKLOAD.length][sampleSweeps];
        int idx = 0;
        for (int w = 0; w < sampleSweeps; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                long t0 = System.nanoTime();
                FilterRules.Rule r = live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite);
                long dt = System.nanoTime() - t0;
                if (r != null) acc++;
                allSamples[idx++] = dt;
                perUrl[i][w] = dt;
            }
        }
        Arrays.sort(allSamples);
        System.out.printf(Locale.ROOT,
                "BLOCKING_RULE_US batch_mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f samples=%d%n",
                batchMeanUs,
                percentile(allSamples, 0.50) / 1000.0,
                percentile(allSamples, 0.95) / 1000.0,
                percentile(allSamples, 0.99) / 1000.0,
                allSamples[allSamples.length - 1] / 1000.0,
                allSamples.length);

        System.out.println("PER_URL_MEDIAN_US:");
        for (int i = 0; i < WORKLOAD.length; i++) {
            Arrays.sort(perUrl[i]);
            FilterRules.Rule hit = live.blockingRule(WORKLOAD[i].topHost, topKeys[i],
                    WORKLOAD[i].host, WORKLOAD[i].url, WORKLOAD[i].type, WORKLOAD[i].fetchSite);
            System.out.printf(Locale.ROOT, "  %-24s p50=%7.3fus p95=%7.3fus hit=%s%n",
                    WORKLOAD[i].label,
                    percentile(perUrl[i], 0.50) / 1000.0,
                    percentile(perUrl[i], 0.95) / 1000.0,
                    hit == null ? "allow" : ("block(" + hit.source + ")"));
        }

        // 4. siteKey() latency
        for (int w = 0; w < 20_000; w++) {
            String k = live.siteKey(WORKLOAD[w % WORKLOAD.length].host);
            if (k != null) acc += k.length();
        }
        long skStart = System.nanoTime();
        final int skOps = 200_000;
        for (int i = 0; i < skOps; i++) {
            String k = live.siteKey(WORKLOAD[i % WORKLOAD.length].host);
            if (k != null) acc += k.length();
        }
        double skNs = (System.nanoTime() - skStart) / (double) skOps;
        System.out.printf(Locale.ROOT, "SITE_KEY_NS mean=%.1fns ops=%d%n", skNs, skOps);

        // 5. css() latency across 6 real hosts
        for (int w = 0; w < 500; w++) {
            for (String[] pair : CSS_HOSTS) acc += live.css(pair[0], pair[1]).length();
        }
        final int cssSweeps = 2_000;
        long[] cssSamples = new long[cssSweeps * CSS_HOSTS.length];
        int cIdx = 0;
        StringBuilder cssSizes = new StringBuilder();
        for (String[] pair : CSS_HOSTS) {
            if (cssSizes.length() > 0) cssSizes.append(", ");
            cssSizes.append(pair[0]).append('=').append(live.css(pair[0], pair[1]).length()).append("ch");
        }
        for (int w = 0; w < cssSweeps; w++) {
            for (String[] pair : CSS_HOSTS) {
                long t0 = System.nanoTime();
                String css = live.css(pair[0], pair[1]);
                long dt = System.nanoTime() - t0;
                acc += css.length();
                cssSamples[cIdx++] = dt;
            }
        }
        Arrays.sort(cssSamples);
        System.out.printf(Locale.ROOT,
                "CSS_LOOKUP_US p50=%.3f p95=%.3f p99=%.3f sizes=[%s]%n",
                percentile(cssSamples, 0.50) / 1000.0,
                percentile(cssSamples, 0.95) / 1000.0,
                percentile(cssSamples, 0.99) / 1000.0,
                cssSizes);

        // 6. Binary cache write/read benchmark (v0.2.0+ via reflection, skipped on v0.1.9)
        benchBinaryCache("BINARY_CACHE", live, topKeys, sig.toString().hashCode());

        // 7. Full v0.3.0 7-list bundled benchmark (when all 7 built-in .txt.gz lists are present)
        String[] allSevenIds = {
                "adguard-cn", "cjx-annoyance", "adguard-mobile", "adguard-mobile-app",
                "easylist", "easyprivacy", "adguard-cookies"
        };
        boolean hasAllSeven = true;
        for (String id : allSevenIds) {
            if (!Files.isRegularFile(filtersDir.resolve(id + ".txt.gz"))) {
                hasAllSeven = false;
                break;
            }
        }
        if (hasAllSeven) {
            acc += benchAllSeven(filtersDir, allSevenIds);
        }

        // 8. URL validation throughput (baseline MainActivity.http() = new URL + RangeDownload.requireHttp,
        //    plus HttpUrlUtil.isHttp() via reflection when present in v0.2.0+)
        benchUrlValidation();

        SINK = acc;
    }

    private static long benchAllSeven(Path filtersDir, String[] listIds) throws Exception {
        RETAINED = null;
        long heapBefore = usedHeapAfterGc();
        CompileTiming cold = compileLists(filtersDir, listIds);
        RETAINED = cold.rules;
        long heapAfter = usedHeapAfterGc();
        double retainedMb = Math.max(0L, heapAfter - heapBefore) / (1024.0 * 1024.0);
        System.out.printf(Locale.ROOT,
                "ALL7_COMPILE_COLD psl=%.2fms read=%.2fms build=%.2fms total=%.2fms retained_heap=%.2fMB network=%d cosmetic=%d skipped=%d%n",
                cold.pslMs, cold.readMs, cold.buildMs, cold.totalMs, retainedMb,
                cold.rules.networkCount, cold.rules.cosmeticCount, cold.rules.skipped);

        for (int i = 0; i < 2; i++) RETAINED = compileLists(filtersDir, listIds).rules;
        double[] wPsl = new double[5], wRead = new double[5], wBuild = new double[5], wTot = new double[5];
        for (int i = 0; i < 5; i++) {
            CompileTiming t = compileLists(filtersDir, listIds);
            RETAINED = t.rules;
            wPsl[i] = t.pslMs;
            wRead[i] = t.readMs;
            wBuild[i] = t.buildMs;
            wTot[i] = t.totalMs;
        }
        System.out.printf(Locale.ROOT,
                "ALL7_COMPILE_WARM_MEDIAN psl=%.2fms read=%.2fms build=%.2fms total=%.2fms%n",
                median(wPsl), median(wRead), median(wBuild), median(wTot));

        FilterRules live = (FilterRules) RETAINED;
        String[] topKeys = new String[WORKLOAD.length];
        for (int i = 0; i < WORKLOAD.length; i++) topKeys[i] = live.siteKey(WORKLOAD[i].topHost);

        int blockedCount = 0;
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < WORKLOAD.length; i++) {
            RequestSample s = WORKLOAD[i];
            FilterRules.Rule hit = live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite);
            if (hit != null) blockedCount++;
            String info = hit == null ? "ALLOW" : ("BLOCK[" + hit.source + "] " + live.description(hit));
            sig.append(i).append(':').append(info).append('\n');
        }
        System.out.printf(Locale.ROOT, "ALL7_WORKLOAD_MATCHES blocked=%d/%d sigHash=0x%08x%n",
                blockedCount, WORKLOAD.length, sig.toString().hashCode());

        long acc = 0;
        for (int w = 0; w < 1_000; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                if (live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite) != null) acc++;
            }
        }
        final int batchSweeps = 5_000;
        long bStart = System.nanoTime();
        for (int w = 0; w < batchSweeps; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                if (live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite) != null) acc++;
            }
        }
        double batchMeanUs = ((System.nanoTime() - bStart) / 1000.0) / (batchSweeps * (double) WORKLOAD.length);

        final int sampleSweeps = 2_000;
        long[] allSamples = new long[sampleSweeps * WORKLOAD.length];
        int idx = 0;
        for (int w = 0; w < sampleSweeps; w++) {
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                long t0 = System.nanoTime();
                FilterRules.Rule r = live.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite);
                long dt = System.nanoTime() - t0;
                if (r != null) acc++;
                allSamples[idx++] = dt;
            }
        }
        Arrays.sort(allSamples);
        System.out.printf(Locale.ROOT,
                "ALL7_BLOCKING_RULE_US batch_mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f samples=%d%n",
                batchMeanUs,
                percentile(allSamples, 0.50) / 1000.0,
                percentile(allSamples, 0.95) / 1000.0,
                percentile(allSamples, 0.99) / 1000.0,
                allSamples[allSamples.length - 1] / 1000.0,
                allSamples.length);

        for (int w = 0; w < 500; w++) {
            for (String[] pair : CSS_HOSTS) acc += live.css(pair[0], pair[1]).length();
        }
        final int cssSweeps = 2_000;
        long[] cssSamples = new long[cssSweeps * CSS_HOSTS.length];
        int cIdx = 0;
        StringBuilder cssSizes = new StringBuilder();
        for (String[] pair : CSS_HOSTS) {
            if (cssSizes.length() > 0) cssSizes.append(", ");
            cssSizes.append(pair[0]).append('=').append(live.css(pair[0], pair[1]).length()).append("ch");
        }
        for (int w = 0; w < cssSweeps; w++) {
            for (String[] pair : CSS_HOSTS) {
                long t0 = System.nanoTime();
                String css = live.css(pair[0], pair[1]);
                long dt = System.nanoTime() - t0;
                acc += css.length();
                cssSamples[cIdx++] = dt;
            }
        }
        Arrays.sort(cssSamples);
        System.out.printf(Locale.ROOT,
                "ALL7_CSS_LOOKUP_US p50=%.3f p95=%.3f p99=%.3f sizes=[%s]%n",
                percentile(cssSamples, 0.50) / 1000.0,
                percentile(cssSamples, 0.95) / 1000.0,
                percentile(cssSamples, 0.99) / 1000.0,
                cssSizes);

        benchBinaryCache("ALL7_BINARY_CACHE", live, topKeys, sig.toString().hashCode());
        return acc;
    }

    private static void benchBinaryCache(String tag, FilterRules live, String[] topKeys, int expectedSigHash) throws Exception {
        Method writeBinary;
        Method readBinary;
        try {
            writeBinary = FilterRules.class.getDeclaredMethod("writeBinary", java.io.DataOutputStream.class);
            readBinary = FilterRules.class.getDeclaredMethod("readBinary", java.io.DataInputStream.class);
        } catch (NoSuchMethodException notPresent) {
            System.out.println(tag + " not_present (baseline v0.1.9)");
            return;
        }
        writeBinary.setAccessible(true);
        readBinary.setAccessible(true);
        Path tmp = Files.createTempFile("aiko-rules-bench-", ".bin");
        try {
            long w0 = System.nanoTime();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 32768))) {
                writeBinary.invoke(live, out);
            }
            double writeMs = (System.nanoTime() - w0) / 1e6;
            long bytes = Files.size(tmp);

            long r0 = System.nanoTime();
            FilterRules coldLoaded;
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(tmp), 32768))) {
                coldLoaded = (FilterRules) readBinary.invoke(null, in);
            }
            double coldReadMs = (System.nanoTime() - r0) / 1e6;

            double[] warmReads = new double[5];
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                try (java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.BufferedInputStream(Files.newInputStream(tmp), 32768))) {
                    coldLoaded = (FilterRules) readBinary.invoke(null, in);
                }
                warmReads[i] = (System.nanoTime() - t0) / 1e6;
            }
            StringBuilder checkSig = new StringBuilder();
            for (int i = 0; i < WORKLOAD.length; i++) {
                RequestSample s = WORKLOAD[i];
                FilterRules.Rule hit = coldLoaded.blockingRule(s.topHost, topKeys[i], s.host, s.url, s.type, s.fetchSite);
                String info = hit == null ? "ALLOW" : ("BLOCK[" + hit.source + "] " + coldLoaded.description(hit));
                checkSig.append(i).append(':').append(info).append('\n');
            }
            int loadedHash = checkSig.toString().hashCode();
            if (loadedHash != expectedSigHash) {
                throw new AssertionError("Binary cache mismatch: expected 0x"
                        + Integer.toHexString(expectedSigHash) + " got 0x" + Integer.toHexString(loadedHash));
            }
            System.out.printf(Locale.ROOT,
                    "%s size=%.2fMB write=%.2fms read_cold=%.2fms read_warm_median=%.2fms sigHash=0x%08x%n",
                    tag, bytes / (1024.0 * 1024.0), writeMs, coldReadMs, median(warmReads), loadedHash);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean legacyHttp(String url) {
        if (url == null) return false;
        try {
            RangeDownload.requireHttp(new URL(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void benchUrlValidation() throws Exception {
        String[] mixed = new String[VALID_URLS.length + INVALID_URLS.length];
        System.arraycopy(VALID_URLS, 0, mixed, 0, VALID_URLS.length);
        System.arraycopy(INVALID_URLS, 0, mixed, VALID_URLS.length, INVALID_URLS.length);

        for (String u : VALID_URLS) {
            if (!legacyHttp(u)) throw new AssertionError("legacyHttp rejected valid: " + u);
        }
        for (String u : INVALID_URLS) {
            if (legacyHttp(u)) throw new AssertionError("legacyHttp accepted invalid: " + u);
        }

        double legValid = measureLegacy(VALID_URLS, 200_000);
        double legInvalid = measureLegacy(INVALID_URLS, 200_000);
        double legMixed = measureLegacy(mixed, 200_000);
        System.out.printf(Locale.ROOT,
                "URL_CHECK_LEGACY_NS valid=%.1fns invalid=%.1fns mixed=%.1fns%n",
                legValid, legInvalid, legMixed);

        try {
            Class<?> util = Class.forName("app.aikobrowser.HttpUrlUtil");
            Method isHttp = util.getMethod("isHttp", String.class);
            for (String u : VALID_URLS) {
                if (!(Boolean) isHttp.invoke(null, u)) throw new AssertionError("HttpUrlUtil rejected valid: " + u);
            }
            for (String u : INVALID_URLS) {
                if ((Boolean) isHttp.invoke(null, u)) throw new AssertionError("HttpUrlUtil accepted invalid: " + u);
            }
            if ((Boolean) isHttp.invoke(null, (Object) null)) throw new AssertionError("HttpUrlUtil accepted null");
            double fastValid = measureReflect(isHttp, VALID_URLS, 200_000);
            double fastInvalid = measureReflect(isHttp, INVALID_URLS, 200_000);
            double fastMixed = measureReflect(isHttp, mixed, 200_000);
            System.out.printf(Locale.ROOT,
                    "URL_CHECK_HTTPURLUTIL_NS valid=%.1fns invalid=%.1fns mixed=%.1fns%n",
                    fastValid, fastInvalid, fastMixed);
        } catch (ClassNotFoundException notYetPresent) {
            System.out.println("URL_CHECK_HTTPURLUTIL_NS not_present (baseline v0.1.9)");
        }
    }

    private static double measureLegacy(String[] urls, int ops) {
        long sink = 0;
        for (int i = 0; i < 40_000; i++) if (legacyHttp(urls[i % urls.length])) sink++;
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) if (legacyHttp(urls[i % urls.length])) sink++;
        long dt = System.nanoTime() - t0;
        SINK ^= sink;
        return dt / (double) ops;
    }

    private static double measureReflect(Method method, String[] urls, int ops) throws Exception {
        long sink = 0;
        for (int i = 0; i < 40_000; i++) if ((Boolean) method.invoke(null, urls[i % urls.length])) sink++;
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) if ((Boolean) method.invoke(null, urls[i % urls.length])) sink++;
        long dt = System.nanoTime() - t0;
        SINK ^= sink;
        return dt / (double) ops;
    }

    private static final class CompileTiming {
        final FilterRules rules;
        final double pslMs;
        final double readMs;
        final double buildMs;
        final double totalMs;

        CompileTiming(FilterRules rules, double pslMs, double readMs, double buildMs, double totalMs) {
            this.rules = rules;
            this.pslMs = pslMs;
            this.readMs = readMs;
            this.buildMs = buildMs;
            this.totalMs = totalMs;
        }
    }

    private static CompileTiming compileOnce(Path root) throws IOException {
        return compileLists(root, new String[]{"easylist", "easyprivacy", "adguard-cn"});
    }

    private static CompileTiming compileLists(Path root, String[] ids) throws IOException {
        long tStart = System.nanoTime();
        FilterRules.Builder b = new FilterRules.Builder();
        b.suffixes(gzip(root, "psl"));
        long tPsl = System.nanoTime();
        for (String id : ids) b.read(gzip(root, id), id);
        long tRead = System.nanoTime();
        FilterRules rules = b.build();
        long tBuild = System.nanoTime();
        return new CompileTiming(
                rules,
                (tPsl - tStart) / 1e6,
                (tRead - tPsl) / 1e6,
                (tBuild - tRead) / 1e6,
                (tBuild - tStart) / 1e6);
    }

    private static Reader gzip(Path folder, String id) throws IOException {
        return new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(folder.resolve(id + ".txt.gz"))),
                StandardCharsets.UTF_8);
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(30); } catch (InterruptedException ignored) { }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static long percentile(long[] sorted, double p) {
        int index = Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(sorted.length * p) - 1));
        return sorted[index];
    }
}
