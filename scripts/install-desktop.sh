#!/usr/bin/env bash
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_prefix=${1:-"$HOME/.local"}
cmake --install "$opendrop_root/build" --prefix "$opendrop_prefix"
python3 - "$opendrop_prefix" <<'PY'
from pathlib import Path
import sys
prefix = Path(sys.argv[1]).resolve()
entry = prefix / 'share/applications/dev.opendrop.desktop'
executable = str(prefix / 'bin/opendrop')
escaped = executable.replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$').replace('%', '%%')
entry.write_text(entry.read_text().replace('Exec=opendrop', f'Exec="{escaped}"'))
print(f'Installed Open Drop: {executable}')
PY
if command -v update-desktop-database >/dev/null; then
    update-desktop-database "$opendrop_prefix/share/applications"
fi
