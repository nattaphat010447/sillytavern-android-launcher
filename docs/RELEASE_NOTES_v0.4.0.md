# STANDROID v0.4.0 — Import & Replace

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release-0.4.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release-0.4.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-release-0.4.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### ฟีเจอร์ใหม่

**Import User Data — นำเข้าข้อมูลผู้ใช้จาก backup ZIP**
- ไปที่ **Advanced Settings** → **Import User Data**
- เลือกไฟล์ ZIP ที่ดาวน์โหลดจาก SillyTavern (User Settings → Account → Download Backups)
- แอปจะ extract และ merge ข้อมูลเข้า `data/default-user/`
- รองรับ characters, chats, settings.json, secrets.json และโฟลเดอร์อื่นๆ ทั้งหมด
- ตรวจสอบโครงสร้าง ZIP ก่อน import
- แสดง progress แบบ real-time พร้อม log
- หยุด server อัตโนมัติก่อนทำงาน

**Replace SillyTavern from ZIP — แทนที่ SillyTavern ทั้งหมดด้วย ZIP**
- ไปที่ **Advanced Settings** → **Replace SillyTavern from ZIP**
- เลือกว่าจะ backup ข้อมูล `data/` ปัจจุบันหรือไม่
- เลือกไฟล์ ZIP ของ SillyTavern ทั้งโฟลเดอร์
- แอปจะแทนที่ installation และรัน npm install ใหม่
- ตรวจสอบว่า ZIP มี `server.js` และ `package.json`
- ลบ `node_modules/` และรัน npm install ใหม่เสมอ
- คืนค่าข้อมูลหลัง replace (ถ้าเลือก backup)
- สร้าง `data/default-user/` เปล่าๆ ถ้าไม่มีข้อมูล

**กรณีใช้งาน:**
- Import SillyTavern ที่ตั้งค่าไว้แล้ว
- Restore จาก backup เต็มรูปแบบ
- เปลี่ยนไปใช้ version ที่แก้ไข/fork
- Downgrade ไปยัง version เฉพาะ

### เมนู Advanced Settings ใหม่

ส่วน TROUBLESHOOTING ตอนนี้มี 4 ตัวเลือก:
1. **Reinstall Dependencies** — ลบ node_modules และติดตั้งใหม่
2. **Import User Data** ⭐ ใหม่ — นำเข้า backup ZIP
3. **Replace SillyTavern from ZIP** ⭐ ใหม่ — แทนที่ installation ทั้งหมด
4. **Full Reset** — ลบและ clone ใหม่จาก GitHub

### ความปลอดภัย

ฟีเจอร์ทั้ง 2 มี:
- หยุด server อัตโนมัติก่อนทำงาน
- ตรวจสอบโครงสร้างไฟล์ก่อน import
- แสดง progress แบบ real-time
- รองรับการยกเลิกพร้อม confirmation
- จัดการ error อย่างชัดเจน
- ลบไฟล์ชั่วคราวอัตโนมัติ

---

## 🇬🇧 English

### New Features

**Import User Data — Import backup ZIP files**
- Go to **Advanced Settings** → **Import User Data**
- Select your backup ZIP (downloaded from SillyTavern: User Settings → Account → Download Backups)
- App extracts and merges data into `data/default-user/`
- Supports characters, chats, settings.json, secrets.json, and all other user data folders
- Validates ZIP structure before importing
- Real-time progress with detailed logs
- Automatically stops server before import

**Replace SillyTavern from ZIP — Replace entire installation**
- Go to **Advanced Settings** → **Replace SillyTavern from ZIP**
- Choose whether to backup your current `data/` directory
- Select your SillyTavern ZIP file (entire folder zipped)
- App replaces installation and runs npm install
- Validates ZIP contains `server.js` and `package.json`
- Always removes `node_modules/` and runs fresh npm install
- Restores data after replacement (if backed up)
- Creates empty `data/default-user/` if no data exists

**Use cases:**
- Import a pre-configured SillyTavern setup
- Restore from a complete backup
- Switch to a modified/forked version
- Downgrade to a specific version

### Updated Advanced Settings Menu

TROUBLESHOOTING section now has 4 options:
1. **Reinstall Dependencies** — Delete node_modules and reinstall
2. **Import User Data** ⭐ NEW — Import backup ZIP
3. **Replace SillyTavern from ZIP** ⭐ NEW — Replace entire installation
4. **Full Reset** — Wipe and re-clone from GitHub

### Safety Features

Both features include:
- Automatic server shutdown before operations
- Structure validation to prevent invalid imports
- Real-time progress tracking
- Cancellation support with confirmation
- Clear error handling
- Automatic cleanup of temporary files

### Known Limitations

- Import User Data expects flat structure (no `data/` prefix in ZIP)
- Replace SillyTavern always runs npm install regardless of existing node_modules
- Server must be stopped before using these features (done automatically)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphat010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
