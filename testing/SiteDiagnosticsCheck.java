package app.aikobrowser;

import java.io.StringReader;

public final class SiteDiagnosticsCheck {
    public static void main(String[] args) throws Exception {
        checkProfileEncoding();
        checkTrace();
    }

    private static void checkProfileEncoding() {
        SiteProfile profile = SiteProfile.decode(new SiteProfile(true, true, 125).encode());
        check(profile.desktop && profile.blockImages && profile.zoom == 125, "all options restored");
        SiteProfile empty = SiteProfile.decode(0);
        check(!empty.desktop && !empty.blockImages && empty.zoom == 100, "default settings");
        check(SiteProfile.decode(Integer.MAX_VALUE) == SiteProfile.DEFAULT, "invalid stored value");
        check(new SiteProfile(false, false, 300).encode() == 0, "invalid text size falls back");
        String device = "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.25 Mobile Safari/537.36";
        String desktop = SiteProfile.desktopAgent(device);
        check(desktop.equals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36"), "matches the installed Chromium major");
        check(SiteProfile.desktopAgent("Unknown WebView") == null, "unrecognized provider");
        System.out.println("PASS per-site settings encoding, defaults, and desktop UA version");
    }

    private static void checkTrace() throws Exception {
        FilterRules.Builder builder = new FilterRules.Builder();
        builder.read(new StringReader("||ads.example^\n@@||ads.example/keep.js$script\n"), "自定义规则");
        builder.read(new StringReader("||ads.example^\n||track.example^\n"), "EasyPrivacy");
        FilterRules rules = builder.build();
        FilterRules.Rule custom = match(rules, "ads.example", "/remove.js");
        check(custom != null && custom.source.equals("自定义规则")
                && rules.description(custom).equals("||ads.example^"), "duplicate chooses first source");
        check(match(rules, "ads.example", "/keep.js") == null, "allow rules override a blocked request");
        FilterRules.Rule builtin = match(rules, "track.example", "/event");
        check(builtin != null && builtin.source.equals("EasyPrivacy"), "built-in provenance");
        FilterTrace trace = new FilterTrace();
        trace.add("ads.example", rules, custom);
        trace.add("track.example", rules, builtin);
        trace.add("ads.example", rules, custom);
        FilterTrace.Snapshot start = trace.snapshot();
        check(start.count == 3 && start.recent.size() == 2, "count requests but deduplicate details");
        check(start.recent.get(0).host.equals("ads.example"), "most recent rule goes to top");
        check(start.recent.get(0).reason.equals("||ads.example^"), "diagnosis shows the matching rule");
        for (int i = 0; i < 16; i++) trace.add("more-" + i + ".ads.example", rules, custom);
        FilterTrace.Snapshot capped = trace.snapshot();
        check(capped.count == 19 && capped.recent.size() == FilterTrace.LIMIT, "bounded records");
        check(capped.recent.get(0).host.equals("more-15.ads.example"), "newest record survives");
        check(start.recent.size() == 2, "snapshot unaffected by subsequent requests");
        FilterTrace anotherTab = new FilterTrace();
        check(anotherTab.snapshot().count == 0, "tabs use separate traces");
        trace.addBootstrap("analytics.example", "analytics.example");
        check(trace.snapshot().recent.get(0).source.equals("启动基础过滤"), "bootstrap is identifiable");
        System.out.println("PASS filter exceptions, source attribution, bounded records, and separate traces");
    }

    private static FilterRules.Rule match(FilterRules rules, String host, String path) {
        return rules.blockingRule("forum.example", "forum.example", host, "https://" + host + path, 1, "cross-site");
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
