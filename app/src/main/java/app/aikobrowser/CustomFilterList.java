package app.aikobrowser;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validate an editor draft with the same parser used by bundled filters. */
final class CustomFilterList {
    static final int MAX_CHARS = 128_000;
    static final int MAX_RULES = 2000;
    final String rules;
    final int count;

    private CustomFilterList(String rules, int count) {
        this.rules = rules;
        this.count = count;
    }

    static CustomFilterList parse(String source) {
        if (source.length() > MAX_CHARS) throw new IllegalArgumentException("规则文本超过 128,000 字符");
        StringBuilder output = new StringBuilder(Math.min(source.length(), 4096));
        List<String> badLines = new ArrayList<>();
        FilterRules.Builder validation = new FilterRules.Builder();
        int start = source.startsWith("\ufeff") ? 1 : 0;
        int len = source.length();
        int valid = 0, invalid = 0, lineNumber = 0;
        int pos = start;
        while (pos <= len) {
            int end = pos;
            while (end < len) {
                char c = source.charAt(end);
                if (c == '\n' || c == '\r') break;
                end++;
            }
            lineNumber++;
            int nextPos = end + 1;
            if (end < len && source.charAt(end) == '\r' && nextPos < len && source.charAt(nextPos) == '\n') {
                nextPos++;
            }
            int trimStart = pos, trimEnd = end;
            while (trimStart < trimEnd && source.charAt(trimStart) <= ' ') trimStart++;
            while (trimEnd > trimStart && source.charAt(trimEnd - 1) <= ' ') trimEnd--;
            if (trimStart < trimEnd) {
                String line = source.substring(trimStart, trimEnd);
                if (!line.startsWith("!") && !line.equalsIgnoreCase("[Adblock Plus 2.0]")) {
                    String target = FilterRules.Builder.disabledRule(line);
                    if (target == null) target = line;
                    boolean cosmetic = target.contains("##") || target.contains("#@#");
                    if (line.length() > 1500 || (!cosmetic && containsWhitespace(target)) || !validation.addRule(target)) {
                        invalid++;
                        if (badLines.size() < 8) badLines.add(Integer.toString(lineNumber));
                    } else {
                        if (++valid > MAX_RULES) throw new IllegalArgumentException("最多保存 2,000 条自定义规则");
                        output.append(line).append('\n');
                    }
                }
            }
            if (end == len) break;
            pos = nextPos;
        }
        if (invalid > 0) {
            throw new IllegalArgumentException("无法识别第 " + String.join("、", badLines)
                    + " 行" + (invalid > badLines.size() ? "等，共 " + invalid + " 行" : "") + "。");
        }
        return new CustomFilterList(output.toString(), valid);
    }

    private static boolean containsWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Normalizes a user-supplied domain or URL (e.g. "https://Ads.Example.com/x", "example.com",
     * "||example.com^") into a lowercase ASCII/punycode host suitable for {@code ||host^} rules,
     * or returns {@code null} if invalid. Pure Java (zero android.* imports).
     */
    static String normalizeDomainForRule(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("@@")) s = s.substring(2);
        if (s.startsWith("||")) s = s.substring(2);
        if (s.startsWith("*.")) s = s.substring(2);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            String proto = s.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (!"http".equals(proto) && !"https".equals(proto)) return null;
            s = s.substring(scheme + 3);
        } else if (s.indexOf(':') >= 0) {
            int colon = s.indexOf(':');
            int slash = s.indexOf('/');
            if (slash < 0 || colon < slash) {
                // Reject non-http schemes like javascript:foo.bar, allow host:port
                boolean digitsOnly = colon + 1 < s.length();
                int portEnd = slash >= 0 ? slash : s.length();
                for (int i = colon + 1; i < portEnd; i++) {
                    char c = s.charAt(i);
                    if (c < '0' || c > '9') { digitsOnly = false; break; }
                }
                if (!digitsOnly) return null;
                s = s.substring(0, colon);
            }
        }
        int authEnd = s.length();
        for (int i = 0; i < authEnd; i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '^' || c == '$') {
                authEnd = i;
                break;
            }
            if (c == '@') return null;
        }
        int hostEnd = authEnd;
        for (int i = 0; i < authEnd; i++) {
            if (s.charAt(i) == ':') {
                hostEnd = i;
                break;
            }
        }
        s = s.substring(0, hostEnd);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty() || s.length() > 253) return null;
        boolean hasNonAscii = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7F) return null;
            if (c > 0x7F) hasNonAscii = true;
        }
        String ascii;
        if (hasNonAscii) {
            try {
                ascii = IDN.toASCII(s).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        } else {
            ascii = s.toLowerCase(Locale.ROOT);
        }
        int len = ascii.length();
        if (len < 3 || len > 253) return null;
        char first = ascii.charAt(0), last = ascii.charAt(len - 1);
        if (!isAsciiAlnum(first) || !isAsciiAlnum(last)) return null;
        boolean hasDot = false;
        char prev = first;
        for (int i = 1; i < len - 1; i++) {
            char c = ascii.charAt(i);
            if (!isAsciiAlnum(c) && c != '.' && c != '-') return null;
            if (c == '.') {
                if (prev == '.' || prev == '-') return null;
                hasDot = true;
            } else if (c == '-' && prev == '.') {
                return null;
            }
            prev = c;
        }
        return hasDot ? ascii : null;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
