# README FAST

Szybka instrukcja uruchomienia na Windows.

## 1. Otwórz folder projektu

Przejdź do:

```powershell
C:\Users\Piotrek\IdeaProjects\BmsManager
```

## 2. Skopiuj konfigurację

```powershell
Copy-Item ".env.example" ".env"
```

Jeśli plik `.env` już istnieje, pomiń ten krok.

## 3. Ustaw port COM

Otwórz plik [.env](C:/Users/Piotrek/IdeaProjects/BmsManager/.env) i ustaw:

```env
SERIAL_PORT=COM5
```

Zmień `COM5` na swój prawdziwy port, jeśli trzeba.

## 4. Włącz MySQL w XAMPP

Uruchom:

```powershell
& "C:\xampp\xampp-control.exe"
```

W XAMPP kliknij `Start` przy `MySQL`.

## 5. Utwórz bazę danych

```powershell
$sql = Get-Content -Raw "bms_schema.sql"
& "C:\xampp\mysql\bin\mysql.exe" -u root -e $sql
```

## 6. Uruchom backend i Web UI

```powershell
.\stop_all.bat
.\run_full_stack.bat normal
```

## 7. Sprawdź backend

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Ma być:

```json
"status":"ok"
"dbConnected":true
```

## 8. Otwórz dashboard

W przeglądarce otwórz:

```text
http://127.0.0.1:8088/dashboard.html
```

## 9. Uruchom UART sender

Jeśli znasz port:

```powershell
.\run_uart_sender.bat COM5
```

albo:

```powershell
.\run_uart_sender.bat COM3
```

## 10. Jeśli coś nie działa

### Backend nie działa

Uruchom jeszcze raz:

```powershell
.\stop_all.bat
.\run_full_stack.bat normal
```

### `dbConnected:false`

To znaczy, że MySQL nie działa albo baza nie została utworzona.

### `Failed to open port COMx`

To znaczy:

- zły numer COM
- port zajęty
- urządzenie niepodłączone

## Pełna instrukcja

Jeśli chcesz dokładniejszą wersję, zobacz:
[README.md](C:/Users/Piotrek/IdeaProjects/BmsManager/README.md)
