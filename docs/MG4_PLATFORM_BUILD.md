# Building the MG4 Platform-Signed APK

This document records the packaging and signing workflow used for the MG4 build targeting the MG4 SWI69 head unit (Android 9 / SAIC MT2712).

Platform signing under `android.uid.system` is required for the application to access vehicle hardware APIs (e.g., CarPropertyManager, CAN telemetry) and USB host controls on the head unit.

## Requirements

- **JDK**: Java 17 or higher compatible with the included Gradle wrapper.
- **Android SDK**: Android SDK 34/36/37 with Build Tools installed.
- **Android NDK**: NDK `28.2.13676358` (or configured via `build.gradle.kts`).
- **Platform Keys**: The MG4 platform signing files (`platform.pk8` and `platform.x509.pem`) located in `tools/` within the repository (or specified via `MG4_PLATFORM_KEYS_DIR`).

## Platform Keys Location

By default, build scripts automatically locate the platform signing keys inside the repository's `tools/` folder:

```text
tools/platform.pk8
tools/platform.x509.pem
tools/platform.key
tools/platform.p12
```

If your keys are stored in a custom directory, set the `MG4_PLATFORM_KEYS_DIR` environment variable to point to that directory.

## Build Commands

### macOS / Linux (bash/zsh)

Run from the repository root:

```bash
# Optional: specify custom paths if not using default SDK locations
export ANDROID_HOME="$HOME/Library/Android/sdk" # or $HOME/Android/Sdk on Linux
export MG4_PLATFORM_KEYS_DIR="./tools"          # Defaults to ./tools automatically

./scripts/build_mg4.sh
```

### Windows (PowerShell)

Run from the repository root:

```powershell
$env:MG4_PLATFORM_KEYS_DIR = "tools"
powershell -File scripts\build_mg4.ps1
```

## Build Workflow Summary

The build script (`scripts/build_mg4.sh` / `build_mg4.ps1`) performs the following steps:

1. **Compiles the APK**: Builds the `githubCar` / `standalone` variant targeting system UID on Android 9.
2. **Aligns Zip Structure**: Runs `zipalign -f 4` on the generated APK.
3. **Platform Signs**: Signs the aligned APK using `apksigner` with `platform.pk8` and `platform.x509.pem`.
4. **Verifies Signature**: Validates the output package signature with `apksigner verify`.
5. **Delivers APK**: Copies the final signed APK to the parent directory.

## Verification

The build script automatically verifies the signature. To manually check the output package:

```bash
BUILD_TOOLS="${ANDROID_HOME}/build-tools/35.0.0"
APK="../DiPlay-MG4-v0.2.9-mg4.46.apk"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
```
