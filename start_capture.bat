@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

echo [Capture] Starting full stack in capture mode (normal ingest)...
if not "%RPI_HOST%"=="" (
    echo [Capture] Expected RPi source: %RPI_HOST%
    powershell -NoProfile -Command "try { if (Test-Connection -ComputerName '%RPI_HOST%' -Count 1 -Quiet) { Write-Output '[Capture] RPi reachable.' } else { Write-Output '[Capture] Warning: RPi not reachable.' } } catch { Write-Output '[Capture] Warning: RPi check failed.' }"
)

call "%~dp0run_full_stack.bat" normal %*
