#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_adb="${OPENDROP_ANDROID_HOME:-$opendrop_root/.tools/android-sdk}/platform-tools/adb"
opendrop_apk="$opendrop_root/dist/open-drop-android.apk"
[[ -x "$opendrop_adb" && -f "$opendrop_apk" ]] || { echo 'Build the APK first with ./scripts/build-android.sh' >&2; exit 1; }
"$opendrop_adb" devices
"$opendrop_adb" install -r "$opendrop_apk"
"$opendrop_adb" shell am start -n dev.opendrop/.MainActivity
