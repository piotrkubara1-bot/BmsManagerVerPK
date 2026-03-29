@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
set "WEB_ROOT=src\main\resources"

echo [WebUI] Serving %WEB_ROOT% on http://127.0.0.1:%WEB_UI_PORT% ...

where python >nul 2>nul
if %ERRORLEVEL%==0 (
    python -m http.server %WEB_UI_PORT% --directory "%WEB_ROOT%"
    exit /b %ERRORLEVEL%
)

where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 -m http.server %WEB_UI_PORT% --directory "%WEB_ROOT%"
    exit /b %ERRORLEVEL%
)

echo [WebUI] Python not found. Install Python 3 and retry.
exit /b 1
