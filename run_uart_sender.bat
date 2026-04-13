@echo off
setlocal

call "%~dp0load_env.bat" >nul 2>nul

if not exist lib mkdir lib
if not exist bin mkdir bin

if not exist "lib\jSerialComm-2.11.0.jar" (
    echo [BMS-UART] Pobieranie jSerialComm...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fazecast/jSerialComm/2.11.0/jSerialComm-2.11.0.jar' -OutFile 'lib\jSerialComm-2.11.0.jar'"
)

echo [BMS-UART] Kompilacja BmsUartSender...
javac -d bin -cp "bin;lib/*" src\main\java\BmsUartSender.java
if errorlevel 1 (
    echo [BMS-UART] Kompilacja nieudana.
    exit /b 1
)

echo [BMS-UART] Uruchamianie...
echo Konfiguracja: PORT=%SERIAL_PORT% (default: COM3), URL=%BMS_API_INGEST_URL%
java -cp "bin;lib/*" BmsUartSender
pause
