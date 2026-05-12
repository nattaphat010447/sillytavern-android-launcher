# STANDROID

**Run SillyTavern on Android — no Termux required.**

STANDROID is an Android launcher that automatically downloads, installs, and runs
[SillyTavern](https://github.com/SillyTavern/SillyTavern) on your device.
Open the app, tap **Start**, and SillyTavern is accessible through a full-screen WebView.

---

## Features

- **Auto-install** — clones SillyTavern from GitHub and installs npm dependencies on first launch
- **Import / Export** — restore from an existing `.zip` backup or import a fresh copy
- **Background service** — SillyTavern keeps running when you switch apps
- **Full-screen WebView** — complete JS, localStorage, and file-upload support
- **Notification controls** — Stop / Restart the server from the notification shade
- **Auto-update** — optional git-based update check on every startup
- **Settings** — configurable port, auto-update toggle, and advanced options
- **Crash recovery** — automatic server restart (up to 3 attempts) on unexpected exit

## Requirements

- Android 13+ (API 33)
- ~1 GB free storage (SillyTavern + node_modules)
- Internet connection on first launch

## Installation

1. Download the latest APK from [Releases](../../releases)
2. Enable **Install unknown apps** for your browser or file manager
3. Open the APK and install
4. Launch STANDROID — setup runs automatically

> **Play Store?** Not currently planned. SillyTavern's nature makes Google Play approval
> uncertain. GitHub sideload is the primary distribution method.

## Building from Source

See [docs/BUILDING.md](docs/BUILDING.md) for full instructions.

```bash
# Quick start (Python 3.8+ required)
git clone https://github.com/nattaphat010447/sillytavern-android-launcher.git
cd sillytavern-android-launcher
python scripts/setup-native-libs.py   # downloads & patches all .so files (~200 MB)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design overview.

## License

Source-available, proprietary — see [LICENSE](LICENSE) for full terms.

In short: you may view and run the code for personal use, but copying, modifying,
or redistributing it is not permitted without written permission.

> SillyTavern is a separate project licensed under AGPL-3.0. STANDROID does not
> modify SillyTavern's source code; it clones the official repository at runtime.
