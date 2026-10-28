"""Stage a Windows Qt application on Linux, checking its recursive DLL imports."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess

# DLLs supplied by supported Windows 10/11 installations. Any other missing DLL
# is an error rather than silently producing an incomplete portable package.
SYSTEM_DLLS = set('''advapi32 authz bcrypt cfgmgr32 comctl32 comdlg32 crypt32 cryptbase
cryptsp d2d1 d3d9 d3d11 d3d12 dcomp dinput8 dnsapi dwmapi dwrite dxgi dxva2 glu32
hid imm32 iphlpapi kernel32 mpr msimg32 msvcrt ncrypt netapi32 normaliz ntdll ole32
oleacc oleaut32 opengl32 powrprof propsys psapi rpcrt4 secur32 setupapi shell32
shcore shlwapi user32 userenv usp10 uxtheme version winhttp wininet winmm
winspool.drv wintrust windowscodecs wldap32 ws2_32 wtsapi32 gdi32 gdi32full
ntmarta dwmapi runtimeobject combase ucrtbase'''.split())


def imports(objdump, path):
    result = subprocess.run([str(objdump), '-p', str(path)], check=True, text=True,
                            encoding='utf-8', stdout=subprocess.PIPE)
    return re.findall(r'DLL Name:\s*(\S+)', result.stdout)


def stage(executable, qt, mingw, destination, platforms):
    destination.mkdir(parents=True, exist_ok=True)
    objdump = mingw / 'bin/x86_64-w64-mingw32-objdump'
    dlls = {}
    for directory in [qt / 'bin', mingw / 'x86_64-w64-mingw32/bin',
                      mingw / 'lib/gcc/x86_64-w64-mingw32/13.1.0']:
        for path in directory.glob('*.dll'):
            dlls.setdefault(path.name.lower(), path)
    seeds = [(executable, destination / executable.name)]
    for platform in platforms:
        seeds.append((qt / 'plugins/platforms' / platform, destination / 'platforms' / platform))
    style = qt / 'plugins/styles/qmodernwindowsstyle.dll'
    if style.is_file():
        seeds.append((style, destination / 'styles' / style.name))
    seen = set()
    while seeds:
        source, target = seeds.pop()
        if target in seen:
            continue
        seen.add(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        for name in imports(objdump, source):
            lower = name.lower()
            if lower in dlls:
                seeds.append((dlls[lower], destination / dlls[lower].name))
            elif (lower.removesuffix('.dll') not in SYSTEM_DLLS
                  and not lower.startswith(('api-ms-win-', 'ext-ms-win-'))):
                raise RuntimeError(f'{source.name}: missing dependency {name}')
    (destination / 'qt.conf').write_text('[Paths]\nPrefix=.\nPlugins=.\n', encoding='utf-8')
    print(f'Checked and staged {len(seen)} executable/DLL files in {destination}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executable', required=True, type=Path)
    parser.add_argument('--qt', required=True, type=Path)
    parser.add_argument('--mingw', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--platform', action='append')
    args = parser.parse_args()
    stage(args.executable, args.qt, args.mingw, args.destination,
          args.platform or ['qwindows.dll'])
