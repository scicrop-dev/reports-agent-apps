#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ID="reports"
BUILD_DIR="$PLUGIN_ROOT/build"
STAGING_DIR="$BUILD_DIR/staging/$PLUGIN_ID"
LAYOUT_ROOT="$PLUGIN_ROOT/package-layout/$PLUGIN_ID"

if [[ ! -f "$BUILD_DIR/plugin.jar" ]]; then
  echo "[step2] Missing build/plugin.jar — run step1 first" >&2; exit 1
fi

# Sync templates from src (single source of truth)
cp "$PLUGIN_ROOT/src/main/resources/templates/isp-index.html"  "$LAYOUT_ROOT/web/templates/"
cp "$PLUGIN_ROOT/src/main/resources/templates/isp-status.html" "$LAYOUT_ROOT/web/templates/"

rm -rf "$BUILD_DIR/staging"
mkdir -p "$BUILD_DIR/staging"
cp -R "$LAYOUT_ROOT" "$STAGING_DIR"
cp "$BUILD_DIR/plugin.jar" "$STAGING_DIR/app/plugin.jar"

echo "[step2] Refreshing checksums.sha256..."
(
  cd "$STAGING_DIR"
  sha256sum \
    app/plugin.jar \
    plugin-manifest.json \
    app/launch.properties \
    config/plugin-permissions.json \
    web/templates/isp-index.html \
    web/templates/isp-status.html \
    > checksums.sha256
)
echo "[step2] Staged at $STAGING_DIR"
