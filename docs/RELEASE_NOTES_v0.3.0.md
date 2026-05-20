# STANDROID v0.3.0 — Edge-to-Edge Fix

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release-0.3.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release-0.3.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-release-0.3.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### แก้ไขบั๊ก

**Status bar บัง UI ของ SillyTavern บน Android 15+**
- Android 15 (API 35) บังคับให้แอปทุกตัวเป็น edge-to-edge ทำให้ status bar (แสดงเวลา/แบต) วาดทับแถบบนของ SillyTavern
- แก้โดยใช้ `WindowInsetsCompat` API — ระบบคำนวณขนาด status bar, navigation bar และ display cutout (notch) ให้เองแบบ dynamic แล้ว apply เป็น padding อัตโนมัติ

**Status bar สีขาวบนบางเครื่อง**
- แก้โดยเชื่อม application theme เข้ากับ `Theme.STAndroid` ที่สร้างไว้แล้ว

### ไม่มีการเปลี่ยนแปลงอื่น

Release นี้เป็น bug fix เท่านั้น — ไม่มีฟีเจอร์ใหม่, ไม่มีการเปลี่ยน API, ไม่กระทบ Android 14 หรือต่ำกว่า

---

## 🇬🇧 English

### Bug fixes

**Status bar overlapping SillyTavern UI on Android 15+**
- Android 15 (API 35) enforces edge-to-edge for all apps, causing the status bar (clock/battery) to draw over the top of the SillyTavern interface
- Fixed using `WindowInsetsCompat` API — the OS dynamically provides the exact height of the status bar, navigation bar, and display cutout (notch), which is applied as padding at runtime

**Status bar appearing white on some devices**
- Fixed by wiring the application theme to the existing `Theme.STAndroid`

### No other changes

This is a bug-fix-only release — no new features, no API changes, no impact on Android 14 or below.

### Known limitations

- First-time setup still requires internet and takes several minutes
- Requires Android 13+ (API 33)
- SillyTavern startup takes 30–90 seconds on first boot (webpack compilation)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphar010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
