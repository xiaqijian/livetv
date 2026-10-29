# -*- coding: utf-8 -*-
"""打包脚本：把内置源与静态页面一起打进单文件窗口版 exe。

用法：python build.py
产物：dist/电视直播.exe（无控制台，原生窗口）
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID_SOURCE = HERE.parent / "app" / "src" / "main" / "res" / "raw" / "channels.txt"
BUNDLED_SOURCE = HERE / "channels.txt"
APP_NAME = "电视直播"


def main() -> int:
    if not ANDROID_SOURCE.exists():
        print(f"找不到内置源：{ANDROID_SOURCE}")
        return 1
    shutil.copyfile(ANDROID_SOURCE, BUNDLED_SOURCE)
    print(f"已同步内置源：{BUNDLED_SOURCE.name}")

    if not (HERE / "static" / "vendor" / "hls.min.js").exists():
        print("缺少 static/vendor/hls.min.js，页面将无法播放 HLS")
        return 1

    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--onefile",
        # 桌面程序不能弹黑框
        "--windowed",
        "--name",
        APP_NAME,
        "--add-data",
        f"static{_sep()}static",
        "--add-data",
        f"channels.txt{_sep()}.",
        "--collect-submodules",
        "encodings",
        # WebView2 窗口依赖
        "--collect-all",
        "webview",
        "--collect-all",
        "pythonnet",
        "--collect-all",
        "clr_loader",
        "--hidden-import",
        "clr",
        str(HERE / "desktop.py"),
    ]
    print("执行：" + " ".join(command))
    result = subprocess.run(command, cwd=HERE)
    if result.returncode != 0:
        print("打包失败")
        return result.returncode

    exe = HERE / "dist" / f"{APP_NAME}.exe"
    if exe.exists():
        print(f"打包完成：{exe}  ({exe.stat().st_size / 1024 / 1024:.1f} MB)")
    return 0


def _sep() -> str:
    return ";" if sys.platform.startswith("win") else ":"


if __name__ == "__main__":
    sys.exit(main())
