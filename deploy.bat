@echo off
chcp 65001 >nul
setlocal

:: 自动加载 .env 环境变量
if exist .env (
    copy "%~dp0.env" "%TEMP%\env.cmd" >nul
    call "%TEMP%\env.cmd"
    del "%TEMP%\env.cmd"
)

echo.
echo ╔═══════════════════════════════════════════════╗
echo ║  PeaceHaven 一键打包 + OSS传输加速上传       ║
echo ╚═══════════════════════════════════════════════╝
echo.

:: Step 1: 打包
echo [1/2] 打包 JAR...
call mvnw.cmd package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 打包失败，请检查编译错误
    pause
    exit /b 1
)
echo [1/2] 打包完成 ✓
echo.

:: Step 2: 上传
echo [2/2] 上传到 OSS (传输加速)...
call mvnw.cmd exec:java -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] 上传失败
    pause
    exit /b 1
)

echo.
echo ══════════════════════════════════════════
echo  全部完成!
echo ══════════════════════════════════════════
pause