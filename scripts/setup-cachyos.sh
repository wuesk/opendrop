#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if ! command -v cmake >/dev/null || ! command -v ninja >/dev/null || ! command -v g++ >/dev/null ||
   ! command -v pkg-config >/dev/null || ! pkg-config --exists Qt6Widgets Qt6Network Qt6Test ||
   ! command -v python3 >/dev/null || ! command -v curl >/dev/null || ! command -v unzip >/dev/null; then
    sudo pacman -S --needed base-devel cmake ninja qt6-base python curl unzip
fi
"$opendrop_root/scripts/build-desktop.sh"
"$opendrop_root/scripts/install-desktop.sh"
"$opendrop_root/scripts/setup-android.sh"
"$opendrop_root/scripts/build-android.sh"
echo 'Open Drop is installed in your application menu. The APK is in dist/open-drop-android.apk.'
