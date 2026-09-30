#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BT=${ANDROID_BUILD_TOOLS:-/Users/tcfdonmez/Android/Sdk/build-tools/35.0.0}
KEYS=${MG4_PLATFORM_KEYS_DIR:?Set MG4_PLATFORM_KEYS_DIR to the directory containing platform.pk8 and platform.x509.pem}
OUT="$ROOT/mobile/build/outputs/apk/debug/mobile-debug.apk"
ALIGNED="$ROOT/mobile/build/outputs/apk/debug/DiPlay-MG4-aligned.apk"
SIGNED="$ROOT/mobile/build/outputs/apk/debug/DiPlay-MG4-SWI69.apk"

: "${DIPLAY_AUTH_ASSETS_DIR:?Set DIPLAY_AUTH_ASSETS_DIR to the directory containing offline-mfi/}"

cd "$ROOT"
DIPLAY_AUTH_ASSETS_DIR="$DIPLAY_AUTH_ASSETS_DIR" ./gradlew :mobile:assembleStandaloneDebug
"$BT/zipalign" -f 4 "$OUT" "$ALIGNED"
"$BT/apksigner" sign \
  --key "$KEYS/platform.pk8" \
  --cert "$KEYS/platform.x509.pem" \
  --out "$SIGNED" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$SIGNED"
echo "$SIGNED"
