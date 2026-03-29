#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

echo "[Simulate] Starting full stack in simulate4 mode..."
exec "${SCRIPT_DIR}/run_full_stack.sh" simulate4 "$@"
