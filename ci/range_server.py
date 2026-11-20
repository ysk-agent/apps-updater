#!/usr/bin/env python3
"""Minimal static file server with HTTP Range support, for the CI
emulator download test.

python3 -m http.server cannot be used for the v0.2.3 test: it
ignores Range headers and always answers 200, which would send
Apps Updater's segmented download down its single-stream fallback
and the parallel path would never be exercised in CI. This server
answers a Range request with 206 and exactly the requested slice,
and a plain request with the full body, serving one directory.

Usage: range_server.py <port> <directory>
"""
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


ROOT = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else ".")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        name = os.path.basename(self.path.split("?")[0])
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        range_header = self.headers.get("Range")
        m = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header or "")
        if m:
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
            end = min(end, size - 1)
            if start > end:
                self.send_error(416)
                return
            length = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(256 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1] if len(sys.argv) > 1 else "8123")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
