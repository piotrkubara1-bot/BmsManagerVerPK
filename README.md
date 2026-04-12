# BMS Host Stack (Serwis + GUI + Web UI)

Ten projekt uruchamia hostowy stos BMS składający się z:

- serwisu API w Javie (`BmsApiServer`)
- nadawcy UART (`BmsUartSender`) - odczytuje dane z TinyBMS i wysyła do API
- interfejsu JavaFX (`MainGUI`) uruchamianego jako osobna aplikacja
- osobnego procesu Web UI (statyczny dashboard)
- opcjonalnego symulatora telemetrii (`BmsTestFeeder`)

## 1. Wymagania

### Host Windows

- Java JDK 20+ w PATH (`java`, `javac`)
- działający MySQL (np. XAMPP lub standardowa instalacja)
- Maven w PATH (`mvn`) dla GUI

### Host Linux/WSL

- środowisko Java Runtime (`java`)
- opcjonalnie JDK (`javac`) do świeżej kompilacji
- Maven (`mvn`) dla GUI
- Python 3 do procesu Web UI

## 2. Konfiguracja bazy danych

Uruchom jednorazowo (Windows + MySQL z XAMPP):

```powershell
$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -e $sql
```

Adnotacja: XAMPP MySQL jest traktowany jako rozwiązanie tymczasowe i może zostać zastąpiony standardową instalacją MySQL.

## 3. Szablon konfiguracji

Użyj dołączonego szablonu konfiguracji środowiska:

```powershell
Copy-Item ".env.example" ".env"
```

Skrypty uruchomieniowe automatycznie wczytują `.env`, jeśli plik istnieje.

Kluczowe zmienne:

- `BMS_API_PORT`, `WEB_UI_PORT`
- `SERIAL_PORT` (domyślnie COM3 dla `BmsUartSender`)
- `BMS_API_INGEST_URL` (url do ingest dla `BmsUartSender`)
- `BMS_SIM_COUNT`, `BMS_SIM_INTERVAL_MS`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `BMS_DB_RETENTION_DAYS` (domyślnie 7 dni)

## 4. Tryby serwisu i nadawcy

### Serwis API (Backend)

Windows:
```powershell
.\run_service.bat normal
.\run_service.bat single
.\run_service.bat simulate
.\run_service.bat simulate4
```

### Nadawca UART (Odczyt z BMS)

Jeśli masz podłączony TinyBMS przez UART:
```powershell
.\run_uart_sender.bat
```
Skrypt automatycznie pobierze bibliotekę `jSerialComm`, skompiluje i uruchomi nadawcę.

## 5. Uruchomienie pełnego stosu

### Windows 

```powershell
.\run_full_stack.bat normal
```

Zatrzymanie wszystkiego:
```powershell
.\run_full_stack.bat stop
```

- API: `http://127.0.0.1:8090/api/health`
- Web UI: `http://127.0.0.1:8088/dashboard.html`

`run_full_stack` uruchamia API + Web UI. GUI JavaFX oraz `BmsUartSender` uruchamiasz osobno.

## 6. Architektura (Co działa gdzie)

### Strona urządzenia (np. RPi lub PC z adapterem UART)

- `BmsUartSender`: Komunikacja UART z TinyBMS (protokół binarny), formatowanie do tekstu i wysyłka `POST` do API.
- Wysyła też okresowy `HEARTBEAT`, aby system wiedział, że połączenie z BMS jest aktywne nawet przy braku zmian danych.

### Serwer / Host (PC)

- `BmsApiServer`: Odbiera dane (ingest), zapisuje do MySQL, serwuje dane przez REST API.
- Magazyn danych MySQL (tabela `bms_history`, `bms_events`).
- Statyczny serwer Web UI (dashboard.html + JS).
- Desktopowe GUI JavaFX (`MainGUI`).
- Symulacja (`BmsTestFeeder`) używana zamiast rzeczywistego nadawcy UART.

Dashboard zawiera zakładki:
- `Live`: Dane w czasie rzeczywistym.
- `RPi Status`: Stan źródeł danych (ingest) i świeżość modułów.
- `Statistics`: Wykresy historyczne z bazy danych.

## 7. JavaFX GUI

Kompilacja i uruchomienie:
```powershell
.\build_and_run_gui.bat
```

Budowanie paczki JAR:
```powershell
.\build_and_run_gui.bat --package
```

## 8. Uwagi

- Tryb `single` wymusza tylko moduł 1.
- Tryb `simulate` / `simulate4` uruchamia `BmsTestFeeder`.
- Retencja danych jest automatyczna (czyści stare rekordy z bazy).
- `Main.java` w tym projekcie jest uproszczonym entrypointem uruchamiającym `BmsUartSender`.

