# Building the MG4 platform-signed APK

This document records the packaging flow used for the MG4CPlay APK distributed for the
MG4 SWI69 head unit (Android 9). The build keeps the existing application identity so an
installed MG4CPlay test build can be updated without clearing its settings.

## Requirements

- JDK compatible with the included Gradle wrapper
- Android SDK 37
- Android NDK `28.2.13676358`
- Android Build Tools `35.0.0` (or set `ANDROID_BUILD_TOOLS`)
- Local standalone authentication assets
- The matching MG4 platform signing certificate and private key

The authentication and platform-signing files are private build inputs. Do not copy them
into the repository or commit them.

The authentication directory must contain:

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

The platform-key directory must contain:

```text
platform.pk8
platform.x509.pem
```

## Versioning

Keep these values synchronized before building:

- `versionCode` and `versionName` in `mobile/build.gradle.kts`
- `VERSION` in `scripts/build_mg4.sh`

Android uses `versionCode` to decide whether an installed package can be updated.

## Build command

Run from the repository root:

```sh
ANDROID_HOME=/absolute/path/to/Android/Sdk \
ANDROID_SDK_ROOT=/absolute/path/to/Android/Sdk \
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets \
MG4_PLATFORM_KEYS_DIR=/absolute/path/to/mg4-platform-keys \
./scripts/build_mg4.sh
```

`scripts/build_mg4.sh` performs the complete delivery workflow:

1. Runs `:mobile:assembleStandaloneDebug` with the explicit authentication assets.
2. Aligns the APK with Android Build Tools `zipalign`.
3. Signs it with `platform.pk8` and `platform.x509.pem`.
4. Verifies the signature and prints the signing certificate with `apksigner`.
5. Copies the final APK to the parent delivery directory as
   `MG4CPlay-v<version>.apk`.

The standalone task rejects missing authentication inputs. Ordinary source/CI builds do
not bundle the private accessory identity.

## Verification

The build script already runs signature verification. For an additional manual check:

```sh
BUILD_TOOLS=/absolute/path/to/Android/Sdk/build-tools/35.0.0
APK=/absolute/path/to/MG4CPlay-v0.2.9-mg4.46.apk

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
"$BUILD_TOOLS/aapt" dump badging "$APK" | head -n 2
shasum -a 256 "$APK"
```

For the MG4 platform build, `apksigner` must report the expected platform certificate.
Never distribute an APK if its certificate differs from the installed build that it is
intended to update.
