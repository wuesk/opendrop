# Run on Windows x64 with Qt 6.8.3 MinGW, its matching compiler, CMake, Ninja and Python.
[CmdletBinding()]
param(
    [string]$QtDir = $env:QT_ROOT_DIR,
    [string]$CompilerBin = "",
    [int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if ($env:OS -ne 'Windows_NT') { throw 'Run this script on Windows.' }
if (!$QtDir -or !(Test-Path "$QtDir/bin/windeployqt.exe")) {
    throw 'Pass -QtDir pointing to a Qt 6 MinGW installation, e.g. C:\Qt\6.8.3\mingw_64.'
}
$QtDir = (Resolve-Path $QtDir).Path
if (!$CompilerBin) { $CompilerBin = Split-Path (Get-Command g++.exe -ErrorAction Stop).Source }
$CompilerBin = (Resolve-Path $CompilerBin).Path
if (!(Test-Path "$CompilerBin/g++.exe")) { throw 'CompilerBin must contain the MinGW g++.exe matching Qt.' }
$env:PATH = "$QtDir\bin;$CompilerBin;$env:PATH"
foreach ($command in @('cmake', 'ninja', 'python')) { Get-Command $command -ErrorAction Stop | Out-Null }

$projectRoot = Split-Path $PSScriptRoot
$buildDir = Join-Path $projectRoot 'build-windows'
$distDir = Join-Path $projectRoot 'dist'
$packageDir = Join-Path $buildDir 'open-drop-windows-x64'
Invoke-Checked 'cmake' @('-S', $projectRoot, '-B', $buildDir, '-G', 'Ninja',
    '-DCMAKE_BUILD_TYPE=Release', "-DCMAKE_PREFIX_PATH=$QtDir",
    "-DCMAKE_CXX_COMPILER=$CompilerBin/g++.exe", '-DBUILD_TESTING=ON')
Invoke-Checked 'cmake' @('--build', $buildDir, '--parallel', "$Jobs")
Invoke-Checked 'ctest' @('--test-dir', $buildDir, '--output-on-failure')

# Always stage from scratch so a previous Qt installation cannot leave stale DLLs.
if (Test-Path $packageDir) { Remove-Item $packageDir -Recurse -Force }
New-Item -ItemType Directory -Force $packageDir, $distDir | Out-Null
Copy-Item (Join-Path $buildDir 'opendrop.exe') $packageDir
Invoke-Checked "$QtDir/bin/windeployqt.exe" @('--release', '--compiler-runtime',
    '--no-translations', '--no-opengl-sw', (Join-Path $packageDir 'opendrop.exe'))
if (!(Test-Path "$packageDir/platforms/qwindows.dll")) { throw 'Windows platform plugin was not deployed.' }
Copy-Item (Join-Path $projectRoot 'docs/windows-readme.txt') (Join-Path $packageDir 'README.txt')
Copy-Item (Join-Path $projectRoot 'docs/licenses') $packageDir -Recurse

# Verify the packaged executable without finding Qt or compiler DLLs on PATH.
$originalPath = $env:PATH
try {
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    Invoke-Checked (Join-Path $packageDir 'opendrop.exe') @('--headless', '--version')
} finally {
    $env:PATH = $originalPath
}
$archive = Join-Path $distDir 'open-drop-windows-x64.zip'
Compress-Archive -Path $packageDir -DestinationPath $archive -Force
Get-FileHash $archive -Algorithm SHA256 | Format-List
Write-Host "Windows package ready: $archive"
