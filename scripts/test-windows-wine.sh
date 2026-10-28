#!/usr/bin/env bash
# Tests the packaged Windows build using a private Wine prefix.
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_tools="$opendrop_root/.tools"
opendrop_wine="${OPENDROP_WINE:-$opendrop_tools/wine/usr/bin/wine}"
[[ -x "$opendrop_wine" ]] || { echo 'Set OPENDROP_WINE to a Wine executable.' >&2; exit 1; }
export WINEPREFIX="$opendrop_tools/wine-prefix"
export WINEDEBUG="${OPENDROP_WINEDEBUG:--all}"
export WINEDLLOVERRIDES='mscoree,mshtml='
export QT_QPA_PLATFORM=offscreen
# Keep Wine's first-run desktop integration inside the project as well.
export XDG_DATA_HOME="$opendrop_tools/wine-xdg/data"
export XDG_CONFIG_HOME="$opendrop_tools/wine-xdg/config"
export XDG_CACHE_HOME="$opendrop_tools/wine-xdg/cache"
unset DISPLAY WAYLAND_DISPLAY
opendrop_wineserver="$(dirname "$opendrop_wine")/wineserver"
trap '
    if [[ -x "$opendrop_wineserver" ]]; then "$opendrop_wineserver" -k || true; fi
' EXIT
opendrop_check=$(mktemp -d "$opendrop_root/build-windows-cross/wine-check.XXXXXX")
python3 - "$opendrop_root/dist/open-drop-windows-x64.zip" "$opendrop_check" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    archive.extractall(sys.argv[2])
PY
opendrop_package="$opendrop_check/open-drop-windows-x64"
timeout 90s "$opendrop_wine" "$opendrop_package/opendrop.exe" --headless --version
python3 "$opendrop_root/scripts/package-windows.py" \
    --executable "$opendrop_root/build-windows-cross/opendrop_tests.exe" \
    --qt "$opendrop_tools/qt-windows/6.8.3/mingw_64" --mingw "$opendrop_tools/mingw/usr" \
    --destination "$opendrop_check/tests" --platform qoffscreen.dll
opendrop_result="$opendrop_root/build-windows-cross/wine-test-results.txt"
# Pass the platform explicitly so the test does not depend on Qt environment variables.
timeout 60s "$opendrop_wine" "$opendrop_check/tests/opendrop_tests.exe" \
    -platform offscreen -o "Z:$opendrop_result,txt"
cat "$opendrop_result"
echo 'Packaged Windows executable and all Qt checks passed under Wine.'
