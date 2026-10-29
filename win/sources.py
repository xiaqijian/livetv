# -*- coding: utf-8 -*-
"""订阅源管理：内置源加载、自定义源下载/校验/持久化、恢复默认。

只做本机文件读写与一次 HTTP GET，不访问任何自有服务端。
"""
from __future__ import annotations

import gzip
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from channels import Channel, UnsupportedSourceError, group_channels, parse

DEFAULT_TIMEOUT = 15
MAX_SOURCE_BYTES = 5 * 1024 * 1024
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)


class SourceError(Exception):
    """订阅源相关错误基类，message 直接面向用户展示。"""


class InvalidSourceUrl(SourceError):
    pass


class SourceDownloadError(SourceError):
    pass


class SourceParseError(SourceError):
    pass


def validate_url(raw: str | None) -> bool:
    """与 Android 端 SourceUrlValidator 对齐：只接受 http/https + 带点的主机名。"""
    url = (raw or "").strip()
    if not url:
        return False
    parts = urllib.parse.urlsplit(url)
    if parts.scheme.lower() not in ("http", "https"):
        return False
    host = (parts.hostname or "").strip()
    return bool(host) and "." in host


class SourceManager:
    def __init__(self, builtin_path: Path, config_path: Path, cache_path: Path):
        self.builtin_path = Path(builtin_path)
        self.config_path = Path(config_path)
        self.cache_path = Path(cache_path)

    # ---------- 对外 ----------

    def builtin(self) -> list[Channel]:
        return self._parse_or_raise(self._read_builtin(), "内置源为空或格式不正确")

    def current(self) -> list[Channel]:
        url = self._custom_url()
        if url:
            cached = self._read_cache()
            if cached is not None:
                try:
                    items = parse(cached)
                    if items:
                        return items
                except UnsupportedSourceError:
                    pass
            # 缓存缺失但配置还在：尝试补下载一次，失败则回落内置源
            try:
                return self._fetch_and_store(url)
            except SourceError:
                pass
        return self.builtin()

    def set_custom(self, raw_url: str) -> list[Channel]:
        url = (raw_url or "").strip()
        if not validate_url(url):
            raise InvalidSourceUrl("请输入以 http:// 或 https:// 开头的完整订阅地址")
        # 先下载并解析成功，再落盘，失败时保留上一次可用的源
        items = self._fetch_and_store(url)
        self._write_config({"custom_url": url, "updated_at": int(time.time())})
        return items

    def reset(self) -> list[Channel]:
        for path in (self.config_path, self.cache_path):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        return self.builtin()

    def meta(self) -> dict:
        url = self._custom_url()
        config = self._read_config()
        return {
            "mode": "custom" if url else "builtin",
            "url": url,
            "updatedAt": config.get("updated_at"),
        }

    def summary(self) -> dict:
        items = self.current()
        return {**self.meta(), "channelCount": len(items), "groupCount": len(group_channels(items))}

    # ---------- 下载 ----------

    def _fetch_and_store(self, url: str) -> list[Channel]:
        text = self._download(url)
        items = self._parse_or_raise(text, "订阅源里没有解析到任何频道")
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(text, encoding="utf-8")
        return items

    def _download(self, url: str) -> str:
        request = urllib.request.Request(
            url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"}
        )
        try:
            with urllib.request.urlopen(request, timeout=DEFAULT_TIMEOUT) as response:
                status = getattr(response, "status", 200)
                if status != 200:
                    raise SourceDownloadError(f"订阅地址返回 {status}")
                raw = response.read(MAX_SOURCE_BYTES + 1)
                if len(raw) > MAX_SOURCE_BYTES:
                    raise SourceDownloadError(
                        f"订阅源超过 {MAX_SOURCE_BYTES // (1024 * 1024)} MB，已拒绝加载"
                    )
                if (response.headers.get("Content-Encoding") or "").lower() == "gzip":
                    raw = gzip.decompress(raw)
                charset = response.headers.get_content_charset() or "utf-8"
        except urllib.error.HTTPError as exc:
            raise SourceDownloadError(f"订阅地址返回 HTTP {exc.code}") from exc
        except SourceDownloadError:
            raise
        except Exception as exc:  # 网络不可达 / 超时 / 证书错误
            raise SourceDownloadError(f"下载订阅源失败：{exc}") from exc
        return self._decode(raw, charset)

    @staticmethod
    def _decode(raw: bytes, charset: str) -> str:
        for enc in (charset, "utf-8", "gb18030"):
            try:
                return raw.decode(enc)
            except (UnicodeDecodeError, LookupError):
                continue
        return raw.decode("utf-8", errors="replace")

    # ---------- 解析 ----------

    @staticmethod
    def _parse_or_raise(text: str, message: str) -> list[Channel]:
        try:
            items = parse(text)
        except UnsupportedSourceError as exc:
            raise SourceParseError(str(exc)) from exc
        if not items:
            raise SourceParseError(message)
        return items

    # ---------- 本地文件 ----------

    def _read_builtin(self) -> str:
        try:
            return self.builtin_path.read_text(encoding="utf-8")
        except FileNotFoundError as exc:
            raise SourceParseError("内置频道文件缺失") from exc
        except OSError as exc:
            raise SourceParseError(f"读取内置频道失败：{exc}") from exc

    def _read_cache(self) -> str | None:
        try:
            return self.cache_path.read_text(encoding="utf-8")
        except (FileNotFoundError, OSError):
            return None

    def _read_config(self) -> dict:
        try:
            data = json.loads(self.config_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, OSError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}

    def _custom_url(self) -> str:
        url = self._read_config().get("custom_url")
        return url.strip() if isinstance(url, str) else ""

    def _write_config(self, payload: dict) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
