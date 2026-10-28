# Open Drop

A small local-network file transfer app: open it on your computer and phone, then drop files onto a nearby device. The desktop app is **C++20 / Qt 6**, suited to CachyOS and KDE. The Android app uses native Java and supports **Android 10 or newer**.

## Use it

1. Put both devices on the same Wi-Fi/LAN and open Open Drop on both.
2. On desktop, drag one or multiple files onto a device. You can also select a device and click **Choose files**.
3. On Android, tap a device to choose files, or share files to **Open Drop** from another app and tap the destination.
4. Tap **Receive** on the destination, either in the app or its incoming-file notification. Received files appear in **Downloads/Open Drop**. The desktop app's **Downloads** button opens that folder.

Android keeps discovery and transfers running in a foreground service when you switch apps or close its screen. Allow notifications when prompted: the ongoing **Open Drop is running** notification has a **Stop** action, and incoming-file notifications have **Receive** and **Decline** actions. **Stop** cancels active transfers and turns discovery off until you tap **Start Open Drop** in the app. The service does not start automatically at device boot.

While enabled, the service holds a CPU wake lock for discovery with the screen off; Stop releases it and the Wi-Fi multicast lock. This uses battery, so stop discovery when you no longer need it. The screen itself can turn off normally.

Devices appear within a few seconds and disappear shortly after going offline. Transfer progress, errors, and completion appear in the app; background transfer progress also appears in the ongoing notification. Duplicate filenames receive a new destination name. No accounts, cloud service, or internet connection are needed during use.

## Install on CachyOS

Run this from a normal terminal with internet access:

```bash
./scripts/setup-cachyos.sh
```

The script installs missing desktop dependencies through `pacman`, builds and tests the desktop app, installs it under `~/.local` with an application menu entry, downloads isolated Java 21 / Android SDK / Gradle build tools into `.tools/`, and builds a signed development APK at **`dist/open-drop-android.apk`**. Android's SDK manager presents required license terms during setup. Your system Java installation is unchanged. Tool downloads are SHA-256 checked.

Copy the APK onto your phone and open it to install, allowing installation from that source when Android asks. Alternatively, connect a phone with USB debugging enabled and authorized, then run:

```bash
./scripts/install-android.sh
```

## Desktop development

Dependencies: `base-devel cmake ninja qt6-base python`. Build and test:

```bash
./scripts/build-desktop.sh
./opendrop
```

Optional installation: `./scripts/install-desktop.sh`. For a custom location, pass a prefix, e.g. `./scripts/install-desktop.sh "$PWD/.local"`.

Run with a custom name or download folder:

```bash
./opendrop --name 'My CachyOS PC' --receive-dir "$HOME/Downloads/Open Drop"
```

The core uses Qt's asynchronous receiver and a worker for outgoing transfers. Files stream in bounded chunks, each with a SHA-256 checksum. Received files become visible only after verification. Disconnecting discards the current partial file; completed files remain saved. Closing the desktop app cancels its active transfers.

## Windows x64

The portable Windows build is at **`dist/open-drop-windows-x64.zip`**. Extract the **whole ZIP** and launch `open-drop-windows-x64/opendrop.exe`. Keep the DLLs and `platforms/` folder beside the executable; no separate Qt installation is needed. Allow the app on your private network if Windows Firewall prompts. Received files save to `Downloads\Open Drop`.

### Cross-compile on Linux

On x86_64 CachyOS/Arch Linux, with Bash, Python 3 (including `venv`), curl, libarchive (`bsdtar`), GnuPG, CMake 3.21+, Ninja, and `archlinux-keyring` installed:

```bash
./scripts/setup-windows-cross.sh
./scripts/build-windows-cross.sh
```

Setup downloads an isolated MinGW-w64 GCC 13.1.0 toolchain and matching Windows/Linux Qt 6.8.3 packages under `.tools/`. Arch compiler packages are checked against their detached signatures using the installed Arch public keyring; aqt verifies Qt archive hashes. No system toolchain is installed or changed. The Python helper dependencies are pinned in `scripts/windows-tools-requirements.txt`. On another Linux distribution, the setup also needs an Arch public signing keyring; supply its binary path with `OPENDROP_ARCH_KEYRING`.

The build uses `cmake/windows-mingw.cmake`, compiles the application and Windows test executable in `build-windows-cross/`, recursively checks and collects non-system DLL dependencies, and creates the ZIP. The ZIP includes Qt's Windows platform/style plugins, runtime libraries, and license texts. Packaging works entirely on Linux; Wine is optional for execution checks.

To run the packaged executable and Qt tests using Wine:

```bash
OPENDROP_WINE=/path/to/wine ./scripts/test-windows-wine.sh
```

The test script uses a private `.tools/wine-prefix/`, extracts the final ZIP, and bounds Wine startup/test time. It defaults to `.tools/wine/usr/bin/wine` when present. Wine is not needed by Windows users.

### Build directly on Windows

Install **Qt 6.8.3 for MinGW 64-bit** and its matching **MinGW 13.1.0** toolchain using the [Qt installer](https://www.qt.io/download-qt-installer-oss). Install CMake 3.21+, Ninja, and Python 3, and put them on PATH. From PowerShell:

```powershell
./scripts/build-windows.ps1 -QtDir 'C:\Qt\6.8.3\mingw_64' -CompilerBin 'C:\Qt\Tools\mingw1310_64\bin'
```

This script builds in `build-windows/`, runs the Qt and TCP/UDP integration tests, and creates the same ZIP using [windeployqt](https://doc.qt.io/qt-6/windows-deployment.html). The **Windows build** GitHub Actions workflow installs the toolchain, builds/tests, and uploads the ZIP artifact when relevant changes are pushed or a pull request is opened. It supports manual runs from the Actions tab after it is pushed to a GitHub repository.

## Android development

```bash
./scripts/setup-android.sh
./scripts/build-android.sh
```

The build runs Java protocol tests, Robolectric service/lifecycle tests on Android 10 and 15, Android lint, APK assembly, and signature verification. Tool versions: JDK 21, Gradle 8.13, Android Gradle plugin 8.13.2, compile/target API 35. Android 0.3.0 adds [Material Components 1.13.0](https://github.com/material-components/material-components-android/blob/1.13.0/docs/getting-started.md) and uses a Material 3 DayNight theme, system dark mode, wallpaper colors on supported Android 12+ devices, Material buttons/cards/dialogs, and a scrollable screen with system-bar and keyboard insets. Invalid manual addresses show an inline error without closing the dialog. File selection survives Activity recreation during theme changes. The package ID is unchanged; builds made with the existing development signing key install as updates. Keep `.tools/android-user/debug.keystore` to install future development builds as updates.

Existing installations can be used by setting `OPENDROP_JAVA_HOME`, `OPENDROP_ANDROID_HOME`, and `OPENDROP_GRADLE` when running the build script. You can also open `android/` in Android Studio.

## Tests and current verification

```bash
ctest --test-dir build --output-on-failure
python3 tests/integration.py build/opendrop
```

`core_and_ui` tests framing, malformed metadata, receive consent, multiple files, empty files, Unicode filenames, duplicate names, checksum failure, interrupted downloads, and the desktop window. Its transfer tests use local socket pairs on Unix and loopback TCP on Windows; the Unix tests can run without network permissions. `protocol_integration` tests real TCP transfers in both directions and UDP discovery against an independent Python peer; it is explicitly skipped when the sandbox blocks networking.

Current 0.3.0 verification: Linux desktop compilation, all 14 Qt checks, and all seven real TCP/UDP integration tests pass. The Android build passes all 30 tests (six protocol checks, 16 service/lifecycle checks, and eight Material UI checks across Android 10 and 15), lint with no errors (16 warnings), APK assembly, and APK signature verification. The new UI checks cover Material controls in dark mode, inline address validation, progress mode changes, and preserving shared files across Activity recreation. The updated signed APK is **`dist/open-drop-android.apk`**.

Windows x64 application and test executables have been cross-compiled successfully on Linux with GCC 13.1.0 and Qt 6.8.3. The portable ZIP is **`dist/open-drop-windows-x64.zip`**, with all recursive non-system DLL imports resolved. Native Windows execution and the Windows-hosted PowerShell/Actions build remain unverified here.

The final Windows ZIP launches under Wine 11.20, and all 14 Windows Qt checks pass, including actual Winsock transfers, consent, checksums, cancellation, and the desktop window using the offscreen platform. Wine checks do not replace a physical Windows GUI/discovery check. SHA-256 hashes for both downloadable files are in `dist/SHA256SUMS`.

No phone is connected through ADB, so the foreground-service behavior still needs a physical-device check: install the update, allow notifications, switch to another app, and confirm that the phone remains listed on desktop. Send a small file and accept it from the notification, then use **Stop** and check that the phone disappears within 12 seconds. Android battery restrictions and force-stop controls can still stop a service; if the phone restricts Open Drop, review its app battery settings.

## Connectivity and v1 limits

Discovery uses UDP **45454** and IPv4 broadcasts/multicast; transfers use TCP **45455**, or an advertised free port if that port is occupied. Allow these on your trusted LAN in the host firewall. Guest Wi-Fi, AP/client isolation, and VPN routing can prevent devices from communicating. **Connect by address** bypasses discovery when direct connectivity is available; the receiving app displays its port and Android also displays its IPv4 address.

With Mullvad, enable **Local network sharing** so the VPN permits communication with nearby devices. With UFW, allow inbound UDP 45454 and TCP 45455 from your trusted LAN on the Wi-Fi interface. For example, on `wlan0` with subnet `192.168.0.0/24`:

```bash
mullvad lan set allow
sudo ufw allow in on wlan0 proto udp from 192.168.0.0/24 to any port 45454 comment 'Open Drop discovery'
sudo ufw allow in on wlan0 proto tcp from 192.168.0.0/24 to any port 45455 comment 'Open Drop transfers'
```

These are persistent network configuration changes; choose the interface and trusted subnet for your own network. Keep the desktop app and Android's background service running, and use the exact receive port shown by the destination app when connecting manually.

This version transfers individual files, up to 500 per batch. It uses plaintext LAN transfers with receive confirmation; use a trusted network. Folders, internet transfers, encryption/pairing, and resumable uploads are future work.

## Repository layout

- `desktop/src/` — Qt application, discovery, streaming receiver and sender.
- `android/app/` — native phone app, matching network implementation and Java tests.
- `tests/` — desktop protocol/UI tests and independent TCP/UDP integration tests.
- `scripts/` — setup, build, packaging, and installation helpers.
- `cmake/` — Windows cross-compilation toolchain.
- `docs/licenses/` — runtime license texts included in the Windows ZIP.
- `docs/protocol.md` — shared wire format and limits.

C++ uses four spaces and the included `.clang-format`. Java uses four spaces. Keep wire changes compatible across both apps and update the [protocol specification](docs/protocol.md) and tests together.

Platform references: [Qt networking](https://doc.qt.io/qt-6/qabstractsocket.html), [Android SDK tools](https://developer.android.com/studio#command-tools), [Android downloads API](https://developer.android.com/reference/android/provider/MediaStore.Downloads).
