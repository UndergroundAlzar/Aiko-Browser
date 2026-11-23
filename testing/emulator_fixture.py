"""Local HTML navigation and deterministic byte ranges for Android smoke testing."""
import hashlib
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BLOCK = bytes(range(256)) * 256
SIZE = 8 * 1024 * 1024
SHA256 = hashlib.sha256(BLOCK * (SIZE // len(BLOCK))).hexdigest()


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/artifact.bin":
            self.download()
            return

        if self.path == "/b":
            title, body = "AIKO_FIXTURE_B", "<h1>AIKO_FIXTURE_B</h1><p>Second page</p>"
        else:
            title = "AIKO_FIXTURE_A"
            body = (
                "<h1>AIKO_FIXTURE_A</h1>"
                "<p><a href='/b'>OPEN_FIXTURE_B</a></p>"
                "<p><a href='/artifact.bin'>DOWNLOAD_8_MIB</a></p>"
                "<div style='height:2000px;padding-top:24px' id='scroll'>Long page</div>"
            )
        payload = (
            "<meta name=viewport content='width=device-width, initial-scale=1'>"
            f"<title>{title}</title><main style='padding:16px;font:22px sans-serif'>{body}</main>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def download(self):
        start, last, status = 0, SIZE - 1, 200
        if value := self.headers.get("Range"):
            pair = value.removeprefix("bytes=").split("-", 1)
            start = int(pair[0])
            last = int(pair[1]) if pair[1] else last
            status = 206
        print(json.dumps({"download": [start, last]}), flush=True)
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="aiko-qa.bin"')
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", '"aiko-qa-stable"')
        self.send_header("Content-Length", str(last - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{last}/{SIZE}")
        self.end_headers()
        try:
            while start <= last:
                offset = start % len(BLOCK)
                n = min(len(BLOCK) - offset, last - start + 1)
                self.wfile.write(BLOCK[offset:offset + n])
                start += n
                time.sleep(0.035)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", 18769), Fixture) as server:
        print(f"8 MiB artifact SHA-256 {SHA256}", flush=True)
        server.serve_forever()
