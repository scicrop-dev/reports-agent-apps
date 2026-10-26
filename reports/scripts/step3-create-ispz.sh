#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ID="reports"
BUILD_DIR="$PLUGIN_ROOT/build"
STAGING_ROOT="$BUILD_DIR/staging"
OUTPUT_DIR="$PLUGIN_ROOT/out"
MANIFEST_PATH="$STAGING_ROOT/$PLUGIN_ID/plugin-manifest.json"

if [[ ! -d "$STAGING_ROOT/$PLUGIN_ID" ]]; then
  echo "[step3] Missing staging — run step2 first" >&2; exit 1
fi

VERSION="$(grep '"version"' "$MANIFEST_PATH" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
if [[ -z "$VERSION" ]]; then
  echo "[step3] Cannot read version from manifest" >&2; exit 1
fi

mkdir -p "$OUTPUT_DIR"
ISPZ="$OUTPUT_DIR/$PLUGIN_ID-$VERSION.ispz"
rm -f "$ISPZ"

(cd "$STAGING_ROOT" && zip -r "$OUTPUT_DIR/$PLUGIN_ID-$VERSION.zip" "$PLUGIN_ID" >/dev/null)
mv "$OUTPUT_DIR/$PLUGIN_ID-$VERSION.zip" "$ISPZ"
echo "[step3] Package ready: $ISPZ"
