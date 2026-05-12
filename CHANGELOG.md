# Changelog

All notable changes to STANDROID are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.1.0] — 2026-05-12

### Added
- Auto-install SillyTavern via shallow git clone (JGit, staging branch)
- npm dependency installation with automatic retry and exponential back-off
- Import SillyTavern from an existing `.zip` backup
- Persistent foreground service — ST keeps running when you switch apps
- Full-screen WebView with complete JS, localStorage, and file-upload support
- Loading overlay with real-time Node.js stdout/stderr log panel
- Pull-to-refresh inside the WebView
- Notification controls — Stop / Restart server from the notification shade
- Auto-update on startup — git fetch + hard reset + npm install (optional, toggleable)
- Settings screen — configurable server port, auto-update toggle
- Advanced settings screen
- Crash recovery — automatic server restart up to 3 times on unexpected exit
- Dynamic Node.js heap size — 35% of device RAM, clamped to 512–2048 MB
- `NODE_COMPILE_CACHE` — compiled bytecode cached across restarts for faster startup
- Automatic port conflict resolution — finds next available port if default is in use
- Android-safe `config.yaml` patching — disables `browserLaunch` and IPv6 on first boot
- Async file logger — all Node.js output saved to `files/logs/standroid.log`
- ABI-split APKs — separate arm64-v8a and armeabi-v7a builds for smaller download size

### Technical
- Kotlin + Gradle Kotlin DSL
- minSdk 33 (Android 13) — POST_NOTIFICATIONS runtime permission
- Node.js binary shipped as `libnode.so` via `jniLibs/` (W^X workaround)
- Native libraries sourced from Termux apt (libcares, libssl, libicu, libsqlite3, etc.)
- R8 / ProGuard minification enabled for release builds
