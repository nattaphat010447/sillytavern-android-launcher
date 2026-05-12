# STANDROID v0.1.0 — First Public Release

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-v0.1.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-v0.1.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-v0.1.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file (~60 MB) |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

STANDROID เวอร์ชันแรกสำหรับสาธารณะ — รัน SillyTavern บน Android ได้โดยไม่ต้องใช้ Termux

### ฟีเจอร์หลัก
- ติดตั้ง SillyTavern อัตโนมัติผ่าน git clone (ครั้งแรกใช้เวลา 5-15 นาที ขึ้นอยู่กับความเร็วอินเทอร์เน็ต)
- นำเข้าข้อมูลจากไฟล์ `.zip` สำรองข้อมูลที่มีอยู่
- รัน SillyTavern อยู่เบื้องหลังได้ต่อเนื่อง
- WebView เต็มจอพร้อม log panel แบบ real-time ขณะรอ server เริ่มต้น
- อัปเดต SillyTavern อัตโนมัติทุกครั้งที่เปิดแอป (ปิดได้ในการตั้งค่า)
- กู้คืนจาก crash อัตโนมัติสูงสุด 3 ครั้ง

### วิธีติดตั้ง
1. ดาวน์โหลด APK จากด้านบน (แนะนำ `arm64-v8a`)
2. เปิดใช้งาน "ติดตั้งแอปจากแหล่งที่ไม่รู้จัก"
3. เปิดไฟล์ APK แล้วติดตั้ง
4. เปิดแอป — การตั้งค่าจะเริ่มต้นอัตโนมัติ

---

## 🇬🇧 English

First public release of STANDROID — run SillyTavern on Android without Termux.

### What's included
- Automatic SillyTavern installation via git clone (first-time setup takes 5–15 min depending on connection speed)
- Import from existing `.zip` backup
- Persistent background service — ST keeps running when you switch apps
- Full-screen WebView with real-time Node.js log panel during startup
- Optional auto-update on every launch (toggleable in Settings)
- Automatic crash recovery (up to 3 restart attempts)
- Configurable server port
- Splash screen on launch

### How to install
1. Download the APK above (recommend `arm64-v8a`)
2. Enable "Install unknown apps" in your browser or file manager
3. Open the APK and install
4. Launch STANDROID — setup starts automatically

### Known limitations
- First-time setup requires internet and takes several minutes (npm install)
- Requires Android 13+ (API 33)
- SillyTavern startup takes 30–90 seconds on first boot (webpack compilation)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphat010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
