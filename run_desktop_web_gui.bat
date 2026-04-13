@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "JAVAC_EXE="
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVAC_EXE for /f "delims=" %%I in ('where javac 2^>nul') do if not defined JAVAC_EXE set "JAVAC_EXE=%%~fI"
if not defined JAVA_EXE if defined JAVAC_EXE set "JAVA_EXE=%JAVAC_EXE:javac.exe=java.exe%"
if not defined JAVA_EXE for /f "delims=" %%I in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~fI"
if not defined JAVAC_EXE (
    echo [DesktopGUI] javac not found.
    exit /b 1
)
if not defined JAVA_EXE (
    echo [DesktopGUI] java not found.
    exit /b 1
)

if "%DESKTOP_WEB_GUI_URL%"=="" (
    if "%WEB_UI_PORT%"=="" (
        set "DESKTOP_WEB_GUI_URL=http://127.0.0.1:8088/dashboard.html"
    ) else (
        set "DESKTOP_WEB_GUI_URL=http://127.0.0.1:%WEB_UI_PORT%/dashboard.html"
    )
)

set "FX_VERSION=20.0.2"
set "FX_DIR=%~dp0lib\javafx\%FX_VERSION%"

if not exist "%FX_DIR%" mkdir "%FX_DIR%"
if not exist "%~dp0bin" mkdir "%~dp0bin"

echo [DesktopGUI] Ensuring JavaFX Web modules...
"%POWERSHELL_EXE%" -NoProfile -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$version = '%FX_VERSION%';" ^
    "$fxDir = [System.IO.Path]::GetFullPath('%FX_DIR%');" ^
    "$base = 'https://repo1.maven.org/maven2/org/openjfx';" ^
    "$artifacts = @(" ^
        "'javafx-base'," ^
        "'javafx-controls'," ^
        "'javafx-graphics'," ^
        "'javafx-media'," ^
        "'javafx-web'" ^
    ");" ^
    "foreach ($artifact in $artifacts) {" ^
        "$plain = Join-Path $fxDir ($artifact + '-' + $version + '.jar');" ^
        "$win = Join-Path $fxDir ($artifact + '-' + $version + '-win.jar');" ^
        "if (!(Test-Path $plain)) { Invoke-WebRequest -Uri ($base + '/' + $artifact + '/' + $version + '/' + $artifact + '-' + $version + '.jar') -OutFile $plain }" ^
        "if (!(Test-Path $win)) { Invoke-WebRequest -Uri ($base + '/' + $artifact + '/' + $version + '/' + $artifact + '-' + $version + '-win.jar') -OutFile $win }" ^
    "}"
if errorlevel 1 (
    echo [DesktopGUI] Failed to download JavaFX runtime files.
    exit /b 1
)

echo [DesktopGUI] Compiling DesktopWebGuiApp...
"%JAVAC_EXE%" --module-path "%FX_DIR%" --add-modules javafx.controls,javafx.web,javafx.media -d "%~dp0bin" "%~dp0src\main\java\DesktopWebGuiApp.java"
if errorlevel 1 (
    echo [DesktopGUI] Compilation failed.
    exit /b 1
)

echo [DesktopGUI] Starting JavaFX desktop viewer...
echo [DesktopGUI] Target URL: %DESKTOP_WEB_GUI_URL%
"%JAVA_EXE%" --module-path "%FX_DIR%" --add-modules javafx.controls,javafx.web,javafx.media -cp "%~dp0bin" DesktopWebGuiApp
exit /b %ERRORLEVEL%
