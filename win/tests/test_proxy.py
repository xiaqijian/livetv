# -*- coding: utf-8 -*-
"""HLS 代理：URL 封装、m3u8 分片/密钥地址重写、协议白名单。"""
import urllib.parse

import pytest

import proxy


# ---------- 代理 URL 封装 ----------

def test_build_proxy_url_roundtrip():
    target = "http://ex.com/a/b.m3u8?x=1&y=2"
    wrapped = proxy.wrap(target)
    assert wrapped.startswith("/api/proxy?u=")
    assert proxy.unwrap(wrapped) == target


def test_unwrap_handles_percent_encoding():
    target = "http://ex.com/中文/频道.m3u8?k=v"
    assert proxy.unwrap(proxy.wrap(target)) == target


@pytest.mark.parametrize(
    "url",
    ["file:///c:/secret.txt", "gopher://a.com/x", "ftp://a.com/x", "", "javascript:alert(1)"],
)
def test_only_http_https_allowed(url):
    with pytest.raises(proxy.ProxyError):
        proxy.wrap(url)


def test_wrap_rejects_loopback_to_self():
    # 防止本服务被当成跳板访问自身；其他端口（如局域网/本机其他服务）仍允许
    proxy.set_self_port(8899)
    try:
        with pytest.raises(proxy.ProxyError):
            proxy.wrap("http://127.0.0.1:8899/api/source")
        assert proxy.wrap("http://127.0.0.1:9000/a.m3u8").startswith("/api/proxy?u=")
    finally:
        proxy.set_self_port(None)


# ---------- m3u8 重写 ----------

BANAL = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-KEY:METHOD=AES-128,URI="/key/k1.bin",IV=0x1234
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6.0,
seg1.ts
#EXTINF:6.0,
/sub/dir/seg2.ts
#EXTINF:6.0,
http://cdn.ex.com/seg3.ts?token=abc
#EXT-X-ENDLIST
"""


def test_rewrite_segments_to_absolute_proxy_urls():
    out = proxy.rewrite_playlist(BANAL, base_url="http://origin.ex.com/live/ch/index.m3u8")
    urls = [proxy.unwrap(line) for line in out.splitlines() if line.startswith("/api/proxy?u=")]
    assert "http://origin.ex.com/live/ch/seg1.ts" in urls
    assert "http://origin.ex.com/sub/dir/seg2.ts" in urls
    assert "http://cdn.ex.com/seg3.ts?token=abc" in urls


def test_rewrite_keeps_tags_and_structure():
    out = proxy.rewrite_playlist(BANAL, base_url="http://origin.ex.com/live/index.m3u8")
    assert "#EXTM3U" in out
    assert "#EXT-X-VERSION:3" in out
    assert "#EXTINF:6.0," in out
    assert "#EXT-X-ENDLIST" in out
    assert out.count("#EXTINF") == 3


def test_rewrite_key_and_map_uri():
    out = proxy.rewrite_playlist(BANAL, base_url="http://origin.ex.com/live/index.m3u8")
    key_line = [l for l in out.splitlines() if l.startswith("#EXT-X-KEY")][0]
    map_line = [l for l in out.splitlines() if l.startswith("#EXT-X-MAP")][0]
    assert 'URI="/api/proxy?u=' in key_line
    assert 'URI="/api/proxy?u=' in map_line
    key_uri = key_line.split('URI="')[1].split('"')[0]
    assert proxy.unwrap(key_uri) == "http://origin.ex.com/key/k1.bin"
    assert "IV=0x1234" in key_line


def test_rewrite_master_playlist_variant_streams():
    master = (
        "#EXTM3U\n"
        '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720\n'
        "720p/index.m3u8\n"
        '#EXT-X-STREAM-INF:BANDWIDTH=2000000\n'
        "http://cdn.ex.com/1080p/index.m3u8\n"
    )
    out = proxy.rewrite_playlist(master, base_url="http://origin.ex.com/live/master.m3u8")
    urls = [proxy.unwrap(l) for l in out.splitlines() if l.startswith("/api/proxy?u=")]
    assert "http://origin.ex.com/live/720p/index.m3u8" in urls
    assert "http://cdn.ex.com/1080p/index.m3u8" in urls


def test_rewrite_ignores_blank_lines_and_plain_comments():
    out = proxy.rewrite_playlist("# a comment\n\n", base_url="http://e.com/a.m3u8")
    assert "# a comment" in out


def test_rewrite_skips_unsupported_scheme_lines():
    out = proxy.rewrite_playlist("rtmp://a.com/x\n", base_url="http://e.com/a.m3u8")
    assert "api/proxy" not in out


def test_is_playlist_detection():
    assert proxy.is_playlist(b"#EXTM3U\n#EXTINF:1,\na.ts\n")
    assert not proxy.is_playlist(b"\x47\x40\x11\x10 binary ts")


# ---------- 上游请求头 ----------

def test_upstream_headers_default_without_referer():
    # 实测部分源带 Referer 会返回 404，默认不带，仅在 4xx 时重试补上
    headers = proxy.upstream_headers("http://ex.com/live/a.m3u8")
    assert "Mozilla" in headers["User-Agent"]
    assert "Referer" not in headers


def test_upstream_headers_with_referer_uses_origin():
    headers = proxy.upstream_headers("http://ex.com/live/a.m3u8", with_referer=True)
    assert headers["Referer"] == "http://ex.com/"
