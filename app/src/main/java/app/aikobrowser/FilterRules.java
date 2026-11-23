package app.aikobrowser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.regex.Pattern;

/** A read-only index for the ABP rules that Android WebView can apply. */
final class FilterRules {
    static final FilterRules EMPTY = new FilterRules(new Builder());
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Pattern UNSAFE_SELECTOR = Pattern.compile("[{};@\\\\]|:(?:has-text|contains|matches-css|xpath|style|remove|upward)\\(");
    private static final Map<String, Integer> TYPES = new HashMap<>();
    static {
        String[] types = {"script", "image", "stylesheet", "font", "media", "subdocument", "xmlhttprequest", "object"};
        for (int i = 0; i < types.length; i++) TYPES.put(types[i], 1 << i);
    }
    private final Map<String, List<Rule>> hosts, pageExceptions;
    private final FragmentIndex fragments;
    private final Map<String, List<Cosmetic>> cosmetics;
    private final Set<String> suffixes;
    private final String evidence;
    final int networkCount, cosmeticCount, skipped;

    private FilterRules(Builder b) {
        hosts = b.hosts;
        fragments = new FragmentIndex(b.fragments);
        pageExceptions = b.pageExceptions;
        cosmetics = b.cosmetics;
        suffixes = b.suffixes;
        evidence = b.evidence.toString();
        networkCount = b.networkCount;
        cosmeticCount = b.cosmeticCount;
        skipped = b.skipped;
    }

    String siteKey(String host) {
        if (host == null || host.indexOf(':') >= 0 || numericHost(host)) return host;
        String current = host, previous = host, registrable = host;
        boolean found = false;
        for (int dot = current.indexOf('.'); dot >= 0; dot = current.indexOf('.')) {
            if (suffixes.contains("!" + current)) return current;
            if (!found && (suffixes.contains(current) || suffixes.contains("*" + current.substring(dot)))) {
                registrable = previous;
                found = true;
            }
            previous = current;
            current = current.substring(dot + 1);
        }
        return registrable == host && !found ? previous : registrable;
    }

    static int requestType(String destination, String path) {
        if (destination != null) {
            switch (destination) {
                case "script": case "worker": case "sharedworker": return 1;
                case "image": return 2;
                case "style": return 4;
                case "font": return 8;
                case "audio": case "video": case "track": return 16;
                case "iframe": case "frame": return 32;
                case "object": case "embed": return 128;
                default: break;
            }
        }
        // With a missing destination, use only unambiguous static asset suffixes.
        if (path == null) return 0;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || path.length() - dot > 6) return 0;
        switch (path.substring(dot).toLowerCase(Locale.ROOT)) {
            case ".js": case ".mjs": return 1;
            case ".gif": case ".png": case ".jpeg": case ".jpg": case ".webp": case ".svg": case ".avif": return 2;
            case ".css": return 4;
            case ".woff": case ".woff2": case ".ttf": case ".otf": return 8;
            case ".mp4": case ".mp3": case ".webm": return 16;
            default: return 0;
        }
    }

    boolean exceptsPage(String host, String url, boolean cosmetic) {
        if (host == null || url == null || pageExceptions.isEmpty()) return false;
        String path = null;
        for (String suffix = host; suffix != null; suffix = parent(suffix)) {
            List<Rule> list = pageExceptions.get(suffix);
            if (list == null) continue;
            for (Rule rule : list) {
                if (rule.cosmeticOnly && !cosmetic) continue;
                if (rule.pattern != null && path == null) path = anchoredUrl(url);
                if (rule.applies(host, path, url, false, 0)) return true;
            }
        }
        return false;
    }

    boolean blocks(String topHost, String topKey, String host, String url, int type, String fetchSite) {
        return blockingRule(topHost, topKey, host, url, type, fetchSite) != null;
    }

    Rule blockingRule(String topHost, String topKey, String host, String url, int type, String fetchSite) {
        if (topHost == null || host == null || url == null || url.length() > 4096) return null;
        String path = null;
        Rule blocked = null;
        boolean partyKnown = fetchSite != null && (fetchSite.equals("cross-site")
                || fetchSite.equals("same-site") || fetchSite.equals("same-origin"));
        boolean third = partyKnown ? fetchSite.equals("cross-site")
                : !host.equals(topHost) && !Objects.equals(topKey, siteKey(host));
        for (String suffix = host; suffix != null; suffix = parent(suffix)) {
            List<Rule> list = hosts.get(suffix);
            if (list == null) continue;
            for (Rule rule : list) {
                if (rule.pattern != null && path == null) path = anchoredUrl(url);
                if (rule.applies(topHost, path, url, third, type)) {
                    if (rule.allow) return null;
                    if (blocked == null) blocked = rule;
                }
            }
        }
        if (fragments.keys.length > 0) {
            long code = 0;
            for (int i = 0; i < url.length(); i++) {
                code = step(code, url.charAt(i));
                if (i < 4) continue;
                List<Rule> list = fragments.get(code);
                if (list == null) continue;
                for (Rule rule : list) {
                    if (rule.applies(topHost, null, url, third, type)) {
                        if (rule.allow) return null;
                        if (blocked == null) blocked = rule;
                    }
                }
            }
        }
        return blocked;
    }

    String description(Rule rule) {
        return evidence.substring(rule.evidenceStart, rule.evidenceEnd);
    }

    private static final class FragmentIndex {
        final long[] keys;
        final List<Rule>[] values;

        @SuppressWarnings("unchecked")
        FragmentIndex(Map<Long, List<Rule>> rules) {
            int size = 0;
            if (!rules.isEmpty()) {
                size = 1;
                while (size < 2 * rules.size()) size <<= 1;
            }
            keys = new long[size];
            values = (List<Rule>[]) new List<?>[size];
            for (Map.Entry<Long, List<Rule>> item : rules.entrySet()) {
                int index = slot(item.getKey());
                keys[index] = item.getKey();
                values[index] = item.getValue();
            }
        }

        List<Rule> get(long code) { return values[slot(code)]; }

        private int slot(long code) {
            int mask = keys.length - 1;
            long mixed = code ^ (code >>> 23);
            int slot = (int) ((mixed * 0x9e3779b97f4a7c15L) >>> 32) & mask;
            while (values[slot] != null && keys[slot] != code) slot = (slot + 1) & mask;
            return slot;
        }
    }

    String css(String host, String url) {
        if (host == null || exceptsPage(host, url, true)) return "";
        Set<String> hide = new LinkedHashSet<>(), allow = new HashSet<>();
        for (String suffix = host; suffix != null; suffix = parent(suffix)) {
            List<Cosmetic> list = cosmetics.get(suffix);
            if (list == null) continue;
            for (Cosmetic rule : list) {
                if (rule.scope.applies(host)) (rule.allow ? allow : hide).add(rule.selector);
            }
        }
        hide.removeAll(allow);
        StringBuilder rules = new StringBuilder();
        for (String selector : hide) {
            if (rules.length() + selector.length() > 96000) break;
            rules.append(selector).append("{display:none!important}\n");
        }
        return rules.toString();
    }

    static final class Builder {
        final Map<String, List<Rule>> hosts = new HashMap<>(), pageExceptions = new HashMap<>();
        final Map<Long, List<Rule>> fragments = new HashMap<>();
        final Map<String, List<Cosmetic>> cosmetics = new HashMap<>();
        final Set<String> suffixes = new HashSet<>(), disabled = new HashSet<>();
        final Map<String, String> lines = new LinkedHashMap<>();
        // Store matched text together, rather than retaining a separate String for every rule.
        final StringBuilder evidence = new StringBuilder();
        int networkCount, cosmeticCount, skipped;

        void suffixes(Reader input) throws IOException {
            try (BufferedReader reader = new BufferedReader(input)) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.isEmpty() && !line.startsWith("//")) {
                        try {
                            String marker = line.startsWith("*.") ? "*." : line.startsWith("!") ? "!" : "";
                            suffixes.add(marker + java.net.IDN.toASCII(line.substring(marker.length())).toLowerCase(Locale.ROOT));
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        }

        void read(Reader input) throws IOException {
            read(input, "过滤规则");
        }

        void read(Reader input, String source) throws IOException {
            try (BufferedReader reader = new BufferedReader(input)) {
                for (String line; (line = reader.readLine()) != null;) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue;
                    if (line.length() > 1500 || lines.size() >= 200000) { skipped++; continue; }
                    String target = disabledRule(line);
                    if (target != null) disabled.add(target); else lines.putIfAbsent(line, source);
                }
            }
        }

        FilterRules build() {
            for (Map.Entry<String, String> entry : lines.entrySet()) if (!disabled.contains(entry.getKey())) {
                if (!addRule(entry.getKey(), entry.getValue())) skipped++;
            }
            return new FilterRules(this);
        }

        static String disabledRule(String line) {
            int first = line.indexOf('$');
            if (first <= 0 || !line.contains("badfilter")
                    || !line.substring(first + 1).matches("(?:[^,]+,)*badfilter(?:,[^,]+)*")) return null;
            ArrayList<String> options = new ArrayList<>(Arrays.asList(line.substring(first + 1).split(",")));
            options.remove("badfilter");
            return line.substring(0, first) + (options.isEmpty() ? "" : "$" + String.join(",", options));
        }

        boolean addRule(String text) {
            return addRule(text, null);
        }

        private boolean addRule(String text, String source) {
            int marker = text.indexOf("#@#");
            boolean cosmeticAllow = marker >= 0;
            if (!cosmeticAllow) marker = text.indexOf("##");
            return marker >= 0 ? cosmetic(text, marker, cosmeticAllow) : network(text, source);
        }

        private boolean cosmetic(String text, int index, boolean allow) {
            String selector = text.substring(index + (allow ? 3 : 2));
            if (index == 0 || selector.length() > 500 || selector.isEmpty()
                    || UNSAFE_SELECTOR.matcher(selector).find() || selector.startsWith("+js")
                    || text.substring(0, index).contains("#")) return false;
            Scope scope = Scope.parse(text.substring(0, index), ",");
            if (scope == null || scope.include.length == 0) return false;
            Cosmetic rule = new Cosmetic(scope, selector, allow);
            for (String host : scope.include) add(cosmetics, host, rule);
            cosmeticCount++;
            return true;
        }

        private boolean network(String text, String source) {
            boolean allow = text.startsWith("@@");
            String body = allow ? text.substring(2) : text;
            int dollar = body.indexOf('$');
            String modifiers = dollar < 0 ? "" : body.substring(dollar + 1);
            String value = dollar < 0 ? body : body.substring(0, dollar);
            if (value.isEmpty() || value.startsWith("/") && value.endsWith("/") && value.length() > 2
                    || value.contains("#") || value.length() > 350) return false;
            Scope scope = Scope.ANY;
            int party = 0, types = 0, exceptTypes = 0;
            boolean page = false, cosmeticOnly = false;
            for (String modifier : modifiers.split(",")) {
                if (modifier.isEmpty()) continue;
                if (modifier.equals("third-party") || modifier.equals("3p")) party = 1;
                else if (modifier.equals("~third-party") || modifier.equals("1p")) party = -1;
                else if (modifier.startsWith("domain=")) {
                    scope = Scope.parse(modifier.substring(7), "\\|");
                    if (scope == null) return false;
                } else if (allow && modifier.equals("document")) {
                    page = true;
                } else if (allow && modifier.equals("elemhide")) {
                    page = true; cosmeticOnly = true;
                } else {
                    boolean neg = modifier.startsWith("~");
                    Integer bit = TYPES.get(neg ? modifier.substring(1) : modifier);
                    if (bit == null) return false;
                    if (neg) exceptTypes |= bit; else types |= bit;
                }
            }
            if (value.startsWith("||")) {
                int end = 2;
                while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end))
                        || value.charAt(end) == '.' || value.charAt(end) == '-')) end++;
                String host = value.substring(2, end).toLowerCase(Locale.ROOT);
                if (!validHost(host) || host.indexOf('.') < 0) return false;
                String tail = value.substring(end);
                if (!tail.isEmpty() && "^/:?|".indexOf(tail.charAt(0)) < 0) return false;
                if (page && (party != 0 || types != 0 || exceptTypes != 0)) return false;
                Pattern pattern = null;
                if (!tail.isEmpty() && !tail.equals("^")) {
                    pattern = pattern(tail, true);
                    if (pattern == null) return false;
                }
                add(page ? pageExceptions : hosts, host, rule(text, source, pattern, scope, allow,
                        party, types, exceptTypes, cosmeticOnly, true));
            } else {
                if (page) return false;
                String literal = "";
                for (String token : value.split("[*^|]")) if (token.length() > literal.length()) literal = token;
                if (literal.length() < 5) return false;
                Pattern pattern = pattern(value, false);
                if (pattern == null) return false;
                // A key late in the longest literal avoids putting every HTTPS rule in the same bucket.
                long key = 0;
                for (int i = literal.length() - 5; i < literal.length(); i++) key = step(key, literal.charAt(i));
                add(fragments, key, rule(text, source, pattern, scope, allow, party, types, exceptTypes, false, false));
            }
            networkCount++;
            return true;
        }

        private Rule rule(String text, String source, Pattern pattern, Scope scope, boolean allow,
                          int party, int types, int exceptTypes, boolean cosmeticOnly, boolean anchored) {
            int start = evidence.length();
            if (!allow && source != null) evidence.append(text);
            return new Rule(pattern, scope, allow, party, types, exceptTypes, cosmeticOnly, anchored,
                    source, start, evidence.length());
        }
    }

    private static final class Scope {
        static final Scope ANY = new Scope(new String[0], new String[0]);
        final String[] include, exclude;
        Scope(String[] include, String[] exclude) { this.include = include; this.exclude = exclude; }
        static Scope parse(String value, String delimiter) {
            ArrayList<String> allow = new ArrayList<>(), deny = new ArrayList<>();
            for (String part : value.toLowerCase(Locale.ROOT).split(delimiter)) {
                boolean neg = part.startsWith("~");
                String host = neg ? part.substring(1) : part;
                if (!validHost(host)) return null;
                (neg ? deny : allow).add(host);
            }
            return new Scope(allow.toArray(new String[0]), deny.toArray(new String[0]));
        }
        boolean applies(String host) {
            for (String item : exclude) if (onDomain(host, item)) return false;
            if (include.length == 0) return true;
            for (String item : include) if (onDomain(host, item)) return true;
            return false;
        }
    }

    static final class Rule {
        final Pattern pattern;
        final Scope scope;
        final boolean allow, cosmeticOnly, anchored;
        final int party, types, exceptTypes;
        final String source;
        final int evidenceStart, evidenceEnd;
        Rule(Pattern pattern, Scope scope, boolean allow, int party, int types, int exceptTypes,
             boolean cosmeticOnly, boolean anchored, String source, int evidenceStart, int evidenceEnd) {
            this.pattern = pattern;
            this.scope = scope;
            this.allow = allow;
            this.party = party;
            this.types = types;
            this.exceptTypes = exceptTypes;
            this.cosmeticOnly = cosmeticOnly;
            this.anchored = anchored;
            this.source = source;
            this.evidenceStart = evidenceStart;
            this.evidenceEnd = evidenceEnd;
        }
        boolean applies(String top, String path, String url, boolean third, int type) {
            if ((party != 0 && (party > 0) != third)
                    || (types != 0 && (types & type) == 0)
                    || (exceptTypes != 0 && (type == 0 || (exceptTypes & type) != 0))
                    || !scope.applies(top)) return false;
            return pattern == null || pattern.matcher(anchored ? path : url).find();
        }
    }

    private static final class Cosmetic {
        final Scope scope;
        final String selector;
        final boolean allow;
        Cosmetic(Scope scope, String selector, boolean allow) {
            this.scope = scope; this.selector = selector; this.allow = allow;
        }
    }

    private static Pattern pattern(String token, boolean anchored) {
        StringBuilder regex = new StringBuilder();
        if (anchored) regex.append('^');
        int wildcards = 0, start = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '*' && c != '^' && c != '|') continue;
            if (i > start) regex.append(Pattern.quote(token.substring(start, i)));
            if (c == '*') {
                if (++wildcards > 2) return null;
                regex.append(".*");
            } else if (c == '^') {
                regex.append("(?:[^A-Za-z0-9_.%\\-]|$)");
            } else if (i == 0 && !anchored) {
                regex.append('^');
            } else if (i == token.length() - 1) {
                regex.append('$');
            } else {
                return null;
            }
            start = i + 1;
        }
        if (start < token.length()) regex.append(Pattern.quote(token.substring(start)));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String anchoredUrl(String url) {
        int start = url.indexOf("://");
        for (int i = start < 0 ? 0 : start + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') return url.substring(i);
        }
        return "";
    }

    private static long step(long code, char c) {
        if (c >= 'A' && c <= 'Z') c = (char) (c | 32);
        return ((code << 8) | (c & 255)) & 0xffffffffffL;
    }

    private static String parent(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? null : host.substring(dot + 1);
    }

    private static boolean validHost(String host) {
        return DOMAIN.matcher(host).matches() && !host.contains("..");
    }

    private static boolean numericHost(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') return false;
        }
        return true;
    }

    private static boolean onDomain(String host, String domain) {
        int start = host.length() - domain.length();
        return start >= 0 && host.regionMatches(true, start, domain, 0, domain.length())
                && (start == 0 || host.charAt(start - 1) == '.');
    }

    private static <T, V> void add(Map<T, List<V>> map, T key, V value) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }
}
