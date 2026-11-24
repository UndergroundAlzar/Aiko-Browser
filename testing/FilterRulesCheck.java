package app.aikobrowser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public final class FilterRulesCheck {
    public static void main(String[] args) throws Exception {
        FilterRules.Builder logic = new FilterRules.Builder();
        logic.suffixes(new StringReader("com\ncn\ncom.cn\n*.ck\n!www.ck\n"));
        logic.read(new StringReader(String.join("\n",
                "||ads.example.com^",
                "@@||ads.example.com/allowed.js$script",
                "||metrics.example.com^$third-party",
                "||cdn.example.com/ad/*$image,domain=site.com|~skip.site.com",
                "promo-banner-$image",
                "||disabled.example.net^",
                "||disabled.example.net^$badfilter",
                "||skipmod.example.net^$redirect=noop.js",
                "@@||safe.site.com/checkout$document",
                "@@||site.com^$generichide",
                "site.com##.banner",
                "skip.site.com#@#.banner",
                "site.com##+js(abort-on-property-read, foo)",
                "site.com##.bad{background:url(https://invalid.example)}")));
        FilterRules r = logic.build();
        check("example.com.cn".equals(r.siteKey("media.example.com.cn")), "China registrable domain");
        check("www.ck".equals(r.siteKey("sub.www.ck")), "PSL exception");
        check("sub.example.ck".equals(r.siteKey("sub.example.ck")), "PSL wildcard");
        check(block(r, "site.com", "ads.example.com", "/creative.js", 1), "host rule");
        check(!block(r, "site.com", "notads.example.com", "/creative.js", 1), "host label boundary");
        check(!block(r, "site.com", "ads.example.com", "/allowed.js", 1), "request allow rule");
        check(!block(r, "example.com", "metrics.example.com", "/a", 0), "first-party sibling");
        check(block(r, "site.com", "metrics.example.com", "/a", 0), "third-party rule");
        check(block(r, "site.com", "cdn.example.com", "/ad/logo.png", 2), "path/type/site rule");
        check(!block(r, "skip.site.com", "cdn.example.com", "/ad/logo.png", 2), "negated scope");
        check(!block(r, "site.com", "cdn.example.com", "/ad/page", 0), "unknown content type");
        check(block(r, "site.com", "cdn.example.net", "/Promo-Banner-2.png", 2), "generic pattern");
        check(!block(r, "site.com", "disabled.example.net", "/a", 0), "badfilter neutralized");
        check(!block(r, "site.com", "skipmod.example.net", "/a", 0), "unsupported modifier");
        check(r.exceptsPage("sub.safe.site.com", "https://sub.safe.site.com/checkout", false), "document exception");
        check(r.css("site.com", "https://site.com/").equals(".banner{display:none!important}\n"), "CSS scope and trusted syntax");
        check(r.css("skip.site.com", "https://skip.site.com/").isEmpty(), "CSS exception");

        if (args.length != 0) {
            Path root = Path.of(args[0]);
            FilterRules.Builder shipped = new FilterRules.Builder();
            shipped.suffixes(gzip(root, "psl"));
            for (String id : new String[]{"easylist", "easyprivacy", "adguard-cn"}) shipped.read(gzip(root, id));
            long then = System.nanoTime();
            FilterRules live = shipped.build();
            long compileMs = (System.nanoTime() - then) / 1_000_000;
            check(live.networkCount > 10000 && live.cosmeticCount > 10000, "shipped lists recognized");
            check(block(live, "linux.do", "qzs.gdtimg.com", "/sample", 1), "Chinese ad network");
            check(block(live, "janitorai.com", "ad.doubleclick.net", "/sample", 1), "international ad network");
            check(block(live, "janitorai.com", "www.google-analytics.com", "/analytics.js", 1), "international tracking rule");
            check(!block(live, "janitorai.com", "janitorai.com", "/api/chats", 0), "sample Janitor AI chat request allowed");
            check(!block(live, "youtube.com", "r1.googlevideo.com", "/videoplayback", 16), "sample YouTube video URL allowed");
            String css = live.css("www.youtube.com", "https://www.youtube.com/");
            check(css.contains("ytd-ad-slot-renderer") || css.contains("ytd-promoted"), "YouTube page rule");
            System.out.printf("PASS shipped 3-list baseline: %,d network, %,d cosmetic, %,d skipped; JVM compile %d ms%n",
                    live.networkCount, live.cosmeticCount, live.skipped, compileMs);

            String[] allSeven = {
                    "adguard-cn", "cjx-annoyance", "adguard-mobile", "adguard-mobile-app",
                    "easylist", "easyprivacy", "adguard-cookies"
            };
            boolean hasAllSeven = true;
            for (String id : allSeven) {
                if (!Files.isRegularFile(root.resolve(id + ".txt.gz"))) {
                    hasAllSeven = false;
                    break;
                }
            }
            if (hasAllSeven) {
                FilterRules.Builder shipped7 = new FilterRules.Builder();
                shipped7.suffixes(gzip(root, "psl"));
                for (String id : allSeven) shipped7.read(gzip(root, id), id);
                long t7 = System.nanoTime();
                FilterRules live7 = shipped7.build();
                long compile7Ms = (System.nanoTime() - t7) / 1_000_000;
                check(live7.networkCount + live7.cosmeticCount > 130_000, "all 7 bundled lists recognized");
                check(block(live7, "linux.do", "qzs.gdtimg.com", "/sample", 1), "7-list Chinese ad network");
                check(block(live7, "m.youku.com", "ad.youku.com", "/sdk.js", 1), "7-list mobile ad network");
                check(block(live7, "janitorai.com", "ad.doubleclick.net", "/sample", 1), "7-list international ad network");
                check(block(live7, "janitorai.com", "www.google-analytics.com", "/analytics.js", 1), "7-list tracking rule");
                check(!block(live7, "janitorai.com", "janitorai.com", "/api/chats", 0), "7-list Janitor AI chat allowed");
                check(!block(live7, "youtube.com", "r1.googlevideo.com", "/videoplayback", 16), "7-list YouTube video allowed");
                check(!block(live7, "github.com", "github.com", "/UndergroundAlzar/Aiko-Browser", 0), "7-list GitHub allowed");
                System.out.printf("PASS shipped 7-list v0.3.0: %,d network, %,d cosmetic (%,d active), %,d skipped; JVM compile %d ms%n",
                        live7.networkCount, live7.cosmeticCount, live7.networkCount + live7.cosmeticCount,
                        live7.skipped, compile7Ms);
            }
        }
        System.out.println("PASS scoped matching, exceptions and cosmetic sanitization");
    }

    static Reader gzip(Path folder, String id) throws IOException {
        return new InputStreamReader(new GZIPInputStream(Files.newInputStream(folder.resolve(id + ".txt.gz"))),
                StandardCharsets.UTF_8);
    }

    static boolean block(FilterRules rules, String site, String host, String path, int type) {
        return rules.blocks(site, rules.siteKey(site), host, "https://" + host + path, type, null);
    }

    static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError(expectation);
    }
}
