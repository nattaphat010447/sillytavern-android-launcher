# STANDROID — Architecture Overview

## High-Level Design

```
┌──────────────────────────────────────────────────────────────┐
│                     Android App (Kotlin)                     │
│                                                              │
│  ┌────────────────┐      ┌───────────────────────────────┐   │
│  │  MainActivity  │─────▶│  SetupActivity (first launch) │   │
│  │  (router /     │      │  1. git clone SillyTavern     │   │
│  │   dashboard)   │      │  2. npm install dependencies  │   │
│  └───────┬────────┘      └──────────────┬────────────────┘   │
│          │                              │ on complete         │
│          ▼                              ▼                    │
│  ┌────────────────────────────────────────────────────────┐  │
│  │   STForegroundService  (persistent notification)       │  │
│  │   ├─ NodeRunner    → spawn libnode.so server.js        │  │
│  │   ├─ HealthChecker → poll http://127.0.0.1:<port>/     │  │
│  │   └─ LogListener  → stdout/stderr → AppLogger          │  │
│  └────────────────────────────────────────────────────────┘  │
│                              │ server ready                  │
│                              ▼                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │   WebViewActivity                                      │  │
│  │   ├─ Full-screen WebView → http://127.0.0.1:<port>/    │  │
│  │   ├─ Loading overlay with real-time Node log           │  │
│  │   └─ Pull-to-refresh / back-navigation                 │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │   SettingsActivity / AdvancedSettingsActivity          │  │
│  │   Port · Auto-update toggle · Advanced options         │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  Storage:                                                    │
│   filesDir/SillyTavern/        ← ST source + node_modules   │
│   filesDir/logs/standroid.log  ← app + Node.js output       │
│   nativeLibraryDir/libnode.so  ← Node binary (executable)   │
└──────────────────────────────────────────────────────────────┘
```

## Node.js Strategy — Prebuilt Binary

The Node binary is shipped as `libnode.so` inside `app/src/main/jniLibs/<abi>/`.

**Why `.so` extension?**
Android 9+ enforces W^X (write XOR execute) — apps cannot `exec()` files from
`filesDir` or `cacheDir`. The system extracts `.so` files from the APK into
`nativeLibraryDir` (e.g. `/data/app/<pkg>/lib/arm64/`), which **is** an executable
partition. By naming the Node binary `libnode.so` and setting `useLegacyPackaging = true`
(`extractNativeLibs=true`), we get an executable Node binary at a predictable path.

```kotlin
val nodePath = File(ctx.applicationInfo.nativeLibraryDir, "libnode.so").absolutePath
```

## Module Structure

```
app/src/main/kotlin/com/standroid/launcher/
├── STApp.kt                     Application — initialises singletons
├── ui/
│   ├── MainActivity.kt          Router: first-launch check, auto-update, dashboard
│   ├── SetupActivity.kt         First-launch wizard (clone + npm install)
│   ├── WebViewActivity.kt       Full-screen ST WebView with loading overlay
│   ├── SettingsActivity.kt      User-facing settings (port, auto-update)
│   ├── AdvancedSettingsActivity.kt  Developer / advanced options
│   └── TopEdgeSwipeRefreshLayout.kt  Pull-to-refresh that only triggers at top
├── service/
│   ├── STForegroundService.kt   Persistent foreground service (start/stop/restart)
│   ├── NodeRunner.kt            ProcessBuilder wrapper for libnode.so
│   └── HealthChecker.kt         HTTP poller — waits for ST to become ready
├── setup/
│   ├── STInstaller.kt           git clone via JGit
│   └── NpmInstaller.kt          npm install via NodeRunner (with retry + fallback)
└── util/
    ├── AppLogger.kt             Logcat + async file logger
    ├── AppPrefs.kt              SharedPreferences wrapper
    ├── Network.kt               Connectivity check
    └── Permissions.kt           POST_NOTIFICATIONS runtime permission helper
```

## Data Flow — First Launch

```
MainActivity
  └─ requestNotificationPermission()
       └─ STInstaller.isInstalled() == false
            └─ SetupActivity
                 ├─ STInstaller.install()
                 │    └─ JGit clone → filesDir/SillyTavern/  (shallow, staging branch)
                 ├─ NpmInstaller.install()
                 │    └─ NodeRunner.start(["npm-cli.js", "install", ...])
                 │         └─ waitFor() exit 0
                 ├─ AppPrefs.isStInstalled = true
                 └─ → MainActivity
                       └─ btnStart → STForegroundService (ACTION_START)
                            ├─ NodeRunner.start(["server.js", "--port", <port>])
                            ├─ HealthChecker.waitUntilReady(<port>)
                            └─ → WebViewActivity loads http://127.0.0.1:<port>/
```

## Data Flow — Auto-Update (subsequent launches)

```
MainActivity.proceed()
  └─ AppPrefs.autoUpdateOnStartup == true && Network.isConnected()
       └─ JGit.fetch() → count new commits on origin/staging
            ├─ 0 new commits → "Up to date" — enable Start immediately
            └─ N new commits → git reset --hard origin/staging
                                → NpmInstaller.install()
                                → "Updated" — enable Start
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Prebuilt Node binary (not nodejs-mobile-android) | No third-party JNI dependency; Node version can be updated freely |
| Clone ST at runtime, not bundled in APK | Keeps APK ~60 MB; always ships latest ST; avoids binary-in-APK concerns |
| JGit for clone/update | Pure Java — no native git binary needed on device |
| `npm install` with fallback npm download | Works even before ST's vendored npm exists |
| `nativeLibraryDir` exec trick | Only reliable W^X workaround without root on AOSP Android 10+ |
| Foreground service with `dataSync` type | Required for long-running background work on Android 14+ |
| minSdk 33 (Android 13) | POST_NOTIFICATIONS runtime permission; modern predictive back gesture |
| Dynamic heap size (35% of RAM, 512–2048 MB) | Adapts to low-end and high-end devices without manual tuning |
| `NODE_COMPILE_CACHE` env var | Caches compiled bytecode across restarts for faster startup |
