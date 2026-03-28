#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -f ".venv/bin/python" ]]; then
  echo "[RPI] Missing .venv. Run ./install_rpi.sh first."
  exit 1
fi

if [[ -f "rpi_sender.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "rpi_sender.env"
  set +a
fi

exec .venv/bin/python bms_uart_sender.py "$@"
