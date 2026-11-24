package app.aikobrowser;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local or remote .user.js file, parsed without evaluating its body (pure Java). */
public final class UserScript {
    static final int MAX_BYTES = 512 * 1024;
    private static final Pattern HEADER = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==UserScript==[ \\t]*$");
    private static final Pattern END = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==/UserScript==[ \\t]*$");
    private static final Pattern ITEM = Pattern.compile("^[ \\t]*//[ \\t]*@([\\w:-]+)(?:[ \\t]+(.*))?$");
    private static final Set<String> GRANTS = new HashSet<>(Arrays.asList(
            "GM_addStyle", "GM_log", "GM_info", "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM.addStyle", "GM.log", "GM.info", "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues"));
    public final String source, code, name, namespace, version, author, description, runAt;
    public final List<UrlPattern> includes, excludes;
    public final Set<String> grants;
    public final Set<String> origins;
    final boolean requiresIsolation, pageWorld, anyPort;

    private UserScript(String source, String code, Map<String, String> metadata,
                       List<UrlPattern> includes, List<UrlPattern> excludes, Set<String> grants) {
        this.source = source;
        this.code = code;
        name = metadata.get("name");
        namespace = metadata.getOrDefault("namespace", "");
        version = metadata.getOrDefault("version", "");
        author = metadata.getOrDefault("author", "");
        String descZh = metadata.get("description:zh-CN");
        if (descZh == null || descZh.isEmpty()) descZh = metadata.get("description:zh");
        description = (descZh != null && !descZh.isEmpty()) ? descZh : metadata.getOrDefault("description", "");
        runAt = metadata.getOrDefault("run-at", "document-idle");
        this.includes = Collections.unmodifiableList(includes);
        this.excludes = Collections.unmodifiableList(excludes);
        this.grants = Collections.unmodifiableSet(grants);
        requiresIsolation = !grants.isEmpty() || "DOM".equals(metadata.get("sandbox"));
        pageWorld = "raw".equals(metadata.get("sandbox"));
        boolean hasAnyPort = false;
        Set<String> allowed = new LinkedHashSet<>();
        for (UrlPattern rule : includes) {
            if (rule.port < 0) hasAnyPort = true;
            if (!allowed.contains("*")) rule.addOrigins(allowed);
        }
        anyPort = hasAnyPort;
        origins = allowed.contains("*") ? Collections.singleton("*") : Collections.unmodifiableSet(allowed);
    }

    public static UserScript parse(String input) {
        if (input == null) throw invalid("脚本内容为空");
        if (input.length() > MAX_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("脚本不能超过 512 KiB");
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
                case "author":
                    metadata.putIfAbsent("author", value.length() > 160 ? value.substring(0, 160) : value);
                    break;
                case "description":
                case "description:zh-CN":
                case "description:zh":
                    metadata.putIfAbsent(key, value.length() > 400 ? value.substring(0, 400) : value);
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

    public boolean needsStorage() {
        for (String grant : grants) if (grant.contains("Value")) return true;
        return false;
    }

    public boolean permits(String method) { return grants.contains("GM_" + method) || grants.contains("GM." + method); }

    public boolean matches(URI url) {
        if (url == null || url.getUserInfo() != null || url.getHost() == null) return false;
        for (UrlPattern rule : excludes) if (rule.matches(url)) return false;
        for (UrlPattern rule : includes) if (rule.matches(url)) return true;
        return false;
    }

    /**
     * Pure-Java detector for remote .user.js install URLs (Greasy Fork, Sleazy Fork,
     * ScriptCat, OpenUserJS, GitHub raw, direct CDN .user.js, etc.).
     * Strictly requires HTTP(S) and excludes HTML code-viewer pages (/blob/, /tree/, etc.).
     */
    public static boolean isUserScriptUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > 8192 || !HttpUrlUtil.isHttp(url)) return false;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return false;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) return false;
        int pathEnd = url.length();
        for (int i = pathStart; i < pathEnd; i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                pathEnd = i;
                break;
            }
        }
        int lastSlash = url.lastIndexOf('/', pathEnd - 1);
        int fileStart = lastSlash >= pathStart ? lastSlash + 1 : pathStart;
        int fileLen = pathEnd - fileStart;
        if (fileLen <= 8 || !url.regionMatches(true, pathEnd - 8, ".user.js", 0, 8)) return false;

        String host = HttpUrlUtil.extractHost(url);
        if (isCodeHostHtmlView(host, url, pathStart, pathEnd)) return false;
        return true;
    }

    /** Detects whether a download filename (e.g. from Content-Disposition) ends with .user.js. */
    public static boolean isUserScriptFilename(String filename) {
        if (filename == null) return false;
        String s = filename.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.length() > 8 && s.regionMatches(true, s.length() - 8, ".user.js", 0, 8);
    }

    private static boolean isCodeHostHtmlView(String host, String url, int pathStart, int pathEnd) {
        if (!"github.com".equals(host) && !"gitlab.com".equals(host)
                && !"gitee.com".equals(host) && !"codeberg.org".equals(host)) {
            return false;
        }
        String path = url.substring(pathStart, pathEnd).toLowerCase(Locale.ROOT);
        return path.contains("/blob/") || path.contains("/tree/")
                || path.contains("/edit/") || path.contains("/blame/")
                || path.contains("/commits/");
    }

    public static URI address(String url) {
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

    public static final class UrlPattern {
        public final String original, scheme, host, path;
        public final boolean subdomains, wildcardPort;
        public final int port;

        private UrlPattern(String original, String scheme, String host, boolean subdomains,
                           int port, boolean wildcardPort, String path) {
            this.original = original;
            this.scheme = scheme;
            this.host = host;
            this.subdomains = subdomains;
            this.port = port;
            this.wildcardPort = wildcardPort;
            this.path = path;
        }

        static UrlPattern parse(String value, String key) {
            String pattern = value.equals("*") || value.equals("<all_urls>") ? "*://*/*" : value;
            int schemeEnd = pattern.indexOf("://");
            if (schemeEnd <= 0 || pattern.length() > 512 || pattern.indexOf('#') >= 0 || pattern.indexOf('\\') >= 0)
                throw invalid("不支持 @" + key + " " + value);
            String scheme = pattern.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if (scheme.equals("http*")) scheme = "*";
            if (!scheme.equals("*") && !httpScheme(scheme)) throw invalid("网址模式仅支持 HTTP(S)");
            int pathStart = pattern.indexOf('/', schemeEnd + 3);
            String authority = pattern.substring(schemeEnd + 3, pathStart < 0 ? pattern.length() : pathStart);
            String path = pathStart < 0 ? "/*" : pattern.substring(pathStart);
            if (authority.contains("@") || authority.contains("?")) throw invalid("网址模式的域名无效");
            boolean subdomains = authority.startsWith("*.");
            if (subdomains) authority = authority.substring(2);
            int colon = authority.lastIndexOf(':');
            int port = -1;
            boolean wildcardPort = false;
            if (colon > authority.lastIndexOf(']')) {
                String valuePort = authority.substring(colon + 1);
                authority = authority.substring(0, colon);
                if (valuePort.equals("*")) {
                    wildcardPort = true;
                } else {
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
            return new UrlPattern(value, scheme, host, subdomains, port, wildcardPort, path);
        }

        void addOrigins(Set<String> allowed) {
            // Narrow to default-port origin rules unless host or port is explicitly wildcarded,
            // so Chromium avoids creating isolated worlds on unrelated sites & iframes.
            // Rare non-default port visits for omitted-port patterns are handled dynamically by UserScripts.
            if (host.equals("*") || wildcardPort) {
                allowed.add("*");
                return;
            }
            if (!scheme.equals("http")) addOrigin(allowed, "https");
            if (!scheme.equals("https")) addOrigin(allowed, "http");
        }

        private void addOrigin(Set<String> allowed, String protocol) {
            String suffix = port > 0 ? ":" + port : "";
            allowed.add(protocol + "://" + host + suffix);
            if (subdomains && !isIpLiteral(host)) allowed.add(protocol + "://*." + host + suffix);
        }

        private static boolean isIpLiteral(String host) {
            if (host.startsWith("[")) return true;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if ((c < '0' || c > '9') && c != '.') return false;
            }
            return !host.isEmpty();
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
