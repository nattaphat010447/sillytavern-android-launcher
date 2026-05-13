# STANDROID v0.2.0 — Live Install Log & Dark-Themed Dialogs

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release-0.2.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release-0.2.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-release-0.2.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### สิ่งที่เพิ่มใหม่

**Live-log dialog สำหรับ Reinstall Dependencies และ Full Reset**
- ดู log การติดตั้ง package แบบ real-time ขณะรอ npm install
- Title แสดงเวลาที่ผ่านไป เช่น `Reinstall Dependencies · 2:15`
- Header แสดงจำนวน package ที่ติดตั้งแล้ว เช่น `847 packages installed so far`
- Log มีสีแยกประเภท: fetch (ชมพู), cache (ม่วง), warn (เหลือง), error (แดง), สำเร็จ (เขียว)
- ปุ่ม Cancel พร้อม dialog ยืนยัน — ยกเลิกได้ทุกเมื่อ (process Node.js จะถูก kill ทันที)
- เมื่อเสร็จ title เปลี่ยนเป็น `[OK] Reinstall Complete` หรือ `[!!] Reinstall Failed`

**Dark-purple theme สำหรับทุก dialog**
- Dialog ทั้งหมดในแอปตอนนี้ใช้ theme เดียวกับ STANDROID (พื้นหลังเข้ม, title สีม่วง, ปุ่มสีม่วง)
- ไม่มี dialog สีเทา system default อีกต่อไป

### แก้ไขบั๊ก

- Reinstall log เดิมแสดงแค่ `GET miss)` ทุกบรรทัด — แก้แล้ว (URL parsing ผิด)
- npm cache hit lines ถูก drop ทั้งหมด — แก้แล้ว (ตอนนี้แสดงเป็น `cache  package-name`)

---

## 🇬🇧 English

### What's new

**Live-log dialog for Reinstall Dependencies and Full Reset**
- Watch npm install output stream in real time while waiting
- Title shows elapsed time: `Reinstall Dependencies · 2:15`
- Stats header shows package count: `847 packages installed so far`
- Color-coded log lines: fetch (pink), cache (purple), warn (yellow), error (red), summary (green)
- Cancel button with confirmation — cancelling immediately kills the Node child process
- On completion, title changes to `[OK] Reinstall Complete` or `[!!] Reinstall Failed`

**Dark-purple themed dialogs**
- All dialogs in the app now use the STANDROID dark-purple theme (dark surface, purple title, purple buttons)
- No more system-default gray dialogs

### Bug fixes

- Reinstall log previously showed only `GET miss)` for every line — fixed (broken URL parser was grabbing the last whitespace-delimited token instead of the URL)
- npm `http cache` lines were silently dropped — fixed (now shown as `cache  package-name`)

### Known limitations

- First-time setup still requires internet and takes several minutes
- Requires Android 13+ (API 33)
- SillyTavern startup takes 30–90 seconds on first boot (webpack compilation)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphar010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
