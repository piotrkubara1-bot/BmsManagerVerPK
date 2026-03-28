# RPI Side Setup

This folder contains the Raspberry Pi side sender that reads UART telemetry and forwards it to the host Java service endpoint:

- Host endpoint: `POST http://<HOST_IP>:8090/api/ingest`
- Payload: plain text `BMS,...` or `EVENT,...` lines

## Files

- `bms_uart_sender.py` - UART reader + HTTP forwarder
- `requirements.txt` - Python dependencies
- `rpi_sender.env.example` - sender environment template
- `install_rpi.sh` - one-time setup on Raspberry Pi
- `run_sender.sh` - run sender with env file
- `systemd/bms-uart-sender.service` - service unit template

## 1. Copy Folder To Raspberry Pi

Example:

```bash
scp -r RPI pi@<RPI_IP>:/home/pi/bms-rpi
```

On Raspberry Pi:

```bash
cd /home/pi/bms-rpi
chmod +x install_rpi.sh run_sender.sh
./install_rpi.sh
```

## 2. Configure Sender

Edit config:

```bash
cp -n rpi_sender.env.example rpi_sender.env
nano rpi_sender.env
```

Set at least:

- `HOST_API_URL` (PC host IP + port 8090)
- `SERIAL_PORT` (for example `/dev/ttyUSB0`)
- `SERIAL_BAUD`
- `DEFAULT_MODULE_ID` (used when module id is missing in incoming lines)

## 3. Run Manually

```bash
./run_sender.sh
```

Dry run (no HTTP posting):

```bash
./run_sender.sh --dry-run
```

## 4. Install As Systemd Service

Recommended target path:

```bash
sudo mkdir -p /opt/bms-rpi
sudo cp -r ./* /opt/bms-rpi/
sudo chown -R pi:pi /opt/bms-rpi
```

Install unit:

```bash
sudo cp systemd/bms-uart-sender.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable bms-uart-sender
sudo systemctl start bms-uart-sender
```

Check status/logs:

```bash
systemctl status bms-uart-sender
journalctl -u bms-uart-sender -f
```

## 5. Ingest Line Compatibility

The sender forwards only lines starting with:

- `BMS,`
- `EVENT,`

If module id is missing, sender injects `DEFAULT_MODULE_ID` as the second field.

Examples accepted by host:

- `BMS,2,15.5,0.2,85000000,155,4100,4098,4097`
- `EVENT,2,49,WARN,Cell imbalance detected`

## 6. Quick End-to-End Check

On PC host, ensure service is running:

```powershell
Set-Location "e:\projekt io"
.\run_service.bat normal
```

Then on Raspberry Pi run sender and verify on PC:

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/health" -Method Get
Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/latest" -Method Get
```
