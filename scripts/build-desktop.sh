#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cmake -S "$opendrop_root" -B "$opendrop_root/build" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build "$opendrop_root/build" --parallel "${OPENDROP_BUILD_JOBS:-4}"
ctest --test-dir "$opendrop_root/build" --output-on-failure
