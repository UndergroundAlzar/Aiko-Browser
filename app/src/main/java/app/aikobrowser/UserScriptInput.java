package app.aikobrowser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/** Import explicit user-selected text and URLs. No page cookies or native request API for scripts. */
final class UserScriptInput {
    static String read(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) throw new IOException("无法打开脚本");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
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
}
