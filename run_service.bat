@echo off
setlocal

set "MODE=%~1"
if "%MODE%"=="" set "MODE=normal"

if not exist lib mkdir lib

echo [Service] Compiling BmsApiServer...
if not exist bin mkdir bin
javac -d bin src\main\java\BmsApiServer.java src\main\java\BmsTestFeeder.java
if errorlevel 1 (
	echo [Service] Compilation failed.
	exit /b 1
)

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%BMS_SIM_COUNT%"=="" set "BMS_SIM_COUNT=120"
if "%BMS_SIM_INTERVAL_MS%"=="" set "BMS_SIM_INTERVAL_MS=1000"
if "%BMS_STOP_EXISTING%"=="" set "BMS_STOP_EXISTING=1"
if "%BMS_STARTUP_RETRIES%"=="" set "BMS_STARTUP_RETRIES=15"
if "%BMS_STARTUP_DELAY_SEC%"=="" set "BMS_STARTUP_DELAY_SEC=1"

if "%BMS_STOP_EXISTING%"=="1" (
	powershell -NoProfile -Command "$listeners = Get-NetTCPConnection -LocalPort %BMS_API_PORT% -State Listen -ErrorAction SilentlyContinue; if ($listeners) { $listeners | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }" >nul 2>nul
)

if /I "%MODE%"=="normal" goto :mode_normal
if /I "%MODE%"=="single" goto :mode_single
if /I "%MODE%"=="simulate" goto :mode_simulate
if /I "%MODE%"=="simulate4" goto :mode_simulate4

echo [Service] Unknown mode "%MODE%".
echo Usage: run_service.bat [normal^|single^|simulate^|simulate4]
exit /b 1

:mode_normal
set "BMS_ALLOWED_MODULES="
echo [Service] Mode: normal - modules 1..4
goto :start_api

:mode_single
set "BMS_ALLOWED_MODULES=1"
echo [Service] Mode: single - module 1 only
goto :start_api

:mode_simulate
set "BMS_ALLOWED_MODULES=1"
echo [Service] Mode: simulate - single module
goto :start_api

:mode_simulate4
set "BMS_ALLOWED_MODULES="
echo [Service] Mode: simulate4 - modules 1..4

:start_api

echo [Service] Starting BmsApiServer on port %BMS_API_PORT% ...
start "BmsApiServer" /B cmd /c java -cp "bin;lib/*" BmsApiServer

set /a __attempt=0
:wait_api
powershell -NoProfile -Command "try { Invoke-RestMethod -Uri 'http://127.0.0.1:%BMS_API_PORT%/api/health' -Method Get | Out-Null; exit 0 } catch { exit 1 }" >nul 2>nul
if not errorlevel 1 goto :api_ok
set /a __attempt+=1
if %__attempt% GEQ %BMS_STARTUP_RETRIES% goto :api_fail
timeout /t %BMS_STARTUP_DELAY_SEC% /nobreak >nul
goto :wait_api

:api_fail
echo [Service] API failed to start on port %BMS_API_PORT% after %BMS_STARTUP_RETRIES% attempts.
exit /b 1

:api_ok

if /I "%MODE%"=="simulate" goto :run_sim_single
if /I "%MODE%"=="simulate4" goto :run_sim_multi

echo [Service] API is running in background.
echo [Service] To stop it: .\stop_all.bat
exit /b 0

:run_sim_single
echo [Service] Starting simulator feed - single module ...
java -cp "bin;lib/*" BmsTestFeeder --mode=single --module=1 --count=%BMS_SIM_COUNT% --interval-ms=%BMS_SIM_INTERVAL_MS% --endpoint=http://127.0.0.1:%BMS_API_PORT%/api/ingest
exit /b %ERRORLEVEL%

:run_sim_multi
echo [Service] Starting simulator feed - 4 modules ...
java -cp "bin;lib/*" BmsTestFeeder --mode=multi --count=%BMS_SIM_COUNT% --interval-ms=%BMS_SIM_INTERVAL_MS% --endpoint=http://127.0.0.1:%BMS_API_PORT%/api/ingest
exit /b %ERRORLEVEL%
