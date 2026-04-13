# BmsManager

Prosta instrukcja uruchomienia projektu na Windows.

Ten README jest napisany specjalnie krok po kroku, bez zgadywania.
Jeżeli robisz to pierwszy raz, wykonuj punkty dokładnie po kolei.

## Co to jest

Projekt składa się z 4 części:

- backend API w Javie
- Web UI w przeglądarce
- GUI JavaFX
- UART sender, który czyta dane z TinyBMS i wysyła je do backendu

Najważniejsze:

- `run_full_stack.bat` uruchamia backend + Web UI
- `run_uart_sender.bat` uruchamia odczyt z portu COM
- GUI uruchamia się osobno

## Czego potrzebujesz

Na Windows musisz mieć:

- Java JDK 20 lub nowsze
- MySQL, najlepiej z XAMPP
- PowerShell

Dobrze jest też mieć:

- podłączony TinyBMS
- numer portu COM, pod którym go widzi Windows, np. `COM3` albo `COM5`

## Gdzie uruchamiać komendy

Wszystkie komendy z tego README uruchamiaj w tym folderze:

```powershell
C:\Users\Piotrek\IdeaProjects\BmsManager
```

Jeżeli jesteś w PowerShell, uruchamiaj pliki `.bat` tak:

```powershell
.\nazwa_pliku.bat
```

Nie tak:

```powershell
nazwa_pliku.bat
```

Bo PowerShell wtedy często nie znajdzie pliku z bieżącego katalogu.

## Pierwsze uruchomienie - zrób to raz

### 1. Skopiuj plik konfiguracyjny

```powershell
Copy-Item ".env.example" ".env"
```

Jeśli plik `.env` już istnieje, ten krok pomiń.

### 2. Ustaw podstawową konfigurację

Otwórz plik [.env](C:/Users/Piotrek/IdeaProjects/BmsManager/.env) i sprawdź te linie:

```env
BMS_API_PORT=8090
WEB_UI_PORT=8088
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=bms
DB_USER=root
DB_PASSWORD=
SERIAL_PORT=COM5
SERIAL_BAUD=115200
BMS_API_INGEST_URL=http://127.0.0.1:8090/api/ingest
```

Najważniejsze rzeczy:

- `DB_PASSWORD=` zostaw puste, jeśli MySQL root nie ma hasła
- `SERIAL_PORT=` ustaw na swój prawdziwy port COM

### 3. Uruchom MySQL

Najprościej przez XAMPP:

```powershell
& "C:\xampp\xampp-control.exe"
```

W panelu XAMPP kliknij `Start` przy `MySQL`.

### 4. Utwórz bazę danych

Uruchom:

```powershell
$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -e $sql
```

Jeśli root ma hasło:

```powershell
$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -p -e $sql
```

### 5. Sprawdź, czy baza istnieje

```powershell
& "C:\xampp\mysql\bin\mysql.exe" -u root -e "SHOW DATABASES LIKE 'bms';"
```

Jeśli wszystko jest OK, zobaczysz `bms`.

## Normalne uruchamianie projektu

### 1. Zatrzymaj stare procesy

```powershell
.\stop_all.bat
```

### 2. Uruchom backend i Web UI

```powershell
.\run_full_stack.bat normal
```

Po chwili powinieneś mieć:

- backend: `http://127.0.0.1:8090/api/health`
- dashboard: `http://127.0.0.1:8088/dashboard.html`

### 3. Sprawdź, czy backend działa

W PowerShell najlepiej użyj:

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Albo:

```powershell
Invoke-WebRequest http://127.0.0.1:8090/api/health -UseBasicParsing
```

Prawidłowy wynik powinien zawierać:

```json
"status":"ok"
"dbConnected":true
```

Jeśli `dbConnected` jest `false`, to znaczy, że MySQL nie działa albo dane w `.env` są złe.

### 4. Otwórz dashboard

W przeglądarce otwórz:

```text
http://127.0.0.1:8088/dashboard.html
```

## Uruchomienie UART sendera

UART sender czyta dane z TinyBMS z portu COM i wysyła je do backendu.

### Najprostsza wersja

Jeśli masz poprawny `SERIAL_PORT` w `.env`:

```powershell
.\run_uart_sender.bat
```

### Uruchomienie z podaniem portu przy starcie

Jeśli chcesz wskazać port ręcznie:

```powershell
.\run_uart_sender.bat COM3
```

albo:

```powershell
.\run_uart_sender.bat COM5
```

albo:

```powershell
.\run_uart_sender.bat --port=COM5
```

### Co oznaczają komunikaty

Jeśli zobaczysz:

```text
[BmsUartSender] Port opened successfully
```

to znaczy, że port COM został otwarty poprawnie.

Jeśli zobaczysz:

```text
[BmsUartSender] Failed to open port COM5
```

to zwykle znaczy jedno z tych:

- zły numer portu
- port jest zajęty przez inny program
- urządzenie nie jest poprawnie podłączone

Wtedy:

- sprawdź numer portu w Menedżerze urządzeń
- zamknij inne programy używające COM
- spróbuj jeszcze raz z innym portem, np. `COM3` lub `COM5`

## GUI JavaFX

GUI uruchomisz osobno:

```powershell
.\build_and_run_gui.bat
```

## Najczęstsze problemy

### PowerShell nie znajduje pliku `.bat`

Jeśli widzisz coś w stylu:

```text
is not recognized
```

to uruchamiaj tak:

```powershell
.\run_full_stack.bat normal
```

zamiast:

```powershell
run_full_stack.bat normal
```

### `dbConnected:false`

To znaczy:

- MySQL nie działa
- baza `bms` nie została utworzona
- login/hasło w `.env` są złe

### `Connection refused`

To znaczy, że backend nie działa na `8090`.

Sprawdź:

```powershell
.\stop_all.bat
.\run_full_stack.bat normal
curl.exe http://127.0.0.1:8090/api/health
```

### `Failed to open port COMx`

To problem z portem szeregowym, nie z bazą i nie z Web UI.

Sprawdź:

- czy kabel jest podłączony
- jaki jest prawdziwy numer COM
- czy inny program nie trzyma portu

### Ostrzeżenie o `restricted method` i `jSerialComm`

Takie warningi:

```text
WARNING: java.lang.System::loadLibrary ...
```

nie oznaczają jeszcze awarii programu.
To ostrzeżenie z nowszej Javy. Sam program może mimo tego działać normalnie.

## Kolejność uruchamiania, jeśli chcesz mieć wszystko

1. Uruchom MySQL w XAMPP.
2. Uruchom `.\stop_all.bat`.
3. Uruchom `.\run_full_stack.bat normal`.
4. Sprawdź `http://127.0.0.1:8090/api/health`.
5. Otwórz `http://127.0.0.1:8088/dashboard.html`.
6. Uruchom `.\run_uart_sender.bat COM3` albo inny właściwy port.
7. Sprawdź, czy dane pojawiają się w dashboardzie.

## Przydatne pliki

- konfiguracja: [.env](C:/Users/Piotrek/IdeaProjects/BmsManager/.env)
- schemat bazy: [bms_schema.sql](C:/Users/Piotrek/IdeaProjects/BmsManager/bms_schema.sql)
- start całego stacka: [run_full_stack.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/run_full_stack.bat)
- start UART: [run_uart_sender.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/run_uart_sender.bat)
- zatrzymanie procesów: [stop_all.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/stop_all.bat)
