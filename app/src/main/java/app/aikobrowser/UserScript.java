package app.aikobrowser;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local or remote .user.js file, parsed without evaluating its body (pure Java). */
public final class UserScript {
    static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_REQUIRES = 12;
    public static final int MAX_RESOURCES = 24;
    public static final int MAX_CONNECTS = 64;
    private static final int MAX_HEADER_CHARS = 65_536;
    private static final Pattern HEADER = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==UserScript==[ \\t]*$");
    private static final Pattern END = Pattern.compile("(?m)^[ \\t]*//[ \\t]*==/UserScript==[ \\t]*$");
    private static final Pattern ITEM = Pattern.compile("^[ \\t]*//[ \\t]*@([\\w:-]+)(?:[ \\t]+(.*))?$");
    private static final Pattern RESOURCE_PAIR = Pattern.compile("^(\\S+)[ \\t]+(\\S.*)$");
    private static final Set<String> GRANTS = new HashSet<>(Arrays.asList(
            "GM_addStyle", "GM_addElement", "GM_log", "GM_info",
            "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM_getValues", "GM_setValues", "GM_deleteValues",
            "GM_addValueChangeListener", "GM_removeValueChangeListener",
            "GM_setClipboard", "GM_registerMenuCommand", "GM_unregisterMenuCommand",
            "GM_openInTab", "GM_notification",
            "GM_xmlhttpRequest", "GM_getResourceText", "GM_getResourceURL", "GM_download",
            "GM.addStyle", "GM.addElement", "GM.log", "GM.info",
            "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues",
            "GM.getValues", "GM.setValues", "GM.deleteValues",
            "GM.addValueChangeListener", "GM.removeValueChangeListener",
            "GM.setClipboard", "GM.registerMenuCommand", "GM.unregisterMenuCommand",
            "GM.openInTab", "GM.notification",
            "GM.xmlHttpRequest", "GM.getResourceText", "GM.getResourceURL", "GM.download",
            "unsafeWindow", "window.close", "window.focus", "window.onurlchange"));

    public final String source, code, name, namespace, version, author, description, runAt;
    public final String updateUrl, downloadUrl, iconUrl;
    public final List<UrlPattern> includes, excludes;
    public final List<String> requires;
    public final Map<String, String> resources;
    public final List<String> connects;
    public final Set<String> grants;
    public final Set<String> origins;
    final boolean requiresIsolation, pageWorld, usesUnsafeWindow, needsValueListener, anyPort;

    private UserScript(String source, String code, Map<String, String> metadata,
                       List<UrlPattern> includes, List<UrlPattern> excludes, Set<String> grants,
                       List<String> requires, Map<String, String> resources, List<String> connects) {
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
        updateUrl = metadata.getOrDefault("updateURL", "");
        downloadUrl = metadata.getOrDefault("downloadURL", "");
        iconUrl = metadata.getOrDefault("icon", "");
        this.includes = Collections.unmodifiableList(includes);
        this.excludes = Collections.unmodifiableList(excludes);
        this.requires = Collections.unmodifiableList(requires);
        this.resources = Collections.unmodifiableMap(resources);
        this.connects = Collections.unmodifiableList(connects);
        this.grants = Collections.unmodifiableSet(grants);
        usesUnsafeWindow = grants.contains("unsafeWindow");
        needsValueListener = grants.contains("GM_addValueChangeListener") || grants.contains("GM.addValueChangeListener");
        String sandbox = metadata.get("sandbox");
        requiresIsolation = !usesUnsafeWindow && (!grants.isEmpty() || "DOM".equals(sandbox) || "JavaScript".equals(sandbox));
        pageWorld = "raw".equals(sandbox) || usesUnsafeWindow;
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
            throw invalid("脚本不能超过 1 MiB");
        String source = input.replace("\r\n", "\n").replace('\r', '\n');
        if (source.startsWith("\ufeff")) source = source.substring(1);
        Matcher start = HEADER.matcher(source), end = END.matcher(source);
        if (!start.find() || start.start() > 4096 || !end.find(start.end()) || end.start() - start.end() > MAX_HEADER_CHARS)
            throw invalid("缺少完整的 // ==UserScript== 元数据区");
        Map<String, String> metadata = new HashMap<>();
        List<UrlPattern> includes = new ArrayList<>(), excludes = new ArrayList<>();
        List<String> requires = new ArrayList<>();
        LinkedHashMap<String, String> resources = new LinkedHashMap<>();
        List<String> connects = new ArrayList<>();
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
                case "updateURL":
                case "downloadURL": {
                    URI safe = UserScriptInput.safeRemoteAddress(value);
                    if (safe != null) metadata.putIfAbsent(key, safe.toASCIIString());
                    break;
                }
                case "icon":
                case "iconURL":
                case "icon64":
                case "icon64URL":
                case "defaulticon": {
                    if (!metadata.containsKey("icon")) {
                        URI safe = UserScriptInput.safeRemoteAddress(value);
                        if (safe != null) {
                            metadata.put("icon", safe.toASCIIString());
                        } else if (value.regionMatches(true, 0, "data:image/", 0, 11) && value.length() <= 16384) {
                            metadata.put("icon", value);
                        }
                    }
                    break;
                }
                case "match": case "include":
                    includes.add(UrlPattern.parse(value, key));
                    break;
                case "exclude": case "exclude-match":
                    excludes.add(UrlPattern.parse(value, key));
                    break;
                case "require": {
                    URI reqUri = UserScriptInput.safeRemoteAddress(value);
                    if (reqUri == null) throw invalid("无效的 @require 地址：" + value);
                    String normalized = reqUri.toASCIIString();
                    if (!requires.contains(normalized)) {
                        if (requires.size() >= MAX_REQUIRES) {
                            throw invalid("每个脚本最多支持 " + MAX_REQUIRES + " 个 @require 依赖");
                        }
                        requires.add(normalized);
                    }
                    break;
                }
                case "resource": {
                    Matcher rm = RESOURCE_PAIR.matcher(value);
                    if (!rm.matches()) throw invalid("无效的 @resource 声明：" + value);
                    String resName = rm.group(1).trim();
                    String resUrlRaw = rm.group(2).trim();
                    if (resName.isEmpty() || resName.length() > 128) {
                        throw invalid("无效的 @resource 名称：" + resName);
                    }
                    URI resUri = UserScriptInput.safeRemoteAddress(resUrlRaw);
                    if (resUri == null) throw invalid("无效的 @resource 地址：" + resUrlRaw);
                    if (!resources.containsKey(resName) && resources.size() >= MAX_RESOURCES) {
                        throw invalid("每个脚本最多支持 " + MAX_RESOURCES + " 个 @resource 资源");
                    }
                    resources.put(resName, resUri.toASCIIString());
                    break;
                }
                case "connect": {
                    String normalized = normalizeConnectRule(value);
                    if (normalized == null) throw invalid("无效的 @connect 规则：" + value);
                    if (!connects.contains(normalized)) {
                        if (connects.size() >= MAX_CONNECTS) {
                            throw invalid("每个脚本最多支持 " + MAX_CONNECTS + " 条 @connect 规则");
                        }
                        connects.add(normalized);
                    }
                    break;
                }
                case "grant":
                    if (value.equals("none")) none = true;
                    else if (GRANTS.contains(value)) grants.add(value);
                    else unsupported.add("@grant " + value);
                    break;
                case "webRequest": case "unwrap": case "run-in":
                    unsupported.add("@" + key);
                    break;
                case "sandbox":
                    if (!value.equals("DOM") && !value.equals("raw") && !value.equals("JavaScript"))
                        unsupported.add("@sandbox " + value);
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
        return new UserScript(source, code, metadata, includes, excludes, grants, requires, resources, connects);
    }

    /** Extracts a single metadata field from a .user.js or body-less .meta.js header (pure Java). */
    public static String extractMetaField(String input, String field) {
        if (input == null || field == null || field.isEmpty()) return "";
        String source = input.replace("\r\n", "\n").replace('\r', '\n');
        if (source.startsWith("\ufeff")) source = source.substring(1);
        Matcher start = HEADER.matcher(source), end = END.matcher(source);
        if (!start.find() || start.start() > 4096 || !end.find(start.end()) || end.start() - start.end() > MAX_HEADER_CHARS) {
            return "";
        }
        for (String row : source.substring(start.end(), end.start()).split("\n")) {
            Matcher item = ITEM.matcher(row);
            if (item.matches() && field.equals(item.group(1))) {
                return item.group(2) == null ? "" : item.group(2).trim();
            }
        }
        return "";
    }

    /** Compares semantic/numeric userscript versions cleanly (returns &gt;0 if left &gt; right). */
    public static int compareVersions(String left, String right) {
        String a = normalizeVersion(left);
        String b = normalizeVersion(right);
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return -1;
        if (b.isEmpty()) return 1;

        String[] aMainPre = splitPrerelease(a);
        String[] bMainPre = splitPrerelease(b);
        int mainCmp = compareVersionSegments(aMainPre[0], bMainPre[0]);
        if (mainCmp != 0) return mainCmp;

        boolean aHasPre = !aMainPre[1].isEmpty();
        boolean bHasPre = !bMainPre[1].isEmpty();
        if (!aHasPre && !bHasPre) return 0;
        if (!aHasPre) return 1;  // 1.2.0 > 1.2.0-beta.1
        if (!bHasPre) return -1;
        return compareVersionSegments(aMainPre[1], bMainPre[1]);
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "";
        String s = v.trim();
        if ((s.startsWith("v") || s.startsWith("V")) && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        return s.trim();
    }

    private static String[] splitPrerelease(String v) {
        int dash = v.indexOf('-');
        if (dash < 0) return new String[]{v, ""};
        return new String[]{v.substring(0, dash), v.substring(dash + 1)};
    }

    private static int compareVersionSegments(String a, String b) {
        String[] pa = a.split("[._-]");
        String[] pb = b.split("[._-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            if (sa.equals(sb)) continue;
            int cmp = compareToken(sa, sb);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static int compareToken(String a, String b) {
        boolean aNum = isAllDigits(a);
        boolean bNum = isAllDigits(b);
        if (aNum && bNum) return compareUnsignedDecimal(a, b);
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia, jb = ib;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) ja++;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) jb++;
                int cmp = compareUnsignedDecimal(a.substring(ia, ja), b.substring(ib, jb));
                if (cmp != 0) return cmp;
                ia = ja;
                ib = jb;
            } else {
                int cmp = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (cmp != 0) return cmp;
                ia++;
                ib++;
            }
        }
        return Integer.compare(a.length() - ia, b.length() - ib);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int compareUnsignedDecimal(String a, String b) {
        int ia = 0, ib = 0;
        while (ia < a.length() - 1 && a.charAt(ia) == '0') ia++;
        while (ib < b.length() - 1 && b.charAt(ib) == '0') ib++;
        String na = a.substring(ia), nb = b.substring(ib);
        if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
        return na.compareTo(nb);
    }

    public boolean needsStorage() {
        for (String grant : grants) if (grant.contains("Value")) return true;
        return false;
    }

    public boolean needsXhr() { return permits("xmlhttpRequest"); }

    public boolean needsDownload() { return permits("download"); }

    public boolean needsMenuCommand() {
        return permits("registerMenuCommand") || permits("unregisterMenuCommand");
    }

    public boolean needsResources() {
        return permits("getResourceText") || permits("getResourceURL");
    }

    public boolean needsBridge() {
        return needsStorage() || needsXhr() || needsMenuCommand() || needsDownload();
    }

    public boolean permits(String method) {
        if ("xmlhttpRequest".equals(method) || "xmlHttpRequest".equals(method)) {
            return grants.contains("GM_xmlhttpRequest") || grants.contains("GM.xmlHttpRequest");
        }
        return grants.contains("GM_" + method) || grants.contains("GM." + method);
    }

    /**
     * Evaluates Tampermonkey @connect rules: '*', 'self', 'localhost', exact domain, or subdomain match.
     * When @connect is omitted, allows same-origin (targetHost == pageHost) and requires user approval for cross-origin.
     */
    public boolean isConnectAllowed(String targetHost, String pageHost) {
        return isConnectAllowed(targetHost, pageHost, null);
    }

    /** Evaluates @connect rules plus optional per-script user-approved hosts. */
    public boolean isConnectAllowed(String targetHost, String pageHost, Set<String> approvedHosts) {
        String target = normalizeConnectHost(targetHost);
        if (target.isEmpty()) return false;
        String page = normalizeConnectHost(pageHost);
        if (connects.isEmpty()) {
            if (!page.isEmpty() && target.equals(page)) return true;
        } else {
            for (String rule : connects) {
                if (matchesConnectRule(rule, target, page)) return true;
            }
        }
        if (approvedHosts != null && !approvedHosts.isEmpty()) {
            for (String approved : approvedHosts) {
                String normRule = normalizeConnectRule(approved);
                if (normRule != null && matchesConnectRule(normRule, target, page)) return true;
            }
        }
        return false;
    }

    /** Returns true when @connect was omitted and targetHost is cross-origin (not yet approved). */
    public boolean needsUserConnectApproval(String targetHost, String pageHost) {
        return needsUserConnectApproval(targetHost, pageHost, null);
    }

    public boolean needsUserConnectApproval(String targetHost, String pageHost, Set<String> approvedHosts) {
        String target = normalizeConnectHost(targetHost);
        if (target.isEmpty()) return false;
        if (isConnectAllowed(target, pageHost, approvedHosts)) return false;
        return connects.isEmpty();
    }

    static String normalizeConnectRule(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 255) return null;
        if (s.equals("*") || s.equals("self") || s.equals("localhost")) return s;
        boolean wildcard = s.startsWith("*.");
        String body = wildcard ? s.substring(2) : s;
        if (body.isEmpty() || body.contains("*") || body.contains("/") || body.contains("@")
                || body.contains("?") || body.contains("#") || body.contains(" ")) {
            return null;
        }
        String normalized = normalizeConnectHost(body);
        if (normalized.isEmpty()) return null;
        if (wildcard && UrlPattern.isIpLiteral(normalized)) return null;
        return wildcard ? "*." + normalized : normalized;
    }

    static String normalizeConnectHost(String host) {
        if (host == null) return "";
        String s = host.trim();
        if (s.isEmpty() || s.length() > 255) return "";
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.toLowerCase(Locale.ROOT);
            try {
                return URI.create("https://" + s + "/").getHost() != null ? s : "";
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        if (s.contains(":") && !s.contains("/")) {
            // Bare IPv6 literal without brackets
            String bracketed = "[" + s.toLowerCase(Locale.ROOT) + "]";
            try {
                return URI.create("https://" + bracketed + "/").getHost() != null ? bracketed : "";
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        try {
            String ascii = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            while (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
            if (ascii.isEmpty() || URI.create("https://" + ascii + "/").getHost() == null) return "";
            return ascii;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean matchesConnectRule(String rule, String target, String page) {
        if ("*".equals(rule)) return true;
        if ("self".equals(rule)) return !page.isEmpty() && target.equals(page);
        if ("localhost".equals(rule)) {
            return "localhost".equals(target) || "127.0.0.1".equals(target) || "[::1]".equals(target);
        }
        String base = rule.startsWith("*.") ? rule.substring(2) : rule;
        if (UrlPattern.isIpLiteral(base)) return target.equals(base);
        return target.equals(base) || target.endsWith("." + base);
    }

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
            // Common Greasy Fork shorthand: @include https://example.com* (without slash before '*')
            if (pathStart < 0 && authority.length() > 1 && authority.endsWith("*")) {
                authority = authority.substring(0, authority.length() - 1);
            }
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

        static boolean isIpLiteral(String host) {
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
