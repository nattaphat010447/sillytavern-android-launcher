# Changelog

All notable changes to STANDROID are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

---

## [0.7.0] — 2026-07-01

### Fixed
- **Extension Update "refusing to merge unrelated histories"** — Extension updates now use fetch + force-reset (`git fetch && git reset --hard origin/<branch>`) instead of `git pull`, eliminating merge failures when local and remote histories have diverged (e.g. after restoring from a backup ZIP)
- **Extension patch not applied on fresh install** — `ExtensionPatcher` previously ran a Node.js script to patch `extensions.js`, but Node is not available immediately after `git clone` (before `npm install`). Rewrote the patcher to use pure Kotlin string replacement with asset templates — no Node dependency, runs correctly at every install stage
- **Extension patch not applied for v0.6.0 upgraders** — Patch is now applied on every app startup via a fire-and-forget coroutine in `MainActivity.proceed()`; idempotent, skips in <1 ms if already patched

### Technical
- `ExtensionPatcher.kt` fully rewritten — Kotlin regex + `Regex.escapeReplacement()` with 6 JS template files instead of a spawned Node process
- JS template files added to `assets/patches/templates/`: `imports.js`, `check-up-to-date.js`, `update-route.js`, `branches-route.js`, `switch-route.js`, `version-route.js`

---

## [0.6.0] — 2026-06-06

### Added
- **Bundled git binary** — ships `libgit.so` + git helper binaries (`libgit-remote-https.so`, `libgit-http-fetch.so`, `libgit-http-push.so`) extracted from the Termux aarch64 package
  - Enables SillyTavern's "Update Extension" feature on Android
  - git is exposed to Node.js via a wrapper script in `cacheDir/bin_wrapper/git`
  - `GIT_EXEC_PATH` and `GIT_TEMPLATE_DIR` environment variables are set automatically
  - `HOME` is set to `filesDir` so git can write `.gitconfig`
- **Git template files** — `GitSetup.kt` copies `assets/git-templates/` to `filesDir/git-templates/` on first launch
  - Required by git for repository initialization and hook templates
- **git dependency libraries** — `setup-native-libs.py` now downloads and stages:
  - `libcurl.so` — HTTPS transport for git clone/fetch/push
  - `libpcre2-8.so` — pattern matching (grep, log, etc.)
  - `libexpat.so` — XML/config parsing
  - `libiconv.so` — character encoding

### Fixed
- Extension "Update" button in SillyTavern now works — previously failed silently with "branches fetch failed / Internal Server Error" because git binary was missing from PATH

### Technical
- New `GitSetup.kt` utility class for asset-to-filesDir template extraction
- `NodeRunner.kt` now creates `bin_wrapper/git` wrapper alongside existing `node`/`xdg-open`/`open` stubs
- `STForegroundService.onCreate()` calls `GitSetup.ensureTemplates()` before starting Node
- `setup-native-libs.py` updated with `extract_git_binary()` and `extract_git_templates()` functions

---

## [0.5.0] — 2026-06-02

### Added
- **File Download / Export support** — SillyTavern exports now save to device storage via Android's Storage Access Framework
  - Native file picker dialog lets user choose save location and filename
  - Supports any download target: Documents, Downloads, Google Drive, SD card, etc.
  - Handles both blob URLs (character cards, presets) and HTTP URLs
  - `BlobDownloader` JavaScript interface bridges WebView blob data to Kotlin
  - `saveFileLauncher` (`ActivityResultContracts.CreateDocument`) drives the SAF save dialog
- **Blob URL registry** — injected JavaScript captures blob references before they are revoked
  - Overrides `URL.createObjectURL()` to store blobs in `window.blobRegistry`
  - Overrides `URL.revokeObjectURL()` to keep registry entries alive for downloads
  - Overrides `HTMLAnchorElement.prototype.click` to capture `a.download` filename
  - `HTMLElement.prototype.dispatchEvent` intercept as secondary filename capture
- **Filename extraction** — multi-strategy pipeline to resolve human-readable filenames
  - Priority 1: `window.blobFilenames` map (from `a.download` attribute)
  - Priority 2: `blob.name` property (File objects)
  - Priority 3: `Content-Disposition` header (RFC 5987 + standard)
  - Priority 4: `URLUtil.guessFileName` fallback
  - Priority 5: timestamp-based name from MIME type
- **WebView console logging** — `WebChromeClient.onConsoleMessage` now routes JS console output to logcat for download debugging

### Fixed
- SillyTavern Export buttons (character cards, presets, world info, chats) silently did nothing — fixed by registering a `DownloadListener` and injecting the blob registry on `onPageFinished`
- `TypeError: Failed to fetch` on blob URLs — SillyTavern calls `URL.revokeObjectURL()` immediately after triggering a download; fixed by overriding `revokeObjectURL` to retain the blob in the registry
- Downloaded files named as random UUID (e.g. `d46f1ce9-6842-4c12-b075-294edce8e014.json`) — fixed by intercepting `HTMLAnchorElement.click` to read `a.download` before the anchor fires

### Technical
- Added `JavascriptInterface` (`AndroidBlobDownloader`) on `WebView` for JS → Kotlin blob callbacks
- `PendingHttpDownload` and `PendingBlobDownload` data classes hold in-flight download state across the SAF result callback
- `saveBase64ToUri()` — decodes Base64 blob payload and writes to SAF URI via `ContentResolver`
- `saveHttpToUri()` — fetches HTTP resource via OkHttp and writes to SAF URI
- Added imports: `ConsoleMessage`, `MimeTypeMap`, `JSONObject`, `URLDecoder`, `Log`

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
