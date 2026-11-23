package app.aikobrowser;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local .user.js file, parsed without evaluating its body. */
final class UserScript {
    static final int MAX_BYTES = 256 * 1024;
    private static final Pattern HEADER = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==UserScript==[ \\t]*$");
    private static final Pattern END = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==/UserScript==[ \\t]*$");
    private static final Pattern ITEM = Pattern.compile("^[ \\t]*//[ \\t]*@([\\w-]+)(?:[ \\t]+(.*))?$");
    private static final Set<String> GRANTS = new HashSet<>(Arrays.asList(
            "GM_addStyle", "GM_log", "GM_info", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM.addStyle", "GM.log", "GM.info", "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues"));
    final String source, code, name, namespace, version, runAt;
    final List<UrlPattern> includes, excludes;
    final Set<String> grants;
    final Set<String> origins;
    final boolean requiresIsolation, pageWorld;

    private UserScript(String source, String code, Map<String, String> metadata,
                       List<UrlPattern> includes, List<UrlPattern> excludes, Set<String> grants) {
        this.source = source;
        this.code = code;
        name = metadata.get("name");
        namespace = metadata.getOrDefault("namespace", "");
        version = metadata.getOrDefault("version", "");
        runAt = metadata.getOrDefault("run-at", "document-idle");
        this.includes = Collections.unmodifiableList(includes);
        this.excludes = Collections.unmodifiableList(excludes);
        this.grants = Collections.unmodifiableSet(grants);
        requiresIsolation = !grants.isEmpty() || "DOM".equals(metadata.get("sandbox"));
        pageWorld = "raw".equals(metadata.get("sandbox"));
        Set<String> allowed = new LinkedHashSet<>();
        for (UrlPattern rule : includes) {
            rule.addOrigins(allowed);
            if (allowed.contains("*")) break;
        }
        origins = allowed.contains("*") ? Collections.singleton("*") : Collections.unmodifiableSet(allowed);
    }

    static UserScript parse(String input) {
        if (input.length() > MAX_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("脚本不能超过 256 KiB");
        String source = input.replace("\r\n", "\n").replace('\r', '\n');
        if (source.startsWith("\ufeff")) source = source.substring(1);
        Matcher start = HEADER.matcher(source), end = END.matcher(source);
        if (!start.find() || start.start() > 4096 || !end.find(start.end()) || end.start() - start.end() > 16_384)
            throw invalid("缺少完整的 // ==UserScript== 元数据区");
        Map<String, String> metadata = new HashMap<>();
        List<UrlPattern> includes = new ArrayList<>(), excludes = new ArrayList<>();
        Set<String> grants = new LinkedHashSet<>(), unsupported = new LinkedHashSet<>();
        boolean none = false;
        for (String row : source.substring(start.end(), end.start()).split("\n")) {
            Matcher item = ITEM.matcher(row);
            if (!item.matches()) continue;
            String key = item.group(1);
            String value = item.group(2) == null ? "" : item.group(2).trim();
            switch (key) {
                case "name": case "namespace": case "version": case "run-at":
                    if (value.length() > 240) throw invalid("@" + key + " 太长");
                    metadata.putIfAbsent(key, value);
                    break;
                case "match": case "include":
                    includes.add(UrlPattern.parse(value, key));
                    break;
                case "exclude": case "exclude-match":
                    excludes.add(UrlPattern.parse(value, key));
                    break;
                case "grant":
                    if (value.equals("none")) none = true;
                    else if (GRANTS.contains(value)) grants.add(value);
                    else unsupported.add("@grant " + value);
                    break;
                case "require": case "resource": case "webRequest": case "unwrap": case "run-in":
                    unsupported.add("@" + key);
                    break;
                case "sandbox":
                    if (!value.equals("DOM") && !value.equals("raw")) unsupported.add("@sandbox " + value);
                    else metadata.put("sandbox", value);
                    break;
                default:
                    break;
            }
        }
        if (!unsupported.isEmpty()) throw invalid("暂不支持 " + String.join("、", unsupported));
        if (none && !grants.isEmpty()) throw invalid("@grant none 不能与其他授权并用");
        if ("raw".equals(metadata.get("sandbox")) && !grants.isEmpty())
            throw invalid("@sandbox raw 仅用于无授权脚本");
        if (metadata.getOrDefault("name", "").isEmpty()) throw invalid("请填写 @name");
        if (includes.isEmpty()) throw invalid("请填写至少一个 HTTP(S) 的 @match 或 @include");
        if (includes.size() + excludes.size() > 64) throw invalid("每个脚本最多 64 个网址模式");
        String timing = metadata.getOrDefault("run-at", "document-idle");
        if (!timing.equals("document-start") && !timing.equals("document-end")
                && !timing.equals("document-idle") && !timing.equals("document-body"))
            throw invalid("暂不支持 @run-at " + timing);
        String code = source.substring(end.end());
        if (code.trim().isEmpty()) throw invalid("脚本没有正文");
        return new UserScript(source, code, metadata, includes, excludes, grants);
    }

    boolean needsStorage() {
        for (String grant : grants) if (grant.contains("Value")) return true;
        return false;
    }

    boolean permits(String method) { return grants.contains("GM_" + method) || grants.contains("GM." + method); }

    boolean matches(URI url) {
        if (url == null || url.getUserInfo() != null || url.getHost() == null) return false;
        for (UrlPattern rule : excludes) if (rule.matches(url)) return false;
        for (UrlPattern rule : includes) if (rule.matches(url)) return true;
        return false;
    }

    static URI address(String url) {
        if (url == null || url.length() > 8192) return null;
        try {
            URI uri = URI.create(url);
            return httpScheme(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static boolean sameOrigin(URI left, URI right) {
        return left != null && right != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && portFor(left) == portFor(right);
    }

    private static boolean httpScheme(String scheme) {
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    private static int portFor(URI url) {
        return url.getPort() != -1 ? url.getPort() : "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }

    static final class UrlPattern {
        final String original, scheme, host, path;
        final boolean subdomains;
        final int port;

        private UrlPattern(String original, String scheme, String host, boolean subdomains, int port, String path) {
            this.original = original;
            this.scheme = scheme;
            this.host = host;
            this.subdomains = subdomains;
            this.port = port;
            this.path = path;
        }

        static UrlPattern parse(String value, String key) {
            String pattern = value.equals("*") || value.equals("<all_urls>") ? "*://*/*" : value;
            int schemeEnd = pattern.indexOf("://");
            if (schemeEnd <= 0 || pattern.length() > 512 || pattern.indexOf('#') >= 0 || pattern.indexOf('\\') >= 0)
                throw invalid("不支持 @" + key + " " + value);
            String scheme = pattern.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (!scheme.equals("*") && !httpScheme(scheme)) throw invalid("网址模式仅支持 HTTP(S)");
            int pathStart = pattern.indexOf('/', schemeEnd + 3);
            String authority = pattern.substring(schemeEnd + 3, pathStart < 0 ? pattern.length() : pathStart);
            String path = pathStart < 0 ? "/*" : pattern.substring(pathStart);
            if (authority.contains("@") || authority.contains("?")) throw invalid("网址模式的域名无效");
            boolean subdomains = authority.startsWith("*.");
            if (subdomains) authority = authority.substring(2);
            int colon = authority.lastIndexOf(':');
            int port = -1;
            if (colon > authority.lastIndexOf(']')) {
                String valuePort = authority.substring(colon + 1);
                authority = authority.substring(0, colon);
                if (!valuePort.equals("*")) {
                    try { port = Integer.parseInt(valuePort); }
                    catch (NumberFormatException ex) { throw invalid("网址模式的端口无效"); }
                    if (port < 1 || port > 65535) throw invalid("网址模式的端口无效");
                }
            }
            String host;
            try {
                host = authority.equals("*") || authority.startsWith("[") ? authority
                        : IDN.toASCII(authority, IDN.USE_STD3_ASCII_RULES);
                host = host.toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                if (host.equals("*") ? subdomains : URI.create("https://" + host + "/").getHost() == null)
                    throw invalid("host");
            } catch (IllegalArgumentException ex) {
                throw invalid("网址模式的域名无效");
            }
            return new UrlPattern(value, scheme, host, subdomains, port, path);
        }

        void addOrigins(Set<String> allowed) {
            // AndroidX uses the default port if omitted. Preserve match-pattern any-port
            // behavior with a scoped check inside the script and (for native messages) Java.
            if (port < 0 || host.equals("*")) {
                allowed.add("*");
                return;
            }
            if (!scheme.equals("http")) addOrigin(allowed, "https");
            if (!scheme.equals("https")) addOrigin(allowed, "http");
        }

        private void addOrigin(Set<String> allowed, String protocol) {
            allowed.add(protocol + "://" + host + ":" + port);
            if (subdomains) allowed.add(protocol + "://*." + host + ":" + port);
        }

        boolean matches(URI uri) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!httpScheme(scheme) || !this.scheme.equals("*") && !this.scheme.equals(scheme)
                    || port != -1 && portFor(uri) != port) return false;
            String actual = uri.getHost().toLowerCase(Locale.ROOT);
            if (actual.endsWith(".")) actual = actual.substring(0, actual.length() - 1);
            if (!host.equals("*") && !host.equals(actual) && !(subdomains && actual.endsWith("." + host))) return false;
            String fragmentFree = uri.getRawPath();
            if (fragmentFree.isEmpty()) fragmentFree = "/";
            if (uri.getRawQuery() != null) fragmentFree += "?" + uri.getRawQuery();
            return glob(path, fragmentFree);
        }
    }

    private static boolean glob(String pattern, String text) {
        int at = 0, token = 0, star = -1, resume = 0;
        while (at < text.length()) {
            if (token < pattern.length() && pattern.charAt(token) == '*') {
                star = token++;
                resume = at;
            } else if (token < pattern.length() && pattern.charAt(token) == text.charAt(at)) {
                token++;
                at++;
            } else if (star >= 0) {
                token = star + 1;
                at = ++resume;
            } else {
                return false;
            }
        }
        while (token < pattern.length() && pattern.charAt(token) == '*') token++;
        return token == pattern.length();
    }
}
