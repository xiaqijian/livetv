# -*- coding: utf-8 -*-
"""频道源解析：M3U / 纯文本 → Channel 列表 → 分组结构。

纯标准库，无第三方依赖，便于单元测试与单机运行。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable

DEFAULT_GROUP = "未分组"

# 纯十六进制大文本视为作者私有加密格式（AES），本工具不解码
_HEX_RE = re.compile(r"^[0-9a-fA-F]+$")
_ATTR_RE = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')
_URL_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*://\S+$")
_GENRE_SUFFIX = ",#genre#"


class UnsupportedSourceError(ValueError):
    """源不是本工具支持的 M3U / 纯文本格式。"""


@dataclass
class Channel:
    name: str
    url: str
    group: str = DEFAULT_GROUP
    logo: str = ""
    tvg_id: str = ""
    backups: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "url": self.url,
            "group": self.group,
            "logo": self.logo,
            "tvgId": self.tvg_id,
            "backups": list(self.backups),
        }


def _is_hex_blob(text: str) -> bool:
    s = text.strip()
    # 40 位以上、且不含换行/空格/冒号的十六进制串，才判定为加密源
    return len(s) >= 40 and bool(_HEX_RE.match(s))


def parse(text: str | None) -> list[Channel]:
    """解析源文本。M3U 与纯文本自动识别，同名同组频道合并备用地址。"""
    if not text:
        return []
    stripped = text.lstrip("\ufeff").strip()
    if not stripped:
        return []
    if _is_hex_blob(stripped):
        raise UnsupportedSourceError("源为加密十六进制格式，Windows 版暂不支持")
    if "#EXTINF" in stripped:
        raw = _parse_m3u(stripped)
    else:
        raw = _parse_txt(stripped)
    return _merge_duplicates(raw)


def _parse_m3u(text: str) -> list[Channel]:
    result: list[Channel] = []
    pending: Channel | None = None

    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            pending = _channel_from_extinf(line)
        elif line.startswith("#"):
            continue
        elif _URL_RE.match(line):
            if pending is None:
                # 无 #EXTINF 的裸地址行，用域名兜底命名，避免整条丢失
                pending = Channel(name=_fallback_name(line), url=line)
            else:
                pending.url = line
            result.append(pending)
            pending = None
    return result


def _channel_from_extinf(line: str) -> Channel:
    # 频道名取最后一个逗号之后；属性取引号内的 tvg-*
    head, _, tail = line.partition(",")
    name = tail.strip() or "未命名频道"
    attrs = {k.lower(): v for k, v in _ATTR_RE.findall(head)}
    if attrs.get("tvg-name"):
        name = attrs["tvg-name"].strip() or name
    return Channel(
        name=name,
        url="",
        group=(attrs.get("group-title") or "").strip() or DEFAULT_GROUP,
        logo=(attrs.get("tvg-logo") or "").strip(),
        tvg_id=(attrs.get("tvg-id") or "").strip(),
    )


def _fallback_name(url: str) -> str:
    host = url.split("://", 1)[-1].split("/", 1)[0]
    return host or "未命名频道"


def _parse_txt(text: str) -> list[Channel]:
    result: list[Channel] = []
    group = DEFAULT_GROUP

    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith(_GENRE_SUFFIX):
            marker = line[: -len(_GENRE_SUFFIX)].strip()
            group = DEFAULT_GROUP if marker in ("", "-") else marker
            continue
        name, sep, url = line.partition(",")
        if not sep:
            continue
        url = url.strip()
        if not _URL_RE.match(url):
            continue
        result.append(
            Channel(name=name.strip() or _fallback_name(url), url=url, group=group)
        )
    return result


def _merge_duplicates(items: Iterable[Channel]) -> list[Channel]:
    merged: dict[tuple[str, str], Channel] = {}
    for item in items:
        key = (item.group, item.name)
        first = merged.get(key)
        if first is None:
            merged[key] = item
        elif item.url and item.url != first.url and item.url not in first.backups:
            first.backups.append(item.url)
    return list(merged.values())


def group_channels(items: Iterable[Channel]) -> list[dict]:
    """按首次出现顺序聚合分组及台数。"""
    counts: dict[str, int] = {}
    for item in items:
        counts[item.group] = counts.get(item.group, 0) + 1
    return [{"name": name, "count": count} for name, count in counts.items()]
