#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

BIN_DIR="bin"
LIB_DIR="lib"
MODE="${1:-normal}"
SIM_COUNT="${BMS_SIM_COUNT:-120}"
SIM_INTERVAL_MS="${BMS_SIM_INTERVAL_MS:-1000}"
STOP_EXISTING="${BMS_STOP_EXISTING:-1}"
STARTUP_RETRIES="${BMS_STARTUP_RETRIES:-15}"
STARTUP_DELAY_SEC="${BMS_STARTUP_DELAY_SEC:-1}"
JAVAC_CMD=""
JAVA_CMD=""
CP_SEP=":"

case "${MODE}" in
	normal|single|simulate|simulate4)
		;;
	*)
		echo "[Service] Unknown mode '${MODE}'."
		echo "Usage: ./run_service.sh [normal|single|simulate|simulate4]"
		exit 1
		;;
esac

mkdir -p "${LIB_DIR}"

if command -v java >/dev/null 2>&1; then
  JAVA_CMD="java"
else
  echo "[Service] Missing Java runtime (java)."
  echo "[Service] Install with: sudo apt update && sudo apt install -y openjdk-21-jdk"
  exit 1
fi

if command -v javac >/dev/null 2>&1; then
	JAVAC_CMD="javac"
else
	JAVAC_CMD=""
fi

CP_VALUE="${BIN_DIR}${CP_SEP}${LIB_DIR}/*"

mkdir -p "${BIN_DIR}"
if [[ -n "${JAVAC_CMD}" ]]; then
	echo "[Service] Compiling BmsApiServer..."
	"${JAVAC_CMD}" -d "${BIN_DIR}" src/main/java/BmsApiServer.java src/main/java/BmsTestFeeder.java
else
	if [[ ! -f "${BIN_DIR}/BmsApiServer.class" || ! -f "${BIN_DIR}/BmsTestFeeder.class" ]]; then
		echo "[Service] Missing javac and no compiled classes in ${BIN_DIR}."
		echo "[Service] Install JDK: sudo apt update && sudo apt install -y openjdk-21-jdk"
		exit 1
	fi
	echo "[Service] javac not found - using existing compiled classes from ${BIN_DIR}."
fi

if [[ "${STOP_EXISTING}" == "1" ]]; then
	if command -v fuser >/dev/null 2>&1; then
		fuser -k "${BMS_API_PORT:-8090}"/tcp >/dev/null 2>&1 || true
	elif command -v lsof >/dev/null 2>&1; then
		lsof -ti tcp:"${BMS_API_PORT:-8090}" | xargs -r kill -9 || true
	fi
fi

if [[ "${MODE}" == "single" || "${MODE}" == "simulate" ]]; then
	export BMS_ALLOWED_MODULES="1"
	echo "[Service] Mode: ${MODE} (module 1 only)"
else
	echo "[Service] Mode: ${MODE} (modules 1..4)"
fi

echo "[Service] Starting BmsApiServer on port ${BMS_API_PORT:-8090} ..."
nohup "${JAVA_CMD}" -cp "${CP_VALUE}" BmsApiServer >/tmp/bms_api.log 2>&1 &
API_PID=$!

if command -v curl >/dev/null 2>&1; then
	api_started=0
	for ((attempt=1; attempt<=STARTUP_RETRIES; attempt++)); do
		if curl -sf "http://127.0.0.1:${BMS_API_PORT:-8090}/api/health" >/dev/null; then
			api_started=1
			break
		fi
		sleep "${STARTUP_DELAY_SEC}"
	done
	if [[ "${api_started}" -ne 1 ]]; then
		echo "[Service] API failed to start on port ${BMS_API_PORT:-8090} after ${STARTUP_RETRIES} attempts."
		echo "[Service] Check logs: /tmp/bms_api.log"
		exit 1
	fi
fi

if [[ "${MODE}" == "simulate" ]]; then
	echo "[Service] Starting simulator feed (single module)..."
	"${JAVA_CMD}" -cp "${CP_VALUE}" BmsTestFeeder --mode=single --module=1 --count="${SIM_COUNT}" --interval-ms="${SIM_INTERVAL_MS}" --endpoint="http://127.0.0.1:${BMS_API_PORT:-8090}/api/ingest"
	echo "[Service] API continues in background (pid ${API_PID})."
	exit 0
fi

if [[ "${MODE}" == "simulate4" ]]; then
	echo "[Service] Starting simulator feed (4 modules)..."
	"${JAVA_CMD}" -cp "${CP_VALUE}" BmsTestFeeder --mode=multi --count="${SIM_COUNT}" --interval-ms="${SIM_INTERVAL_MS}" --endpoint="http://127.0.0.1:${BMS_API_PORT:-8090}/api/ingest"
	echo "[Service] API continues in background (pid ${API_PID})."
	exit 0
fi

echo "[Service] API is running in background (pid ${API_PID})."
echo "[Service] To stop it: pkill -f BmsApiServer"
