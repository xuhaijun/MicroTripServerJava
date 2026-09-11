@echo off
setlocal EnableExtensions
REM 切换到项目根（scripts\ 的上一级），不依赖固定路径，便于项目移动
cd /d "%~dp0\.."

REM ---------- JWT 密钥（与 run-local.sh 行为一致：复用或生成 .jwt_secret） ----------
if not exist ".jwt_secret" (
  powershell -NoProfile -Command "$c=48; $s=-join ((65..90)+(97..122)+(48..57)+@(43,47) | Get-Random -Count $c | ForEach-Object {[char]$_}); [System.IO.File]::WriteAllText('.jwt_secret',$s)"
  echo [start-app] 已生成新的 JWT 密钥 -^> .jwt_secret
) else (
  echo [start-app] 复用已有 .jwt_secret
)
set /p JWT_SECRET=< ".jwt_secret"

REM ---------- 本地无证书：关闭 HTTPS 强制（否则请求被重定向到不存在的 https 端口） ----------
set REQUIRE_HTTPS=false

REM ---------- 等待依赖端口（服务可能早于 MySQL/Redis 启动，最多等 60s） ----------
call :waitport 3306 MySQL
call :waitport 6379 Redis

REM ---------- 启动（前台运行，保持服务进程；停止由 WinSW 终止进程树） ----------
java -XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -jar "target\micro-trip-server-boot.jar" --spring.profiles.active=prod,mysql,redis --server.port=3000
goto :eof

:waitport
set PORT=%1
set NAME=%2
set TRIES=0
:loop
powershell -NoProfile -Command "Test-NetConnection -ComputerName 127.0.0.1 -Port %PORT% -InformationLevel Quiet -WarningAction SilentlyContinue" | findstr /i "True" >nul 2>&1
if not errorlevel 1 (
  echo [start-app] %NAME% :%PORT% 已就绪
  goto :eof
)
set /a TRIES+=1
if %TRIES% GEQ 60 (
  echo [start-app] 等待 %NAME% :%PORT% 超时(60s)，仍尝试启动（可能失败）
  goto :eof
)
echo [start-app] 等待 %NAME% :%PORT% ... (%TRIES%s)
timeout /t 1 /nobreak >nul
goto :loop
