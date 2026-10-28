#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_tools="$opendrop_root/.tools"
export JAVA_HOME="${OPENDROP_JAVA_HOME:-$opendrop_tools/jdk}"
export ANDROID_HOME="${OPENDROP_ANDROID_HOME:-$opendrop_tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$opendrop_tools/gradle-home"
export ANDROID_USER_HOME="$opendrop_tools/android-user"
export PATH="$JAVA_HOME/bin:$PATH"
opendrop_gradle="${OPENDROP_GRADLE:-$opendrop_tools/gradle-8.13/bin/gradle}"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -x "$opendrop_gradle" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
    echo 'Android tools are missing. Run ./scripts/setup-android.sh first.' >&2
    exit 1
fi
# Keep the development signing key inside this workspace; it is never committed.
mkdir -p "$ANDROID_USER_HOME" "$opendrop_root/dist"
"$opendrop_gradle" -p "$opendrop_root/android" --no-daemon testDebugUnitTest assembleDebug lintDebug
cp -- "$opendrop_root/android/app/build/outputs/apk/debug/app-debug.apk" "$opendrop_root/dist/open-drop-android.apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$opendrop_root/dist/open-drop-android.apk"
echo "APK ready: $opendrop_root/dist/open-drop-android.apk"
