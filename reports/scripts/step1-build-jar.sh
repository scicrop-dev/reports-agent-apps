#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PLUGIN_ROOT/build"
mkdir -p "$BUILD_DIR"
echo "[step1] Building reports jar..."
cd "$PLUGIN_ROOT"
mvn -q -DskipTests clean package
JAR_PATH="$(ls "$PLUGIN_ROOT/target"/*.jar | grep -v 'original-' | head -n 1)"
if [[ -z "$JAR_PATH" ]]; then
  echo "[step1] ERROR: JAR not found in target/" >&2; exit 1
fi
cp "$JAR_PATH" "$BUILD_DIR/plugin.jar"
echo "[step1] Jar ready at $BUILD_DIR/plugin.jar"
