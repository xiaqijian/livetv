# -*- coding: utf-8 -*-
"""订阅源管理：地址校验、下载解析、持久化、恢复默认。"""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import sources

VALID_M3U = (
    "#EXTM3U\n"
    '#EXTINF:-1 group-title="央视频道", CCTV1\nhttp://ex.com/1.m3u8\n'
    '#EXTINF:-1 group-title="卫视频道", 湖南卫视\nhttp://ex.com/2.m3u8\n'
).encode("utf-8")

BUILTIN_M3U = (
    "#EXTM3U\n"
    '#EXTINF:-1 group-title="内置", 内置台\nhttp://builtin/1.m3u8\n'
).encode("utf-8")


class _Handler(BaseHTTPRequestHandler):
    routes = {}

    def do_GET(self):  # noqa: N802
        body = self.routes.get(self.path)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture()
def http_server():
    _Handler.routes = {"/ok.m3u": VALID_M3U, "/empty.m3u": b"", "/bad.m3u": b"garbage\nno url"}
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


@pytest.fixture()
def manager(tmp_path):
    builtin = tmp_path / "builtin.m3u"
    builtin.write_bytes(BUILTIN_M3U)
    return sources.SourceManager(
        builtin_path=builtin, config_path=tmp_path / "config.json", cache_path=tmp_path / "cache.m3u"
    )


# ---------- 地址校验 ----------

@pytest.mark.parametrize(
    "url",
    ["", "   ", "example.com/a.m3u", "ftp://a.com/a.m3u", "http://localhost/a.m3u", "http:///a.m3u"],
)
def test_invalid_url_rejected(manager, url):
    with pytest.raises(sources.InvalidSourceUrl):
        manager.set_custom(url)


def test_valid_lan_url_accepted_by_validator():
    assert sources.validate_url("http://192.168.1.5:8080/tv.m3u")
    assert sources.validate_url("  https://a.example.com/x.txt  ")


# ---------- 下载与解析 ----------

def test_set_custom_downloads_and_parses(manager, http_server):
    result = manager.set_custom(f"{http_server}/ok.m3u")
    assert [c.name for c in result] == ["CCTV1", "湖南卫视"]
    assert manager.meta()["mode"] == "custom"


def test_set_custom_persists_across_restart(manager, http_server, tmp_path):
    manager.set_custom(f"{http_server}/ok.m3u")
    again = sources.SourceManager(
        builtin_path=tmp_path / "builtin.m3u",
        config_path=tmp_path / "config.json",
        cache_path=tmp_path / "cache.m3u",
    )
    assert [c.name for c in again.current()] == ["CCTV1", "湖南卫视"]
    assert again.meta()["mode"] == "custom"


def test_set_custom_404_raises_download_error(manager, http_server):
    with pytest.raises(sources.SourceDownloadError):
        manager.set_custom(f"{http_server}/missing.m3u")


def test_set_custom_unreachable_host_raises_download_error(manager):
    with pytest.raises(sources.SourceDownloadError):
        manager.set_custom("http://127.0.0.1:1/none.m3u")


def test_set_custom_zero_channels_raises_parse_error(manager, http_server):
    with pytest.raises(sources.SourceParseError):
        manager.set_custom(f"{http_server}/bad.m3u")


def test_set_custom_empty_body_raises_parse_error(manager, http_server):
    with pytest.raises(sources.SourceParseError):
        manager.set_custom(f"{http_server}/empty.m3u")


def test_failed_custom_does_not_overwrite_previous(manager, http_server):
    manager.set_custom(f"{http_server}/ok.m3u")
    with pytest.raises(sources.SourceDownloadError):
        manager.set_custom(f"{http_server}/missing.m3u")
    assert [c.name for c in manager.current()] == ["CCTV1", "湖南卫视"]


def test_oversized_source_rejected(manager, http_server, monkeypatch):
    monkeypatch.setattr(sources, "MAX_SOURCE_BYTES", 10)
    with pytest.raises(sources.SourceDownloadError):
        manager.set_custom(f"{http_server}/ok.m3u")


# ---------- 恢复默认 ----------

def test_reset_restores_builtin(manager, http_server):
    manager.set_custom(f"{http_server}/ok.m3u")
    result = manager.reset()
    assert [c.name for c in result] == ["内置台"]
    assert manager.meta()["mode"] == "builtin"


def test_reset_clears_persisted_config(manager, http_server, tmp_path):
    manager.set_custom(f"{http_server}/ok.m3u")
    manager.reset()
    assert not (tmp_path / "config.json").exists() or json.loads(
        (tmp_path / "config.json").read_text(encoding="utf-8")
    ).get("custom_url") in (None, "")


def test_corrupted_config_falls_back_to_builtin(manager, tmp_path):
    (tmp_path / "config.json").write_text("{ not json", encoding="utf-8")
    assert [c.name for c in manager.current()] == ["内置台"]
    assert manager.meta()["mode"] == "builtin"


def test_missing_builtin_raises(manager, tmp_path):
    (tmp_path / "builtin.m3u").unlink()
    with pytest.raises(sources.SourceParseError):
        manager.current()
