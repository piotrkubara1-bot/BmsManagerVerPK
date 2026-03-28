# BMS Host Stack (Serwis + GUI + Web UI)

Ten projekt uruchamia hostowy stos BMS składający się z:

- serwisu API w Javie (`BmsApiServer`)
- interfejsu JavaFX (`MainGUI`)
- osobnego procesu Web UI (statyczny dashboard)
- opcjonalnego symulatora telemetrii (`BmsTestFeeder`)

## 1. Wymagania

### Host Windows

- Java JDK 21+ w PATH (`java`, `javac`)
- działający MySQL (aktualnie może być XAMPP)
- opcjonalnie: JavaFX SDK dla GUI

### Host Linux/WSL

- środowisko Java Runtime (`java`)
- opcjonalnie JDK (`javac`) do świeżej kompilacji
- opcjonalnie: JavaFX SDK dla GUI
- Python 3 do procesu Web UI

## 2. Konfiguracja bazy danych

Uruchom jednorazowo (Windows + MySQL z XAMPP):

```powershell
Set-Location "e:\projekt io"
$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -e $sql
```

Adnotacja: XAMPP MySQL jest traktowany jako rozwiązanie tymczasowe w tym repozytorium i zostanie zastąpiony standardową instalacją MySQL.

## 3. Szablon konfiguracji

Użyj dołączonego szablonu konfiguracji środowiska:

```powershell
Set-Location "e:\projekt io"
Copy-Item ".env.example" ".env" -Force
```

Kluczowe zmienne:

- `BMS_API_PORT`, `WEB_UI_PORT`
- `BMS_SIM_COUNT`, `BMS_SIM_INTERVAL_MS`
- `BMS_STARTUP_RETRIES`, `BMS_STARTUP_DELAY_SEC`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`

## 4. Tryby serwisu

### Windows

```powershell
Set-Location "e:\projekt io"
.\run_service.bat normal
.\run_service.bat single
.\run_service.bat simulate
.\run_service.bat simulate4
```

### Linux/WSL

```bash
cd "/mnt/e/projekt io"
./run_service.sh normal
./run_service.sh single
./run_service.sh simulate
./run_service.sh simulate4
```

### Opcjonalne strojenie symulacji

```powershell
$env:BMS_SIM_COUNT="30"
$env:BMS_SIM_INTERVAL_MS="200"
.\run_service.bat simulate4
```

```bash
BMS_SIM_COUNT=30 BMS_SIM_INTERVAL_MS=200 ./run_service.sh simulate4
```

## 5. Uruchomienie pełnego stosu

### Windows (zalecane w tym repozytorium)

```powershell
Set-Location "e:\projekt io"
.\run_full_stack.bat normal --no-gui
```

Zatrzymanie wszystkiego:

```powershell
.\run_full_stack.bat stop
```

- API: `http://127.0.0.1:8090/api/health`
- Web UI: `http://127.0.0.1:8088/dashboard.html`

Aby uruchomić z GUI JavaFX, usuń `--no-gui` i upewnij się, że ustawiono `JAVAFX_PATH`.

### Linux/WSL

```bash
cd "/mnt/e/projekt io"
./run_full_stack.sh normal --no-gui
```

Zatrzymanie wszystkiego:

```bash
./run_full_stack.sh stop
```

## 6. Co działa gdzie (RPi vs PC)

### Raspberry Pi (strona urządzenia)

- komunikacja UART z rzeczywistymi modułami BMS
- proces zbierania/przekazywania danych wysyłający telemetrię do hosta PC
- opcjonalne lokalne buforowanie przy problemach sieciowych

### PC lub host (Windows/Linux)

- serwis Java API (`BmsApiServer`) i endpointy ingest
- magazyn danych MySQL
- statyczny serwer Web UI (dashboard)
- desktopowe GUI JavaFX
- symulacja (`BmsTestFeeder`) gdy nie ma podłączonego urządzenia

Pakiet konfiguracji nadawcy po stronie RPi jest w katalogu `RPI/`.
Instrukcja instalacji i uruchomienia znajduje się w `RPI/README.md`.

## 7. JavaFX GUI

Sprawdzenie samej kompilacji na Windows:

```powershell
Set-Location "e:\projekt io"
.\build_and_run_gui.bat --compile-only
```

Jeżeli JavaFX nie jest dostępny, ustaw:

```powershell
set JAVAFX_PATH=C:\javafx-sdk-21\lib
```

Skrypt GUI na Linux:

```bash
export JAVAFX_PATH=/path/to/javafx/lib
./build_and_run_gui.sh --compile-only
```

## 8. Szybka weryfikacja API

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/health" -Method Get
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/latest" -Method Get
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/statistics" -Method Get
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/cell-settings" -Method Get
```

Aktualizacja zapisywalnego ustawienia:

```powershell
$body='moduleId=1&key=early_balancing_threshold_v&value=3.82'
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/cell-settings" -Method Post -ContentType "application/x-www-form-urlencoded" -Body $body
```

## 9. Uwagi

- tryb `single` wymusza tylko moduł 1 (`BMS_ALLOWED_MODULES=1`)
- tryb `simulate` wysyła jeden moduł, a `simulate4` wszystkie moduły
- WSL może uruchamiać serwis bez `javac`, jeśli w `bin` są gotowe klasy
- bezpośredni write-through komend TinyBMS (na poziomie urządzenia) pozostaje kolejnym etapem mapowania protokołu
- aktualna konfiguracja z XAMPP MySQL zostanie zastąpiona docelowo standardowym MySQL
