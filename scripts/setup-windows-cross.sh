#!/usr/bin/env bash
# Isolated Linux -> Windows x64 tools; does not install system packages.
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_tools="$opendrop_root/.tools"
opendrop_keyring="${OPENDROP_ARCH_KEYRING:-$opendrop_tools/archlinux-keyring.gpg}"
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    echo 'This bootstrap supports x86_64 Linux.' >&2
    exit 1
fi
for opendrop_command in curl python3 bsdtar gpg gpgv; do
    command -v "$opendrop_command" >/dev/null || { echo "Missing command: $opendrop_command" >&2; exit 1; }
done
mkdir -p "$opendrop_tools/downloads" "$opendrop_tools/mingw"
if [[ -z "${OPENDROP_ARCH_KEYRING:-}" && ! -s "$opendrop_keyring" ]]; then
    gpg --batch --yes --dearmor --output "$opendrop_keyring" /usr/share/pacman/keyrings/archlinux.gpg
fi
[[ -s "$opendrop_keyring" ]] || { echo 'An Arch package-signing keyring is required (OPENDROP_ARCH_KEYRING).' >&2; exit 1; }
export PIP_CACHE_DIR="$opendrop_tools/pip-cache"
# Qt uses GCC 13.1 with the MSVCRT runtime; keep its cross-compiler ABI aligned.
opendrop_packages=(
    mingw-w64-binutils-2.39-1-x86_64
    mingw-w64-headers-11.0.0-1-any
    mingw-w64-crt-11.0.0-1-any
    mingw-w64-winpthreads-11.0.0-1-any
    mingw-w64-gcc-13.1.0-1-x86_64
)
for opendrop_package in "${opendrop_packages[@]}"; do
    opendrop_archive="$opendrop_tools/downloads/$opendrop_package.pkg.tar.zst"
    opendrop_name="${opendrop_package%-*-*}"
    opendrop_name="${opendrop_name%-*}"
    opendrop_url="https://archive.archlinux.org/packages/m/$opendrop_name/$opendrop_package.pkg.tar.zst"
    if [[ ! -f "$opendrop_archive" ]]; then
        curl --fail --location --retry 2 --output "$opendrop_archive.part" "$opendrop_url"
        mv -- "$opendrop_archive.part" "$opendrop_archive"
    fi
    if [[ ! -f "$opendrop_archive.sig" ]]; then
        curl --fail --location --retry 2 --output "$opendrop_archive.sig" "$opendrop_url.sig"
    fi
    gpgv --keyring "$opendrop_keyring" "$opendrop_archive.sig" "$opendrop_archive"
    if [[ ! -f "$opendrop_tools/mingw/.$opendrop_package.extracted" ]]; then
        bsdtar -xf "$opendrop_archive" -C "$opendrop_tools/mingw"
        touch "$opendrop_tools/mingw/.$opendrop_package.extracted"
    fi
done
if [[ ! -x "$opendrop_tools/windows-python/bin/python" ]]; then
    python3 -m venv "$opendrop_tools/windows-python"
fi
"$opendrop_tools/windows-python/bin/pip" install -r "$opendrop_root/scripts/windows-tools-requirements.txt"
if [[ ! -f "$opendrop_tools/qt-windows/6.8.3/mingw_64/lib/cmake/Qt6/Qt6Config.cmake" ]]; then
    "$opendrop_tools/windows-python/bin/aqt" install-qt windows desktop 6.8.3 win64_mingw \
        --archives qtbase -O "$opendrop_tools/qt-windows"
fi
if [[ ! -x "$opendrop_tools/qt-host/6.8.3/gcc_64/libexec/moc" ]]; then
    "$opendrop_tools/windows-python/bin/aqt" install-qt linux desktop 6.8.3 linux_gcc_64 \
        --archives qtbase -O "$opendrop_tools/qt-host"
fi
echo 'Windows cross-build tools ready under .tools/.'
