# -*- coding: utf-8 -*-
"""本地 HTTP 服务：频道 JSON 接口、订阅源管理、HLS 代理、静态页面。

只监听 127.0.0.1，且校验 Host 头，防止局域网访问与 DNS rebinding。
"""
from __future__ import annotations

import json
import mimetypes
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import proxy
from channels import UnsupportedSourceError, group_channels, parse
from sources import (
    InvalidSourceUrl,
    SourceDownloadError,
    SourceManager,
    SourceParseError,
)

MAX_JSON_BODY = 8 * 1024
ALLOWED_HOSTNAMES = {"127.0.0.1", "localhost", "::1"}
MIME_OVERRIDES = {
    ".m3u8": "application/vnd.apple.mpegurl",
    ".ts": "video/mp2t",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".svg": "image/svg+xml",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LiveTVWin/1.0"

    # 由 make_server 注入
    manager: SourceManager
    static_dir: Path

    def log_message(self, fmt, *args):  # 保持安静，避免控制台被请求日志刷屏
        return

    # ---------- 请求入口 ----------

    def do_GET(self):  # noqa: N802
        if not self._host_allowed():
            return self._json_error(403, "仅允许本机访问")
        path = urllib.parse.urlsplit(self.path).path
        try:
            if path in ("/", "/index.html"):
                return self._serve_static("index.html")
            if path.startswith("/static/"):
                return self._serve_static(path[len("/static/"):])
            if path == "/api/health":
                return self._json_ok({"ok": True})
            if path == "/api/meta":
                return self._json_ok(self.manager.summary())
            if path == "/api/channels":
                return self._json_ok(self._channels_payload())
            if path == proxy.PROXY_PATH:
                return self._handle_proxy()
            return self._json_error(404, "接口不存在")
        except Exception as exc:  # 兜底，避免连接直接断掉
            return self._json_error(500, f"服务器内部错误：{exc}")

    def do_POST(self):  # noqa: N802
        if not self._host_allowed():
            return self._json_error(403, "仅允许本机访问")
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/source":
            return self._handle_set_source()
        if path == "/api/source/reset":
            return self._handle_reset_source()
        return self._json_error(404, "接口不存在")

    def do_OPTIONS(self):  # noqa: N802
        self.send_response(204)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------- 业务 ----------

    def _channels_payload(self) -> dict:
        items = self.manager.current()
        return {
            "channels": [c.to_dict() for c in items],
            "groups": group_channels(items),
            "meta": {**self.manager.meta(), "channelCount": len(items)},
        }

    def _handle_set_source(self):
        payload = self._read_json()
        if payload is None:
            return self._json_error(400, "请求格式不正确")
        try:
            self.manager.set_custom(str(payload.get("url", "")))
        except InvalidSourceUrl as exc:
            return self._json_error(400, str(exc))
        except (SourceDownloadError, SourceParseError) as exc:
            return self._json_error(502, str(exc))
        self._json_ok(self._channels_payload())

    def _handle_reset_source(self):
        self.manager.reset()
        self._json_ok(self._channels_payload())

    def _handle_proxy(self):
        target = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("u", [""])[0]
        if not target:
            return self._json_error(400, "缺少上游地址参数 u")
        try:
            status, headers, body = proxy.fetch(target)
        except proxy.ProxyRequestError as exc:
            return self._json_error(400, str(exc))
        except proxy.ProxyError as exc:
            return self._json_error(502, str(exc))

        content_type = headers.get("Content-Type") or "application/octet-stream"
        if proxy.is_playlist(body):
            try:
                text = body.decode("utf-8")
            except UnicodeDecodeError:
                text = body.decode("utf-8", errors="replace")
            body = proxy.rewrite_playlist(text, target).encode("utf-8")
            content_type = "application/vnd.apple.mpegurl"
        self._send(200, body, content_type)

    # ---------- 静态文件 ----------

    def _serve_static(self, relative: str):
        relative = urllib.parse.unquote(relative)
        try:
            target = (self.static_dir / relative).resolve()
        except (OSError, ValueError):
            return self._json_error(400, "路径不合法")
        root = self.static_dir.resolve()
        if root != target and root not in target.parents:
            return self._json_error(403, "禁止访问该路径")
        if not target.is_file():
            return self._json_error(404, "文件不存在")
        content_type = MIME_OVERRIDES.get(
            target.suffix.lower()
        ) or mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        # hls.js 有 400 KB，允许浏览器缓存，避免每次刷新都重下
        in_vendor = "vendor" in target.relative_to(root).parts
        cache = "public, max-age=86400" if in_vendor else "no-cache"
        self._send(200, target.read_bytes(), content_type, cache_control=cache)

    # ---------- 响应工具 ----------

    def _read_json(self) -> dict | None:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if length <= 0 or length > MAX_JSON_BODY:
            return None
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _host_allowed(self) -> bool:
        host = (self.headers.get("Host") or "").split(":")[0].strip().lower()
        return host in ALLOWED_HOSTNAMES

    def _cors_headers(self):
        # 页面与服务同源，CORS 头只为让 hls.js 的跨源请求更宽容
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _send(self, status: int, body: bytes, content_type: str, cache_control: str = "no-store"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache_control)
        self._cors_headers()
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json_ok(self, payload: dict):
        body = json.dumps({"ok": True, **payload}, ensure_ascii=False).encode("utf-8")
        self._send(200, body, "application/json; charset=utf-8")

    def _json_error(self, status: int, message: str):
        body = json.dumps({"ok": False, "error": message}, ensure_ascii=False).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")


def make_server(
    manager: SourceManager, static_dir: Path, port: int = 0, host: str = "127.0.0.1"
) -> ThreadingHTTPServer:
    handler = type(
        "BoundHandler",
        (Handler,),
        {"manager": manager, "static_dir": Path(static_dir)},
    )
    httpd = ThreadingHTTPServer((host, port), handler)
    httpd.daemon_threads = True
    return httpd
