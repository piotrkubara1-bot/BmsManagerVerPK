#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

echo "[Capture] Starting full stack in capture mode (normal ingest)..."
if [[ -n "${RPI_HOST:-}" ]]; then
  echo "[Capture] Expected RPi source: ${RPI_HOST}"
  if ping -c 1 -W 1 "${RPI_HOST}" >/dev/null 2>&1; then
    echo "[Capture] RPi reachable."
  else
    echo "[Capture] Warning: RPi not reachable."
  fi
fi

exec "${SCRIPT_DIR}/run_full_stack.sh" normal "$@"
