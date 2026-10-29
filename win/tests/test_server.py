# -*- coding: utf-8 -*-
"""本地服务集成测试：真实起服务后用 HTTP 请求验证路由与安全边界。"""
import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

import server
import sources

BUILTIN = (
    "#EXTM3U\n"
    '#EXTINF:-1 group-title="央视频道", CCTV1\nhttp://ex.com/1.m3u8\n'
    '#EXTINF:-1 group-title="央视频道", CCTV2\nhttp://ex.com/2.m3u8\n'
    '#EXTINF:-1 group-title="卫视频道", 湖南卫视\nhttp://ex.com/3.m3u8\n'
).encode("utf-8")
CUSTOM = "#EXTM3U\n#EXTINF:-1 group-title=\"自定义\", 我的台\nhttp://ex.com/x.m3u8\n".encode("utf-8")

STATIC_HTML = b"<html><body>livetv</body></html>"


class _Upstream(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        if self.path == "/custom.m3u":
            self.send_response(200)
            self.send_header("Content-Length", str(len(CUSTOM)))
            self.end_headers()
            self.wfile.write(CUSTOM)
        elif self.path == "/live/index.m3u8":
            body = b"#EXTM3U\n#EXTINF:6.0,\nseg1.ts\n"
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/live/seg1.ts":
            body = b"\x47\x40\x11\x10fake-ts"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args):
        pass


@pytest.fixture()
def upstream():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Upstream)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


@pytest.fixture()
def live(tmp_path):
    builtin = tmp_path / "builtin.m3u"
    builtin.write_bytes(BUILTIN)
    static_dir = tmp_path / "static"
    static_dir.mkdir()
    (static_dir / "index.html").write_bytes(STATIC_HTML)
    (static_dir / "vendor").mkdir()
    (static_dir / "vendor" / "lib.js").write_bytes(b"var lib=1;")

    manager = sources.SourceManager(
        builtin_path=builtin, config_path=tmp_path / "config.json", cache_path=tmp_path / "cache.m3u"
    )
    httpd = server.make_server(manager, static_dir, port=0)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    yield base, manager
    httpd.shutdown()


def _get(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return r.status, r.read(), dict(r.headers)


def _post(url, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


# ---------- 频道接口 ----------

def test_channels_endpoint_returns_channels_and_groups(live):
    base, _ = live
    status, body, headers = _get(f"{base}/api/channels")
    data = json.loads(body)
    assert status == 200
    assert "application/json" in headers["Content-Type"]
    assert len(data["channels"]) == 3
    assert [g["name"] for g in data["groups"]] == ["央视频道", "卫视频道"]
    assert data["meta"]["mode"] == "builtin"
    assert data["channels"][0]["name"] == "CCTV1"


def test_health_endpoint(live):
    base, _ = live
    status, body, _ = _get(f"{base}/api/health")
    assert status == 200 and json.loads(body)["ok"] is True


# ---------- 静态资源 ----------

def test_index_served(live):
    base, _ = live
    status, body, headers = _get(f"{base}/")
    assert status == 200
    assert body == STATIC_HTML
    assert "text/html" in headers["Content-Type"]


def test_path_traversal_blocked(live, tmp_path):
    base, _ = live
    (tmp_path / "secret.txt").write_text("secret", encoding="utf-8")
    for path in ("/static/../secret.txt", "/static/..%2Fsecret.txt", "/static/%2e%2e/secret.txt"):
        with pytest.raises(urllib.error.HTTPError) as exc:
            _get(base + path)
        assert exc.value.code in (400, 403, 404)


def test_vendor_asset_allows_browser_caching(live):
    base, _ = live
    status, body, headers = _get(f"{base}/static/vendor/lib.js")
    assert status == 200 and body == b"var lib=1;"
    assert "max-age" in headers["Cache-Control"]


def test_index_not_cached_to_avoid_stale_page(live):
    base, _ = live
    _, _, headers = _get(f"{base}/")
    assert headers["Cache-Control"] == "no-cache"


def test_unknown_path_returns_404(live):
    base, _ = live
    with pytest.raises(urllib.error.HTTPError) as exc:
        _get(f"{base}/nope")
    assert exc.value.code == 404


# ---------- 换源 ----------

def test_set_source_switches_channels(live, upstream):
    base, manager = live
    status, data = _post(f"{base}/api/source", {"url": f"{upstream}/custom.m3u"})
    assert status == 200
    assert data["ok"] is True
    assert data["meta"]["mode"] == "custom"
    _, body, _ = _get(f"{base}/api/channels")
    assert [c["name"] for c in json.loads(body)["channels"]] == ["我的台"]


def test_set_source_invalid_url_returns_400(live):
    base, _ = live
    with pytest.raises(urllib.error.HTTPError) as exc:
        _post(f"{base}/api/source", {"url": "ftp://x/y"})
    assert exc.value.code == 400
    payload = json.loads(exc.value.read().decode("utf-8"))
    assert payload["ok"] is False
    assert "http" in payload["error"]


def test_set_source_download_failure_returns_502(live, upstream):
    base, _ = live
    with pytest.raises(urllib.error.HTTPError) as exc:
        _post(f"{base}/api/source", {"url": f"{upstream}/missing.m3u"})
    assert exc.value.code in (400, 502)


def test_reset_source_restores_builtin(live, upstream):
    base, manager = live
    _post(f"{base}/api/source", {"url": f"{upstream}/custom.m3u"})
    status, data = _post(f"{base}/api/source/reset", {})
    assert status == 200 and data["meta"]["mode"] == "builtin"
    _, body, _ = _get(f"{base}/api/channels")
    assert len(json.loads(body)["channels"]) == 3


# ---------- 代理 ----------

def test_proxy_rewrites_playlist(live, upstream):
    base, _ = live
    target = f"{upstream}/live/index.m3u8"
    status, body, headers = _get(f"{base}/api/proxy?u=" + urllib.parse.quote(target, safe=""))
    text = body.decode("utf-8")
    assert status == 200
    assert "/api/proxy?u=" in text
    assert "seg1.ts" not in text.split("/api/proxy?u=")[0]


def test_proxy_passes_binary_through(live, upstream):
    base, _ = live
    target = f"{upstream}/live/seg1.ts"
    status, body, headers = _get(f"{base}/api/proxy?u=" + urllib.parse.quote(target, safe=""))
    assert status == 200
    assert body == b"\x47\x40\x11\x10fake-ts"
    assert headers["Content-Type"] == "video/mp2t"
    assert headers["Access-Control-Allow-Origin"] == "*"


def test_proxy_missing_param_returns_400(live):
    base, _ = live
    with pytest.raises(urllib.error.HTTPError) as exc:
        _get(f"{base}/api/proxy")
    assert exc.value.code == 400


def test_proxy_rejects_non_http_scheme(live):
    base, _ = live
    with pytest.raises(urllib.error.HTTPError) as exc:
        _get(f"{base}/api/proxy?u=" + urllib.parse.quote("file:///c:/windows/win.ini", safe=""))
    assert exc.value.code == 400


def test_proxy_upstream_404_is_502(live, upstream):
    base, _ = live
    target = f"{upstream}/live/missing.ts"
    with pytest.raises(urllib.error.HTTPError) as exc:
        _get(f"{base}/api/proxy?u=" + urllib.parse.quote(target, safe=""))
    assert exc.value.code == 502
