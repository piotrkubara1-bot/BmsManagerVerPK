#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

echo "[RPI] Installing required system packages..."
sudo apt-get update
sudo apt-get install -y python3 python3-venv python3-pip

echo "[RPI] Creating virtual environment..."
python3 -m venv .venv

# shellcheck disable=SC1091
source .venv/bin/activate

echo "[RPI] Installing Python dependencies..."
pip install --upgrade pip
pip install -r requirements.txt

if [[ ! -f "rpi_sender.env" ]]; then
  cp rpi_sender.env.example rpi_sender.env
  echo "[RPI] Created rpi_sender.env from template. Edit HOST_API_URL and SERIAL_PORT."
fi

echo "[RPI] Install complete."
