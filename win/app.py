# -*- coding: utf-8 -*-
"""电视直播 Windows 版入口：起本地服务并自动打开浏览器。

用法：python app.py [--port 8899] [--no-browser]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import urllib.request
import webbrowser
from pathlib import Path

import proxy
import server
from sources import SourceManager

APP_TITLE = "电视直播 Windows 版"
DEFAULT_PORT = 8899
PORT_SCAN_RANGE = 20


def resource_dir() -> Path:
    """PyInstaller 打包后资源解压目录，开发时为脚本所在目录。"""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def data_dir() -> Path:
    """配置与缓存放程序目录（便携）；目录不可写时退回 LOCALAPPDATA。"""
    portable = Path(sys.executable).parent if getattr(sys, "frozen", False) else resource_dir()
    fallback = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "LiveTVWin"
    for candidate in (portable, fallback):
        try:
            candidate.mkdir(parents=True, exist_ok=True)
            probe = candidate / ".write-test"
            probe.write_text("ok", encoding="utf-8")
            probe.unlink()
            return candidate
        except OSError:
            continue
    raise SystemExit("无法创建配置目录，请检查磁盘权限")


def builtin_source_path() -> Path:
    bundled = resource_dir() / "channels.txt"
    if bundled.exists():
        return bundled
    # 开发模式：直接复用 Android 端的内置源，避免维护两份数据
    return resource_dir().parent / "app" / "src" / "main" / "res" / "raw" / "channels.txt"


def running_instance(port: int) -> bool:
    """目标端口上是否已有本服务在跑。"""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=0.6) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("ok") is True
    except Exception:
        return False


def pick_port(preferred: int) -> tuple[int, bool]:
    """返回 (端口, 是否已有实例)。"""
    for offset in range(PORT_SCAN_RANGE):
        port = preferred + offset
        if running_instance(port):
            return port, True
        try:
            import socket

            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.settimeout(0.3)
                if probe.connect_ex(("127.0.0.1", port)) != 0:
                    return port, False
        except OSError:
            continue
    raise SystemExit(f"端口 {preferred}~{preferred + PORT_SCAN_RANGE - 1} 都被占用，请先关闭占用程序")


def open_browser_later(url: str, delay: float = 0.6) -> None:
    def run():
        webbrowser.open(url)

    threading.Timer(delay, run).start()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=APP_TITLE)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args(argv)

    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    try:
        port, already_running = pick_port(args.port)
    except SystemExit as exc:
        print(exc)
        return 1

    url = f"http://127.0.0.1:{port}/"
    if already_running:
        print(f"服务已在运行：{url}")
        if not args.no_browser:
            webbrowser.open(url)
        return 0

    builtin = builtin_source_path()
    if not builtin.exists():
        print(f"找不到内置频道文件：{builtin}")
        return 1

    home = data_dir()
    manager = SourceManager(
        builtin_path=builtin,
        config_path=home / "config.json",
        cache_path=home / "cache.m3u",
    )
    static_dir = resource_dir() / "static"
    if not static_dir.is_dir():
        print(f"找不到页面资源目录：{static_dir}")
        return 1

    httpd = server.make_server(manager, static_dir, port=port)
    proxy.set_self_port(port)

    summary = manager.summary()
    print("=" * 46)
    print(f"  {APP_TITLE}")
    print("=" * 46)
    print(f"  地址：{url}")
    print(f"  频道：{summary['channelCount']} 台 / {summary['groupCount']} 个分组")
    print(f"  源：{'自定义' if summary['mode'] == 'custom' else '内置'}")
    print("  关闭此窗口即退出")
    print("=" * 46)

    if not args.no_browser:
        open_browser_later(url)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")
    finally:
        httpd.shutdown()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
