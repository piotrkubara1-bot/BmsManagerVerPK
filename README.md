# BMS Host Stack (Serwis + GUI + Web UI)

Ten projekt uruchamia hostowy stos BMS składający się z:

- serwisu API w Javie (`BmsApiServer`)
- interfejsu JavaFX (`MainGUI`) uruchamianego jako osobna aplikacja
- osobnego procesu Web UI (statyczny dashboard)
- opcjonalnego symulatora telemetrii (`BmsTestFeeder`)

## 1. Wymagania

### Host Windows

- Java JDK 20+ w PATH (`java`, `javac`) (takie mialem zainstalowane i z ta wersja javy pracowalem)
- działający MySQL (aktualnie może być XAMPP) w przyszlosci jakis maly serwer sql
- Maven w PATH (`mvn`) dla GUI

### Host Linux/WSL

- środowisko Java Runtime (`java`)
- opcjonalnie JDK (`javac`) do świeżej kompilacji
- Maven (`mvn`) dla GUI
- Python 3 do procesu Web UI (mozliwe ze zostanie zmienione na inny serwer web)

## 2. Konfiguracja bazy danych

Uruchom jednorazowo (Windows + MySQL z XAMPP):

$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -e $sql


Adnotacja: XAMPP MySQL jest traktowany jako rozwiązanie tymczasowe w tym repozytorium i zostanie zastąpiony standardową instalacją MySQL.

## 3. Szablon konfiguracji

Użyj dołączonego szablonu konfiguracji środowiska:

Copy-Item ".env.example" ".env" 


Skrypty uruchomieniowe automatycznie wczytują `.env`, jeśli plik istnieje.

Kluczowe zmienne:

- `BMS_API_PORT`, `WEB_UI_PORT`
- `BMS_SIM_COUNT`, `BMS_SIM_INTERVAL_MS`
- `BMS_STARTUP_RETRIES`, `BMS_STARTUP_DELAY_SEC`
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `BMS_DB_URL`, `BMS_DB_USER`, `BMS_DB_PASS` (opcjonalny override)
- `BMS_DB_RETENTION_DAYS`, `BMS_DB_RETENTION_INTERVAL_MIN`
- `RPI_HOST`
- `BMS_RPI_OFFLINE_SECONDS`

Domyślna retencja bazy to 7 dni (`BMS_DB_RETENTION_DAYS=7`).
To dobre ustawienie startowe: ogranicza rozrost bazy i zwykle pokrywa potrzeby diagnostyczne.

Endpointy `history` i `statistics` działają teraz na danych z bazy (z fallbackiem do pamięci, gdy DB jest niedostępne).

## 4. Tryby serwisu

### Windows


.\run_service.bat normal
.\run_service.bat single
.\run_service.bat simulate
.\run_service.bat simulate4


### Linux/WSL

./run_service.sh normal
./run_service.sh single
./run_service.sh simulate
./run_service.sh simulate4


### Opcjonalne strojenie symulacji

Windows:
$env:BMS_SIM_COUNT="30"
$env:BMS_SIM_INTERVAL_MS="200"
.\run_service.bat simulate4

Linux:
BMS_SIM_COUNT=30 BMS_SIM_INTERVAL_MS=200 ./run_service.sh simulate4


## 5. Uruchomienie pełnego stosu

### Windows 


.\run_full_stack.bat normal


Zatrzymanie wszystkiego:

.\run_full_stack.bat stop


- API: `http://127.0.0.1:8090/api/health`
- Web UI: `http://127.0.0.1:8088/dashboard.html`

`run_full_stack` uruchamia tylko API + Web UI. GUI JavaFX uruchamiasz osobno.

### Linux/WSL


./run_full_stack.sh normal


Zatrzymanie wszystkiego:


./run_full_stack.sh stop


### Szybki start trybów (simulate/capture)

Windows:
.\start_simulate.bat
.\start_capture.bat


Linux/WSL:

./start_simulate.sh
./start_capture.sh


`start_capture` uruchamia tryb normalnego przechwytywania danych z ingest.
Jeśli ustawisz `RPI_HOST` w `.env`, skrypt zrobi szybki check dostępności RPi.

Dashboard zawiera dodatkową zakładkę `RPi Status`, która pokazuje źródła ingest i świeżość danych modułów.
W zakładce `Statistics` można wybrać moduł, ustawić liczbę próbek i zobaczyć wykresy: napięcia, prądu, SOC, statusu oraz napięć ogniw.

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
Nadawca RPi wysyła też okresowy `HEARTBEAT` (konfigurowany przez `HEARTBEAT_INTERVAL_SEC`), co utrzymuje status źródła jako online przy braku ramek telemetrycznych.

## 7. JavaFX GUI

Sprawdzenie samej kompilacji na Windows:


.\build_and_run_gui.bat --compile-only


Uruchomienie GUI (dev):


.\build_and_run_gui.bat


Zbudowanie jednego pliku JAR z GUI (standalone):

.\build_and_run_gui.bat --package


Powstaje plik:

- `target/bms-gui-standalone.jar`

## 8. Uwagi

- tryb `single` wymusza tylko moduł 1 (`BMS_ALLOWED_MODULES=1`)
- tryb `simulate` wysyła jeden moduł, a `simulate4` wszystkie moduły
- WSL może uruchamiać serwis bez `javac`, jeśli w `bin` są gotowe klasy
- bezpośredni write-through komend TinyBMS (na poziomie urządzenia) pozostaje kolejnym etapem mapowania protokołu
- aktualna konfiguracja z XAMPP MySQL zostanie zastąpiona docelowo standardowym MySQL

## 9. Uwagi cz.2
Readme.MD zostalo wygenerowane przez AI na podstawie mojego opisu programu ze wzgledu na brak czytelnosci oryginalnego opisu.

