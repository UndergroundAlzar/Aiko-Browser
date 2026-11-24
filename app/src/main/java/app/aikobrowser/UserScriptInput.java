package app.aikobrowser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Import explicit user-selected text and URLs. No page cookies or native request API for scripts. */
final class UserScriptInput {
    static String read(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) throw new IOException("无法打开脚本");
        ByteArrayOutputStream data = new ByteArrayOutputStream(Math.min(Math.max(1024, maxBytes), 16384));
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (count > maxBytes - data.size()) throw new IOException("脚本文件太大");
            data.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data.toByteArray())).toString();
    }

    static final class Downloaded {
        final String source, site;
        Downloaded(String source, String site) { this.source = source; this.site = site; }
    }

    static Downloaded download(String input) throws IOException {
        URI endpoint = UserScript.address(input);
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()))
            throw new IOException("请输入 HTTPS 的脚本源码地址");
        URL url = endpoint.toURL();
        for (int redirects = 0; redirects <= 4; redirects++) {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/plain, application/javascript, */*;q=0.5");
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    try (InputStream stream = connection.getInputStream()) {
                        return new Downloaded(read(stream, UserScript.MAX_BYTES), url.getHost());
                    }
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    throw new IOException("脚本服务器返回 HTTP " + status);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("脚本地址跳转无效");
                URL next = new URL(url, location);
                if (!"https".equals(next.getProtocol()) || UserScript.address(next.toExternalForm()) == null)
                    throw new IOException("脚本地址只能跳转到 HTTPS");
                url = next;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("脚本地址跳转次数过多");
    }

    /**
     * Normalizes an HTTP(S) remote script URL by percent-encoding unsafe path/query/fragment
     * characters (spaces, brackets, pipes, non-ASCII, stray '%') while preserving authority validation.
     * Matches HttpUrlUtil.isHttp acceptance so isUserScriptUrl() and fetchRemoteScript() never disagree.
     */
    static URI safeRemoteAddress(String input) {
        if (input == null || input.isEmpty() || input.length() > 8192 || !HttpUrlUtil.isHttp(input)) {
            return null;
        }
        URI direct = UserScript.address(input);
        if (direct != null) return direct;
        int schemeEnd = input.indexOf("://");
        if (schemeEnd < 0) return null;
        int tailStart = input.length();
        for (int i = schemeEnd + 3; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                tailStart = i;
                break;
            }
        }
        if (tailStart >= input.length()) return null;
        StringBuilder out = new StringBuilder(input.length() + 24);
        out.append(input, 0, tailStart);
        encodeUriTail(input, tailStart, out);
        return UserScript.address(out.toString());
    }

    private static void encodeUriTail(String s, int start, StringBuilder out) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        int len = s.length();
        for (int i = start; i < len; ) {
            int cp = s.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp == '%' && i + 2 < len && isHexDigit(s.charAt(i + 1)) && isHexDigit(s.charAt(i + 2))) {
                out.append('%');
            } else if (cp < 0x80 && isSafeUriTailChar((char) cp)) {
                out.append((char) cp);
            } else {
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : utf8) {
                    out.append('%').append(hex[(b >>> 4) & 0xF]).append(hex[b & 0xF]);
                }
            }
            i += charCount;
        }
    }

    private static boolean isSafeUriTailChar(char c) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        switch (c) {
            case '-': case '.': case '_': case '~':
            case ':': case '/': case '?': case '#':
            case '@': case '!': case '$': case '&':
            case '\'': case '(': case ')': case '*':
            case '+': case ',': case ';': case '=':
                return true;
            default:
                return false;
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isHtmlDocument(String source, boolean looksHtml) {
        int i = 0, len = source.length();
        if (len > 0 && source.charAt(0) == '\ufeff') i = 1;
        while (i < len && Character.isWhitespace(source.charAt(i))) i++;
        if (i >= len || source.charAt(i) != '<') return false;
        if (looksHtml) return true;
        return source.regionMatches(true, i, "<!doctype", 0, 9)
                || source.regionMatches(true, i, "<html", 0, 5);
    }

    /**
     * Fetches a remote .user.js script over HTTP/HTTPS (up to maxBytes, 15s timeout).
     * Follows safe redirects (allows HTTP->HTTPS upgrade, blocks HTTPS->HTTP downgrade),
     * percent-encodes unsafe path/query/redirect characters, and rejects HTML web pages.
     */
    static Downloaded downloadRemote(String input, int maxBytes) throws IOException {
        URI endpoint = safeRemoteAddress(input);
        if (endpoint == null) throw new IOException("请输入有效的 HTTP(S) 脚本地址");
        URL url = endpoint.toURL();
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/javascript, application/javascript, text/plain, */*;q=0.5");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) AikoBrowser/0.3.1");
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    String contentType = connection.getContentType();
                    String lowerCt = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
                    boolean looksHtml = lowerCt.contains("text/html") || lowerCt.contains("application/xhtml+xml");
                    String source;
                    try (InputStream stream = connection.getInputStream()) {
                        source = read(stream, maxBytes);
                    }
                    if (isHtmlDocument(source, looksHtml)) {
                        throw new IOException("该链接返回的是网页而非油猴脚本");
                    }
                    if (!source.contains("==UserScript==")) {
                        throw new IOException(looksHtml
                                ? "该链接返回的是网页而非油猴脚本"
                                : "该文件不是有效的油猴脚本（缺少 ==UserScript==）");
                    }
                    return new Downloaded(source, url.getHost());
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException("脚本服务器返回 HTTP " + status);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) throw new IOException("脚本地址跳转无效");
                URL next = new URL(url, location.trim());
                URI safeNext = safeRemoteAddress(next.toExternalForm());
                if (safeNext == null) {
                    throw new IOException("脚本跳转地址无效");
                }
                if ("https".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(safeNext.getScheme())) {
                    throw new IOException("脚本地址尝试降级为非 HTTPS");
                }
                url = safeNext.toURL();
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("脚本地址跳转次数过多");
    }
}
