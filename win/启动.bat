@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 优先用 py 启动器的 pythonw（窗口运行，不留黑框）
where py >nul 2>nul
if %errorlevel%==0 (
    start "" pyw -3 desktop.py %*
    exit /b 0
)

where pythonw >nul 2>nul
if %errorlevel%==0 (
    start "" pythonw desktop.py %*
    exit /b 0
)

where python >nul 2>nul
if %errorlevel%==0 (
    python desktop.py %*
    exit /b 0
)

echo.
echo   未检测到 Python。请任选一种方式：
echo   1. 安装 Python 3.10+ 后重新双击本文件
echo   2. 改用 电视直播.exe 单文件版（无需安装 Python）
echo.
pause
exit /b 1
