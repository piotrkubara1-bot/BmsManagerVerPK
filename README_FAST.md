# README FAST

Najkrótsza wersja dla Windows.

## 1. Wejdź do folderu

```powershell
C:\Users\Piotrek\IdeaProjects\BmsManager
```

## 2. Skopiuj `.env`

```powershell
Copy-Item ".env.example" ".env"
```

## 3. Jedna komenda do odpalenia serwera

```powershell
.\run_server_stack.bat
```

To robi:

- MySQL
- schema bazy
- backend
- Web UI

## 4. Otwórz dashboard

```text
http://127.0.0.1:8088/dashboard.html
```

## 5. Jeśli nie masz BMS

W Web UI:

1. wejdź w `Cell Settings`
2. wpisz:

```text
SIMULATED
```

3. kliknij `Save COM Port`
4. kliknij `Start UART`

## 6. Jeśli masz prawdziwy BMS

W Web UI:

1. wpisz np. `COM3` albo `COM5`
2. kliknij `Save COM Port`
3. kliknij `Start UART`

## 7. Sprawdź health

```powershell
curl.exe http://127.0.0.1:8090/api/health
```

Ma być:

```json
"status":"ok"
"dbConnected":true
```

## 8. Stop

```powershell
.\run_server_stack.bat stop
```

## 9. Aplikacja mobilna

Osobny projekt jest tutaj:

[mobile-viewer-android](C:/Users/Piotrek/IdeaProjects/BmsManager/mobile-viewer-android)

Otwórz ten folder w Android Studio osobno.

Apka mobilna jest w Java.

Telefon musi być w tym samym Wi-Fi co komputer.

Na PC sprawdź IP:

```powershell
ipconfig
```

W telefonie wpisz adres backendu, np.:

```text
http://192.168.1.100:8090
```

Nie wpisuj `127.0.0.1`.

## 10. Pełna instrukcja

[README.md](C:/Users/Piotrek/IdeaProjects/BmsManager/README.md)
