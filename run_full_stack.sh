#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-normal}"
NO_GUI="${2:-}"
if [[ "${MODE}" == "--no-gui" ]]; then
  NO_GUI="--no-gui"
  MODE="normal"
fi

if [[ "${MODE}" == "stop" ]]; then
  chmod +x ./stop_all.sh
  ./stop_all.sh
  exit 0
fi

BMS_API_PORT="${BMS_API_PORT:-8090}"
WEB_UI_PORT="${WEB_UI_PORT:-8088}"
GUI_API_BASE="${GUI_API_BASE:-http://127.0.0.1:${BMS_API_PORT}}"
export BMS_API_PORT GUI_API_BASE
export GUI_INPUT_MODE="api"

echo "[FullStack] Starting service mode: ${MODE}"
nohup ./run_service.sh "${MODE}" >/tmp/bms_service_fullstack.log 2>&1 &

sleep 2

echo "[FullStack] Starting Web UI on port ${WEB_UI_PORT}"
nohup WEB_UI_PORT="${WEB_UI_PORT}" ./run_web_ui.sh >/tmp/bms_webui_fullstack.log 2>&1 &

if [[ "${NO_GUI}" == "--no-gui" ]]; then
  echo "[FullStack] --no-gui enabled, skipping JavaFX GUI launch."
  echo "[FullStack] Service: http://127.0.0.1:${BMS_API_PORT}/api/health"
  echo "[FullStack] Web UI : http://127.0.0.1:${WEB_UI_PORT}/dashboard.html"
  exit 0
fi

echo "[FullStack] Starting JavaFX GUI..."
./build_and_run_gui.sh
