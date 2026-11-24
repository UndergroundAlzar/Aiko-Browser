package app.aikobrowser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class UserCustomizationCheck {
    public static void main(String[] args) throws IOException {
        customFilters();
        metadata();
        tampermonkeyV040();
        pageToolkitAndDevTools();
        imports();
    }

    private static void customFilters() throws IOException {
        CustomFilterList user = CustomFilterList.parse("\ufeff! My filters\r\n"
                + "@@||tracker.example/needed.js$script,domain=forum.example\r\n"
                + "||ads.example^\r\nforum.example##.sponsored\r\n"
                + "forum.example#@#.needed\r\n||retired.example^$badfilter\r\n");
        require(user.count == 5, "counts actual rules only");
        FilterRules.Builder all = new FilterRules.Builder();
        all.read(new StringReader(user.rules));
        all.read(new StringReader("||tracker.example^\n||retired.example^\nforum.example##.needed\n"));
        FilterRules merged = all.build();
        require(merged.blocks("forum.example", "forum.example", "ads.example",
                "https://ads.example/a.js", 1, "cross-site"), "custom network rule");
        require(!merged.blocks("forum.example", "forum.example", "tracker.example",
                "https://tracker.example/needed.js", 1, "cross-site"), "custom exception overrides bundled block");
        require(merged.blocks("news.example", "news.example", "tracker.example",
                "https://tracker.example/needed.js", 1, "cross-site"), "exception only applies on intended site");
        require(!merged.blocks("forum.example", "forum.example", "retired.example",
                "https://retired.example/a.js", 1, "cross-site"), "custom badfilter disables bundled rule");
        require(merged.css("forum.example", "https://forum.example/").equals(".sponsored{display:none!important}\n"),
                "custom hiding and exception are combined");
        try {
            CustomFilterList.parse("! Comment\n||ads.example^\nforum.example##+js(alert)\n");
            throw new AssertionError("unsupported scriptlet");
        } catch (IllegalArgumentException invalid) {
            require(invalid.getMessage().contains("第 3 行"), "actual editor line number");
        }
        require(CustomFilterList.parse(" \n! empty ").rules.isEmpty(), "clearing editor removes the custom list");
        System.out.println("PASS custom blocks, scoped exceptions, hiding, badfilter, validation and clearing");
    }

    private static void metadata() {
        String text = "\ufeff// ==UserScript==\r\n// @name Forum helper\r\n// @namespace aiko\r\n"
                + "// @match *://*.forum.example/wiki/*\r\n"
                + "// @include https://extra.example:8443/notes*\r\n"
                + "// @exclude https://admin.forum.example/wiki/*\r\n"
                + "// @grant GM_getValue\r\n// @grant GM.setValue\r\n// @run-at document-start\r\n"
                + "// @noframes\r\n// ==/UserScript==\r\nlet value = GM_getValue('left', 0);\r\n";
        UserScript script = UserScript.parse(text);
        require(script.requiresIsolation && script.needsStorage() && script.permits("setValue")
                && !script.permits("deleteValue"), "least-privilege metadata");
        require(script.matches(UserScript.address("https://forum.example/wiki/start#part")), "wildcard includes root");
        require(script.matches(UserScript.address("http://chat.forum.example:8080/wiki/a?b=1")), "subdomain, wildcard port");
        require(!script.matches(UserScript.address("https://admin.forum.example/wiki/secret")), "exclude wins");
        require(!script.matches(UserScript.address("https://badforum.example/wiki/start")), "domain boundary");
        require(!script.matches(UserScript.address("https://example.com/?path=https://forum.example/wiki/start")),
                "embedded URL never matches scope");
        require(!script.matches(UserScript.address("file:///wiki/start")), "no local protocol");
        require(!script.matches(UserScript.address("https://extra.example/notes")), "fixed port scope");
        require(script.matches(UserScript.address("https://extra.example:8443/notes?a=1")), "include with fixed port");
        require(!UserScript.sameOrigin(UserScript.address("https://forum.example/wiki/"),
                UserScript.address("https://forum.example:8443")), "native gate checks effective port");
        require(!UserScript.sameOrigin(UserScript.address("http://forum.example/wiki/"),
                UserScript.address("https://forum.example")), "native gate checks scheme");
        require(UserScript.sameOrigin(UserScript.address("https://forum.example/wiki/"),
                UserScript.address("https://forum.example:443")), "explicit default port is same origin");

        // Verify expanded Tampermonkey grants (GM_addValueChangeListener, unsafeWindow, etc.)
        String janitorLike = "// ==UserScript==\n// @name Aiko Janitor\n// @namespace aiko.janitor\n"
                + "// @version 1.0.1\n// @match https://janitorai.com/*\n// @include https://jani.com*\n"
                + "// @run-at document-idle\n// @noframes\n// @sandbox DOM\n"
                + "// @grant GM_getValue\n// @grant GM_setValue\n// @grant GM_addValueChangeListener\n"
                + "// @grant GM_removeValueChangeListener\n// ==/UserScript==\nconsole.log('ok');";
        UserScript j = UserScript.parse(janitorLike);
        require(j.requiresIsolation && !j.pageWorld && j.needsStorage() && j.needsValueListener
                && j.permits("addValueChangeListener") && j.matches(UserScript.address("https://janitorai.com/zh/chats/1373717782"))
                && j.matches(UserScript.address("https://jani.com/chats/1")), "aiko-janitor metadata and grants");

        String unsafeScript = "// ==UserScript==\n// @name UnsafeHelper\n// @match https://janitorai.com/*\n"
                + "// @grant unsafeWindow\n// @grant GM_addElement\n// @grant GM_registerMenuCommand\n"
                + "// @grant window.onurlchange\n// ==/UserScript==\nunsafeWindow.x = 1;";
        UserScript u = UserScript.parse(unsafeScript);
        require(!u.requiresIsolation && u.pageWorld && u.usesUnsafeWindow, "unsafeWindow uses pageWorld");

        expectInvalid("// @grant GM_cookie", "GM_cookie");
        expectInvalid("// @webRequest [{\"selector\":\"*\"}]", "@webRequest");
        expectInvalid("// @grant none\n// @grant GM_setValue", "@grant none");
        expectInvalid("// @sandbox raw\n// @grant GM_setValue", "@sandbox raw");
        expectInvalid("// @run-at document-policy", "document-policy");
        expectInvalid("// @exclude /regex/", "@exclude");
        try {
            UserScript.parse("// ==UserScript==\n// @name No scope\n// ==/UserScript==\nalert(1);");
            throw new AssertionError("Missing scope was accepted");
        } catch (IllegalArgumentException invalid) {
            require(invalid.getMessage().contains("@match"), "missing scope requires explicit intent");
        }
        System.out.println("PASS Tampermonkey metadata, match/exclude scope, origin checks and unsupported grants");
    }

    private static void tampermonkeyV040() {
        String full = "// ==UserScript==\n"
                + "// @name Full TM Script\n"
                + "// @namespace aiko.v040\n"
                + "// @version 2.1.0\n"
                + "// @match https://app.example.com/*\n"
                + "// @require https://cdn.example.com/lib-a.js\n"
                + "// @require https://cdn.example.com/lib-b.js\n"
                + "// @resource themeCss https://cdn.example.com/theme.css\n"
                + "// @resource badgePng https://cdn.example.com/badge.png\n"
                + "// @connect api.example.com\n"
                + "// @connect *.trusted.org\n"
                + "// @connect self\n"
                + "// @connect localhost\n"
                + "// @updateURL https://update.greasyfork.org/scripts/123/full.meta.js\n"
                + "// @downloadURL https://update.greasyfork.org/scripts/123/full.user.js\n"
                + "// @icon https://cdn.example.com/icon.png\n"
                + "// @grant GM_xmlhttpRequest\n"
                + "// @grant GM.xmlHttpRequest\n"
                + "// @grant GM_getResourceText\n"
                + "// @grant GM.getResourceText\n"
                + "// @grant GM_getResourceURL\n"
                + "// @grant GM.getResourceURL\n"
                + "// @grant GM_download\n"
                + "// @grant GM.download\n"
                + "// ==/UserScript==\n"
                + "GM_xmlhttpRequest({method:'GET',url:'https://api.example.com/v1'});\n";

        UserScript s = UserScript.parse(full);
        require(s.needsXhr() && s.permits("xmlhttpRequest") && s.permits("xmlHttpRequest"), "GM_xmlhttpRequest grants");
        require(s.needsResources() && s.permits("getResourceText") && s.permits("getResourceURL"), "resource grants");
        require(s.needsDownload() && s.permits("download"), "download grants");
        require(s.needsBridge() && !s.needsStorage(), "bridge needed without storage");
        require(s.requires.size() == 2
                && s.requires.get(0).equals("https://cdn.example.com/lib-a.js")
                && s.requires.get(1).equals("https://cdn.example.com/lib-b.js"), "@require order preserved");
        require(s.resources.size() == 2
                && "https://cdn.example.com/theme.css".equals(s.resources.get("themeCss"))
                && "https://cdn.example.com/badge.png".equals(s.resources.get("badgePng")), "@resource parsed");
        require("https://update.greasyfork.org/scripts/123/full.meta.js".equals(s.updateUrl), "@updateURL parsed");
        require("https://update.greasyfork.org/scripts/123/full.user.js".equals(s.downloadUrl), "@downloadURL parsed");
        require("https://cdn.example.com/icon.png".equals(s.iconUrl), "@icon parsed");

        // @connect matching rules
        require(s.isConnectAllowed("api.example.com", "app.example.com"), "exact @connect host");
        require(s.isConnectAllowed("v2.api.example.com", "app.example.com"), "subdomain of @connect host");
        require(!s.isConnectAllowed("evilapi.example.com", "app.example.com"), "domain boundary on @connect");
        require(s.isConnectAllowed("sub.trusted.org", "app.example.com"), "wildcard @connect subdomain");
        require(s.isConnectAllowed("trusted.org", "app.example.com"), "wildcard @connect apex");
        require(s.isConnectAllowed("app.example.com", "app.example.com"), "@connect self");
        require(s.isConnectAllowed("127.0.0.1", "app.example.com"), "@connect localhost ipv4");
        require(s.isConnectAllowed("localhost", "app.example.com"), "@connect localhost");
        require(!s.isConnectAllowed("blocked.evil.com", "app.example.com"), "unlisted host denied");
        require(!s.needsUserConnectApproval("blocked.evil.com", "app.example.com"), "explicit @connect list denies unlisted");

        // Omitted @connect: same-origin allowed, cross-origin requires user approval unless approved
        UserScript omittedConnect = UserScript.parse("// ==UserScript==\n// @name NoConnect\n"
                + "// @match https://page.example/*\n// @grant GM_xmlhttpRequest\n// ==/UserScript==\nconsole.log(1);");
        require(omittedConnect.isConnectAllowed("page.example", "page.example"), "omitted @connect allows same-origin");
        require(!omittedConnect.needsUserConnectApproval("page.example", "page.example"), "same-origin no prompt");
        require(!omittedConnect.isConnectAllowed("api.other.com", "page.example"), "omitted @connect denies cross-origin");
        require(omittedConnect.needsUserConnectApproval("api.other.com", "page.example"), "omitted @connect marks approval needed");
        Set<String> approved = new HashSet<>(Collections.singleton("api.other.com"));
        require(omittedConnect.isConnectAllowed("api.other.com", "page.example", approved), "user approved host allowed");
        require(!omittedConnect.needsUserConnectApproval("api.other.com", "page.example", approved), "approved host no longer prompts");

        // Wildcard @connect *
        UserScript wildcardConnect = UserScript.parse("// ==UserScript==\n// @name StarConnect\n"
                + "// @match https://page.example/*\n// @connect *\n// @grant GM.xmlHttpRequest\n// ==/UserScript==\nconsole.log(1);");
        require(wildcardConnect.isConnectAllowed("anywhere.io", "page.example"), "@connect * allows any host");

        // Bounds & safety checks on @require / @resource / @connect
        expectInvalid("// @require file:///etc/passwd", "@require");
        expectInvalid("// @resource badonlyone", "@resource");
        expectInvalid("// @resource evil javascript:alert(1)", "@resource");
        expectInvalid("// @connect https://bad/path", "@connect");
        StringBuilder tooManyReqs = new StringBuilder();
        for (int i = 0; i < 13; i++) tooManyReqs.append("// @require https://cdn.example/r").append(i).append(".js\n");
        expectInvalid(tooManyReqs.toString(), "12");
        StringBuilder tooManyRes = new StringBuilder();
        for (int i = 0; i < 25; i++) tooManyRes.append("// @resource r").append(i).append(" https://cdn.example/r").append(i).append(".css\n");
        expectInvalid(tooManyRes.toString(), "24");

        // Version comparison & .meta.js field extraction
        require(UserScript.compareVersions("1.10.0", "1.2.0") > 0, "numeric segment compare");
        require(UserScript.compareVersions("v2.0", "2.0.0") == 0, "v-prefix and trailing zero equality");
        require(UserScript.compareVersions("1.2.0", "1.2.0-beta.1") > 0, "release > prerelease");
        require(UserScript.compareVersions("2026.11.24", "2026.11.5") > 0, "date version compare");
        require(UserScript.compareVersions("1.0", "1.1") < 0, "smaller version");
        String metaOnly = "// ==UserScript==\n// @name Meta\n// @version 3.4.5\n// ==/UserScript==\n";
        require("3.4.5".equals(UserScript.extractMetaField(metaOnly, "version")), "extractMetaField on .meta.js");
        System.out.println("PASS v0.4.0 Tampermonkey grants, @require/@resource/@connect, updateURL and semver checks");
    }

    private static void pageToolkitAndDevTools() {
        String unlock = PageToolkit.unlockCopyScript();
        require(unlock.contains("user-select:text!important") && unlock.contains("stopImmediatePropagation")
                && unlock.contains("__aikoCopyUnlocked"), "unlockCopyScript content");

        String gallery = PageToolkit.extractGalleryImagesScript();
        require(gallery.contains("data-original") && gallery.contains("data-src")
                && gallery.contains("srcset") && gallery.contains("backgroundImage"), "extractGalleryImagesScript content");

        String sniff = PageToolkit.sniffMediaScript();
        require(sniff.contains("__aikoSniffedMedia") && sniff.contains("m3u8")
                && sniff.contains("mp4") && sniff.contains("flac"), "sniffMediaScript content");

        String cssScript = PageToolkit.customCssAndFontScript(
                "body { content: \"hi\\n'world'\"; }", "@font-face { font-family: 'Aiko'; }", 16, true);
        require(cssScript.contains("__aiko_user_style") && cssScript.contains("16px")
                && cssScript.contains("user-scalable=yes") && cssScript.contains("@font-face"), "customCssAndFontScript");

        String clearCss = PageToolkit.customCssAndFontScript("", "", 0, false);
        require(clearCss.contains("existing.remove()") && !clearCss.contains("unlockViewport"), "customCssAndFontScript clear");

        String anti = PageToolkit.antiAdblockScriptlets("news.example.com");
        require(anti.contains("canRunAds") && anti.contains("adsBlocked")
                && anti.contains("adblockDetected") && anti.contains("news.example.com"), "antiAdblockScriptlets");

        String draft = PageToolkit.formDraftHookScript();
        require(draft.contains("__aikoCollectDraft") && draft.contains("__aikoRestoreDraft")
                && draft.contains("password:1"), "formDraftHookScript");

        String dtInject = WebDevTools.injectScript();
        String dtToggle = WebDevTools.toggleScript();
        require(dtInject.contains("__aiko_devtools_root") && dtInject.contains("attachShadow")
                && dtInject.contains("Console") && dtInject.contains("Network")
                && dtInject.contains("Storage") && dtInject.contains("Elements")
                && dtInject.contains("localStorage") && dtInject.contains("document.cookie")
                && dtInject.contains("window.__aikoDevTools"), "WebDevTools injectScript structure");
        require(!dtInject.contains("http://") && !dtInject.contains("https://"), "WebDevTools has zero external CDN links");
        require(dtToggle.contains("__aikoDevTools.toggle()"), "WebDevTools toggleScript");
        System.out.println("PASS PageToolkit (6 modules) and offline WebDevTools script generation");
    }

    private static void expectInvalid(String metadata, String issue) {
        try {
            UserScript.parse("// ==UserScript==\n// @name Example\n// @match https://example.com/*\n"
                    + metadata + "\n// ==/UserScript==\nconst x = 1;");
            throw new AssertionError("Unexpected accepted metadata " + issue);
        } catch (IllegalArgumentException invalid) {
            require(invalid.getMessage().contains(issue), "specific reason " + issue + " (got: " + invalid.getMessage() + ")");
        }
    }

    private static void imports() throws IOException {
        byte[] original = "脚本".getBytes(StandardCharsets.UTF_8);
        require(UserScriptInput.read(new ByteArrayInputStream(original), 10).equals("脚本"), "UTF-8 file");
        rejectBytes(new byte[]{(byte)0xc3, (byte)0x28}, 1024);
        rejectBytes(original, 2);
        System.out.println("PASS file input UTF-8 and size limits");
    }

    private static void rejectBytes(byte[] data, int limit) throws IOException {
        try {
            UserScriptInput.read(new ByteArrayInputStream(data), limit);
            throw new AssertionError("Invalid file was accepted");
        } catch (IOException invalid) {
            // Import rejected.
        }
    }

    private static void require(boolean ok, String condition) {
        if (!ok) throw new AssertionError(condition);
    }
}
