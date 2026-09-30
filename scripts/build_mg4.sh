#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BT=${ANDROID_BUILD_TOOLS:-/Users/tcfdonmez/Android/Sdk/build-tools/35.0.0}
KEYS=${MG4_PLATFORM_KEYS_DIR:?Set MG4_PLATFORM_KEYS_DIR to the directory containing platform.pk8 and platform.x509.pem}
OUT="$ROOT/mobile/build/outputs/apk/debug/mobile-debug.apk"
VERSION="0.2.9-mg4.14"
ALIGNED="$ROOT/mobile/build/outputs/apk/debug/DiPlay-MG4-SWI69-v$VERSION-aligned.apk"
SIGNED="$ROOT/mobile/build/outputs/apk/debug/DiPlay-MG4-SWI69-v$VERSION.apk"
DELIVERY_DIR=$(CDPATH= cd -- "$ROOT/.." && pwd)
DELIVERY_APK="$DELIVERY_DIR/DiPlay-MG4-SWI69-v$VERSION.apk"

: "${DIPLAY_AUTH_ASSETS_DIR:?Set DIPLAY_AUTH_ASSETS_DIR to the directory containing offline-mfi/}"

cd "$ROOT"
if [ -n "${DIPLAY_PREBUILT_JNI_DIR:-}" ]; then
  DIPLAY_AUTH_ASSETS_DIR="$DIPLAY_AUTH_ASSETS_DIR" \
  DIPLAY_PREBUILT_JNI_DIR="$DIPLAY_PREBUILT_JNI_DIR" \
  ./gradlew :mobile:assembleStandaloneDebug
else
  DIPLAY_AUTH_ASSETS_DIR="$DIPLAY_AUTH_ASSETS_DIR" ./gradlew :mobile:assembleStandaloneDebug
fi
"$BT/zipalign" -f 4 "$OUT" "$ALIGNED"
"$BT/apksigner" sign \
  --key "$KEYS/platform.pk8" \
  --cert "$KEYS/platform.x509.pem" \
  --out "$SIGNED" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$SIGNED"
cp "$SIGNED" "$DELIVERY_APK"
echo "$DELIVERY_APK"
