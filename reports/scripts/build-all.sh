#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/step1-build-jar.sh"
"$SCRIPT_DIR/step2-stage-package.sh"
"$SCRIPT_DIR/step3-create-ispz.sh"
echo "[build-all] Done."
