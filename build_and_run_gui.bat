@echo off
setlocal enabledelayedexpansion

set "MODE=%~1"

if "%JAVAFX_PATH%"=="" set "JAVAFX_PATH=C:\javafx-sdk-21\lib"
if "%MODULES%"=="" set "MODULES=javafx.controls,javafx.fxml"

if not exist "%JAVAFX_PATH%\javafx.controls.jar" (
	echo [GUI] JavaFX SDK not found at "%JAVAFX_PATH%".
	echo [GUI] Set JAVAFX_PATH to your javafx lib folder, e.g.:
	echo [GUI]   set JAVAFX_PATH=C:\javafx-sdk-21\lib
	exit /b 1
)

echo [GUI] Compiling Java sources...
if not exist bin mkdir bin

> .java_sources.txt (
	for /r "src\main\java" %%F in (*.java) do (
		set "p=%%F"
		set "p=!p:%CD%\=!"
		echo !p!
	)
)

javac --module-path "%JAVAFX_PATH%" --add-modules %MODULES% -d bin @.java_sources.txt
if errorlevel 1 (
	echo [GUI] Compilation failed.
	del .java_sources.txt >nul 2>nul
	exit /b 1
)

del .java_sources.txt >nul 2>nul

xcopy /E /Y /I "src\main\resources\*" "bin\" >nul

if /I "%MODE%"=="--compile-only" (
	echo [GUI] Compile-only mode complete.
	exit /b 0
)

echo [GUI] Starting MainGUI...
java --module-path "%JAVAFX_PATH%" --add-modules %MODULES% -cp bin MainGUI
