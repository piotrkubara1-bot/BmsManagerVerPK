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
- `TINYBMS_ACTIVE_POLL` (`true` for TinyBMS S516 binary protocol polling)
- `TINYBMS_POLL_INTERVAL_SEC` (delay between full read cycles)
- `TINYBMS_COMMAND_TIMEOUT_SEC` (timeout for each TinyBMS command)
- `DEFAULT_MODULE_ID` (used when module id is missing in incoming lines)
- `FORCE_DEFAULT_MODULE_ID` (`true` maps all incoming BMS/EVENT lines to `DEFAULT_MODULE_ID`; useful for single-pack capture)
- `LOG_UNKNOWN_EVERY_N` (logs every Nth ignored UART line to help diagnose unexpected serial format; set `0` to disable)
- `HEARTBEAT_INTERVAL_SEC` (seconds between keep-alive `HEARTBEAT` lines; set `0` to disable)

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

For TinyBMS S516, sender now supports active polling mode (`TINYBMS_ACTIVE_POLL=true`) and emits normalized
`BMS,module,voltage,current,soc,status,cells...` lines directly to host ingest.

When active poll is enabled, raw UART text framing is not required.

The sender forwards only lines starting with:

- `BMS,`
- `EVENT,`
- `HEARTBEAT` (generated periodically by sender for source liveness)

If module id is missing, sender injects `DEFAULT_MODULE_ID` as the second field.

If your UART source is single-pack and module id detection is ambiguous, set:

- `FORCE_DEFAULT_MODULE_ID=true`

This prevents lines from being interpreted as module `2/3/4` and rejected by host-side `BMS_ALLOWED_MODULES`.

The sender also logs a warning when `/api/ingest` reports `rejected > 0` (even with HTTP 200), so malformed lines are visible in logs.

Examples accepted by host:

- `BMS,2,15.5,0.2,85000000,155,4100,4098,4097`
- `EVENT,2,49,WARN,Cell imbalance detected`
- `HEARTBEAT`

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
