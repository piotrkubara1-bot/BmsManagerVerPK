#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-run}"
JAVAFX_PATH="${JAVAFX_PATH:-/usr/share/openjfx/lib}"
MODULES="${MODULES:-javafx.controls,javafx.fxml}"
BIN_DIR="bin"
SRC_JAVA="src/main/java"
SRC_RES="src/main/resources"

if [[ ! -f "${JAVAFX_PATH}/javafx.controls.jar" ]]; then
	echo "[GUI] JavaFX SDK not found at ${JAVAFX_PATH}."
	echo "[GUI] Export JAVAFX_PATH to your javafx lib directory and retry."
	exit 1
fi

echo "[GUI] Compiling Java sources..."
mkdir -p "${BIN_DIR}"
find "${SRC_JAVA}" -name "*.java" > .java_sources.tmp
javac --module-path "${JAVAFX_PATH}" --add-modules "${MODULES}" -d "${BIN_DIR}" @.java_sources.tmp
rm -f .java_sources.tmp

echo "[GUI] Copying resources..."
cp -R "${SRC_RES}"/. "${BIN_DIR}"/

if [[ "${MODE}" == "--compile-only" ]]; then
	echo "[GUI] Compile-only mode complete."
	exit 0
fi

echo "[GUI] Starting MainGUI..."
java --module-path "${JAVAFX_PATH}" --add-modules "${MODULES}" -cp "${BIN_DIR}" MainGUI
