#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

MODE="${1:-normal}"
if [[ "${MODE}" == "--no-gui" ]]; then
  MODE="normal"
fi

if [[ "${MODE}" == "stop" ]]; then
  chmod +x ./stop_all.sh
  ./stop_all.sh
  exit 0
fi

BMS_API_PORT="${BMS_API_PORT:-8090}"
WEB_UI_PORT="${WEB_UI_PORT:-8088}"
export BMS_API_PORT

echo "[FullStack] Starting service mode: ${MODE}"
nohup ./run_service.sh "${MODE}" >/tmp/bms_service_fullstack.log 2>&1 &

sleep 2

echo "[FullStack] Starting Web UI on port ${WEB_UI_PORT}"
nohup env WEB_UI_PORT="${WEB_UI_PORT}" ./run_web_ui.sh >/tmp/bms_webui_fullstack.log 2>&1 &

echo "[FullStack] GUI is not started here. Run ./build_and_run_gui.sh separately."
echo "[FullStack] Service: http://127.0.0.1:${BMS_API_PORT}/api/health"
echo "[FullStack] Web UI : http://127.0.0.1:${WEB_UI_PORT}/dashboard.html"
exit 0
