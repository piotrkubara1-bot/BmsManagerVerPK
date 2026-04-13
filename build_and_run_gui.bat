@echo off
setlocal enabledelayedexpansion

call "%~dp0load_env.bat" >nul 2>nul

set "MODE=%~1"

where mvn >nul 2>nul
if errorlevel 1 (
	echo [GUI] Maven is required but not found in PATH.
	exit /b 1
)

if not exist "%~dp0pom.xml" (
	echo [GUI] pom.xml not found. Maven mode cannot continue.
	exit /b 1
)

echo [GUI] Maven mode only.
if /I "%MODE%"=="--compile-only" (
	call mvn -q -DskipTests compile
	exit /b %ERRORLEVEL%
)

if /I "%MODE%"=="--package" (
	echo [GUI] Packaging standalone JAR...
	call mvn -q -Pgui-standalone -DskipTests clean package
	exit /b %ERRORLEVEL%
)

if /I "%MODE%"=="--run-jar" (
	if not exist "%~dp0target\bms-gui-standalone.jar" (
		echo [GUI] Standalone JAR not found. Building it first...
		call mvn -q -Pgui-standalone -DskipTests clean package
		if errorlevel 1 exit /b %ERRORLEVEL%
	)
	java -jar "%~dp0target\bms-gui-standalone.jar"
	exit /b %ERRORLEVEL%
)

call mvn -q -DskipTests javafx:run
exit /b %ERRORLEVEL%
