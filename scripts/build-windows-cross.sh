#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_tools="$opendrop_root/.tools"
opendrop_build="$opendrop_root/build-windows-cross"
opendrop_qt="$opendrop_tools/qt-windows/6.8.3/mingw_64"
opendrop_mingw="$opendrop_tools/mingw/usr"
export PATH="$opendrop_mingw/bin:$PATH"
if [[ ! -x "$opendrop_mingw/bin/x86_64-w64-mingw32-g++" || ! -f "$opendrop_qt/lib/cmake/Qt6/Qt6Config.cmake" ]]; then
    echo 'Run ./scripts/setup-windows-cross.sh first.' >&2
    exit 1
fi
cmake -S "$opendrop_root" -B "$opendrop_build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$opendrop_root/cmake/windows-mingw.cmake" \
    -DCMAKE_PREFIX_PATH="$opendrop_qt" -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
cmake --build "$opendrop_build" --parallel "${OPENDROP_BUILD_JOBS:-4}"
opendrop_stage=$(mktemp -d "$opendrop_build/package.XXXXXX")
opendrop_package="$opendrop_stage/open-drop-windows-x64"
python3 "$opendrop_root/scripts/package-windows.py" \
    --executable "$opendrop_build/opendrop.exe" --qt "$opendrop_qt" \
    --mingw "$opendrop_mingw" --destination "$opendrop_package"
cp "$opendrop_root/docs/windows-readme.txt" "$opendrop_package/README.txt"
cp -r "$opendrop_root/docs/licenses" "$opendrop_package/licenses"
mkdir -p "$opendrop_root/dist"
python3 - "$opendrop_stage" "$opendrop_root/dist" <<'PY'
import shutil, sys
shutil.make_archive(sys.argv[2] + '/open-drop-windows-x64', 'zip', sys.argv[1])
PY
sha256sum "$opendrop_root/dist/open-drop-windows-x64.zip"
echo "Windows package ready: $opendrop_root/dist/open-drop-windows-x64.zip"
echo "Staged runtime for Windows/Wine checks: $opendrop_package"
