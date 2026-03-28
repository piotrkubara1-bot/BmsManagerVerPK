@echo off
setlocal

set "MODE=%~1"
if "%MODE%"=="" set "MODE=normal"
set "NO_GUI=%~2"
if /I "%MODE%"=="--no-gui" (
    set "NO_GUI=--no-gui"
    set "MODE=normal"
)

if /I "%MODE%"=="stop" (
    call stop_all.bat
    exit /b %ERRORLEVEL%
)

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
if "%GUI_API_BASE%"=="" set "GUI_API_BASE=http://127.0.0.1:%BMS_API_PORT%"
set "GUI_INPUT_MODE=api"

echo [FullStack] Starting service mode: %MODE%
start "BMS-Service" /B cmd /c "set BMS_API_PORT=%BMS_API_PORT%&& call run_service.bat %MODE%"

timeout /t 2 /nobreak >nul

echo [FullStack] Starting Web UI on port %WEB_UI_PORT%
start "BMS-WebUI" /B cmd /c "set WEB_UI_PORT=%WEB_UI_PORT%&& call run_web_ui.bat"

if /I "%NO_GUI%"=="--no-gui" (
    echo [FullStack] --no-gui enabled, skipping JavaFX GUI launch.
    echo [FullStack] Service: http://127.0.0.1:%BMS_API_PORT%/api/health
    echo [FullStack] Web UI : http://127.0.0.1:%WEB_UI_PORT%/dashboard.html
    exit /b 0
)

echo [FullStack] Starting JavaFX GUI...
call build_and_run_gui.bat
