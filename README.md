# BmsManager

Instrukcja dla Windows, krok po kroku.

Najważniejsze:

- cały serwerowy stack uruchamiasz jedną komendą:
  `.\run_server_stack.bat`
- to stawia:
  - MySQL z XAMPP
  - schemat bazy z `bms_schema.sql`
  - backend API
  - Web UI
- aplikację mobilną uruchamiasz osobno z folderu:
  [mobile-viewer-android](C:/Users/Piotrek/IdeaProjects/BmsManager/mobile-viewer-android)
- aplikację desktopową JavaFX uruchamiasz osobno, tylko jeśli chcesz

## Co działa bez podłączonego BMS

Tak, możesz uruchomić:

- MySQL
- backend
- Web UI
- bazę danych
- dashboard

nawet wtedy, gdy fizyczny TinyBMS nie jest podłączony.

W takim przypadku po prostu:

- nie będzie prawdziwej telemetrii,
- albo możesz użyć trybu `SIMULATED`.

## Wymagania

Musisz mieć:

- Java JDK 20+
- XAMPP w `C:\xampp`
- PowerShell

Przydatne pliki:

- konfiguracja: [.env](C:/Users/Piotrek/IdeaProjects/BmsManager/.env)
- szybka instrukcja: [README_FAST.md](C:/Users/Piotrek/IdeaProjects/BmsManager/README_FAST.md)
- mobilny viewer: [mobile-viewer-android](C:/Users/Piotrek/IdeaProjects/BmsManager/mobile-viewer-android)

## Gdzie uruchamiać komendy

W tym folderze:

```powershell
C:\Users\Piotrek\IdeaProjects\BmsManager
```

W PowerShell pliki `.bat` uruchamiaj z `.\`

Przykład:

```powershell
.\run_server_stack.bat
```

## Pierwsze przygotowanie

### 1. Skopiuj konfigurację

```powershell
Copy-Item ".env.example" ".env"
```

Jeśli `.env` już istnieje, pomiń.

### 2. Sprawdź `.env`

Otwórz [.env](C:/Users/Piotrek/IdeaProjects/BmsManager/.env) i zobacz przynajmniej te pola:

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

Jeśli nie masz podłączonego BMS i chcesz symulację, ustaw:

```env
SERIAL_PORT=SIMULATED
```

## Jedna komenda do uruchomienia serwera

Najprostsza komenda:

```powershell
.\run_server_stack.bat
```

Ten skrypt:

1. uruchamia MySQL z XAMPP,
2. czeka aż MySQL zacznie słuchać,
3. importuje `bms_schema.sql`,
4. uruchamia backend,
5. uruchamia Web UI.

### Zatrzymanie

```powershell
.\run_server_stack.bat stop
```

albo:

```powershell
.\stop_all.bat
```

## Adresy po uruchomieniu

- health:
  [http://127.0.0.1:8090/api/health](http://127.0.0.1:8090/api/health)
- dashboard:
  [http://127.0.0.1:8088/dashboard.html](http://127.0.0.1:8088/dashboard.html)

## Jak sprawdzić, czy wszystko działa

Uruchom:

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Ma być coś w tym stylu:

```json
{"status":"ok","dbConnected":true,...}
```

Najważniejsze:

- `"status":"ok"`
- `"dbConnected":true`

## Web UI

Otwórz:

[http://127.0.0.1:8088/dashboard.html](http://127.0.0.1:8088/dashboard.html)

W zakładce `Cell Settings` masz teraz:

- zapis portu COM do `.env`
- opcję `SIMULATED`
- przycisk `Start UART`
- przycisk `Stop UART`
- logi procesu UART

### Dopuszczalne wartości portu

- `COM3`
- `COM5`
- inny prawdziwy port typu `COM7`
- `SIMULATED`

## Tryb SIMULATED

Jeśli chcesz testować bez podłączonego BMS:

1. wejdź do Web UI
2. w `Saved COM Port` wpisz:

```text
SIMULATED
```

3. kliknij `Save COM Port`
4. kliknij `Start UART`

Wtedy sender nie otwiera prawdziwego portu COM, tylko generuje testową telemetrię i wysyła ją do backendu.

To jest najprostszy tryb do sprawdzania dashboardu i API bez sprzętu.

## Ręczne uruchomienie UART sendera

Jeśli chcesz go odpalić z terminala:

```powershell
.\run_uart_sender.bat
```

albo z konkretnym portem:

```powershell
.\run_uart_sender.bat COM3
```

albo:

```powershell
.\run_uart_sender.bat SIMULATED
```

## Aplikacja mobilna Android

Dodałem osobny projekt Android Studio:

[mobile-viewer-android](C:/Users/Piotrek/IdeaProjects/BmsManager/mobile-viewer-android)

To jest osobna aplikacja do telefonu, tylko do podglądu danych.

Ona:

- łączy się z backendem przez HTTP,
- pokazuje `health`,
- pokazuje najnowsze dane z `/api/latest`,
- nie steruje UART,
- nie zapisuje ustawień TinyBMS.

### Jak ją otworzyć

1. Otwórz Android Studio.
2. Wybierz `Open`.
3. Wskaż folder:

[mobile-viewer-android](C:/Users/Piotrek/IdeaProjects/BmsManager/mobile-viewer-android)

4. Poczekaj na sync Gradle.
5. Uruchom apkę na emulatorze albo telefonie.

### Ważne

Apka mobilna jest teraz napisana w Java, nie w Kotlinie.

W telefonie wpisujesz adres backendu, np.:

```text
http://192.168.1.100:8090
```

Nie `127.0.0.1`, bo telefon i komputer to nie to samo urządzenie.

### Jak połączyć telefon z PC

Telefon i komputer muszą być w tej samej sieci lokalnej, najczęściej w tym samym Wi-Fi.

Na komputerze sprawdź adres IP:

```powershell
ipconfig
```

Szukaj adresu IPv4, np.:

```text
192.168.1.100
```

Potem:

1. uruchom serwer na PC:

```powershell
.\run_server_stack.bat
```

2. sprawdź lokalnie na PC:

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

3. wpisz w telefonie adres:

```text
http://TWOJ_IP_Z_PC:8090
```

na przykład:

```text
http://192.168.1.100:8090
```

### Jeśli telefon nie łączy się z PC

Sprawdź:

- czy telefon i PC są w tym samym Wi-Fi
- czy backend działa
- czy firewall Windows nie blokuje portu `8090`

Jeśli trzeba, dodaj regułę firewalla dla portu `8090`.

Szybki test z innego urządzenia w sieci:

```text
http://TWOJ_IP_Z_PC:8090/api/health
```

Jeśli to nie działa poza PC, to problem jest sieciowy, nie w aplikacji Android.

## Desktop GUI

Desktopową apkę JavaFX uruchamiasz osobno:

```powershell
.\build_and_run_gui.bat
```

Nie jest potrzebna do działania backendu i Web UI.

## Najczęstsze problemy

### `dbConnected:false`

To znaczy:

- MySQL nie wstał,
- dane bazy w `.env` są złe,
- backend wystartował zanim baza była gotowa.

Najprościej:

```powershell
.\stop_all.bat
.\run_server_stack.bat
```

### `Failed to open port COMx`

To znaczy:

- zły port COM,
- port zajęty przez inny program,
- urządzenie niepodłączone.

Jeśli nie masz sprzętu, użyj:

```text
SIMULATED
```

### Web UI działa, ale nie ma danych

To znaczy zwykle:

- UART sender nie działa,
- nie kliknąłeś `Start UART`,
- port jest zły,
- albo nie włączyłeś `SIMULATED`.

### PowerShell nie widzi `.bat`

Używaj:

```powershell
.\run_server_stack.bat
```

nie:

```powershell
run_server_stack.bat
```

## Najważniejsze pliki

- start wszystkiego: [run_server_stack.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/run_server_stack.bat)
- zwykły backend + Web UI: [run_full_stack.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/run_full_stack.bat)
- UART sender: [run_uart_sender.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/run_uart_sender.bat)
- zatrzymanie: [stop_all.bat](C:/Users/Piotrek/IdeaProjects/BmsManager/stop_all.bat)
- backend: [BmsApiServer.java](C:/Users/Piotrek/IdeaProjects/BmsManager/src/main/java/BmsApiServer.java)
- sender UART: [BmsUartSender.java](C:/Users/Piotrek/IdeaProjects/BmsManager/src/main/java/BmsUartSender.java)
