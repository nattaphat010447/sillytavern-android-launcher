# STANDROID v0.8.0 — File Explorer & Webpack Cache Fix

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release-0.8.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release-0.8.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-release-0.8.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### ฟีเจอร์ใหม่

**File Explorer + Editor ในตัว**
- เพิ่มปุ่ม "Files" ในหน้าหลัก เข้าถึง File Explorer ได้เลย
- เบราส์โฟลเดอร์ทั้งหมดของ SillyTavern ได้อย่างอิสระ
- Breadcrumb bar ด้านบน กดที่ชื่อโฟลเดอร์ใดก็ข้ามไปได้เลย
- กดปุ่ม ⋮ หรือ long-press → bottom sheet: Copy / View & Edit / Zip & Share / Delete
- Copy/Paste ในตัว — กด Copy แล้วเข้าโฟลเดอร์ปลายทาง กด Paste ใน toolbar
- **Zip & Share** — zip ไฟล์หรือโฟลเดอร์ใดก็ได้แล้วแชร์ผ่าน app อื่นทันที
- โฟลเดอร์ protected (`node_modules`, `.git`): แสดง 🔒 เข้าดูได้แต่ Copy/Delete/Paste ถูกบล็อก

**File Editor ในตัว**
- แตะไฟล์ข้อความ (`.yaml`, `.json`, `.js`, `.md` ฯลฯ) เพื่อเปิดแก้ไขได้ในแอปทันที
- บันทึกทับไฟล์เดิมด้วยปุ่ม Save
- ไฟล์ > 1 MB หรือ binary → เปิด read-only อัตโนมัติ พร้อม banner แจ้งเตือน
- มี "Discard changes?" dialog เมื่อกด back ขณะมีการแก้ไขค้าง
- บันทึก `config.yaml` ขณะ server รันอยู่ → เสนอ restart SillyTavern ทันที

**Image Preview ในตัว**
- แตะไฟล์รูปภาพ (PNG, JPG, WEBP, GIF, BMP) → เปิดดูในแอปได้เลย
- Decode แบบ two-pass ด้วย `inSampleSize` เพื่อป้องกัน OOM บนรูปขนาดใหญ่
- ถ้า decode ไม่ได้ → fallback ไปเปิดด้วย app อื่นในเครื่องอัตโนมัติ

### ด้านเทคนิค

- `FileExplorerActivity`, `FileEditorActivity`, `ImagePreviewActivity` — 3 Activity ใหม่
- `healWebpackCache()` ใน `STForegroundService` — ลบเฉพาะ broken cache dir ก่อน Node.js เริ่มทำงาน
- `file_paths.xml` เพิ่ม `<files-path name="st_files" path="SillyTavern/" />` สำหรับ FileProvider

---

## 🇬🇧 English

### New features

**Built-in File Explorer**
- New "Files" button on the main screen opens a full directory browser for SillyTavern
- Breadcrumb bar at the top — tap any segment to jump back up the tree
- Folders sort first, files alphabetically
- Three-dot menu (⋮) or long-press any item → bottom sheet: Copy / View & Edit / Zip & Share / Delete
- Internal clipboard: Copy marks a file/folder; a Paste button appears in the toolbar when active
- **Zip & Share** — zips any file or folder on-device and opens the system share chooser
- Protected folders (`node_modules`, `.git`): shown with 🔒, navigable but Copy/Delete/Paste blocked

**Built-in File Editor**
- Tap any text file (`.yaml`, `.json`, `.js`, `.md`, etc.) to open the in-app editor and save in-place
- Files > 1 MB or detected as binary → opened read-only with a banner automatically
- Dirty-state tracking: Save button highlights when there are unsaved changes
- "Discard changes?" confirmation dialog on back
- Saving `config.yaml` while the server is running → offers to restart SillyTavern immediately

**Built-in Image Preview**
- Tap an image (PNG, JPG, WEBP, GIF, BMP) → opens in-app without leaving the explorer
- Two-pass `BitmapFactory` decode with `inSampleSize` downscaling (capped at 2048 px) to prevent OOM on large images
- Falls back to the system image viewer if decode fails

### Technical

- `FileExplorerActivity.kt` — new; routes taps by type: directory → navigate, image → `ImagePreviewActivity`, text → `FileEditorActivity`, binary → `Intent.ACTION_VIEW` + `MimeTypeMap`; `isText()` falls back to null-byte sniff for unknown extensions
- `FileEditorActivity.kt` — new; in-place read/write; 1 MB edit limit / 2 MB view limit; dirty tracking; config.yaml restart dialog
- `ImagePreviewActivity.kt` — new; OOM-safe two-pass `BitmapFactory` decode (≤ 2048 px)
- `file_paths.xml` — added `<files-path name="st_files" path="SillyTavern/" />` for FileProvider URI access
- `STForegroundService.healWebpackCache()` — condition-checked stale cache removal before every server start

### Known limitations

- First-time setup still requires internet and takes several minutes
- Requires Android 13+ (API 33)
- SillyTavern startup takes 30–90 seconds on first boot (webpack compilation)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphar010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
