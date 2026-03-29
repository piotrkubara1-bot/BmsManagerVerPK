#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/load_env.sh"

MODE="${1:-run}"

if ! command -v mvn >/dev/null 2>&1; then
	echo "[GUI] Maven is required but not found in PATH."
	exit 1
fi

if [[ ! -f "${SCRIPT_DIR}/pom.xml" ]]; then
	echo "[GUI] pom.xml not found. Maven mode cannot continue."
	exit 1
fi

echo "[GUI] Maven mode only."
if [[ "${MODE}" == "--compile-only" ]]; then
	mvn -q -DskipTests compile
	exit 0
fi

if [[ "${MODE}" == "--package" ]]; then
	echo "[GUI] Packaging standalone JAR..."
	mvn -q -Pgui-standalone -DskipTests clean package
	exit 0
fi

if [[ "${MODE}" == "--run-jar" ]]; then
	if [[ ! -f "${SCRIPT_DIR}/target/bms-gui-standalone.jar" ]]; then
		echo "[GUI] Standalone JAR not found. Building it first..."
		mvn -q -Pgui-standalone -DskipTests clean package
	fi
	java -jar "${SCRIPT_DIR}/target/bms-gui-standalone.jar"
	exit 0
fi

mvn -q -DskipTests javafx:run
