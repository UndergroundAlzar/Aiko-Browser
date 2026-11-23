package app.aikobrowser;

import java.net.IDN;
import java.util.Locale;

/** Zero-allocation HTTP(S) URL validation and fast host/origin extraction (pure Java). */
public final class HttpUrlUtil {
    private HttpUrlUtil() {}

    /**
     * Fast HTTP/HTTPS URL validator matching RangeDownload.requireHttp(new URL(url)) semantics
     * without allocating URL objects or throwing exceptions on invalid input.
     */
    public static boolean isHttp(String url) {
        return parseAuthorityEnd(url, false) > 0;
    }

    /** Returns normalized lowercase ASCII/punycode host, or "" if not a valid HTTP(S) URL. */
    public static String extractHost(String url) {
        if (url == null) return "";
        int schemeEnd = schemePrefixLength(url, false);
        if (schemeEnd < 0) return "";
        int authEnd = validateAndFindAuthorityEnd(url, schemeEnd);
        if (authEnd <= schemeEnd) return "";
        int hostEnd = findHostEnd(url, schemeEnd, authEnd);
        return normalizeHostRange(url, schemeEnd, hostEnd);
    }

    /**
     * Returns canonical HTTPS origin ("https://host" or "https://host:port" omitting :443),
     * or "" if not a valid HTTPS URL suitable for preconnect.
     */
    public static String extractOrigin(String url) {
        if (url == null) return "";
        int schemeEnd = schemePrefixLength(url, true);
        if (schemeEnd < 0) return "";
        int authEnd = validateAndFindAuthorityEnd(url, schemeEnd);
        if (authEnd <= schemeEnd) return "";
        int hostEnd = findHostEnd(url, schemeEnd, authEnd);
        String host = normalizeHostRange(url, schemeEnd, hostEnd);
        if (host.isEmpty()) return "";
        int port = -1;
        if (hostEnd < authEnd && url.charAt(hostEnd) == ':') {
            int pStart = hostEnd + 1;
            if (pStart < authEnd) {
                port = parsePort(url, pStart, authEnd);
                if (port <= 0) return "";
            }
        }
        if (port < 0 || port == 443) return "https://" + host;
        return "https://" + host + ":" + port;
    }

    private static int parseAuthorityEnd(String url, boolean httpsOnly) {
        if (url == null) return -1;
        int schemeEnd = schemePrefixLength(url, httpsOnly);
        if (schemeEnd < 0) return -1;
        return validateAndFindAuthorityEnd(url, schemeEnd);
    }

    private static int schemePrefixLength(String url, boolean httpsOnly) {
        int len = url.length();
        if (len < 8) return -1;
        char c0 = url.charAt(0);
        char c1 = url.charAt(1);
        char c2 = url.charAt(2);
        char c3 = url.charAt(3);
        if ((c0 != 'h' && c0 != 'H') || (c1 != 't' && c1 != 'T')
                || (c2 != 't' && c2 != 'T') || (c3 != 'p' && c3 != 'P')) {
            return -1;
        }
        char c4 = url.charAt(4);
        if (c4 == 's' || c4 == 'S') {
            if (len < 9) return -1;
            return (url.charAt(5) == ':' && url.charAt(6) == '/' && url.charAt(7) == '/') ? 8 : -1;
        }
        if (httpsOnly) return -1;
        return (c4 == ':' && url.charAt(5) == '/' && url.charAt(6) == '/') ? 7 : -1;
    }

    private static int validateAndFindAuthorityEnd(String url, int authStart) {
        int len = url.length();
        int authEnd = len;
        for (int i = authStart; i < len; i++) {
            char c = url.charAt(i);
            if (c <= 0x1F || c == 0x7F) return -1;
            if (authEnd == len && (c == '/' || c == '?' || c == '#')) {
                authEnd = i;
            }
        }
        if (authEnd <= authStart) return -1;

        char first = url.charAt(authStart);
        if (first == '[') {
            int close = -1;
            for (int i = authStart + 1; i < authEnd; i++) {
                char c = url.charAt(i);
                if (c == ']') {
                    close = i;
                    break;
                }
                if (!isIpv6Char(c)) return -1;
            }
            if (close <= authStart + 1) return -1;
            int after = close + 1;
            if (after < authEnd) {
                if (url.charAt(after) != ':') return -1;
                if (after + 1 < authEnd && parsePort(url, after + 1, authEnd) < 0) return -1;
            }
            return authEnd;
        }

        int colon = -1;
        for (int i = authStart; i < authEnd; i++) {
            char c = url.charAt(i);
            if (c == ':') {
                if (colon >= 0) return -1;
                colon = i;
                continue;
            }
            if (colon < 0) {
                if (!isHostChar(c)) return -1;
            } else {
                if (c < '0' || c > '9') return -1;
            }
        }
        int hostEnd = colon >= 0 ? colon : authEnd;
        if (hostEnd <= authStart) return -1;
        // Reject hosts consisting solely of dots (e.g. "http://./")
        boolean hasNonDot = false;
        for (int i = authStart; i < hostEnd; i++) {
            if (url.charAt(i) != '.') {
                hasNonDot = true;
                break;
            }
        }
        if (!hasNonDot) return -1;
        if (colon >= 0 && colon + 1 < authEnd && parsePort(url, colon + 1, authEnd) < 0) {
            return -1;
        }
        return authEnd;
    }

    private static int findHostEnd(String url, int authStart, int authEnd) {
        if (url.charAt(authStart) == '[') {
            int close = url.indexOf(']', authStart + 1);
            return close >= 0 && close < authEnd ? close + 1 : authEnd;
        }
        for (int i = authStart; i < authEnd; i++) {
            if (url.charAt(i) == ':') return i;
        }
        return authEnd;
    }

    private static int parsePort(String s, int start, int end) {
        int digits = end - start;
        if (digits <= 0 || digits > 5) return -1;
        int port = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            port = port * 10 + (c - '0');
        }
        return port <= 65535 ? port : -1;
    }

    private static boolean isHostChar(char c) {
        if (c > 0x7F) return !Character.isWhitespace(c) && !Character.isISOControl(c);
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
        return c == '.' || c == '-' || c == '_';
    }

    private static boolean isIpv6Char(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == ':' || c == '.';
    }

    private static String normalizeHostRange(String url, int start, int end) {
        while (end > start && url.charAt(end - 1) == '.') end--;
        if (end <= start) return "";
        boolean hasUpper = false;
        boolean hasNonAscii = false;
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c > 0x7F) {
                hasNonAscii = true;
                break;
            }
            if (c >= 'A' && c <= 'Z') hasUpper = true;
        }
        String raw = url.substring(start, end);
        if (!hasUpper && !hasNonAscii) return raw;
        if (!hasNonAscii) return raw.toLowerCase(Locale.ROOT);
        try {
            String ascii = IDN.toASCII(raw);
            return ascii.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
