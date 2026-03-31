#!/usr/bin/env python3
"""Read UART lines on Raspberry Pi and forward BMS/EVENT lines to host API."""

from __future__ import annotations

import argparse
import logging
import os
import signal
import struct
import sys
import time
from collections import deque
from dataclasses import dataclass
from typing import Deque, Optional

import requests
import serial


RUNNING = True


def handle_signal(signum, _frame):
    global RUNNING
    logging.info("signal=%s received, shutting down", signum)
    RUNNING = False


@dataclass
class Config:
    host_api_url: str
    serial_port: str
    serial_baud: int
    serial_timeout: float
    default_module_id: int
    heartbeat_interval_sec: float
    post_timeout_sec: float
    retry_backoff_sec: float
    max_queue_lines: int
    force_default_module_id: bool
    log_unknown_every_n: int
    tinybms_active_poll: bool
    tinybms_poll_interval_sec: float
    tinybms_command_timeout_sec: float
    dry_run: bool


@dataclass
class TinyBmsSnapshot:
    voltage_v: float
    current_a: float
    soc_raw: int
    status_code: int
    cells_mv: list[int]


def env_or_default(name: str, default: str) -> str:
    return os.getenv(name, default)


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Forward UART BMS data to host /api/ingest")
    parser.add_argument("--host-api-url", default=env_or_default("HOST_API_URL", "http://127.0.0.1:8090/api/ingest"))
    parser.add_argument("--serial-port", default=env_or_default("SERIAL_PORT", "/dev/ttyUSB0"))
    parser.add_argument("--serial-baud", type=int, default=int(env_or_default("SERIAL_BAUD", "115200")))
    parser.add_argument("--serial-timeout", type=float, default=float(env_or_default("SERIAL_TIMEOUT", "1.0")))
    parser.add_argument("--default-module-id", type=int, default=int(env_or_default("DEFAULT_MODULE_ID", "1")))
    parser.add_argument("--heartbeat-interval-sec", type=float, default=float(env_or_default("HEARTBEAT_INTERVAL_SEC", "10")))
    parser.add_argument("--post-timeout-sec", type=float, default=float(env_or_default("POST_TIMEOUT_SEC", "3")))
    parser.add_argument("--retry-backoff-sec", type=float, default=float(env_or_default("RETRY_BACKOFF_SEC", "1.0")))
    parser.add_argument("--max-queue-lines", type=int, default=int(env_or_default("MAX_QUEUE_LINES", "2000")))
    parser.add_argument(
        "--force-default-module-id",
        action="store_true",
        default=env_bool("FORCE_DEFAULT_MODULE_ID", False),
        help="Always map incoming BMS/EVENT lines to DEFAULT_MODULE_ID (best for single-pack setups)",
    )
    parser.add_argument(
        "--log-unknown-every-n",
        type=int,
        default=int(env_or_default("LOG_UNKNOWN_EVERY_N", "20")),
        help="Log every Nth ignored UART line to help diagnose parser mismatches (0 disables)",
    )
    parser.add_argument(
        "--tinybms-active-poll",
        action="store_true",
        default=env_bool("TINYBMS_ACTIVE_POLL", True),
        help="Actively poll TinyBMS binary protocol (recommended for S516)",
    )
    parser.add_argument(
        "--tinybms-poll-interval-sec",
        type=float,
        default=float(env_or_default("TINYBMS_POLL_INTERVAL_SEC", "2.0")),
        help="Delay between TinyBMS poll cycles",
    )
    parser.add_argument(
        "--tinybms-command-timeout-sec",
        type=float,
        default=float(env_or_default("TINYBMS_COMMAND_TIMEOUT_SEC", "0.5")),
        help="Timeout for a single TinyBMS command response",
    )
    parser.add_argument("--dry-run", action="store_true", help="Read and normalize UART lines but do not post")
    return parser.parse_args()


def build_config(args: argparse.Namespace) -> Config:
    module_id = args.default_module_id
    if module_id < 1 or module_id > 4:
        raise ValueError("default module id must be 1..4")

    return Config(
        host_api_url=args.host_api_url,
        serial_port=args.serial_port,
        serial_baud=args.serial_baud,
        serial_timeout=args.serial_timeout,
        default_module_id=module_id,
        heartbeat_interval_sec=max(args.heartbeat_interval_sec, 0.0),
        post_timeout_sec=args.post_timeout_sec,
        retry_backoff_sec=args.retry_backoff_sec,
        max_queue_lines=args.max_queue_lines,
        force_default_module_id=args.force_default_module_id,
        log_unknown_every_n=max(args.log_unknown_every_n, 0),
        tinybms_active_poll=args.tinybms_active_poll,
        tinybms_poll_interval_sec=max(args.tinybms_poll_interval_sec, 0.1),
        tinybms_command_timeout_sec=max(args.tinybms_command_timeout_sec, 0.1),
        dry_run=args.dry_run,
    )


def is_valid_module(token: str) -> bool:
    if not token.isdigit():
        return False
    module_id = int(token)
    return 1 <= module_id <= 4


def normalize_line(line: str, default_module_id: int, force_default_module_id: bool) -> Optional[str]:
    raw = line.strip()
    if not raw:
        return None

    if raw.startswith("BMS,"):
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) < 5:
            return None
        if force_default_module_id:
            payload_start = 2 if len(parts) >= 6 and is_valid_module(parts[1]) else 1
            return "BMS,{module},{payload}".format(module=default_module_id, payload=",".join(parts[payload_start:]))
        if len(parts) >= 6 and is_valid_module(parts[1]):
            return ",".join(parts)
        return "BMS,{module},{payload}".format(module=default_module_id, payload=",".join(parts[1:]))

    if raw.startswith("EVENT,"):
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) < 2:
            return None
        if force_default_module_id:
            payload_start = 2 if len(parts) >= 3 and is_valid_module(parts[1]) else 1
            return "EVENT,{module},{payload}".format(module=default_module_id, payload=",".join(parts[payload_start:]))
        if len(parts) >= 3 and is_valid_module(parts[1]):
            return ",".join(parts)
        return "EVENT,{module},{payload}".format(module=default_module_id, payload=",".join(parts[1:]))

    if raw == "HEARTBEAT" or raw.startswith("HEARTBEAT,"):
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) >= 2 and parts[1] and is_valid_module(parts[1]):
            return "HEARTBEAT,{module}".format(module=parts[1])
        return "HEARTBEAT"

    return None


def open_serial(config: Config) -> serial.Serial:
    logging.info("opening serial port=%s baud=%s", config.serial_port, config.serial_baud)
    return serial.Serial(
        port=config.serial_port,
        baudrate=config.serial_baud,
        timeout=config.serial_timeout,
    )


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF


def read_exact(ser: serial.Serial, n_bytes: int, timeout_sec: float) -> Optional[bytes]:
    deadline = time.monotonic() + timeout_sec
    out = bytearray()
    while len(out) < n_bytes and RUNNING:
        chunk = ser.read(n_bytes - len(out))
        if chunk:
            out.extend(chunk)
            continue
        if time.monotonic() >= deadline:
            break
    if len(out) != n_bytes:
        return None
    return bytes(out)


def send_tinybms_cmd(ser: serial.Serial, payload: bytes) -> None:
    crc = crc16_modbus(payload)
    frame = payload + bytes((crc & 0xFF, (crc >> 8) & 0xFF))
    ser.reset_input_buffer()
    ser.write(frame)
    ser.flush()


def read_float_cmd(ser: serial.Serial, cmd: int, timeout_sec: float) -> Optional[float]:
    send_tinybms_cmd(ser, bytes((0xAA, cmd)))
    resp = read_exact(ser, 8, timeout_sec)
    if not resp or resp[0] != 0xAA or resp[1] != cmd:
        return None
    return struct.unpack("<f", resp[2:6])[0]


def read_uint16_cmd(ser: serial.Serial, cmd: int, timeout_sec: float) -> Optional[int]:
    send_tinybms_cmd(ser, bytes((0xAA, cmd)))
    resp = read_exact(ser, 6, timeout_sec)
    if not resp or resp[0] != 0xAA or resp[1] != cmd:
        return None
    return int.from_bytes(resp[2:4], byteorder="little", signed=False)


def read_uint32_cmd(ser: serial.Serial, cmd: int, timeout_sec: float) -> Optional[int]:
    send_tinybms_cmd(ser, bytes((0xAA, cmd)))
    resp = read_exact(ser, 8, timeout_sec)
    if not resp or resp[0] != 0xAA or resp[1] != cmd:
        return None
    return int.from_bytes(resp[2:6], byteorder="little", signed=False)


def read_cell_voltages(ser: serial.Serial, timeout_sec: float) -> Optional[list[int]]:
    send_tinybms_cmd(ser, bytes((0xAA, 0x1C)))
    header = read_exact(ser, 3, timeout_sec)
    if not header or header[0] != 0xAA or header[1] != 0x1C:
        return None
    payload_len = int(header[2])
    body = read_exact(ser, payload_len + 2, timeout_sec)
    if not body:
        return None
    payload = body[:payload_len]
    cell_count = payload_len // 2
    cells = []
    for i in range(cell_count):
        raw_cell = int.from_bytes(payload[i * 2 : i * 2 + 2], byteorder="little", signed=False)
        # TinyBMS may report cell voltage in deci-mV (e.g. 40280 for 4028.0 mV).
        cell_mv = raw_cell // 10 if raw_cell >= 10000 else raw_cell
        cells.append(cell_mv)
    return cells


def read_tinybms_snapshot(ser: serial.Serial, timeout_sec: float) -> Optional[TinyBmsSnapshot]:
    voltage = read_float_cmd(ser, 0x14, timeout_sec)
    if voltage is None:
        return None
    current = read_float_cmd(ser, 0x15, timeout_sec)
    if current is None:
        return None
    soc_raw = read_uint32_cmd(ser, 0x1A, timeout_sec)
    if soc_raw is None:
        return None
    status_code = read_uint16_cmd(ser, 0x18, timeout_sec)
    if status_code is None:
        return None
    cells_mv = read_cell_voltages(ser, timeout_sec)
    if cells_mv is None:
        return None
    return TinyBmsSnapshot(
        voltage_v=voltage,
        current_a=current,
        soc_raw=soc_raw,
        status_code=status_code,
        cells_mv=cells_mv,
    )


def snapshot_to_ingest_line(snapshot: TinyBmsSnapshot, module_id: int) -> str:
    cells = ",".join(str(v) for v in snapshot.cells_mv)
    if cells:
        return "BMS,{module},{voltage:.3f},{current:.3f},{soc},{status},{cells}".format(
            module=module_id,
            voltage=snapshot.voltage_v,
            current=snapshot.current_a,
            soc=snapshot.soc_raw,
            status=snapshot.status_code,
            cells=cells,
        )
    return "BMS,{module},{voltage:.3f},{current:.3f},{soc},{status}".format(
        module=module_id,
        voltage=snapshot.voltage_v,
        current=snapshot.current_a,
        soc=snapshot.soc_raw,
        status=snapshot.status_code,
    )


def post_payload(session: requests.Session, config: Config, payload: str) -> None:
    resp = session.post(
        config.host_api_url,
        data=payload.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        timeout=config.post_timeout_sec,
    )
    resp.raise_for_status()

    # /api/ingest returns 200 even when all lines are rejected; surface that in logs.
    content_type = (resp.headers.get("Content-Type") or "").lower()
    if "application/json" not in content_type:
        return
    try:
        data = resp.json()
    except ValueError:
        return
    accepted = int(data.get("accepted", 0)) if isinstance(data, dict) else 0
    heartbeat = int(data.get("heartbeat", 0)) if isinstance(data, dict) else 0
    rejected = int(data.get("rejected", 0)) if isinstance(data, dict) else 0
    if rejected > 0 and accepted == 0 and heartbeat == 0:
        logging.warning("ingest rejected payload=%s response=%s", payload, data)


def flush_queue(session: requests.Session, config: Config, queue: Deque[str]) -> bool:
    while queue and RUNNING:
        payload = queue[0]
        if config.dry_run:
            logging.info("dry-run payload=%s", payload)
            queue.popleft()
            continue
        try:
            post_payload(session, config, payload)
            queue.popleft()
        except Exception as exc:  # noqa: BLE001
            logging.warning("post failed (queued=%s): %s", len(queue), exc)
            return False
    return True


def main() -> int:
    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    log_level = env_or_default("LOG_LEVEL", "INFO").upper()
    logging.basicConfig(level=log_level, format="[%(asctime)s] %(levelname)s %(message)s")

    try:
        config = build_config(parse_args())
    except Exception as exc:  # noqa: BLE001
        logging.error("invalid config: %s", exc)
        return 2

    queue: Deque[str] = deque(maxlen=config.max_queue_lines)
    dropped_count = 0
    raw_bytes_count = 0
    raw_lines_count = 0
    normalized_lines_count = 0
    ignored_lines_count = 0
    tinybms_poll_ok = 0
    tinybms_poll_fail = 0
    last_stats_log = time.monotonic()
    text_buffer = ""

    session = requests.Session()

    while RUNNING:
        try:
            with open_serial(config) as ser:
                logging.info(
                    "sender started host_api_url=%s default_module_id=%s force_default_module_id=%s tinybms_active_poll=%s",
                    config.host_api_url,
                    config.default_module_id,
                    config.force_default_module_id,
                    config.tinybms_active_poll,
                )
                last_heartbeat_sent = time.monotonic()
                while RUNNING:
                    now = time.monotonic()
                    if now - last_stats_log >= 15.0:
                        logging.info(
                            "stats bytes=%s raw=%s normalized=%s ignored=%s poll_ok=%s poll_fail=%s queued=%s dropped=%s",
                            raw_bytes_count,
                            raw_lines_count,
                            normalized_lines_count,
                            ignored_lines_count,
                            tinybms_poll_ok,
                            tinybms_poll_fail,
                            len(queue),
                            dropped_count,
                        )
                        last_stats_log = now

                    if config.heartbeat_interval_sec > 0 and now - last_heartbeat_sent >= config.heartbeat_interval_sec:
                        if len(queue) == queue.maxlen:
                            dropped_count += 1
                        queue.append("HEARTBEAT")
                        last_heartbeat_sent = now

                    if config.tinybms_active_poll:
                        snapshot = read_tinybms_snapshot(ser, config.tinybms_command_timeout_sec)
                        if snapshot is None:
                            tinybms_poll_fail += 1
                            time.sleep(config.tinybms_poll_interval_sec)
                            continue
                        tinybms_poll_ok += 1
                        line = snapshot_to_ingest_line(snapshot, config.default_module_id)
                        normalized_lines_count += 1
                        if len(queue) == queue.maxlen:
                            dropped_count += 1
                        queue.append(line)
                        ok = flush_queue(session, config, queue)
                        if not ok:
                            time.sleep(config.retry_backoff_sec)
                        time.sleep(config.tinybms_poll_interval_sec)
                        continue

                    # Read chunks and split on both LF and CR, because UART firmware may use either.
                    raw = ser.read(ser.in_waiting or 1)
                    if not raw:
                        flush_queue(session, config, queue)
                        continue

                    raw_bytes_count += len(raw)

                    try:
                        chunk = raw.decode("utf-8", errors="ignore")
                    except Exception:
                        continue

                    if not chunk:
                        continue

                    text_buffer += chunk

                    if len(text_buffer) > 8192:
                        logging.warning("uart buffer exceeded 8192 bytes without line delimiter; dropping stale data")
                        text_buffer = ""
                        continue

                    lines_to_process = []
                    while True:
                        i_lf = text_buffer.find("\n")
                        i_cr = text_buffer.find("\r")
                        indices = [i for i in (i_lf, i_cr) if i >= 0]
                        if not indices:
                            break
                        split_idx = min(indices)
                        lines_to_process.append(text_buffer[:split_idx])
                        text_buffer = text_buffer[split_idx + 1 :]

                    for line in lines_to_process:
                        raw_lines_count += 1
                        normalized = normalize_line(line, config.default_module_id, config.force_default_module_id)
                        if not normalized:
                            ignored_lines_count += 1
                            if config.log_unknown_every_n > 0 and ignored_lines_count % config.log_unknown_every_n == 0:
                                logging.warning("ignored uart line sample=%r", line.strip())
                            continue

                        normalized_lines_count += 1

                        if len(queue) == queue.maxlen:
                            dropped_count += 1
                        queue.append(normalized)

                        ok = flush_queue(session, config, queue)
                        if not ok:
                            time.sleep(config.retry_backoff_sec)

        except serial.SerialException as exc:
            logging.error("serial error: %s", exc)
            time.sleep(config.retry_backoff_sec)
        except Exception as exc:  # noqa: BLE001
            logging.error("unexpected error: %s", exc)
            time.sleep(config.retry_backoff_sec)

    if dropped_count > 0:
        logging.warning("queue overflow dropped_lines=%s", dropped_count)

    logging.info("sender stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
