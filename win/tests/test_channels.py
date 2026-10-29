# -*- coding: utf-8 -*-
"""频道解析与分组。"""
from pathlib import Path

import pytest

import channels as ch

RAW_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res" / "raw"
BUILTIN = RAW_DIR / "channels.txt"


M3U_BASIC = """#EXTM3U x-tvg-url="http://epg.example.com/e.xml"
#EXTINF:-1 tvg-name="CCTV1" tvg-id="1" tvg-logo="http://logo/cctv1.png" group-title="央视频道", CCTV1
http://example.com/cctv1.m3u8
#EXTINF:-1 group-title="卫视频道", 湖南卫视
http://example.com/hunan.m3u8
"""


# ---------- M3U 解析 ----------

def test_parse_m3u_returns_all_channels():
    result = ch.parse(M3U_BASIC)
    assert len(result) == 2
    assert result[0].name == "CCTV1"
    assert result[1].name == "湖南卫视"


def test_parse_m3u_extracts_attributes():
    cctv1 = ch.parse(M3U_BASIC)[0]
    assert cctv1.url == "http://example.com/cctv1.m3u8"
    assert cctv1.group == "央视频道"
    assert cctv1.logo == "http://logo/cctv1.png"
    assert cctv1.tvg_id == "1"


def test_parse_m3u_group_falls_back_when_missing():
    text = '#EXTINF:-1, 无分组台\nhttp://example.com/a.m3u8\n'
    assert ch.parse(text)[0].group == ch.DEFAULT_GROUP


def test_parse_m3u_skips_extinf_without_url():
    text = '#EXTINF:-1 group-title="A", 空台\n#EXTINF:-1 group-title="A", 有台\nhttp://example.com/a.m3u8\n'
    result = ch.parse(text)
    assert len(result) == 1
    assert result[0].name == "有台"


def test_parse_m3u_merges_duplicate_names_into_backup_urls():
    text = (
        '#EXTINF:-1 group-title="A", 同名台\nhttp://example.com/1.m3u8\n'
        '#EXTINF:-1 group-title="A", 同名台\nhttp://example.com/2.m3u8\n'
    )
    result = ch.parse(text)
    assert len(result) == 1
    assert result[0].url == "http://example.com/1.m3u8"
    assert result[0].backups == ["http://example.com/2.m3u8"]


# ---------- 纯文本源 ----------

def test_parse_txt_name_comma_url():
    result = ch.parse("CCTV1,http://example.com/a.m3u8\n湖南卫视,http://example.com/b.m3u8\n")
    assert [c.name for c in result] == ["CCTV1", "湖南卫视"]
    assert result[0].url == "http://example.com/a.m3u8"
    assert result[0].group == ch.DEFAULT_GROUP


def test_parse_txt_supports_genre_group_marker():
    text = "央视频道,#genre#\nCCTV1,http://a/1.m3u8\n卫视频道,#genre#\n湖南卫视,http://a/2.m3u8\n"
    result = ch.parse(text)
    assert result[0].group == "央视频道"
    assert result[1].group == "卫视频道"


def test_parse_txt_ignores_comment_and_garbage_lines():
    text = "# 注释\n\n不是频道\nCCTV1,http://a/1.m3u8\n"
    result = ch.parse(text)
    assert len(result) == 1


# ---------- 异常与边界 ----------

@pytest.mark.parametrize("raw", ["", "   ", "\n\n"])
def test_parse_empty_input_returns_empty_list(raw):
    assert ch.parse(raw) == []


def test_parse_raises_on_unsupported_hex_source():
    with pytest.raises(ch.UnsupportedSourceError):
        ch.parse("2423abcdef" + "0" * 40)


# ---------- 分组聚合 ----------

def test_group_channels_counts_and_order():
    result = ch.parse(M3U_BASIC)
    groups = ch.group_channels(result)
    assert [g["name"] for g in groups] == ["央视频道", "卫视频道"]
    assert [g["count"] for g in groups] == [1, 1]


def test_group_channels_keeps_first_appearance_order():
    text = (
        '#EXTINF:-1 group-title="卫视", A\nhttp://a/1.m3u8\n'
        '#EXTINF:-1 group-title="央视", B\nhttp://a/2.m3u8\n'
        '#EXTINF:-1 group-title="卫视", C\nhttp://a/3.m3u8\n'
    )
    groups = ch.group_channels(ch.parse(text))
    assert [g["name"] for g in groups] == ["卫视", "央视"]
    assert groups[0]["count"] == 2


# ---------- 与真实内置源对齐 ----------

@pytest.mark.skipif(not BUILTIN.exists(), reason="内置源不存在")
def test_builtin_source_channel_and_group_counts():
    # 441 条记录里有 1 组重名（卫视频道/东方卫视 2 个地址），合并后为 440 台
    result = ch.parse(BUILTIN.read_text(encoding="utf-8"))
    assert len(result) == 440
    assert len(ch.group_channels(result)) == 11
    assert all(c.url.startswith(("http://", "https://")) for c in result)
    assert all(c.name for c in result)


@pytest.mark.skipif(not BUILTIN.exists(), reason="内置源不存在")
def test_builtin_source_drops_identical_duplicate_record():
    # 「卫视频道/东方卫视」在源里出现两次且地址完全相同，应合并为一条且不产生备用线路
    result = ch.parse(BUILTIN.read_text(encoding="utf-8"))
    dfs = [c for c in result if c.name == "东方卫视"]
    assert len(dfs) == 1
    assert dfs[0].backups == []


@pytest.mark.skipif(not BUILTIN.exists(), reason="内置源不存在")
def test_builtin_source_has_no_duplicate_names_within_group():
    result = ch.parse(BUILTIN.read_text(encoding="utf-8"))
    keys = [(c.group, c.name) for c in result]
    assert len(keys) == len(set(keys))
