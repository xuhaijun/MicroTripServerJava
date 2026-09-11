@echo off
rem ============================================================
rem install-mysql-service.bat
rem Register the local MySQL 8.4 as a Windows service (auto-start),
rem pointing at D:/mysql-local/data (the dir that holds the microtrip DB).
rem
rem HOW TO RUN: right-click this file -> "Run as administrator".
rem
rem IMPORTANT: the winget MySQL installer may have already created a
rem "MySQL84" service that points to the DEFAULT data dir (empty, no
rem microtrip DB). This script detects that mismatch, deletes the wrong
rem service, and re-creates it with the correct --datadir. Safe & idempotent.
rem
rem Data dir: D:/mysql-local/data  (DO NOT DELETE this folder)
rem Service name: MySQL84
rem ============================================================
chcp 65001 >nul 2>&1
setlocal

set "MYSQL_HOME=C:\Program Files\MySQL\MySQL Server 8.4"
set "MYSQLD=%MYSQL_HOME%\bin\mysqld.exe"
set "MYSQLADMIN=%MYSQL_HOME%\bin\mysqladmin.exe"
set "MYSQL=%MYSQL_HOME%\bin\mysql.exe"
set "SERVICE_NAME=MySQL84"
set "DATADIR=D:/mysql-local/data"

echo ============================================================
echo  MicroTrip - Register local MySQL as a Windows service
echo ============================================================

rem --- 0. sanity check: binaries exist ---
if not exist "%MYSQLD%" (
    echo ERROR: mysqld.exe not found at "%MYSQLD%"
    echo        Fix MYSQL_HOME in this script, then retry.
    pause
    exit /b 1
)

rem --- 1. inspect any existing service and its data dir ---
set "SVC_EXISTS=0"
set "DATADIR_OK=0"
sc qc %SERVICE_NAME% > "%TEMP%\mysql_svcqc.txt" 2>nul
if exist "%TEMP%\mysql_svcqc.txt" (
    findstr /I /C:"BINARY_PATH_NAME" "%TEMP%\mysql_svcqc.txt" >nul && set "SVC_EXISTS=1"
    findstr /I /C:"%DATADIR%" "%TEMP%\mysql_svcqc.txt" >nul && set "DATADIR_OK=1"
    del /q "%TEMP%\mysql_svcqc.txt" >nul 2>&1
)

if "%SVC_EXISTS%"=="1" (
    if "%DATADIR_OK%"=="1" (
        echo [info] Service %SERVICE_NAME% already exists and points to %DATADIR% (correct).
    ) else (
        echo [WARN] Service %SERVICE_NAME% exists but points to a DIFFERENT data dir.
        echo        Starting it would serve an EMPTY database (no microtrip tables).
        echo        Re-creating it with the correct data dir: %DATADIR%
        net stop %SERVICE_NAME% >nul 2>&1
        sc delete %SERVICE_NAME% >nul 2>&1
        if errorlevel 1 (
            echo ERROR: sc delete failed. Make sure you ran this as Administrator.
            pause
            exit /b 1
        )
        set "SVC_EXISTS=0"
    )
)

rem --- 2. stop any command-line MySQL instance (root, no password) ---
echo [1/3] Stopping any command-line MySQL instance (root, no password)...
"%MYSQLADMIN%" -h127.0.0.1 -P3306 -uroot --connect-timeout=3 shutdown 2>nul
echo       (harmless if nothing was running)
timeout /t 3 >nul

rem --- 3. install the Windows service if needed ---
if "%SVC_EXISTS%"=="0" (
    echo [2/3] Installing Windows service "%SERVICE_NAME%" (datadir=%DATADIR%)...
    "%MYSQLD%" --install %SERVICE_NAME% --datadir=%DATADIR% --port=3306 --character-set-server=utf8mb4
    if errorlevel 1 (
        echo ERROR: install failed. Confirm you ran this as Administrator.
        pause
        exit /b 1
    )
) else (
    echo [2/3] Service already correct - skipping install.
)

rem --- 4. start the service ---
echo [3/3] Starting service "%SERVICE_NAME%"...
net start %SERVICE_NAME%
if errorlevel 1 (
    echo ERROR: service failed to start. Inspect the error log:
    echo       %DATADIR%\mysql_local.err
    pause
    exit /b 1
)

rem --- 5. verify with the app account ---
timeout /t 3 >nul
echo.
echo [verify] Connecting with the app account (microtrip)...
"%MYSQL%" -h127.0.0.1 -P3306 -umicrotrip -pmicrotrip123 -e "SELECT 'MySQL service is UP' AS status, NOW() AS now; SHOW DATABASES LIKE 'microtrip';"
if errorlevel 1 (
    echo WARN: service is running but the app-user login / microtrip DB failed.
    echo       Check the microtrip user / password / grants, or the data dir.
) else (
    echo.
    echo SUCCESS: MySQL runs as a Windows service and auto-starts on boot,
    echo          serving the microtrip database from %DATADIR%.
)
echo.
echo Tip: manage later with  net stop %SERVICE_NAME%  /  net start %SERVICE_NAME%
pause
endlocal
