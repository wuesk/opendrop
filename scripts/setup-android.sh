#!/usr/bin/env bash
# Installs isolated Android build tools under .tools; does not change system Java.
set -euo pipefail
opendrop_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
opendrop_tools="$opendrop_root/.tools"
mkdir -p "$opendrop_tools/downloads"
for opendrop_command in curl python3 unzip tar sha256sum; do
    command -v "$opendrop_command" >/dev/null || { echo "Missing command: $opendrop_command" >&2; exit 1; }
done
if [[ $(uname -m) != x86_64 ]]; then
    echo 'This bootstrap currently supports x86_64 Linux.' >&2
    exit 1
fi
fetch() {
    curl --fail --location --retry 2 --connect-timeout 15 --output "$2.part" "$1"
    mv -- "$2.part" "$2"
}
verify() { printf '%s  %s\n' "$1" "$2" | sha256sum --check --status; }

if [[ ! -x "$opendrop_tools/jdk/bin/javac" ]]; then
    echo 'Downloading Java 21 build tools from Eclipse Adoptium…'
    fetch 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&vendor=eclipse' "$opendrop_tools/downloads/jdk.json"
    readarray -t opendrop_jdk_info < <(python3 - "$opendrop_tools/downloads/jdk.json" <<'PY'
import json, sys
package = json.load(open(sys.argv[1]))[0]['binary']['package']
print(package['link'])
print(package['checksum'])
PY
    )
    fetch "${opendrop_jdk_info[0]}" "$opendrop_tools/downloads/jdk.tar.gz"
    verify "${opendrop_jdk_info[1]}" "$opendrop_tools/downloads/jdk.tar.gz"
    mkdir -p "$opendrop_tools/jdk"
    tar -xzf "$opendrop_tools/downloads/jdk.tar.gz" -C "$opendrop_tools/jdk" --strip-components=1
fi
export JAVA_HOME="$opendrop_tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$opendrop_tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$opendrop_tools/gradle-home"
export ANDROID_USER_HOME="$opendrop_tools/android-user"
mkdir -p "$ANDROID_HOME" "$ANDROID_USER_HOME"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo 'Downloading Android command-line tools…'
    fetch 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' "$opendrop_tools/downloads/android-tools.zip"
    verify '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583' "$opendrop_tools/downloads/android-tools.zip"
    mkdir -p "$opendrop_tools/android-unpack" "$ANDROID_HOME/cmdline-tools"
    unzip -q -o "$opendrop_tools/downloads/android-tools.zip" -d "$opendrop_tools/android-unpack"
    mv "$opendrop_tools/android-unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
opendrop_sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
echo 'Installing Android SDK packages. The SDK manager will present any required licenses.'
"$opendrop_sdkmanager" --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if [[ ! -x "$opendrop_tools/gradle-8.13/bin/gradle" ]]; then
    echo 'Downloading Gradle 8.13…'
    fetch 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' "$opendrop_tools/downloads/gradle.zip"
    fetch 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' "$opendrop_tools/downloads/gradle.sha256"
    verify "$(tr -d '\r\n' < "$opendrop_tools/downloads/gradle.sha256")" "$opendrop_tools/downloads/gradle.zip"
    unzip -q -o "$opendrop_tools/downloads/gradle.zip" -d "$opendrop_tools"
fi
echo 'Android build tools installed under .tools/.'
