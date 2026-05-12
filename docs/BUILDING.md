# Building STANDROID from Source

## Prerequisites

| Tool | Minimum version | Notes |
|------|----------------|-------|
| JDK | 17 | Temurin/OpenJDK recommended |
| Android SDK | API 33–35 | Install via Android Studio |
| Python | 3.8+ | For the native-lib setup script |
| ADB | any | For device deployment |

> **Windows users:** use `gradlew.bat` instead of `./gradlew`

---

## Quick Start

```bash
# 1. Clone
git clone https://github.com/nattaphat010447/sillytavern-android-launcher.git
cd sillytavern-android-launcher

# 2. Stage native libraries (downloads ~200 MB from Termux apt)
python scripts/setup-native-libs.py

# 3. Build
./gradlew :app:assembleDebug

# 4. Deploy
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

That's it. No manual patching, no hunting for .so files.

---

## What `setup-native-libs.py` does

The script is the single source of truth for all native dependencies.
It runs entirely from Python's standard library (no extra packages needed,
though `pip install zstandard` speeds up extraction of newer Termux packages).

1. **Fetches the Termux aarch64 package index** (`Packages.gz`)
2. **Downloads the following packages** as `.deb` files:
   - `nodejs-lts` → `libnode.so` (the Node.js runtime)
   - `c-ares` → `libcares.so`
   - `libnghttp2` → `libnghttp2.so`
   - `openssl` → `libssl.so`, `libcrypto.so`
   - `brotli` → `libbrotlidec.so`, `libbrotlienc.so`, `libbrotlicommon.so`
   - `libicu` → `libicui18n.so`, `libicuuc.so`, `libicudata.so`
   - `sqlite` → `libsqlite3.so`
   - `libngtcp2` / `libnghttp3` → QUIC support (optional)
3. **Patches versioned SONAME strings** in-place (e.g. `libcares.so.2` →
   `libcares.so`) so Android's bionic linker can resolve them from
   `nativeLibraryDir`.  Patches are byte-exact — no ELF offsets change.
4. **Replaces NDK `libc++_shared.so`** with Termux's version, which exports
   the `__ndk1` vtable symbols that Termux's libnode requires.
5. **Verifies** every `DT_NEEDED` entry is satisfied before exiting.

All files land in `app/src/main/jniLibs/arm64-v8a/`.

### Re-running / updating

```bash
# Force re-download everything (e.g. after a Termux Node update)
python scripts/setup-native-libs.py --force
```

---

## Why jniLibs is not in git

The `.so` files total ~200 MB.  Committing binaries to git:
- bloats clone size for all contributors
- makes `git log` and diffs meaningless for those paths
- provides no reproducibility benefit (the script pins to Termux's *latest*,
  which is what you'd want anyway)

CI runs `setup-native-libs.py` on every push (see `.github/workflows/build-debug.yml`).

---

## CI / GitHub Actions

The workflow (`.github/workflows/build-debug.yml`) mirrors the quick-start exactly:

```
Checkout → setup-java → setup-python → pip install zstandard
→ python scripts/setup-native-libs.py
→ ./gradlew :app:assembleDebug
→ Upload APK artifact
```

The built APK is available as a workflow artifact for 7 days.

---

## Troubleshooting

### `libnode.so: empty/missing DT_HASH/DT_GNU_HASH`
The script handles this automatically by downloading from Termux (which
uses GNU hash). If you see this error, re-run `setup-native-libs.py --force`.

### `cannot locate symbol "__ndk1…" in libc++_shared.so`
The script replaces the NDK `libc++_shared.so` with Termux's version.
If this recurs, run `setup-native-libs.py --force`.

### `zstd decompression failed`
```bash
pip install zstandard
python scripts/setup-native-libs.py --force
```

### Network errors
The script retries automatically. If Termux's CDN is down, wait and retry.
