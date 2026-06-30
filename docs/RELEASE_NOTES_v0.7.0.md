# STANDROID v0.7.0 — Extension Update Fix

## ดาวน์โหลด / Download

| ไฟล์ / File | เหมาะกับ / Best for |
|-------------|---------------------|
| ⭐ **`standroid-arm64-v8a-release-0.7.0.apk`** | Android phone ส่วนใหญ่ (2019+) — **เลือกอันนี้ถ้าไม่แน่ใจ** / Most phones — **choose this if unsure** |
| `standroid-armeabi-v7a-release-0.7.0.apk` | เครื่องเก่า 32-bit / Older 32-bit devices |
| `standroid-universal-release-0.7.0.apk` | ทุกเครื่อง แต่ขนาดใหญ่กว่า / Any device, larger file |

**ต้องการ Android 13+ (API 33) / Requires Android 13+ (API 33)**

---

## 🇹🇭 ภาษาไทย

### แก้ไขบั๊ก

**Extension Update ล้มเหลวด้วย "refusing to merge unrelated histories"**
- ปัญหา: กด Update Extension ใน SillyTavern แล้วได้ error `GitResponseError: refusing to merge unrelated histories`
- สาเหตุ: `simple-git` ใช้ `git pull` ซึ่งล้มเหลวเมื่อ local repo กับ remote มี commit history ต่างกัน
- แก้: เปลี่ยนวิธี update เป็น fetch + force-reset (เทียบเท่า `git fetch && git reset --hard origin/<branch>`) — ไม่มีการ merge เกิดขึ้นเลย

### ด้านเทคนิค

- `ExtensionPatcher.kt` เขียนใหม่ทั้งหมด — ใช้ Kotlin regex + asset templates แทน Node.js script
- Template JS files แยกเป็นไฟล์ใน `assets/patches/templates/` (6 ไฟล์)
- แก้ `.gitignore` ที่ exclude `app/src/main/assets/` ออกจาก repo โดยไม่ตั้งใจ

---

## 🇬🇧 English

### Bug fixes

**Extension Update failing with "refusing to merge unrelated histories"**
- The "Update Extension" button in SillyTavern threw `GitResponseError: refusing to merge unrelated histories`
- Root cause: `simple-git`'s `git pull` fails when the local extension repo and remote have diverged histories (common after Import User Data from backup)
- Fixed: replaced `git.pull()` with fetch + force-reset (equivalent to `git fetch && git reset --hard origin/<branch>`) — no merge operation is attempted

### Technical

- `ExtensionPatcher.kt` fully rewritten — uses Kotlin regex + `Regex.escapeReplacement()` with JS template files instead of a Node.js script
- 6 JS template files added to `assets/patches/templates/`: `imports.js`, `check-up-to-date.js`, `update-route.js`, `branches-route.js`, `switch-route.js`, `version-route.js`
- `.gitignore` fixed — `app/src/main/assets/` was unintentionally excluded, causing patch assets to be missing from cloned repos

### Known limitations

- First-time setup still requires internet and takes several minutes
- Requires Android 13+ (API 33)
- SillyTavern startup takes 30–90 seconds on first boot (webpack compilation)

---

## Full Changelog

See [CHANGELOG.md](https://github.com/nattaphar010447/sillytavern-android-launcher/blob/main/CHANGELOG.md) for the complete list of changes.
