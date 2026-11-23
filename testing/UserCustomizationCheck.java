package app.aikobrowser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

public final class UserCustomizationCheck {
    public static void main(String[] args) throws IOException {
        customFilters();
        metadata();
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
        expectInvalid("// @grant GM_xmlhttpRequest", "GM_xmlhttpRequest");
        expectInvalid("// @require https://example.com/dependency.js", "@require");
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

    private static void expectInvalid(String metadata, String issue) {
        try {
            UserScript.parse("// ==UserScript==\n// @name Example\n// @match https://example.com/*\n"
                    + metadata + "\n// ==/UserScript==\nconst x = 1;");
            throw new AssertionError("Unexpected accepted metadata " + issue);
        } catch (IllegalArgumentException invalid) {
            require(invalid.getMessage().contains(issue), "specific reason " + issue);
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
