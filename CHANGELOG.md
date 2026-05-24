# Changelog

All notable changes to STANDROID are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.4.0] — 2026-05-24

### Added
- **Import User Data** — Import SillyTavern backup ZIP files directly into the app
  - Extracts and merges user data (characters, chats, settings) into `data/default-user/`
  - Validates ZIP structure before importing
  - Real-time progress with detailed logs
  - Automatically stops server before import
  - Supports standard SillyTavern backup format (flat structure)
- **Replace SillyTavern from ZIP** — Replace entire SillyTavern installation with custom ZIP
  - Optional automatic backup of current `data/` directory
  - Validates ZIP contains valid SillyTavern (checks for `server.js` and `package.json`)
  - Always removes `node_modules/` and runs fresh npm install
  - Restores data after replacement (if backed up)
  - Creates empty `data/default-user/` if no data exists
  - Real-time progress with timer and logs
- **ZipExtractor utility class** — Handles ZIP extraction, validation, and file copying
  - Extract ZIP files with progress tracking
  - Validate backup and installation structures
  - Recursive file copying with overwrite support

### Changed
- Advanced Settings TROUBLESHOOTING section now has 4 options (was 2):
  1. Reinstall Dependencies
  2. Import User Data (new)
  3. Replace SillyTavern from ZIP (new)
  4. Full Reset

---

## [0.3.0] — 2026-05-20

### Fixed
- Status bar (clock/battery) overlapping SillyTavern UI on Android 15+ (edge-to-edge enforcement) — fixed using `WindowInsetsCompat` to apply dynamic system bar padding to `WebViewActivity`'s root view; adapts automatically to every device, orientation, notch, and foldable configuration
- Status bar appearing in system-default color (white/black) on some devices — fixed by wiring the application-level theme in `AndroidManifest.xml` to `Theme.STAndroid` so all activities inherit the dark-purple status bar color (`bg_deep`)

---

## [0.2.0] — 2026-05-13

### Added
- Live-log dialog for Reinstall Dependencies and Full Reset — shows elapsed timer (M:SS in title), package counter, and color-coded log output in real time
- Cancel button with confirmation on all long-running operations — cancelling properly kills the Node child process
- `ThemeOverlay.STANDROID.Dialog` — all `AlertDialog`s now match the dark-purple theme (title in `purple_glow`, dark surface, styled buttons, 20dp corners)
- `bg_log_panel` drawable — rounded log panel with purple stroke border

### Changed
- `npm install` now runs with `--loglevel=http` so package downloads stream live to the UI
- Log line parser rewritten — correctly handles both `http fetch GET 200` (network) and `http cache` (local cache) lines, including scoped packages with `%2f` URL encoding
- Stats header simplified to package count only (`N packages installed so far`)
- `SettingsActivity` progress dialog also uses the STANDROID dialog theme

### Fixed
- Reinstall log previously showed only `GET miss)` for every line due to broken URL parsing (`substringAfterLast(" ")` grabbed the last token instead of the URL)
- `http cache` lines from npm were silently dropped — most lines when `~/.npm/_cacache` was warm

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
