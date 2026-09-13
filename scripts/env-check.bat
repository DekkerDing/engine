@echo off
REM ============================================================
REM env-check.bat — 一键环境体检（Windows）
REM 用法: scripts\env-check.bat
REM 检查: JDK 版本 / Gradle / Node / Python 及依赖
REM ============================================================
chcp 65001 >nul
echo ============================================
echo   engine 开发环境体检
echo ============================================

echo.
echo [1/4] Java...
java -version 2>&1 | findstr /i "version" || echo   [FAIL] 未找到 java，请安装 JDK 8

echo.
echo [2/4] Gradle...
gradle --version 2>&1 | findstr /i "^Gradle" || echo   [FAIL] 未找到 gradle，请安装 Gradle 7.6+ 或使用 gradlew

echo.
echo [3/4] Node / npm...
node --version 2>&1 || echo   [FAIL] 未找到 node，请安装 Node 18+
npm --version 2>&1 || echo   [FAIL] 未找到 npm

echo.
echo [4/4] Python（含依赖自检）...
set HF_ENDPOINT=https://hf-mirror.com
set PYTHONIOENCODING=utf-8
python "%~dp0..\python\env_check.py"

echo.
echo 体检完成。全部 PASS 即可执行: gradlew build
pause
