# -*- coding: utf-8 -*-
"""电视直播 桌面版：原生窗口（内嵌 WebView2），不打开浏览器。

窗口关闭即退出程序；服务只监听 127.0.0.1，随窗口生命周期启停。

用法：python desktop.py [--port 8899]
"""
from __future__ import annotations

import argparse
import ctypes
import sys
import threading

import webview

import app as backend
import proxy
import server
from sources import SourceManager

WINDOW_TITLE = "电视直播"


def enable_dpi_awareness() -> None:
    """高分屏下让 WebView2 按真实像素渲染，避免界面发虚。"""
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def fatal(message: str) -> None:
    """窗口版没有控制台，出错必须弹窗，否则用户只看到程序一闪而过。"""
    try:
        ctypes.windll.user32.MessageBoxW(None, message, WINDOW_TITLE, 0x10)
    except Exception:
        print(message)


def build_backend(port: int):
    """启动本地服务线程，返回 (httpd, manager)。"""
    builtin = backend.builtin_source_path()
    if not builtin.exists():
        raise RuntimeError(f"找不到内置频道文件：\n{builtin}")

    static_dir = backend.resource_dir() / "static"
    if not static_dir.is_dir():
        raise RuntimeError(f"找不到页面资源目录：\n{static_dir}")

    home = backend.data_dir()
    manager = SourceManager(
        builtin_path=builtin,
        config_path=home / "config.json",
        cache_path=home / "cache.m3u",
    )
    httpd = server.make_server(manager, static_dir, port=port)
    proxy.set_self_port(port)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, manager


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=WINDOW_TITLE)
    parser.add_argument("--port", type=int, default=backend.DEFAULT_PORT)
    parser.add_argument("--width", type=int, default=1366)
    parser.add_argument("--height", type=int, default=800)
    args = parser.parse_args(argv)

    enable_dpi_awareness()

    try:
        port, already_running = backend.pick_port(args.port)
    except SystemExit as exc:
        fatal(str(exc))
        return 1

    url = f"http://127.0.0.1:{port}/"
    httpd = None
    if not already_running:
        try:
            httpd, _manager = build_backend(port)
        except Exception as exc:
            fatal(f"启动失败：{exc}")
            return 1
    # 已有实例在跑时直接复用它的服务，窗口关闭时也不去关它

    try:
        webview.create_window(
            WINDOW_TITLE,
            url,
            width=args.width,
            height=args.height,
            min_size=(1024, 640),
            background_color="#0b0f14",
        )
        webview.start(gui="edgechromium", debug=False, private_mode=False)
    except Exception as exc:
        fatal(f"窗口创建失败：{exc}\n\n请确认系统已安装 WebView2 运行时。")
        return 1
    finally:
        if httpd is not None:
            httpd.shutdown()
            httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
