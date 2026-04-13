@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

echo [Stop] Stopping listeners on ports %BMS_API_PORT% and %WEB_UI_PORT% ...
"%POWERSHELL_EXE%" -NoProfile -Command "$ports = @(%BMS_API_PORT%, %WEB_UI_PORT%); $listeners = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort }; if ($listeners) { $listeners | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }"

echo [Stop] Done.
exit /b 0
