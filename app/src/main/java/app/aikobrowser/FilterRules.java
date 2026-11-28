package app.aikobrowser;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A read-only index for the ABP rules that Android WebView can apply. */
final class FilterRules {
    static final FilterRules EMPTY = new FilterRules();
    private static final int BINARY_MAGIC = 0x41494B46; // "AIKF"
    private static final int BINARY_END = 0x464B4941;   // "FKIA"
    private static final int BINARY_VERSION = 1;
    private static final int MAX_BINARY_ITEMS = 600_000;
    private static final int MAX_EVIDENCE_BYTES = 24 * 1024 * 1024;
    private static final int MAX_RULE_LINE_CHARS = 32_768;
    private static final int MAX_UNIQUE_LINES = 450_000;

    private static final int FLAG_ALLOW = 1 << 16;
    private static final int FLAG_COSMETIC_ONLY = 1 << 17;
    private static final int FLAG_ANCHORED = 1 << 18;
    private static final int FLAG_PARTY_3P = 1 << 19;
    private static final int FLAG_PARTY_1P = 1 << 20;
    private static final int PARTY_MASK = FLAG_PARTY_3P | FLAG_PARTY_1P;
    private static final int CHECK_MASK = 0xFFFF | PARTY_MASK;
    private static final int KEY_END_SHIFT = 21;

    private static final long[] BOILERPLATE_5GRAMS = {
            gram5("http:"), gram5("ttps:"), gram5("tps:/"), gram5("ps://"), gram5("s://w"),
            gram5("://ww"), gram5("//www"), gram5("/www."), gram5(".com/"), gram5(".net/"),
            gram5(".org/"), gram5("in.js"), gram5("er.js"), gram5(".gif?"), gram5(".php?"),
            gram5("cs.js"), gram5("ng.js"), gram5("https"), gram5("://m."), gram5(".html"),
            gram5(".aspx"), gram5("ts.js"), gram5("ck.js"), gram5("ad.js"), gram5("/ads/"),
            gram5("s.php"), gram5("ag.js"), gram5("nt.js"), gram5("at.js"), gram5(".cgi?")
    };

    private final HostTable hosts, pageExceptions, cosmetics;
    private final FragmentIndex blockFragments, allowFragments;
    private final StringSet exactSuffixes, wildcardSuffixes, exceptionSuffixes;
    private final byte[] evidence;
    final int networkCount, cosmeticCount, skipped;

    private FilterRules() {
        hosts = HostTable.EMPTY;
        pageExceptions = HostTable.EMPTY;
        cosmetics = HostTable.EMPTY;
        blockFragments = FragmentIndex.EMPTY;
        allowFragments = FragmentIndex.EMPTY;
        exactSuffixes = StringSet.EMPTY;
        wildcardSuffixes = StringSet.EMPTY;
        exceptionSuffixes = StringSet.EMPTY;
        evidence = new byte[0];
        networkCount = 0;
        cosmeticCount = 0;
        skipped = 0;
    }

    private FilterRules(HostTable hosts, HostTable pageExceptions, HostTable cosmetics,
                        FragmentIndex blockFragments, FragmentIndex allowFragments,
                        StringSet exactSuffixes, StringSet wildcardSuffixes, StringSet exceptionSuffixes,
                        byte[] evidence, int networkCount, int cosmeticCount, int skipped) {
        this.hosts = hosts;
        this.pageExceptions = pageExceptions;
        this.cosmetics = cosmetics;
        this.blockFragments = blockFragments;
        this.allowFragments = allowFragments;
        this.exactSuffixes = exactSuffixes;
        this.wildcardSuffixes = wildcardSuffixes;
        this.exceptionSuffixes = exceptionSuffixes;
        this.evidence = evidence;
        this.networkCount = networkCount;
        this.cosmeticCount = cosmeticCount;
        this.skipped = skipped;
    }

    String siteKey(String host) {
        if (host == null || host.indexOf(':') >= 0 || numericHost(host)) return host;
        int current = 0, previous = 0, registrable = 0;
        boolean found = false;
        boolean hasExc = !exceptionSuffixes.isEmpty();
        boolean hasWild = !wildcardSuffixes.isEmpty();
        for (int dot = host.indexOf('.'); dot >= 0; dot = host.indexOf('.', current)) {
            if (hasExc && exceptionSuffixes.contains(host, current)) {
                return current == 0 ? host : host.substring(current);
            }
            int next = dot + 1;
            if (!found && (exactSuffixes.contains(host, current)
                    || (hasWild && wildcardSuffixes.contains(host, next)))) {
                registrable = previous;
                found = true;
                if (!hasExc) break;
            }
            previous = current;
            current = next;
        }
        int resultStart = (!found && registrable == 0) ? previous : registrable;
        return resultStart == 0 ? host : host.substring(resultStart);
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
        int len = path.length();
        int dot = path.lastIndexOf('.');
        if (dot < 0) return 0;
        int extLen = len - dot;
        if (extLen < 3 || extLen > 6) return 0;
        switch (extLen) {
            case 3:
                if (equalsAsciiLower(path, dot, ".js")) return 1;
                return 0;
            case 4:
                if (equalsAsciiLower(path, dot, ".mjs")) return 1;
                if (equalsAsciiLower(path, dot, ".png") || equalsAsciiLower(path, dot, ".jpg")
                        || equalsAsciiLower(path, dot, ".gif") || equalsAsciiLower(path, dot, ".svg")) return 2;
                if (equalsAsciiLower(path, dot, ".css")) return 4;
                if (equalsAsciiLower(path, dot, ".ttf") || equalsAsciiLower(path, dot, ".otf")) return 8;
                if (equalsAsciiLower(path, dot, ".mp4") || equalsAsciiLower(path, dot, ".mp3")) return 16;
                return 0;
            case 5:
                if (equalsAsciiLower(path, dot, ".webp") || equalsAsciiLower(path, dot, ".jpeg")
                        || equalsAsciiLower(path, dot, ".avif")) return 2;
                if (equalsAsciiLower(path, dot, ".woff")) return 8;
                if (equalsAsciiLower(path, dot, ".webm")) return 16;
                return 0;
            case 6:
                if (equalsAsciiLower(path, dot, ".woff2")) return 8;
                return 0;
            default:
                return 0;
        }
    }

    boolean hasPageExceptions() {
        return !pageExceptions.isEmpty();
    }

    boolean exceptsPage(String host, String url, boolean cosmetic) {
        return exceptsPage(host, url, cosmetic, false);
    }

    // Referrer policy may remove the client path. Honor a matching host's document
    // exceptions conservatively when a worker request has only the origin to go on.
    boolean exceptsPage(String host, String url, boolean cosmetic, boolean unknownPath) {
        if (host == null || url == null || pageExceptions.isEmpty()) return false;
        int pathStart = -1;
        int urlLen = url.length();
        for (int start = 0; ; ) {
            int dot = host.indexOf('.', start);
            if (dot < 0) break;
            Object bucket = pageExceptions.get(host, start);
            if (bucket != null) {
                if (bucket instanceof Rule) {
                    Rule rule = (Rule) bucket;
                    if (!rule.isCosmeticOnly() || cosmetic) {
                        if (unknownPath && rule.scope.applies(host)) return true;
                        if (rule.pattern != null && pathStart < 0) pathStart = anchoredOffset(url);
                        if (rule.matchesPage(host, url, pathStart, urlLen)) return true;
                    }
                } else {
                    Rule[] list = (Rule[]) bucket;
                    for (int i = 0; i < list.length; i++) {
                        Rule rule = list[i];
                        if (rule.isCosmeticOnly() && !cosmetic) continue;
                        if (unknownPath && rule.scope.applies(host)) return true;
                        if (rule.pattern != null && pathStart < 0) pathStart = anchoredOffset(url);
                        if (rule.matchesPage(host, url, pathStart, urlLen)) return true;
                    }
                }
            }
            start = dot + 1;
        }
        return false;
    }

    boolean blocks(String topHost, String topKey, String host, String url, int type, String fetchSite) {
        return blockingRule(topHost, topKey, host, url, type, fetchSite) != null;
    }

    boolean blocksUrlFast(String host, String url) {
        if (host == null || url == null || exceptsPage(host, url, false)) return false;
        return blockingRule(host, host, host, url, 0, "same-origin") != null;
    }

    Rule blockingRule(String topHost, String topKey, String host, String url, int type, String fetchSite) {
        if (topHost == null || host == null || url == null) return null;
        int urlLen = url.length();
        if (urlLen > 4096) return null;

        // -1 = lazy unknown, 0 = first-party, 1 = third-party
        int thirdState;
        if (fetchSite != null) {
            if ("cross-site".equals(fetchSite)) thirdState = 1;
            else if ("same-site".equals(fetchSite) || "same-origin".equals(fetchSite)) thirdState = 0;
            else thirdState = host.equals(topHost) ? 0 : -1;
        } else {
            thirdState = host.equals(topHost) ? 0 : -1;
        }

        int pathStart = -1;
        Rule blocked = null;

        // 1. Host-anchored rules (zero-alloc suffix walk, skipping single-label TLDs)
        if (!hosts.isEmpty()) {
            for (int start = 0; ; ) {
                int dot = host.indexOf('.', start);
                if (dot < 0) break;
                Object bucket = hosts.get(host, start);
                if (bucket != null) {
                    if (bucket instanceof Rule) {
                        Rule rule = (Rule) bucket;
                        boolean allow = rule.isAllow();
                        if (blocked == null || allow) {
                            if (rule.needsParty() && thirdState < 0) {
                                thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                            }
                            if (rule.pattern != null && pathStart < 0) pathStart = anchoredOffset(url);
                            if (rule.appliesHost(topHost, url, pathStart, urlLen, thirdState == 1, type)) {
                                if (allow) return null;
                                blocked = rule;
                            }
                        }
                    } else {
                        Rule[] list = (Rule[]) bucket;
                        for (int i = 0; i < list.length; i++) {
                            Rule rule = list[i];
                            boolean allow = rule.isAllow();
                            if (blocked != null && !allow) continue;
                            if (rule.needsParty() && thirdState < 0) {
                                thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                            }
                            if (rule.pattern != null && pathStart < 0) pathStart = anchoredOffset(url);
                            if (rule.appliesHost(topHost, url, pathStart, urlLen, thirdState == 1, type)) {
                                if (allow) return null;
                                blocked = rule;
                            }
                        }
                    }
                }
                start = dot + 1;
            }
        }

        // 2. Fragment block rules (only scanned if not already blocked by hosts)
        if (blocked == null && !blockFragments.isEmpty() && urlLen >= 5) {
            long code = 0;
            for (int i = 0; i < urlLen; i++) {
                code = step(code, url.charAt(i));
                if (i < 4) continue;
                Object bucket = blockFragments.get(code);
                if (bucket == null) continue;
                if (bucket instanceof Rule) {
                    Rule rule = (Rule) bucket;
                    if (rule.needsParty() && thirdState < 0) {
                        thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                    }
                    if (rule.appliesFragment(topHost, url, urlLen, i, thirdState == 1, type)) {
                        blocked = rule;
                        break;
                    }
                } else {
                    Rule[] list = (Rule[]) bucket;
                    for (int j = 0; j < list.length; j++) {
                        Rule rule = list[j];
                        if (rule.needsParty() && thirdState < 0) {
                            thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                        }
                        if (rule.appliesFragment(topHost, url, urlLen, i, thirdState == 1, type)) {
                            blocked = rule;
                            break;
                        }
                    }
                    if (blocked != null) break;
                }
            }
        }

        // 3. Fragment allow rules (only scanned when a block rule matched!)
        if (blocked != null && !allowFragments.isEmpty() && urlLen >= 5) {
            long code = 0;
            for (int i = 0; i < urlLen; i++) {
                code = step(code, url.charAt(i));
                if (i < 4) continue;
                Object bucket = allowFragments.get(code);
                if (bucket == null) continue;
                if (bucket instanceof Rule) {
                    Rule rule = (Rule) bucket;
                    if (rule.needsParty() && thirdState < 0) {
                        thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                    }
                    if (rule.appliesFragment(topHost, url, urlLen, i, thirdState == 1, type)) {
                        return null;
                    }
                } else {
                    Rule[] list = (Rule[]) bucket;
                    for (int j = 0; j < list.length; j++) {
                        Rule rule = list[j];
                        if (rule.needsParty() && thirdState < 0) {
                            thirdState = Objects.equals(topKey, siteKey(host)) ? 0 : 1;
                        }
                        if (rule.appliesFragment(topHost, url, urlLen, i, thirdState == 1, type)) {
                            return null;
                        }
                    }
                }
            }
        }

        return blocked;
    }

    String description(Rule rule) {
        int len = rule.evidenceEnd - rule.evidenceStart;
        return len <= 0 ? "" : new String(evidence, rule.evidenceStart, len, StandardCharsets.UTF_8);
    }

    String css(String host, String url) {
        if (host == null || exceptsPage(host, url, true)) return "";
        return cssForSiteOnly(host);
    }

    String cssForSiteOnly(String host) {
        if (host == null || cosmetics.isEmpty()) return "";
        Set<String> hide = null;
        Set<String> allow = null;
        for (int start = 0; ; ) {
            Object bucket = cosmetics.get(host, start);
            if (bucket != null) {
                if (bucket instanceof Cosmetic) {
                    Cosmetic rule = (Cosmetic) bucket;
                    if (rule.scope.applies(host)) {
                        if (rule.allow) {
                            if (allow == null) allow = new HashSet<>(8);
                            allow.add(rule.selector);
                        } else {
                            if (hide == null) hide = new LinkedHashSet<>(16);
                            hide.add(rule.selector);
                        }
                    }
                } else {
                    Cosmetic[] list = (Cosmetic[]) bucket;
                    for (int i = 0; i < list.length; i++) {
                        Cosmetic rule = list[i];
                        if (rule.scope.applies(host)) {
                            if (rule.allow) {
                                if (allow == null) allow = new HashSet<>(8);
                                allow.add(rule.selector);
                            } else {
                                if (hide == null) hide = new LinkedHashSet<>(16);
                                hide.add(rule.selector);
                            }
                        }
                    }
                }
            }
            int dot = host.indexOf('.', start);
            if (dot < 0) break;
            start = dot + 1;
        }
        if (hide == null || hide.isEmpty()) return "";
        if (allow != null) hide.removeAll(allow);
        if (hide.isEmpty()) return "";
        StringBuilder rules = new StringBuilder(Math.min(96000, hide.size() * 40));
        for (String selector : hide) {
            if (rules.length() + selector.length() > 96000) break;
            rules.append(selector).append("{display:none!important}\n");
        }
        return rules.toString();
    }

    // --- Binary serialization for fingerprinted cold-start cache ---

    void writeBinary(DataOutputStream out) throws IOException {
        byte[] scratch = new byte[4096];
        out.writeInt(BINARY_MAGIC);
        out.writeInt(BINARY_VERSION);
        out.writeInt(networkCount);
        out.writeInt(cosmeticCount);
        out.writeInt(skipped);
        out.writeInt(evidence.length);
        out.write(evidence);

        // Collect unique source strings for compact storage
        ArrayList<String> sourceList = new ArrayList<>(8);
        Map<String, Integer> sourceIds = new HashMap<>(16);
        collectSources(hosts, sourceList, sourceIds);
        collectSources(pageExceptions, sourceList, sourceIds);
        collectSources(blockFragments, sourceList, sourceIds);
        collectSources(allowFragments, sourceList, sourceIds);
        out.writeInt(sourceList.size());
        for (int i = 0; i < sourceList.size(); i++) {
            writeUtf(out, sourceList.get(i), scratch);
        }

        writeStringSet(out, exactSuffixes, scratch);
        writeStringSet(out, wildcardSuffixes, scratch);
        writeStringSet(out, exceptionSuffixes, scratch);
        writeRuleHostTable(out, hosts, sourceIds, scratch);
        writeRuleHostTable(out, pageExceptions, sourceIds, scratch);
        writeFragmentIndex(out, blockFragments, sourceIds, scratch);
        writeFragmentIndex(out, allowFragments, sourceIds, scratch);
        writeCosmeticHostTable(out, cosmetics, scratch);
        out.writeInt(BINARY_END);
    }

    static FilterRules readBinary(DataInputStream in) throws IOException {
        if (in.readInt() != BINARY_MAGIC || in.readInt() != BINARY_VERSION) {
            throw new IOException("Incompatible filter cache");
        }
        byte[] scratch = new byte[4096];
        int networkCount = in.readInt();
        int cosmeticCount = in.readInt();
        int skipped = in.readInt();
        int evLen = in.readInt();
        if (evLen < 0 || evLen > MAX_EVIDENCE_BYTES) throw new IOException("Invalid evidence size");
        byte[] evidence = new byte[evLen];
        in.readFully(evidence);

        int srcCount = in.readInt();
        if (srcCount < 0 || srcCount > 1024) throw new IOException("Invalid source pool");
        String[] sources = new String[srcCount];
        for (int i = 0; i < srcCount; i++) sources[i] = readUtf(in, scratch);

        StringSet exact = readStringSet(in, scratch);
        StringSet wild = readStringSet(in, scratch);
        StringSet exc = readStringSet(in, scratch);
        HostTable hosts = readRuleHostTable(in, sources, evLen, scratch);
        HostTable pageExceptions = readRuleHostTable(in, sources, evLen, scratch);
        FragmentIndex blockFragments = readFragmentIndex(in, sources, evLen, scratch);
        FragmentIndex allowFragments = readFragmentIndex(in, sources, evLen, scratch);
        HostTable cosmetics = readCosmeticHostTable(in, scratch);
        if (in.readInt() != BINARY_END) throw new IOException("Truncated filter cache");
        return new FilterRules(hosts, pageExceptions, cosmetics, blockFragments, allowFragments,
                exact, wild, exc, evidence, networkCount, cosmeticCount, skipped);
    }

    private static void collectSources(HostTable table, List<String> list, Map<String, Integer> ids) {
        Object[] vals = table.values;
        for (int i = 0; i < vals.length; i++) {
            Object v = vals[i];
            if (v == null) continue;
            if (v instanceof Rule) registerSource(((Rule) v).source, list, ids);
            else {
                Rule[] arr = (Rule[]) v;
                for (int j = 0; j < arr.length; j++) registerSource(arr[j].source, list, ids);
            }
        }
    }

    private static void collectSources(FragmentIndex index, List<String> list, Map<String, Integer> ids) {
        Object[] vals = index.values;
        for (int i = 0; i < vals.length; i++) {
            Object v = vals[i];
            if (v == null) continue;
            if (v instanceof Rule) registerSource(((Rule) v).source, list, ids);
            else {
                Rule[] arr = (Rule[]) v;
                for (int j = 0; j < arr.length; j++) registerSource(arr[j].source, list, ids);
            }
        }
    }

    private static void registerSource(String source, List<String> list, Map<String, Integer> ids) {
        if (source != null && !ids.containsKey(source) && list.size() < 255) {
            ids.put(source, list.size());
            list.add(source);
        }
    }

    private static void writeVarInt(DataOutputStream out, int v) throws IOException {
        // Unsigned varint for compact non-negative lengths
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0, shift = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Corrupt varint");
    }

    private static void writeUtf(DataOutputStream out, String s, byte[] scratch) throws IOException {
        if (s == null) {
            writeVarInt(out, 0);
            return;
        }
        int n = s.length();
        if (n <= scratch.length) {
            boolean ascii = true;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c >= 0x80) { ascii = false; break; }
                scratch[i] = (byte) c;
            }
            if (ascii) {
                writeVarInt(out, n + 1);
                out.write(scratch, 0, n);
                return;
            }
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length + 1);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in, byte[] scratch) throws IOException {
        int raw = readVarInt(in);
        if (raw == 0) return null;
        int len = raw - 1;
        if (len == 0) return "";
        if (len > 262_144) throw new IOException("String too large");
        if (len <= scratch.length) {
            in.readFully(scratch, 0, len);
            return new String(scratch, 0, len, StandardCharsets.UTF_8);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStringSet(DataOutputStream out, StringSet set, byte[] scratch) throws IOException {
        out.writeInt(set.size);
        for (int i = 0; i < set.keys.length; i++) {
            String k = set.keys[i];
            if (k != null) writeUtf(out, k, scratch);
        }
    }

    private static StringSet readStringSet(DataInputStream in, byte[] scratch) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_BINARY_ITEMS) throw new IOException("Invalid set size");
        if (count == 0) return StringSet.EMPTY;
        StringSet.Builder b = new StringSet.Builder(count);
        for (int i = 0; i < count; i++) b.add(readUtf(in, scratch));
        return b.build();
    }

    private static void writeRule(DataOutputStream out, Rule r, Map<String, Integer> sourceIds, byte[] scratch)
            throws IOException {
        out.writeInt(r.mask);
        writeVarInt(out, r.evidenceStart);
        writeVarInt(out, r.evidenceEnd - r.evidenceStart);
        Integer srcId = r.source == null ? null : sourceIds.get(r.source);
        out.writeByte(srcId == null || srcId >= 255 ? 0xFF : srcId);
        if (r.pattern == null) {
            out.writeByte(0);
        } else if (r.pattern instanceof String) {
            out.writeByte(1);
            writeUtf(out, (String) r.pattern, scratch);
        } else {
            GlobPattern gp = (GlobPattern) r.pattern;
            out.writeByte(2);
            out.writeByte((gp.anchorStart ? 1 : 0) | (gp.anchorEnd ? 2 : 0) | (gp.parts.length << 2));
            for (int i = 0; i < gp.parts.length; i++) writeUtf(out, gp.parts[i], scratch);
        }
        writeScope(out, r.scope, scratch);
    }

    private static Rule readRule(DataInputStream in, String[] sources, int evidenceLen, byte[] scratch) throws IOException {
        int mask = in.readInt();
        int evStart = readVarInt(in);
        int evLen = readVarInt(in);
        if (evStart < 0 || evLen < 0 || (long) evStart + evLen > evidenceLen) {
            throw new IOException("Invalid evidence span");
        }
        int evEnd = evStart + evLen;
        int srcIdx = in.readUnsignedByte();
        if (srcIdx != 0xFF && srcIdx >= sources.length) throw new IOException("Invalid source index");
        String source = srcIdx == 0xFF ? null : sources[srcIdx];
        int patType = in.readByte();
        Object pattern;
        if (patType == 0) {
            pattern = null;
        } else if (patType == 1) {
            pattern = readUtf(in, scratch);
            if (pattern == null) throw new IOException("Missing literal pattern");
        } else if (patType == 2) {
            int hdr = in.readByte() & 0xFF;
            boolean aStart = (hdr & 1) != 0;
            boolean aEnd = (hdr & 2) != 0;
            int count = hdr >>> 2;
            if (count < 1 || count > 3) throw new IOException("Invalid glob parts");
            String[] parts = new String[count];
            for (int i = 0; i < count; i++) {
                String p = readUtf(in, scratch);
                if (p == null) throw new IOException("Missing glob part");
                parts[i] = p;
            }
            pattern = new GlobPattern(parts, aStart, aEnd);
        } else {
            throw new IOException("Invalid pattern type");
        }
        Scope scope = readScope(in, scratch);
        return new Rule(pattern, scope, mask, source, evStart, evEnd);
    }

    private static void writeScope(DataOutputStream out, Scope s, byte[] scratch) throws IOException {
        if (s == Scope.ANY) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        writeVarInt(out, s.include.length);
        for (int i = 0; i < s.include.length; i++) writeUtf(out, s.include[i], scratch);
        writeVarInt(out, s.exclude.length);
        for (int i = 0; i < s.exclude.length; i++) writeUtf(out, s.exclude[i], scratch);
    }

    private static Scope readScope(DataInputStream in, byte[] scratch) throws IOException {
        int flag = in.readByte();
        if (flag == 0) return Scope.ANY;
        int incLen = readVarInt(in);
        if (incLen > 4096) throw new IOException("Invalid scope include");
        String[] inc = incLen == 0 ? Scope.EMPTY : new String[incLen];
        for (int i = 0; i < incLen; i++) inc[i] = readUtf(in, scratch);
        int excLen = readVarInt(in);
        if (excLen > 4096) throw new IOException("Invalid scope exclude");
        String[] exc = excLen == 0 ? Scope.EMPTY : new String[excLen];
        for (int i = 0; i < excLen; i++) exc[i] = readUtf(in, scratch);
        return new Scope(inc, exc);
    }

    private static void writeRuleHostTable(DataOutputStream out, HostTable table,
                                           Map<String, Integer> sourceIds, byte[] scratch) throws IOException {
        out.writeInt(table.size);
        for (int i = 0; i < table.keys.length; i++) {
            String k = table.keys[i];
            if (k == null) continue;
            writeUtf(out, k, scratch);
            Object v = table.values[i];
            if (v instanceof Rule) {
                writeVarInt(out, 1);
                writeRule(out, (Rule) v, sourceIds, scratch);
            } else {
                Rule[] arr = (Rule[]) v;
                writeVarInt(out, arr.length);
                for (int j = 0; j < arr.length; j++) writeRule(out, arr[j], sourceIds, scratch);
            }
        }
    }

    private static HostTable readRuleHostTable(DataInputStream in, String[] sources, int evidenceLen, byte[] scratch)
            throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_BINARY_ITEMS) throw new IOException("Invalid host table");
        if (count == 0) return HostTable.EMPTY;
        HostTable table = new HostTable(count);
        for (int i = 0; i < count; i++) {
            String key = readUtf(in, scratch);
            if (key == null) throw new IOException("Missing host key");
            int n = readVarInt(in);
            if (n < 1 || n > 65536) throw new IOException("Invalid bucket length");
            Object val;
            if (n == 1) {
                val = readRule(in, sources, evidenceLen, scratch);
            } else {
                Rule[] arr = new Rule[n];
                for (int j = 0; j < n; j++) arr[j] = readRule(in, sources, evidenceLen, scratch);
                val = arr;
            }
            table.putDirect(key, val);
        }
        return table;
    }

    private static void writeFragmentIndex(DataOutputStream out, FragmentIndex idx,
                                           Map<String, Integer> sourceIds, byte[] scratch) throws IOException {
        out.writeInt(idx.size);
        for (int i = 0; i < idx.values.length; i++) {
            Object v = idx.values[i];
            if (v == null) continue;
            out.writeLong(idx.keys[i]);
            if (v instanceof Rule) {
                writeVarInt(out, 1);
                writeRule(out, (Rule) v, sourceIds, scratch);
            } else {
                Rule[] arr = (Rule[]) v;
                writeVarInt(out, arr.length);
                for (int j = 0; j < arr.length; j++) writeRule(out, arr[j], sourceIds, scratch);
            }
        }
    }

    private static FragmentIndex readFragmentIndex(DataInputStream in, String[] sources, int evidenceLen, byte[] scratch)
            throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_BINARY_ITEMS) throw new IOException("Invalid fragment index");
        if (count == 0) return FragmentIndex.EMPTY;
        FragmentIndex index = new FragmentIndex(count);
        for (int i = 0; i < count; i++) {
            long key = in.readLong();
            int n = readVarInt(in);
            if (n < 1 || n > 65536) throw new IOException("Invalid fragment bucket");
            Object val;
            if (n == 1) {
                val = readRule(in, sources, evidenceLen, scratch);
            } else {
                Rule[] arr = new Rule[n];
                for (int j = 0; j < n; j++) arr[j] = readRule(in, sources, evidenceLen, scratch);
                val = arr;
            }
            index.putDirect(key, val);
        }
        return index;
    }

    private static void writeCosmeticHostTable(DataOutputStream out, HostTable table, byte[] scratch)
            throws IOException {
        out.writeInt(table.size);
        for (int i = 0; i < table.keys.length; i++) {
            String k = table.keys[i];
            if (k == null) continue;
            writeUtf(out, k, scratch);
            Object v = table.values[i];
            if (v instanceof Cosmetic) {
                writeVarInt(out, 1);
                writeCosmetic(out, (Cosmetic) v, scratch);
            } else {
                Cosmetic[] arr = (Cosmetic[]) v;
                writeVarInt(out, arr.length);
                for (int j = 0; j < arr.length; j++) writeCosmetic(out, arr[j], scratch);
            }
        }
    }

    private static void writeCosmetic(DataOutputStream out, Cosmetic c, byte[] scratch) throws IOException {
        out.writeBoolean(c.allow);
        writeUtf(out, c.selector, scratch);
        writeScope(out, c.scope, scratch);
    }

    private static HostTable readCosmeticHostTable(DataInputStream in, byte[] scratch) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_BINARY_ITEMS) throw new IOException("Invalid cosmetic table");
        if (count == 0) return HostTable.EMPTY;
        HostTable table = new HostTable(count);
        for (int i = 0; i < count; i++) {
            String key = readUtf(in, scratch);
            int n = readVarInt(in);
            if (n < 1 || n > 65536) throw new IOException("Invalid cosmetic bucket");
            Object val;
            if (n == 1) {
                val = readCosmetic(in, scratch);
            } else {
                Cosmetic[] arr = new Cosmetic[n];
                for (int j = 0; j < n; j++) arr[j] = readCosmetic(in, scratch);
                val = arr;
            }
            table.putDirect(key, val);
        }
        return table;
    }

    private static Cosmetic readCosmetic(DataInputStream in, byte[] scratch) throws IOException {
        boolean allow = in.readBoolean();
        String selector = readUtf(in, scratch);
        Scope scope = readScope(in, scratch);
        return new Cosmetic(scope, selector, allow);
    }

    // --- Compact Data Structures ---

    private static final class StringSet {
        static final StringSet EMPTY = new StringSet(new String[0], 0);
        final String[] keys;
        final int size;
        private final int mask;

        StringSet(String[] keys, int size) {
            this.keys = keys;
            this.size = size;
            this.mask = keys.length - 1;
        }

        boolean isEmpty() { return size == 0; }

        boolean contains(String s, int start) {
            if (size == 0) return false;
            int len = s.length() - start;
            int slot = mix32(hashRange(s, start, s.length())) & mask;
            for (;;) {
                String k = keys[slot];
                if (k == null) return false;
                if (k.length() == len && s.regionMatches(start, k, 0, len)) return true;
                slot = (slot + 1) & mask;
            }
        }

        static final class Builder {
            private String[] keys;
            private int size;

            Builder(int expected) {
                int cap = expected <= 0 ? 16 : nextPow2(Math.max(16, expected * 2));
                keys = new String[cap];
            }

            void add(String s) {
                if ((size + 1) * 2 >= keys.length) grow();
                int mask = keys.length - 1;
                int slot = mix32(hashRange(s, 0, s.length())) & mask;
                for (;;) {
                    String k = keys[slot];
                    if (k == null) {
                        keys[slot] = s;
                        size++;
                        return;
                    }
                    if (k.equals(s)) return;
                    slot = (slot + 1) & mask;
                }
            }

            private void grow() {
                String[] old = keys;
                keys = new String[old.length << 1];
                int mask = keys.length - 1;
                for (int i = 0; i < old.length; i++) {
                    String k = old[i];
                    if (k == null) continue;
                    int slot = mix32(hashRange(k, 0, k.length())) & mask;
                    while (keys[slot] != null) slot = (slot + 1) & mask;
                    keys[slot] = k;
                }
            }

            StringSet build() {
                return size == 0 ? EMPTY : new StringSet(keys, size);
            }
        }
    }

    private static final class HostTable {
        static final HostTable EMPTY = new HostTable();
        final String[] keys;
        final Object[] values;
        final int size;
        private final int mask;

        private HostTable() {
            keys = new String[0];
            values = new Object[0];
            size = 0;
            mask = 0;
        }

        HostTable(int expectedSize) {
            int cap = nextPow2(Math.max(16, expectedSize * 2));
            keys = new String[cap];
            values = new Object[cap];
            size = expectedSize;
            mask = cap - 1;
        }

        private HostTable(String[] keys, Object[] values, int size) {
            this.keys = keys;
            this.values = values;
            this.size = size;
            this.mask = keys.length - 1;
        }

        boolean isEmpty() { return size == 0; }

        void putDirect(String key, Object val) {
            int slot = mix32(hashRange(key, 0, key.length())) & mask;
            while (keys[slot] != null) slot = (slot + 1) & mask;
            keys[slot] = key;
            values[slot] = val;
        }

        Object get(String host, int start) {
            if (size == 0) return null;
            int len = host.length() - start;
            int slot = mix32(hashRange(host, start, host.length())) & mask;
            for (;;) {
                String k = keys[slot];
                if (k == null) return null;
                if (k.length() == len && host.regionMatches(start, k, 0, len)) return values[slot];
                slot = (slot + 1) & mask;
            }
        }

        static final class Builder {
            private String[] keys;
            private Object[] values;
            private int size;

            Builder(int initialCap) {
                int cap = nextPow2(Math.max(16, initialCap));
                keys = new String[cap];
                values = new Object[cap];
            }

            void add(String key, Object value) {
                if ((size + 1) * 2 >= keys.length) grow();
                int mask = keys.length - 1;
                int slot = mix32(hashRange(key, 0, key.length())) & mask;
                for (;;) {
                    String k = keys[slot];
                    if (k == null) {
                        keys[slot] = key;
                        values[slot] = value;
                        size++;
                        return;
                    }
                    if (k.equals(key)) {
                        Object existing = values[slot];
                        if (existing instanceof ArrayList) {
                            @SuppressWarnings("unchecked")
                            ArrayList<Object> list = (ArrayList<Object>) existing;
                            list.add(value);
                        } else {
                            ArrayList<Object> list = new ArrayList<>(4);
                            list.add(existing);
                            list.add(value);
                            values[slot] = list;
                        }
                        return;
                    }
                    slot = (slot + 1) & mask;
                }
            }

            int findSlot(String key) {
                int mask = keys.length - 1;
                int slot = mix32(hashRange(key, 0, key.length())) & mask;
                for (;;) {
                    String k = keys[slot];
                    if (k == null) return -1;
                    if (k.equals(key)) return slot;
                    slot = (slot + 1) & mask;
                }
            }

            Object valueAt(int slot) { return values[slot]; }
            void setValueAt(int slot, Object val) { values[slot] = val; }

            void removeSlot(int slot) {
                int mask = keys.length - 1;
                size--;
                int next = (slot + 1) & mask;
                while (keys[next] != null) {
                    int ideal = mix32(hashRange(keys[next], 0, keys[next].length())) & mask;
                    if (((next - ideal) & mask) >= ((next - slot) & mask)) {
                        keys[slot] = keys[next];
                        values[slot] = values[next];
                        slot = next;
                    }
                    next = (next + 1) & mask;
                }
                keys[slot] = null;
                values[slot] = null;
            }

            private void grow() {
                String[] oldK = keys;
                Object[] oldV = values;
                keys = new String[oldK.length << 1];
                values = new Object[oldV.length << 1];
                int mask = keys.length - 1;
                for (int i = 0; i < oldK.length; i++) {
                    String k = oldK[i];
                    if (k == null) continue;
                    int slot = mix32(hashRange(k, 0, k.length())) & mask;
                    while (keys[slot] != null) slot = (slot + 1) & mask;
                    keys[slot] = k;
                    values[slot] = oldV[i];
                }
            }

            HostTable buildRules() {
                if (size == 0) return EMPTY;
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    if (v instanceof ArrayList) {
                        @SuppressWarnings("unchecked")
                        ArrayList<Rule> list = (ArrayList<Rule>) v;
                        values[i] = list.size() == 1 ? list.get(0) : list.toArray(new Rule[0]);
                    }
                }
                return new HostTable(keys, values, size);
            }

            HostTable buildCosmetics() {
                if (size == 0) return EMPTY;
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    if (v instanceof ArrayList) {
                        @SuppressWarnings("unchecked")
                        ArrayList<Cosmetic> list = (ArrayList<Cosmetic>) v;
                        values[i] = list.size() == 1 ? list.get(0) : list.toArray(new Cosmetic[0]);
                    }
                }
                return new HostTable(keys, values, size);
            }
        }
    }

    private static final class FragmentIndex {
        static final FragmentIndex EMPTY = new FragmentIndex();
        final long[] bloom;
        final long[] keys;
        final Object[] values;
        final int size;
        private final int bloomBitMask;
        private final int tableMask;

        private FragmentIndex() {
            bloom = new long[0];
            keys = new long[0];
            values = new Object[0];
            size = 0;
            bloomBitMask = 0;
            tableMask = 0;
        }

        FragmentIndex(int expectedSize) {
            int cap = nextPow2(Math.max(16, expectedSize * 2));
            int bloomWords = Math.max(8, Math.min(512, cap >>> 3));
            bloom = new long[bloomWords];
            bloomBitMask = (bloomWords << 6) - 1;
            keys = new long[cap];
            values = new Object[cap];
            size = expectedSize;
            tableMask = cap - 1;
        }

        boolean isEmpty() { return size == 0; }

        void putDirect(long code, Object value) {
            long mixed = mix64(code);
            int b1 = (int) mixed & bloomBitMask;
            int b2 = (int) (mixed >>> 32) & bloomBitMask;
            bloom[b1 >>> 6] |= 1L << b1;
            bloom[b2 >>> 6] |= 1L << b2;
            int slot = (int) (mixed >>> 16) & tableMask;
            while (values[slot] != null) slot = (slot + 1) & tableMask;
            keys[slot] = code;
            values[slot] = value;
        }

        Object get(long code) {
            long mixed = mix64(code);
            int b1 = (int) mixed & bloomBitMask;
            if ((bloom[b1 >>> 6] & (1L << b1)) == 0L) return null;
            int b2 = (int) (mixed >>> 32) & bloomBitMask;
            if ((bloom[b2 >>> 6] & (1L << b2)) == 0L) return null;
            int slot = (int) (mixed >>> 16) & tableMask;
            for (;;) {
                Object v = values[slot];
                if (v == null) return null;
                if (keys[slot] == code) return v;
                slot = (slot + 1) & tableMask;
            }
        }
    }

    // --- Primitive Helper Maps/Sets for Builder (zero boxing) ---

    private static final class LongHashSet {
        private long[] keys;
        private boolean hasZero;
        private int size;

        LongHashSet(int initialCap) {
            keys = new long[nextPow2(Math.max(16, initialCap))];
        }

        boolean add(long key) {
            if (key == 0L) {
                if (hasZero) return false;
                hasZero = true;
                size++;
                return true;
            }
            if ((size + 1) * 2 >= keys.length) grow();
            int mask = keys.length - 1;
            int slot = (int) mix64(key) & mask;
            for (;;) {
                long k = keys[slot];
                if (k == 0L) {
                    keys[slot] = key;
                    size++;
                    return true;
                }
                if (k == key) return false;
                slot = (slot + 1) & mask;
            }
        }

        private void grow() {
            long[] old = keys;
            keys = new long[old.length << 1];
            int mask = keys.length - 1;
            for (int i = 0; i < old.length; i++) {
                long k = old[i];
                if (k == 0L) continue;
                int slot = (int) mix64(k) & mask;
                while (keys[slot] != 0L) slot = (slot + 1) & mask;
                keys[slot] = k;
            }
        }
    }

    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private int size;

        LongIntMap(int initialCap) {
            int cap = nextPow2(Math.max(16, initialCap));
            keys = new long[cap];
            values = new int[cap];
        }

        void increment(long key) {
            long stored = key == 0L ? Long.MIN_VALUE : key;
            if ((size + 1) * 2 >= keys.length) grow();
            int mask = keys.length - 1;
            int slot = (int) mix64(stored) & mask;
            for (;;) {
                long k = keys[slot];
                if (k == 0L) {
                    keys[slot] = stored;
                    values[slot] = 1;
                    size++;
                    return;
                }
                if (k == stored) {
                    values[slot]++;
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        int get(long key) {
            long stored = key == 0L ? Long.MIN_VALUE : key;
            int mask = keys.length - 1;
            int slot = (int) mix64(stored) & mask;
            for (;;) {
                long k = keys[slot];
                if (k == 0L) return 0;
                if (k == stored) return values[slot];
                slot = (slot + 1) & mask;
            }
        }

        private void grow() {
            long[] oldK = keys;
            int[] oldV = values;
            keys = new long[oldK.length << 1];
            values = new int[oldK.length << 1];
            int mask = keys.length - 1;
            for (int i = 0; i < oldK.length; i++) {
                long k = oldK[i];
                if (k == 0L) continue;
                int slot = (int) mix64(k) & mask;
                while (keys[slot] != 0L) slot = (slot + 1) & mask;
                keys[slot] = k;
                values[slot] = oldV[i];
            }
        }
    }

    private static final class ByteBuf {
        byte[] data = new byte[256];
        int length;

        void appendUtf8(String s) {
            int n = s.length();
            ensure(length + n);
            int p = length;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    data[p++] = (byte) c;
                } else {
                    length = p;
                    byte[] tail = s.substring(i).getBytes(StandardCharsets.UTF_8);
                    ensure(length + tail.length);
                    System.arraycopy(tail, 0, data, length, tail.length);
                    length += tail.length;
                    return;
                }
            }
            length = p;
        }

        boolean equalsUtf8(int start, int end, String s) {
            int len = end - start;
            if (len < s.length()) return false;
            // Fast path for ASCII
            if (len == s.length()) {
                for (int i = 0; i < len; i++) {
                    char c = s.charAt(i);
                    if (c >= 0x80) return new String(data, start, len, StandardCharsets.UTF_8).equals(s);
                    if ((data[start + i] & 0xFF) != c) return false;
                }
                return true;
            }
            return new String(data, start, len, StandardCharsets.UTF_8).equals(s);
        }

        byte[] toByteArray() {
            return length == 0 ? new byte[0] : Arrays.copyOf(data, length);
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int next = data.length;
            while (next < needed) next = next < (1 << 20) ? next << 1 : next + (next >>> 1);
            data = Arrays.copyOf(data, next);
        }
    }

    private static final class PendingFragment {
        Rule rule;
        final String value;
        final boolean simpleLiteral;

        PendingFragment(Rule rule, String value, boolean simpleLiteral) {
            this.rule = rule;
            this.value = value;
            this.simpleLiteral = simpleLiteral;
        }
    }

    // --- Builder ---

    static final class Builder {
        private final HostTable.Builder hosts = new HostTable.Builder(64);
        private final HostTable.Builder pageExceptions = new HostTable.Builder(16);
        private final HostTable.Builder cosmetics = new HostTable.Builder(64);
        private final ArrayList<PendingFragment> blockFrags = new ArrayList<>();
        private final ArrayList<PendingFragment> allowFrags = new ArrayList<>();
        private final StringSet.Builder exactSuffixes = new StringSet.Builder(16);
        private final StringSet.Builder wildcardSuffixes = new StringSet.Builder(16);
        private final StringSet.Builder exceptionSuffixes = new StringSet.Builder(8);
        private final Set<String> disabled = new HashSet<>(16);
        private final IdentityHashMap<Rule, String> allowTexts = new IdentityHashMap<>();
        private final ByteBuf evidence = new ByteBuf();
        private LongHashSet seenLines;
        private int uniqueLines;
        int networkCount, cosmeticCount, skipped;

        void suffixes(Reader input) throws IOException {
            try (BufferedReader reader = new BufferedReader(input, 32768)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    int len = line.length();
                    if (len == 0 || (len >= 2 && line.charAt(0) == '/' && line.charAt(1) == '/')) continue;
                    int offset = 0;
                    int kind = 0; // 0 = exact, 1 = wildcard (*.), 2 = exception (!)
                    char first = line.charAt(0);
                    if (first == '*' && len >= 2 && line.charAt(1) == '.') {
                        offset = 2;
                        kind = 1;
                    } else if (first == '!') {
                        offset = 1;
                        kind = 2;
                    }
                    String body = offset == 0 ? line : line.substring(offset);
                    String normalized = normalizeSuffixAsciiOrIdn(body);
                    if (normalized == null) continue;
                    if (kind == 0) exactSuffixes.add(normalized);
                    else if (kind == 1) wildcardSuffixes.add(normalized);
                    else exceptionSuffixes.add(normalized);
                }
            }
        }

        private static String normalizeSuffixAsciiOrIdn(String body) {
            boolean ascii = true;
            boolean hasUpper = false;
            int len = body.length();
            for (int i = 0; i < len; i++) {
                char c = body.charAt(i);
                if (c > 127) { ascii = false; break; }
                if (c >= 'A' && c <= 'Z') hasUpper = true;
            }
            if (ascii) {
                return hasUpper ? toLowerAsciiString(body) : body;
            }
            try {
                return java.net.IDN.toASCII(body).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        void read(Reader input) throws IOException {
            read(input, "过滤规则");
        }

        void read(Reader input, String source) throws IOException {
            if (seenLines == null) seenLines = new LongHashSet(1024);
            try (BufferedReader reader = new BufferedReader(input, 32768)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    line = trimFast(line);
                    int len = line.length();
                    if (len == 0) continue;
                    char first = line.charAt(0);
                    if (first == '!' || first == '[') continue;
                    if (len > MAX_RULE_LINE_CHARS || uniqueLines >= MAX_UNIQUE_LINES) { skipped++; continue; }
                    String target = disabledRule(line);
                    if (target != null) {
                        disabled.add(target);
                        continue;
                    }
                    if (!seenLines.add(hash64(line))) continue;
                    uniqueLines++;
                    if (!disabled.isEmpty() && disabled.contains(line)) continue;
                    if (!addRule(line, source)) skipped++;
                }
            }
        }

        FilterRules build() {
            if (!disabled.isEmpty()) applyBadfilters();
            FragmentIndex blockIndex = buildFragmentIndex(blockFrags);
            FragmentIndex allowIndex = buildFragmentIndex(allowFrags);
            return new FilterRules(
                    hosts.buildRules(),
                    pageExceptions.buildRules(),
                    cosmetics.buildCosmetics(),
                    blockIndex,
                    allowIndex,
                    exactSuffixes.build(),
                    wildcardSuffixes.build(),
                    exceptionSuffixes.build(),
                    evidence.toByteArray(),
                    networkCount,
                    cosmeticCount,
                    skipped);
        }

        private void applyBadfilters() {
            for (String target : disabled) {
                if (removeMatchingRule(hosts, target)) continue;
                if (removeMatchingRule(pageExceptions, target)) continue;
                if (removeMatchingFragment(blockFrags, target)) continue;
                removeMatchingFragment(allowFrags, target);
            }
        }

        private boolean ruleMatchesText(Rule rule, String text) {
            if (rule.isAllow()) {
                String saved = allowTexts.get(rule);
                return text.equals(saved);
            }
            return rule.evidenceEnd > rule.evidenceStart
                    && evidence.equalsUtf8(rule.evidenceStart, rule.evidenceEnd, text);
        }

        private boolean removeMatchingRule(HostTable.Builder table, String target) {
            boolean allow = target.startsWith("@@");
            String body = allow ? target.substring(2) : target;
            if (!body.startsWith("||")) return false;
            int end = 2;
            while (end < body.length()) {
                char c = body.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '-') end++;
                else break;
            }
            String host = toLowerAsciiString(body.substring(2, end));
            int slot = table.findSlot(host);
            if (slot < 0) return false;
            Object bucket = table.valueAt(slot);
            if (bucket instanceof Rule) {
                if (ruleMatchesText((Rule) bucket, target)) {
                    table.removeSlot(slot);
                    networkCount--;
                    return true;
                }
                return false;
            }
            @SuppressWarnings("unchecked")
            ArrayList<Rule> list = (ArrayList<Rule>) bucket;
            for (int i = 0; i < list.size(); i++) {
                if (ruleMatchesText(list.get(i), target)) {
                    list.remove(i);
                    if (list.isEmpty()) table.removeSlot(slot);
                    else if (list.size() == 1) table.setValueAt(slot, list.get(0));
                    networkCount--;
                    return true;
                }
            }
            return false;
        }

        private boolean removeMatchingFragment(ArrayList<PendingFragment> list, String target) {
            for (int i = 0; i < list.size(); i++) {
                if (ruleMatchesText(list.get(i).rule, target)) {
                    list.remove(i);
                    networkCount--;
                    return true;
                }
            }
            return false;
        }

        private static FragmentIndex buildFragmentIndex(ArrayList<PendingFragment> items) {
            int n = items.size();
            if (n == 0) return FragmentIndex.EMPTY;

            // Pass 1: count 5-gram frequencies across all fragment rules
            LongIntMap freq = new LongIntMap(Math.max(32, n * 4));
            long[] seenInRule = new long[350];
            for (int idx = 0; idx < n; idx++) {
                String value = items.get(idx).value;
                int seenCount = 0;
                int vLen = value.length();
                int runLen = 0;
                long code = 0;
                for (int i = 0; i < vLen; i++) {
                    char c = value.charAt(i);
                    if (c == '*' || c == '^' || c == '|') {
                        runLen = 0;
                        code = 0;
                        continue;
                    }
                    code = step(code, c);
                    if (++runLen >= 5) {
                        boolean dup = false;
                        for (int k = 0; k < seenCount; k++) {
                            if (seenInRule[k] == code) { dup = true; break; }
                        }
                        if (!dup) {
                            seenInRule[seenCount++] = code;
                            freq.increment(code);
                        }
                    }
                }
            }

            // Pass 2: assign each rule to its rarest non-boilerplate 5-gram
            long[] chosenKeys = new long[n];
            int uniqueCount = 0;
            // Use open-addressed map from 5-gram -> Object (Rule or ArrayList<Rule>)
            int mapCap = nextPow2(Math.max(32, n * 2));
            long[] mapKeys = new long[mapCap];
            Object[] mapVals = new Object[mapCap];
            int mapMask = mapCap - 1;

            for (int idx = 0; idx < n; idx++) {
                PendingFragment pf = items.get(idx);
                String value = pf.value;
                int vLen = value.length();
                int runLen = 0;
                long code = 0;
                long bestCode = 0;
                int bestEnd = -1;
                int bestScore = Integer.MAX_VALUE;
                for (int i = 0; i < vLen; i++) {
                    char c = value.charAt(i);
                    if (c == '*' || c == '^' || c == '|') {
                        runLen = 0;
                        code = 0;
                        continue;
                    }
                    code = step(code, c);
                    if (++runLen >= 5) {
                        int score = freq.get(code) + (isBoilerplate5Gram(code) ? 10000 : 0);
                        if (score < bestScore) {
                            bestScore = score;
                            bestCode = code;
                            bestEnd = i;
                        }
                    }
                }
                if (bestEnd < 0) continue;
                Rule rule = pf.rule;
                if (pf.simpleLiteral) {
                    rule = rule.withKeyEnd(bestEnd);
                }
                chosenKeys[idx] = bestCode;
                long storedKey = bestCode == 0L ? Long.MIN_VALUE : bestCode;
                int slot = (int) mix64(storedKey) & mapMask;
                for (;;) {
                    Object existing = mapVals[slot];
                    if (existing == null) {
                        mapKeys[slot] = storedKey;
                        mapVals[slot] = rule;
                        uniqueCount++;
                        break;
                    }
                    if (mapKeys[slot] == storedKey) {
                        if (existing instanceof Rule) {
                            ArrayList<Rule> bucket = new ArrayList<>(4);
                            bucket.add((Rule) existing);
                            bucket.add(rule);
                            mapVals[slot] = bucket;
                        } else {
                            @SuppressWarnings("unchecked")
                            ArrayList<Rule> bucket = (ArrayList<Rule>) existing;
                            bucket.add(rule);
                        }
                        break;
                    }
                    slot = (slot + 1) & mapMask;
                }
            }

            if (uniqueCount == 0) return FragmentIndex.EMPTY;
            FragmentIndex index = new FragmentIndex(uniqueCount);
            for (int i = 0; i < mapCap; i++) {
                Object v = mapVals[i];
                if (v == null) continue;
                long key = mapKeys[i] == Long.MIN_VALUE ? 0L : mapKeys[i];
                if (v instanceof Rule) {
                    index.putDirect(key, v);
                } else {
                    @SuppressWarnings("unchecked")
                    ArrayList<Rule> bucket = (ArrayList<Rule>) v;
                    index.putDirect(key, bucket.toArray(new Rule[0]));
                }
            }
            return index;
        }

        static String disabledRule(String line) {
            int first = line.indexOf('$');
            if (first <= 0 || line.indexOf("badfilter", first + 1) < 0) return null;
            int len = line.length();
            int start = first + 1;
            if (start >= len) return null;
            // Match exact regex (?:[^,]+,)*badfilter(?:,[^,]+)* and drop first "badfilter"
            int badStart = -1, badEnd = -1;
            int pos = start;
            while (pos <= len) {
                int comma = line.indexOf(',', pos);
                int end = comma < 0 ? len : comma;
                if (end == pos) return null; // empty option token
                if (badStart < 0 && end - pos == 9 && line.regionMatches(pos, "badfilter", 0, 9)) {
                    badStart = pos;
                    badEnd = end;
                }
                if (comma < 0) break;
                pos = comma + 1;
                if (pos == len) return null; // trailing comma
            }
            if (badStart < 0) return null;
            if (badStart == start && badEnd == len) {
                return line.substring(0, first);
            }
            StringBuilder out = new StringBuilder(len - 9);
            out.append(line, 0, first + 1);
            if (badStart == start) {
                out.append(line, badEnd + 1, len);
            } else {
                out.append(line, start, badStart - 1);
                if (badEnd < len) out.append(line, badEnd, len);
            }
            return out.toString();
        }

        boolean addRule(String text) {
            return addRule(text, null);
        }

        private boolean addRule(String text, String source) {
            int hash = text.indexOf('#');
            if (hash >= 0) {
                int marker = text.indexOf("#@#", hash);
                boolean cosmeticAllow = marker >= 0;
                if (!cosmeticAllow) marker = text.indexOf("##", hash);
                if (marker >= 0) return cosmetic(text, marker, cosmeticAllow);
            }
            return network(text, source);
        }

        private boolean cosmetic(String text, int index, boolean allow) {
            int selStart = index + (allow ? 3 : 2);
            int selLen = text.length() - selStart;
            if (index == 0 || selLen <= 0 || selLen > 500) return false;
            if (text.regionMatches(selStart, "+js", 0, 3)) return false;
            for (int i = 0; i < index; i++) {
                if (text.charAt(i) == '#') return false;
            }
            if (isUnsafeSelector(text, selStart, text.length())) return false;
            Scope scope = Scope.parse(text, 0, index, ',');
            if (scope == null || scope.include.length == 0) return false;
            String selector = text.substring(selStart);
            // Indexed exclusively under each domain in scope.include; lookup already guarantees include matches.
            Scope matchScope = scope.exclude.length == 0 ? Scope.ANY : new Scope(Scope.EMPTY, scope.exclude);
            Cosmetic rule = new Cosmetic(matchScope, selector, allow);
            for (int i = 0; i < scope.include.length; i++) cosmetics.add(scope.include[i], rule);
            cosmeticCount++;
            return true;
        }

        private boolean network(String text, String source) {
            boolean allow = text.startsWith("@@");
            int bodyStart = allow ? 2 : 0;
            int len = text.length();
            int dollar = text.indexOf('$', bodyStart);
            int valEnd = dollar < 0 ? len : dollar;
            int valLen = valEnd - bodyStart;
            if (valLen == 0 || valLen > 350) return false;
            if (valLen > 2 && text.charAt(bodyStart) == '/' && text.charAt(valEnd - 1) == '/') return false;
            for (int i = bodyStart; i < valEnd; i++) {
                if (text.charAt(i) == '#') return false;
            }

            Scope scope = Scope.ANY;
            int party = 0, types = 0, exceptTypes = 0;
            boolean page = false, cosmeticOnly = false;
            if (dollar >= 0 && dollar + 1 < len) {
                int pos = dollar + 1;
                while (pos < len) {
                    int comma = text.indexOf(',', pos);
                    int end = comma < 0 ? len : comma;
                    int mLen = end - pos;
                    if (mLen > 0) {
                        if ((mLen == 11 && text.regionMatches(pos, "third-party", 0, 11))
                                || (mLen == 2 && text.regionMatches(pos, "3p", 0, 2))) {
                            party = 1;
                        } else if ((mLen == 12 && text.regionMatches(pos, "~third-party", 0, 12))
                                || (mLen == 2 && text.regionMatches(pos, "1p", 0, 2))) {
                            party = -1;
                        } else if (mLen >= 7 && text.regionMatches(pos, "domain=", 0, 7)) {
                            scope = Scope.parse(text, pos + 7, end, '|');
                            if (scope == null) return false;
                        } else if (allow && mLen == 8 && text.regionMatches(pos, "document", 0, 8)) {
                            page = true;
                        } else if (allow && mLen == 8 && text.regionMatches(pos, "elemhide", 0, 8)) {
                            page = true;
                            cosmeticOnly = true;
                        } else {
                            boolean neg = text.charAt(pos) == '~';
                            int bit = parseTypeBit(text, neg ? pos + 1 : pos, end);
                            if (bit == 0) return false;
                            if (neg) exceptTypes |= bit;
                            else types |= bit;
                        }
                    }
                    if (comma < 0) break;
                    pos = comma + 1;
                }
            }

            if (valLen >= 2 && text.charAt(bodyStart) == '|' && text.charAt(bodyStart + 1) == '|') {
                int end = bodyStart + 2;
                boolean hasDot = false;
                while (end < valEnd) {
                    char c = text.charAt(end);
                    if (Character.isLetterOrDigit(c) || c == '.' || c == '-') {
                        if (c == '.') hasDot = true;
                        end++;
                    } else {
                        break;
                    }
                }
                if (!hasDot) return false;
                String host = toLowerAsciiString(text.substring(bodyStart + 2, end));
                if (!validHost(host)) return false;
                int tailLen = valEnd - end;
                if (tailLen > 0) {
                    char firstTail = text.charAt(end);
                    if (firstTail != '^' && firstTail != '/' && firstTail != ':' && firstTail != '?' && firstTail != '|') {
                        return false;
                    }
                }
                if (page && (party != 0 || types != 0 || exceptTypes != 0)) return false;
                Object pattern = null;
                if (tailLen > 0 && !(tailLen == 1 && text.charAt(end) == '^')) {
                    pattern = compileMatcher(text, end, valEnd, true);
                    if (pattern == null) return false;
                }
                Rule r = makeRule(text, source, pattern, scope, allow, party, types, exceptTypes, cosmeticOnly, true);
                (page ? pageExceptions : hosts).add(host, r);
            } else {
                if (page) return false;
                // Check max literal run length >= 5 without regex split
                int maxRun = 0, run = 0;
                for (int i = bodyStart; i < valEnd; i++) {
                    char c = text.charAt(i);
                    if (c == '*' || c == '^' || c == '|') {
                        if (run > maxRun) maxRun = run;
                        run = 0;
                    } else {
                        run++;
                    }
                }
                if (run > maxRun) maxRun = run;
                if (maxRun < 5) return false;

                Object pattern = compileMatcher(text, bodyStart, valEnd, false);
                if (pattern == null) return false;
                String value = text.substring(bodyStart, valEnd);
                boolean simpleLiteral = pattern instanceof String;
                Rule r = makeRule(text, source, pattern, scope, allow, party, types, exceptTypes, false, false);
                PendingFragment pf = new PendingFragment(r, value, simpleLiteral);
                if (allow) allowFrags.add(pf);
                else blockFrags.add(pf);
            }
            networkCount++;
            return true;
        }

        private Rule makeRule(String text, String source, Object pattern, Scope scope, boolean allow,
                              int party, int types, int exceptTypes, boolean cosmeticOnly, boolean anchored) {
            int start = evidence.length;
            if (!allow && source != null) evidence.appendUtf8(text);
            int mask = (types & 0xFF)
                    | ((exceptTypes & 0xFF) << 8)
                    | (allow ? FLAG_ALLOW : 0)
                    | (cosmeticOnly ? FLAG_COSMETIC_ONLY : 0)
                    | (anchored ? FLAG_ANCHORED : 0)
                    | (party > 0 ? FLAG_PARTY_3P : party < 0 ? FLAG_PARTY_1P : 0);
            Rule r = new Rule(pattern, scope, mask, source, start, evidence.length);
            if (allow) allowTexts.put(r, text);
            return r;
        }
    }

    private static final class Scope {
        static final String[] EMPTY = new String[0];
        static final Scope ANY = new Scope(EMPTY, EMPTY);
        final String[] include, exclude;

        Scope(String[] include, String[] exclude) {
            this.include = include;
            this.exclude = exclude;
        }

        static Scope parse(String value, int start, int end, char delimiter) {
            ArrayList<String> allow = null;
            ArrayList<String> deny = null;
            boolean sawPositive = false;
            int pos = start;
            // Exact match for String.split(delimiter): trailing empty tokens are ignored,
            // leading/middle empty tokens produce "" which fails validHost("").
            int effectiveEnd = end;
            while (effectiveEnd > start && value.charAt(effectiveEnd - 1) == delimiter) effectiveEnd--;
            if (effectiveEnd == start) {
                // Value was empty or all delimiters -> split returns [""] -> validHost("") is false!
                return null;
            }
            while (pos <= effectiveEnd) {
                int next = value.indexOf(delimiter, pos);
                int partEnd = (next < 0 || next > effectiveEnd) ? effectiveEnd : next;
                boolean neg = partEnd > pos && value.charAt(pos) == '~';
                int hStart = neg ? pos + 1 : pos;
                String host = toLowerAsciiString(value.substring(hStart, partEnd));
                if (neg) {
                    if (!validHost(host)) return null;
                    if (deny == null) deny = new ArrayList<>(2);
                    deny.add(host);
                } else {
                    sawPositive = true;
                    if (validHost(host)) {
                        if (allow == null) allow = new ArrayList<>(2);
                        allow.add(host);
                    } else if (!isWildcardTldHost(host)) {
                        // Reject malformed tokens, while skipping only unsupported wildcard-TLD siblings (e.g. tripadvisor.*)
                        return null;
                    }
                }
                if (next < 0 || next >= effectiveEnd) break;
                pos = next + 1;
            }
            // Never turn a positive-scoped rule (e.g. $domain=google.*) into global Scope.ANY when all positives were skipped
            if (sawPositive && allow == null) return null;
            return new Scope(
                    allow == null ? EMPTY : allow.toArray(new String[0]),
                    deny == null ? EMPTY : deny.toArray(new String[0]));
        }

        private static boolean isWildcardTldHost(String host) {
            int len = host.length();
            return len >= 3 && host.charAt(len - 2) == '.' && host.charAt(len - 1) == '*'
                    && validHost(host.substring(0, len - 2));
        }

        boolean applies(String host) {
            for (int i = 0; i < exclude.length; i++) if (onDomain(host, exclude[i])) return false;
            if (include.length == 0) return true;
            for (int i = 0; i < include.length; i++) if (onDomain(host, include[i])) return true;
            return false;
        }
    }

    private static final class GlobPattern {
        final String[] parts;
        final boolean anchorStart, anchorEnd;

        GlobPattern(String[] parts, boolean anchorStart, boolean anchorEnd) {
            this.parts = parts;
            this.anchorStart = anchorStart;
            this.anchorEnd = anchorEnd;
        }

        boolean matches(String url, int start, int len) {
            int count = parts.length;
            if (count == 1) {
                String p0 = parts[0];
                if (anchorStart && anchorEnd) return matchPartAt(url, start, len, p0) == len;
                if (anchorStart) return matchPartAt(url, start, len, p0) >= 0;
                if (anchorEnd) return matchPartAtEnd(url, start, len, p0);
                return findPart(url, start, len, p0) >= 0;
            }
            int pos = anchorStart ? matchPartAt(url, start, len, parts[0]) : findPart(url, start, len, parts[0]);
            if (pos < 0) return false;
            if (count == 2) {
                return anchorEnd ? matchPartAtEnd(url, pos, len, parts[1]) : findPart(url, pos, len, parts[1]) >= 0;
            }
            pos = findPart(url, pos, len, parts[1]);
            if (pos < 0) return false;
            return anchorEnd ? matchPartAtEnd(url, pos, len, parts[2]) : findPart(url, pos, len, parts[2]) >= 0;
        }

        private static int matchPartAt(String url, int pos, int len, String part) {
            int pLen = part.length();
            for (int j = 0; j < pLen; j++) {
                char pc = part.charAt(j);
                if (pc == '^') {
                    if (pos < len) {
                        if (isWordChar(url.charAt(pos))) return -1;
                        pos++;
                    }
                } else {
                    if (pos >= len || toLowerAscii(url.charAt(pos)) != pc) return -1;
                    pos++;
                }
            }
            return pos;
        }

        private static int findPart(String url, int from, int len, String part) {
            int pLen = part.length();
            if (pLen == 0) return from;
            char first = part.charAt(0);
            if (first != '^') {
                // Fast check: scan for first char (note: part may contain '^' later, which can match $ at len)
                int nonSep = 0;
                for (int i = 0; i < pLen; i++) if (part.charAt(i) != '^') nonSep++;
                int maxStart = len - nonSep;
                for (int s = from; s <= maxStart; s++) {
                    if (toLowerAscii(url.charAt(s)) == first) {
                        int end = matchPartAt(url, s, len, part);
                        if (end >= 0) return end;
                    }
                }
                return -1;
            }
            int nonSep = 0;
            for (int i = 0; i < pLen; i++) if (part.charAt(i) != '^') nonSep++;
            int maxStart = len - nonSep;
            for (int s = from; s <= maxStart; s++) {
                int end = matchPartAt(url, s, len, part);
                if (end >= 0) return end;
            }
            return -1;
        }

        private static boolean matchPartAtEnd(String url, int minStart, int len, String part) {
            int pLen = part.length();
            if (pLen == 0) return minStart <= len;
            int sepCount = 0;
            for (int i = 0; i < pLen; i++) if (part.charAt(i) == '^') sepCount++;
            if (sepCount == 0) {
                int s = len - pLen;
                return s >= minStart && equalsAsciiLower(url, s, part);
            }
            int lo = Math.max(minStart, len - pLen);
            int hi = len - (pLen - sepCount);
            for (int s = lo; s <= hi; s++) {
                if (matchPartAt(url, s, len, part) == len) return true;
            }
            return false;
        }
    }

    static final class Rule {
        final Object pattern; // null, String (lowercase prefix/literal), or GlobPattern
        final Scope scope;
        final int mask; // bits 0..7=types, 8..15=exceptTypes, 16=allow, 17=cosmeticOnly, 18=anchored, 19=3p, 20=1p, 21..30=keyEnd
        final String source;
        final int evidenceStart, evidenceEnd;

        Rule(Object pattern, Scope scope, int mask, String source, int evidenceStart, int evidenceEnd) {
            this.pattern = pattern;
            this.scope = scope;
            this.mask = mask;
            this.source = source;
            this.evidenceStart = evidenceStart;
            this.evidenceEnd = evidenceEnd;
        }

        Rule withKeyEnd(int keyEnd) {
            return new Rule(pattern, scope, mask | ((keyEnd & 0x3FF) << KEY_END_SHIFT), source, evidenceStart, evidenceEnd);
        }

        boolean isAllow() { return (mask & FLAG_ALLOW) != 0; }
        boolean isCosmeticOnly() { return (mask & FLAG_COSMETIC_ONLY) != 0; }
        boolean needsParty() { return (mask & PARTY_MASK) != 0; }

        boolean matchesPage(String top, String url, int pathStart, int urlLen) {
            if (scope != Scope.ANY && !scope.applies(top)) return false;
            return matchesAnchoredPattern(url, pathStart, urlLen);
        }

        boolean appliesHost(String top, String url, int pathStart, int urlLen, boolean third, int type) {
            int m = mask;
            if ((m & CHECK_MASK) != 0) {
                int p = m & PARTY_MASK;
                if (p != 0 && ((p == FLAG_PARTY_3P) != third)) return false;
                int t = m & 0xFF;
                if (t != 0 && (t & type) == 0) return false;
                int et = (m >>> 8) & 0xFF;
                if (et != 0 && (type == 0 || (et & type) != 0)) return false;
            }
            if (scope != Scope.ANY && !scope.applies(top)) return false;
            return matchesAnchoredPattern(url, pathStart, urlLen);
        }

        private boolean matchesAnchoredPattern(String url, int pathStart, int urlLen) {
            Object pat = pattern;
            if (pat == null) return true;
            if (pat instanceof String) {
                String prefix = (String) pat;
                return pathStart + prefix.length() <= urlLen && equalsAsciiLower(url, pathStart, prefix);
            }
            return ((GlobPattern) pat).matches(url, pathStart, urlLen);
        }

        boolean appliesFragment(String top, String url, int urlLen, int gramEndIdx, boolean third, int type) {
            int m = mask;
            if ((m & CHECK_MASK) != 0) {
                int p = m & PARTY_MASK;
                if (p != 0 && ((p == FLAG_PARTY_3P) != third)) return false;
                int t = m & 0xFF;
                if (t != 0 && (t & type) == 0) return false;
                int et = (m >>> 8) & 0xFF;
                if (et != 0 && (type == 0 || (et & type) != 0)) return false;
            }
            if (scope != Scope.ANY && !scope.applies(top)) return false;
            Object pat = pattern;
            if (pat instanceof String) {
                String lit = (String) pat;
                int start = gramEndIdx - ((m >>> KEY_END_SHIFT) & 0x3FF);
                return start >= 0 && start + lit.length() <= urlLen && equalsAsciiLower(url, start, lit);
            }
            return ((GlobPattern) pat).matches(url, 0, urlLen);
        }
    }

    private static final class Cosmetic {
        final Scope scope;
        final String selector;
        final boolean allow;

        Cosmetic(Scope scope, String selector, boolean allow) {
            this.scope = scope;
            this.selector = selector;
            this.allow = allow;
        }
    }

    // --- Pattern Compiler (zero java.util.regex.Pattern) ---

    private static Object compileMatcher(String text, int start, int end, boolean anchored) {
        int len = end - start;
        int wildcards = 0;
        boolean hasSep = false;
        boolean anchorStart = anchored;
        boolean anchorEnd = false;
        int bodyStart = start;
        int bodyEnd = end;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(start + i);
            if (c == '*') {
                if (++wildcards > 2) return null;
            } else if (c == '^') {
                hasSep = true;
            } else if (c == '|') {
                if (i == 0 && !anchored) {
                    anchorStart = true;
                    bodyStart = start + 1;
                } else if (i == len - 1) {
                    anchorEnd = true;
                    bodyEnd = end - 1;
                } else {
                    return null;
                }
            }
        }

        // Fast pure prefix (anchored) or pure literal (!anchored)
        if (wildcards == 0 && !hasSep && !anchorEnd && (anchored || !anchorStart)) {
            return toLowerAsciiString(text.substring(bodyStart, bodyEnd));
        }

        String[] parts = new String[wildcards + 1];
        int pIdx = 0;
        int segStart = bodyStart;
        for (int i = bodyStart; i < bodyEnd; i++) {
            if (text.charAt(i) == '*') {
                parts[pIdx++] = toLowerAsciiString(text.substring(segStart, i));
                segStart = i + 1;
            }
        }
        parts[pIdx] = toLowerAsciiString(text.substring(segStart, bodyEnd));
        return new GlobPattern(parts, anchorStart, anchorEnd);
    }

    // --- Pure Helper Routines (zero regex, zero hot-path alloc) ---

    private static int anchoredOffset(String url) {
        int start = url.indexOf("://");
        int len = url.length();
        for (int i = start < 0 ? 0 : start + 3; i < len; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') return i;
        }
        return len;
    }

    private static long step(long code, char c) {
        if (c >= 'A' && c <= 'Z') c = (char) (c | 32);
        return ((code << 8) | (c & 255)) & 0xffffffffffL;
    }

    private static long gram5(String s) {
        long code = 0;
        for (int i = 0; i < 5; i++) code = step(code, s.charAt(i));
        return code;
    }

    private static boolean isBoilerplate5Gram(long code) {
        for (int i = 0; i < BOILERPLATE_5GRAMS.length; i++) {
            if (BOILERPLATE_5GRAMS[i] == code) return true;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '%' || c == '-';
    }

    private static char toLowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c | 32) : c;
    }

    private static String toLowerAsciiString(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = s.toCharArray();
                chars[i] = (char) (c | 32);
                for (int j = i + 1; j < len; j++) {
                    char cj = chars[j];
                    if (cj >= 'A' && cj <= 'Z') chars[j] = (char) (cj | 32);
                }
                return new String(chars);
            }
        }
        return s;
    }

    private static boolean equalsAsciiLower(String haystack, int offset, String lowerNeedle) {
        int n = lowerNeedle.length();
        for (int i = 0; i < n; i++) {
            char hc = haystack.charAt(offset + i);
            if (hc >= 'A' && hc <= 'Z') hc = (char) (hc | 32);
            if (hc != lowerNeedle.charAt(i)) return false;
        }
        return true;
    }

    private static boolean validHost(String host) {
        int len = host.length();
        if (len == 0 || len > 253) return false;
        char first = host.charAt(0);
        if (!isHostAlnum(first)) return false;
        if (len == 1) return true;
        char last = host.charAt(len - 1);
        if (!isHostAlnum(last)) return false;
        char prev = first;
        for (int i = 1; i < len - 1; i++) {
            char c = host.charAt(i);
            if (!isHostAlnum(c) && c != '.' && c != '-') return false;
            if (c == '.' && prev == '.') return false;
            prev = c;
        }
        return true;
    }

    private static boolean isHostAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isUnsafeSelector(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '}' || c == ';' || c == '@' || c == '\\') return true;
            if (c == ':') {
                int rem = end - (i + 1);
                if (rem >= 6) {
                    char next = text.charAt(i + 1);
                    if (next == 'h' && rem >= 9 && text.regionMatches(i + 1, "has-text(", 0, 9)) return true;
                    if (next == 'c' && rem >= 9 && text.regionMatches(i + 1, "contains(", 0, 9)) return true;
                    if (next == 'm' && rem >= 12 && text.regionMatches(i + 1, "matches-css(", 0, 12)) return true;
                    if (next == 'x' && rem >= 6 && text.regionMatches(i + 1, "xpath(", 0, 6)) return true;
                    if (next == 's' && rem >= 6 && text.regionMatches(i + 1, "style(", 0, 6)) return true;
                    if (next == 'r' && rem >= 7 && text.regionMatches(i + 1, "remove(", 0, 7)) return true;
                    if (next == 'u' && rem >= 7 && text.regionMatches(i + 1, "upward(", 0, 7)) return true;
                }
            }
        }
        return false;
    }

    private static int parseTypeBit(String text, int start, int end) {
        int len = end - start;
        switch (len) {
            case 4:
                if (text.regionMatches(start, "font", 0, 4)) return 8;
                return 0;
            case 5:
                if (text.regionMatches(start, "image", 0, 5)) return 2;
                if (text.regionMatches(start, "media", 0, 5)) return 16;
                return 0;
            case 6:
                if (text.regionMatches(start, "script", 0, 6)) return 1;
                if (text.regionMatches(start, "object", 0, 6)) return 128;
                return 0;
            case 10:
                if (text.regionMatches(start, "stylesheet", 0, 10)) return 4;
                return 0;
            case 11:
                if (text.regionMatches(start, "subdocument", 0, 11)) return 32;
                return 0;
            case 14:
                if (text.regionMatches(start, "xmlhttprequest", 0, 14)) return 64;
                return 0;
            default:
                return 0;
        }
    }

    private static String trimFast(String s) {
        int end = s.length();
        int start = 0;
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return (start == 0 && end == s.length()) ? s : s.substring(start, end);
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

    private static int hashRange(String s, int start, int end) {
        int h = 0;
        for (int i = start; i < end; i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private static int mix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    private static long mix64(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L ^ ((long) s.length() << 48);
        int len = s.length();
        for (int i = 0; i < len; i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix64(h);
    }

    private static int nextPow2(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
