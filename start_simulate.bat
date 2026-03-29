@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

echo [Simulate] Starting full stack in simulate4 mode...
call "%~dp0run_full_stack.bat" simulate4 %*
