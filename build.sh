#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./build.sh [debug|release] [options]

Builds the app and sideloads it to every connected adb device.

Steps:
  1. Build the requested variant (debug by default). By default this first
     runs the same gate as CI: ./gradlew check (full lint + unit tests).
  2. Install the resulting APK on every connected device.

Options:
  debug            Build/install the debug variant (default)
  release          Build/install the release variant
  --install-only   Skip the build; install the last built APK as-is
  --no-checks      Build without the check gate (lint + unit tests)
  -h, --help       Show this help and exit
EOF
}

INSTALL_ONLY=false
CHECKS=true
BUILD_TYPE="debug"

for arg in "$@"; do
    case "$arg" in
        --install-only) INSTALL_ONLY=true ;;
        --no-checks) CHECKS=false ;;
        debug|release) BUILD_TYPE="$arg" ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Error: unrecognized argument: $arg (run with --help)"; exit 1 ;;
    esac
done

if [ "$INSTALL_ONLY" = false ]; then
    if [ "$CHECKS" = true ]; then
        echo "[build] Running CI gate (check) + assembling vanilla-${BUILD_TYPE}..."
        ./gradlew check "assembleVanilla${BUILD_TYPE^}"
    else
        echo "[build] Assembling vanilla-${BUILD_TYPE} (checks skipped)..."
        ./gradlew "assembleVanilla${BUILD_TYPE^}"
    fi
else
    echo "[build] Skipped (--install-only)"
fi

APK=$(ls app/build/outputs/apk/vanilla/${BUILD_TYPE}/*.apk)
echo "[apk] Found: $APK"

DEVICES=$(adb devices | grep -w device | awk '{print $1}')
if [ -z "$DEVICES" ]; then
    echo "[install] No connected adb devices, nothing to install"
else
    for dev in $DEVICES; do
        echo "[install] Installing on $dev..."
        adb -s "$dev" install -t "$APK"
    done
fi

echo "[done] Installed: $APK"
