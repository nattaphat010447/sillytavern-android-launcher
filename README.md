# STANDROID

**รัน SillyTavern บน Android — โดยไม่ต้องใช้ Termux**

STANDROID คือแอปสำหรับ Android ที่ดาวน์โหลด ติดตั้ง และรัน [SillyTavern](https://github.com/SillyTavern/SillyTavern) ให้อัตโนมัติ
เปิดแอป กด **Start** แล้ว SillyTavern จะพร้อมใช้งานผ่าน WebView แบบเต็มจอ

---

## ฟีเจอร์

- **ติดตั้งอัตโนมัติ** — clone SillyTavern จาก GitHub และติดตั้ง npm dependencies ในครั้งแรก
- **นำเข้า / ส่งออก** — กู้คืนจากไฟล์ `.zip` สำรองข้อมูลที่มีอยู่
- **รันอยู่เบื้องหลัง** — SillyTavern ยังทำงานต่อเมื่อสลับแอป
- **WebView เต็มจอ** — รองรับ JS, localStorage และอัปโหลดไฟล์ครบ
- **ควบคุมผ่านการแจ้งเตือน** — หยุด / รีสตาร์ท server จาก notification shade
- **อัปเดตอัตโนมัติ** — ตรวจสอบอัปเดตผ่าน git ทุกครั้งที่เปิดแอป (ปิดได้)
- **ตั้งค่าได้** — กำหนด port, toggle auto-update และตัวเลือกขั้นสูง
- **กู้คืนจาก crash** — รีสตาร์ท server อัตโนมัติสูงสุด 3 ครั้งเมื่อเกิดข้อผิดพลาด

## ความต้องการของระบบ

- Android 13 ขึ้นไป (API 33)
- พื้นที่ว่างประมาณ 1 GB (SillyTavern + node_modules)
- การเชื่อมต่ออินเทอร์เน็ตในการเปิดใช้งานครั้งแรก

## วิธีติดตั้ง

1. ดาวน์โหลด APK ล่าสุดจาก [Releases](../../releases)
2. เปิดใช้งาน **ติดตั้งแอปจากแหล่งที่ไม่รู้จัก** ในเบราว์เซอร์หรือตัวจัดการไฟล์
3. เปิดไฟล์ APK แล้วติดตั้ง
4. เปิด STANDROID — การตั้งค่าจะเริ่มต้นอัตโนมัติ

### ควรเลือก APK ไหน?

เนื่องจาก STANDROID ต้องการ Android 13+ เครื่องที่รองรับเกือบทั้งหมดใช้สถาปัตยกรรม **arm64-v8a**

| ไฟล์ APK | เหมาะกับ |
|----------|----------|
| ⭐ **`standroid-arm64-v8a-*.apk`** | Android phone ส่วนใหญ่ (ปี 2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** |
| `standroid-armeabi-v7a-*.apk` | เครื่องเก่า 32-bit (หายาก) |
| `standroid-universal-*.apk` | ใช้ได้กับทุกเครื่อง แต่ขนาดใหญ่กว่า (~60 MB) |

> **ไม่รู้จะเลือกอะไร?** ดาวน์โหลด `universal` — ติดตั้งได้กับทุกเครื่องแน่นอน

> **Play Store?** ยังไม่มีแผนในตอนนี้ ลักษณะของ SillyTavern ทำให้การอนุมัติจาก Google Play ไม่แน่นอน การ sideload ผ่าน GitHub เป็นวิธีหลักในการแจกจ่าย

---

---

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

### Which APK should I download?

Since STANDROID requires Android 13+, nearly all compatible devices use the **arm64-v8a** architecture.

| APK file | Best for |
|----------|----------|
| ⭐ **`standroid-arm64-v8a-*.apk`** | Most Android phones (2019+). **Choose this if unsure.** |
| `standroid-armeabi-v7a-*.apk` | Rare older 32-bit devices |
| `standroid-universal-*.apk` | Works on any device, but larger (~60 MB) |

> **Not sure?** Download `universal` — it works on every device.

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
