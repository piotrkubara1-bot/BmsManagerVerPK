@echo off
setlocal

if "%BMS_API_PORT%"=="" set "BMS_API_PORT=8090"
if "%WEB_UI_PORT%"=="" set "WEB_UI_PORT=8088"

echo [Stop] Stopping BMS-related processes on Windows...
powershell -NoProfile -Command "$targets = Get-CimInstance Win32_Process | Where-Object { $cl = $_.CommandLine; if (-not $cl) { return $false }; $name = $_.Name.ToLower(); $cll = $cl.ToLower(); ((($name -eq 'java.exe') -or ($name -eq 'javaw.exe')) -and ($cll.Contains('bmsapiserver') -or $cll.Contains('bmstestfeeder') -or $cll.Contains('maingui') -or $cll.Contains('build_and_run_gui.bat'))) -or ((($name -like 'python*.exe') -or ($name -eq 'py.exe')) -and $cll.Contains('http.server') -and $cll.Contains('%WEB_UI_PORT%')) }; foreach ($proc in $targets) { Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>nul

echo [Stop] Releasing ports %BMS_API_PORT%, %WEB_UI_PORT%, 8091 ...
powershell -NoProfile -Command "$ports = @(%BMS_API_PORT%, %WEB_UI_PORT%, 8091); foreach ($port in $ports) { $ls = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if ($ls) { $ls | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } } }" >nul 2>nul

echo [Stop] Stopping related processes in WSL (if available)...
wsl -e bash -lc "pkill -f BmsApiServer || true; pkill -f BmsTestFeeder || true; pkill -f MainGUI || true; pkill -f 'http.server' || true" >nul 2>nul

echo [Stop] Done.
exit /b 0