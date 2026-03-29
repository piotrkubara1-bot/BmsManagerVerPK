#!/usr/bin/env python3
"""Read UART lines on Raspberry Pi and forward BMS/EVENT lines to host API."""

from __future__ import annotations

import argparse
import logging
import os
import signal
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
    dry_run: bool


def env_or_default(name: str, default: str) -> str:
    return os.getenv(name, default)


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
        dry_run=args.dry_run,
    )


def is_valid_module(token: str) -> bool:
    if not token.isdigit():
        return False
    module_id = int(token)
    return 1 <= module_id <= 4


def normalize_line(line: str, default_module_id: int) -> Optional[str]:
    raw = line.strip()
    if not raw:
        return None

    if raw.startswith("BMS,"):
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) < 5:
            return None
        if len(parts) >= 6 and is_valid_module(parts[1]):
            return ",".join(parts)
        return "BMS,{module},{payload}".format(module=default_module_id, payload=",".join(parts[1:]))

    if raw.startswith("EVENT,"):
        parts = [p.strip() for p in raw.split(",")]
        if len(parts) < 2:
            return None
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


def post_payload(session: requests.Session, config: Config, payload: str) -> None:
    resp = session.post(
        config.host_api_url,
        data=payload.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        timeout=config.post_timeout_sec,
    )
    resp.raise_for_status()


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

    session = requests.Session()

    while RUNNING:
        try:
            with open_serial(config) as ser:
                logging.info("sender started host_api_url=%s", config.host_api_url)
                last_heartbeat_sent = time.monotonic()
                while RUNNING:
                    now = time.monotonic()
                    if config.heartbeat_interval_sec > 0 and now - last_heartbeat_sent >= config.heartbeat_interval_sec:
                        if len(queue) == queue.maxlen:
                            dropped_count += 1
                        queue.append("HEARTBEAT")
                        last_heartbeat_sent = now

                    raw = ser.readline()
                    if not raw:
                        flush_queue(session, config, queue)
                        continue

                    try:
                        line = raw.decode("utf-8", errors="ignore")
                    except Exception:
                        continue

                    normalized = normalize_line(line, config.default_module_id)
                    if not normalized:
                        continue

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
