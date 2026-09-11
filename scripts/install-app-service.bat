@echo off
REM ============================================================
REM 将 MicroTripServerJava 注册为 Windows 服务（开机自启）
REM ------------------------------------------------------------
REM 必须以管理员身份运行（右键 -> 以管理员身份运行）
REM 作用：下载 WinSW（若缺） -> 生成配置 -> 安装并启动 MicroTripServer 服务
REM 服务入口是 scripts\start-app.bat（它会等待 MySQL/Redis 就绪再启动 App）
REM ============================================================
net session >nul 2>&1
if errorlevel 1 (
  echo [错误] 本脚本需管理员权限，请右键「以管理员身份运行」
  pause
  exit /b 1
)
setlocal
cd /d "%~dp0"

set WINSW_URL=https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe
set WINSW_EXE=WinSW.exe
set SVC_EXE=microtrip-server.exe
set SVC_XML=microtrip-server.xml

REM 项目根 = scripts\ 的上一级（动态解析，支持项目移动）
for %%I in ("%~dp0..") do set PROJECT_ROOT=%%~fI

REM ---------- 1. 下载 WinSW（若缺失） ----------
if not exist "%WINSW_EXE%" (
  echo [1/4] 下载 WinSW ...
  powershell -NoProfile -Command "Invoke-WebRequest -Uri '%WINSW_URL%' -OutFile '%WINSW_EXE%' -UseBasicParsing"
  if not exist "%WINSW_EXE%" (
    echo [错误] 下载失败。请手动下载 %WINSW_URL% 并放到 %~dp0
    pause
    exit /b 1
  )
)

REM ---------- 2. 复制为同名 exe（WinSW 要求 exe 与 xml 同名、同目录） ----------
copy /Y "%WINSW_EXE%" "%SVC_EXE%" >nul
echo [2/4] WinSW 就绪: %SVC_EXE%

REM ---------- 3. 生成服务配置 xml（动态写入项目绝对路径） ----------
echo [3/4] 生成 %SVC_XML% ...
(
echo ^<service^>
echo   ^<id^>MicroTripServer^</id^>
echo   ^<name^>MicroTrip Server (Java Backend)^</name^>
echo   ^<description^>MicroTrip backend, port 3000, profile=prod,mysql,redis^</description^>
echo   ^<executable^>scripts\start-app.bat^</executable^>
echo   ^<workingdirectory^>%PROJECT_ROOT%^</workingdirectory^>
echo   ^<logpath^>%PROJECT_ROOT%\.run^</logpath^>
echo   ^<logmode^>roll^</logmode^>
echo   ^<startmode^>Automatic^</startmode^>
echo   ^<onfailure action="restart" delay="10 sec" /^>
echo   ^<priority^>Normal^</priority^>
echo ^</service^>
) > "%SVC_XML%"
if not exist "%SVC_XML%" (
  echo [错误] 生成 %SVC_XML% 失败
  pause
  exit /b 1
)

REM ---------- 4. 安装并启动服务 ----------
echo [4/4] 安装并启动服务 MicroTripServer ...
"%SVC_EXE%" install
if errorlevel 1 (
  echo [错误] 安装失败，详见上方输出
  pause
  exit /b 1
)
"%SVC_EXE%" start
echo.
echo 完成。Windows 服务名: MicroTripServer（开机自启）
echo   . 停止:  %SVC_EXE% stop
echo   . 重启:  %SVC_EXE% restart
echo   . 卸载:  %SVC_EXE% uninstall
echo   . 日志:  .run\microtrip-server.out.log / microtrip-server.wrapper.log
pause
