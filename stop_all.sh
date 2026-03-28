#!/usr/bin/env bash
set -euo pipefail

BMS_API_PORT="${BMS_API_PORT:-8090}"
WEB_UI_PORT="${WEB_UI_PORT:-8088}"

echo "[Stop] Stopping BMS-related processes on Linux/WSL..."
pkill -f BmsApiServer || true
pkill -f BmsTestFeeder || true
pkill -f MainGUI || true
pkill -f "http.server" || true

echo "[Stop] Releasing ports ${BMS_API_PORT}, ${WEB_UI_PORT}, 8091 ..."
if command -v fuser >/dev/null 2>&1; then
  fuser -k "${BMS_API_PORT}"/tcp >/dev/null 2>&1 || true
  fuser -k "${WEB_UI_PORT}"/tcp >/dev/null 2>&1 || true
  fuser -k 8091/tcp >/dev/null 2>&1 || true
elif command -v lsof >/dev/null 2>&1; then
  lsof -ti tcp:"${BMS_API_PORT}" | xargs -r kill -9 || true
  lsof -ti tcp:"${WEB_UI_PORT}" | xargs -r kill -9 || true
  lsof -ti tcp:8091 | xargs -r kill -9 || true
fi

echo "[Stop] Done."