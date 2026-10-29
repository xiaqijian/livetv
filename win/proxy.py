# -*- coding: utf-8 -*-
"""HLS 代理：把上游流地址包成本机地址，绕过浏览器 CORS，并重写 m3u8 内部地址。

只允许 http/https；拒绝回环地址，避免本服务被当作跳板访问本机。
"""
from __future__ import annotations

import re
import urllib.error
import urllib.parse
import urllib.request

PROXY_PATH = "/api/proxy"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)
# 部分直播源分片下载本身就要二三十秒，超时给宽一些
DEFAULT_TIMEOUT = 30
MAX_BODY_BYTES = 32 * 1024 * 1024

_ALLOWED_SCHEMES = ("http", "https")
_BLOCKED_HOSTS = {"localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"}
_URI_ATTR_RE = re.compile(r'URI="([^"]*)"')
# 这些标签内部含 URI 属性，需要一并改写
_URI_TAGS = ("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF",
             "#EXT-X-PART", "#EXT-X-PRELOAD-HINT", "#EXT-X-SESSION-KEY")


class ProxyError(Exception):
    """代理相关错误基类，message 直接面向用户展示。"""


class ProxyRequestError(ProxyError):
    """请求本身不合法（协议不支持、指向自身），应回 400。"""


# 本服务自身端口，用于阻止把本服务当作代理跳板；启动时由 app 注入
_self_port: int | None = None


def set_self_port(port: int | None) -> None:
    global _self_port
    _self_port = port


def _is_loopback(host: str) -> bool:
    return host in _BLOCKED_HOSTS or host.startswith("127.")


def _check_target(url: str) -> str:
    url = (url or "").strip()
    if not url:
        raise ProxyRequestError("缺少上游地址")
    parts = urllib.parse.urlsplit(url)
    if parts.scheme.lower() not in _ALLOWED_SCHEMES:
        raise ProxyRequestError(f"不支持的协议：{parts.scheme or '未知'}")
    host = (parts.hostname or "").strip().lower()
    if not host:
        raise ProxyRequestError("上游地址缺少主机名")
    # 允许访问局域网/本机其他端口（有些源就在内网），但禁止代理回本服务自身
    if _self_port is not None and _is_loopback(host):
        port = parts.port or (443 if parts.scheme.lower() == "https" else 80)
        if port == _self_port:
            raise ProxyRequestError("不允许代理回本服务自身")
    return url


def wrap(url: str) -> str:
    """把上游地址包成 /api/proxy?u=... 形式。"""
    target = _check_target(url)
    return f"{PROXY_PATH}?u=" + urllib.parse.quote(target, safe="")


def unwrap(wrapped: str) -> str:
    """从 /api/proxy?u=... 还原上游地址。"""
    query = urllib.parse.urlsplit(wrapped).query
    values = urllib.parse.parse_qs(query).get("u") or []
    return values[0] if values else ""


def is_playlist(body: bytes) -> bool:
    return body.lstrip()[:7].upper().startswith(b"#EXTM3U")


def rewrite_playlist(text: str, base_url: str) -> str:
    """把 m3u8 里的分片、子列表、密钥地址全部改写成本机代理地址。"""
    lines: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            lines.append(raw)
            continue
        if line.startswith("#"):
            if line.startswith(_URI_TAGS):
                lines.append(_rewrite_uri_attr(line, base_url))
            else:
                lines.append(raw)
            continue
        absolute = urllib.parse.urljoin(base_url, line)
        try:
            lines.append(wrap(absolute))
        except ProxyError:
            # 不支持的协议原样保留，交给浏览器自己判断
            lines.append(raw)
    return "\n".join(lines) + "\n"


def _rewrite_uri_attr(line: str, base_url: str) -> str:
    def replace(match: re.Match) -> str:
        absolute = urllib.parse.urljoin(base_url, match.group(1))
        try:
            return f'URI="{wrap(absolute)}"'
        except ProxyError:
            return match.group(0)

    return _URI_ATTR_RE.sub(replace, line)


def upstream_headers(url: str, with_referer: bool = False) -> dict[str, str]:
    """默认不发送 Referer：实测相当一部分直播源对 Referer 做了防盗链，
    带上反而返回 404；只有上游明确拒绝时才由 fetch 重试补上。"""
    parts = urllib.parse.urlsplit(url)
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }
    if with_referer:
        headers["Referer"] = f"{parts.scheme}://{parts.netloc}/"
    return headers


def fetch(url: str, timeout: float = DEFAULT_TIMEOUT) -> tuple[int, dict[str, str], bytes]:
    """请求上游，返回 (状态码, 响应头, 原始字节)。异常统一转为 ProxyError。"""
    target = _check_target(url)
    try:
        return _open(target, timeout, with_referer=False)
    except ProxyError as exc:
        # 上游因防盗链拒绝时，补上同源 Referer 再试一次
        if getattr(exc, "upstream_status", None) in (401, 403, 404, 406):
            try:
                return _open(target, timeout, with_referer=True)
            except ProxyError:
                raise exc from None
        raise


def _open(url: str, timeout: float, with_referer: bool) -> tuple[int, dict[str, str], bytes]:
    request = urllib.request.Request(url, headers=upstream_headers(url, with_referer))
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(MAX_BODY_BYTES + 1)
            if len(body) > MAX_BODY_BYTES:
                raise ProxyError("上游响应过大，已中断")
            return response.status, dict(response.headers), body
    except ProxyError:
        raise
    except urllib.error.HTTPError as exc:
        error = ProxyError(f"上游返回 HTTP {exc.code}")
        error.upstream_status = exc.code
        raise error from exc
    except Exception as exc:
        raise ProxyError(f"无法连接上游：{exc}") from exc
