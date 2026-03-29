#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

WEB_UI_PORT="${WEB_UI_PORT:-8088}"
WEB_ROOT="src/main/resources"

echo "[WebUI] Serving ${WEB_ROOT} on http://127.0.0.1:${WEB_UI_PORT} ..."

if command -v python3 >/dev/null 2>&1; then
  python3 -m http.server "${WEB_UI_PORT}" --directory "${WEB_ROOT}"
  exit 0
fi

if command -v python >/dev/null 2>&1; then
  python -m http.server "${WEB_UI_PORT}" --directory "${WEB_ROOT}"
  exit 0
fi

echo "[WebUI] Python 3 not found. Install python3 and retry."
exit 1
