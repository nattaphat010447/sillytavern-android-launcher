# 🔧 STANDROID v0.6.0 — Extension Updates Support

**Release Date:** June 6, 2026

## 📦 Downloads

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-x86_64-release.apk` | Android emulator, BlissOS, ChromeOS, WSA |
| `standroid-universal-release.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### ฟีเจอร์ใหม่

**ตอนนี้ Extension Update ใช้งานได้จริงแล้ว**

**x86_64 support:** รองรับ Android Emulator, BlissOS, ChromeOS, และ Windows Subsystem for Android (WSA) แล้ว

**APK แยกตาม architecture:** ขนาดเล็กลง — เลือกดาวน์โหลดแค่สถาปัตยกรรมของเครื่องคุณ (~50 MB แทนที่จะเป็น ~140 MB)

### สิ่งที่แก้ไข

1. **SELinux denied execute** → เปลี่ยนจาก shell wrapper script เป็น **symlinks** ชี้ไปที่ .so files ใน nativeLibraryDir
2. **Missing libpcre2-8.so** → แก้ชื่อ package ใน setup script (`libpcre2` → `pcre2`)
3. **Missing libgit-remote-https.so** → Termux ใช้ symlink; สร้าง copy จาก http binary
4. **LD_LIBRARY_PATH ไม่ส่งต่อไปที่ child process** → ตั้งค่าใน ProcessBuilder environment
5. **SSL certificate errors** → ตั้ง `GIT_SSL_CAPATH` ชี้ไปที่ Android system certs
6. **libcurl.so ต้องการ libngtcp2_crypto_ossl.so** → เพิ่ม ELF patcher ที่ลบ dependency ที่ไม่มี (git ไม่ใช้ HTTP/3)
7. **bin_wrapper ใน cacheDir ใช้ไม่ได้บาง ROM** → ย้ายไป filesDir

### สิ่งที่เพิ่มเข้ามา

- **x86_64 ABI support** — ใช้ `python scripts/setup-native-libs.py --abi x86_64` เพื่อ build สำหรับ PC
- **APK splits** — แต่ละ architecture ได้ APK แยก ขนาดเล็กกว่าเดิม
- `libssh2.so` — git SSH transport (optional)
- `libngtcp2_crypto_ossl.so` — QUIC crypto module
- `remove_dt_needed()` ELF patcher — ลบ dependencies ที่หาไม่เจอออกจาก shared libraries

### สิ่งที่เปลี่ยนแปลง

- **bin_wrapper location** — `cacheDir` → `filesDir` (หลีกเลี่ยง noexec mount)
- Build config เปลี่ยนจาก product flavors เป็น ABI splits

---

## 🇬🇧 English

### What's New

**Extension Updates Now Works**

**x86_64 support:** Now runs on Android Emulator, BlissOS, ChromeOS, and Windows Subsystem for Android (WSA).

**Per-ABI APK splits:** Smaller downloads — choose only your device's architecture (~50 MB instead of ~140 MB for universal).

### Fixed

**7 critical issues resolved to make Extension Update work:**

1. **SELinux denied execute on shell wrappers** → switched to **symlinks** pointing to `nativeLibraryDir/*.so` (executable context)
2. **Missing libpcre2-8.so** → corrected Termux package name in setup script (`libpcre2` → `pcre2`)
3. **Missing libgit-remote-https.so** → Termux uses symlink; manually duplicated from http binary
4. **LD_LIBRARY_PATH not inherited by child processes** → now set explicitly in `ProcessBuilder.environment()`
5. **SSL certificate errors: "trust anchors from /data/data/com.termux/..."** → set `GIT_SSL_CAPATH` to Android system certs + `http.sslCAPath` git config
6. **libcurl.so requires libngtcp2_crypto_ossl.so (HTTP/3 QUIC)** → added ELF `remove_dt_needed()` patcher to strip unused dependency (git never uses HTTP/3)
7. **bin_wrapper/ in cacheDir fails on some ROMs (noexec mount)** → moved to `filesDir/bin_wrapper/`

### Added

- **x86_64 ABI support** — `setup-native-libs.py --abi x86_64` downloads Intel binaries for emulator/PC
  - Use `--abi all` to download both arm64-v8a and x86_64
- **APK splits per-ABI** — separate APK for each architecture:
  - `standroid-arm64-v8a-{debug|release}.apk` (~50 MB)
  - `standroid-armeabi-v7a-{debug|release}.apk` (~45 MB)
  - `standroid-x86_64-{debug|release}.apk` (~50 MB)
  - `standroid-universal-{debug|release}.apk` (~140 MB, all ABIs)
- `libssh2.so` — optional git SSH transport library
- `libngtcp2_crypto_ossl.so` — QUIC crypto module (bundled from Termux `libngtcp2` package)
- `remove_dt_needed()` ELF patcher in `setup-native-libs.py` — strips unavailable/unused DT_NEEDED entries from shared libraries

### Changed

- **bin_wrapper location** — moved from `cacheDir` to `filesDir` (avoids noexec mount issues on some Android ROMs)
- **Build configuration** — replaced `productFlavors` ("arm"/"x86") with `splits.abi` block for cleaner per-architecture outputs
- `applicationVariants.all` custom naming logic added for APK outputs

### Technical Details

**setup-native-libs.py:**
- `load_termux_index_for_abi(abi)` — ABI-specific package index loading
- `setup_abi(abi, force)` — per-ABI setup function with `--abi all` multi-ABI support
- `remove_dt_needed(path, lib_name)` — two-pass ELF dynamic section parser:
  - Pass 1: locate DT_STRTAB (string table address)
  - Pass 2: scan DT_NEEDED entries, match target lib_name, patch tag DT_NEEDED (1) → DT_DEBUG (21) so linker ignores it
- `fetch_cacert()` stub added (Mozilla CA bundle — prepared for future full SSL fix)

**NodeRunner.kt:**
- `symlinkBinary(target, link)` — creates symlinks instead of shell wrappers (SELinux-safe: kernel resolves symlink target's context)
- Environment variables added:
  - `GIT_SSL_CAPATH` → `/system/etc/security/cacerts` (Android system CA directory)
  - `GIT_CONFIG_COUNT`, `GIT_CONFIG_KEY_0/1`, `GIT_CONFIG_VALUE_0/1` — override git config via env
  - `http.sslCAPath` set to system certs path
- Moved git helper symlinks to `filesDir/bin_wrapper/git-core/` subdirectory (per `GIT_EXEC_PATH`)

**app/build.gradle.kts:**
- Removed `flavorDimensions` + `productFlavors` blocks (arm/x86)
- Added `splits { abi { isEnable=true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk=true } }`
- Added `applicationVariants.all { outputs.all { ... } }` for custom APK naming

### Known Limitations

- Git operations require internet access (HTTPS); SSH not tested on Android
- SSL verification uses Android system certs — some custom CAs may not work
- First Extension update may be slow as git fetches full branch history
- x86 (32-bit) not supported — only x86_64

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphat010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
