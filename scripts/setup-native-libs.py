#!/usr/bin/env python3
"""
setup-native-libs.py — one-command native library setup for STANDROID
======================================================================

Downloads, patches, and stages all required .so files for the target ABI
from the Termux apt repository.  Run this ONCE after cloning (per ABI).

Usage:
    python scripts/setup-native-libs.py                      # arm64-v8a (default)
    python scripts/setup-native-libs.py --abi x86_64         # x86_64 (emulator/PC)
    python scripts/setup-native-libs.py --abi all            # both arm64-v8a and x86_64
    python scripts/setup-native-libs.py --force              # re-download arm64-v8a
    python scripts/setup-native-libs.py --abi x86_64 --force # re-download x86_64

Requirements:
    Python 3.8+  |  Internet access
    Optional: pip install zstandard   (needed only if Termux ships .tar.zst packages)

What this script does:
    1. Fetches the Termux package index for the target ABI
    2. Downloads Node.js LTS + all shared-library dependencies
    3. Downloads git binary + helpers for SillyTavern extension updates
    4. Patches versioned SONAME strings (libcares.so.2 → libcares.so, etc.)
    5. Replaces NDK libc++_shared.so with Termux version (has vtable symbols)
    6. Extracts git template files into app/src/main/assets/git-templates/
    7. Verifies every DT_NEEDED entry is satisfied

After running, build with Gradle:
    ./gradlew assembleArmDebug    # ARM APK  (arm64-v8a + armeabi-v7a)
    ./gradlew assembleX86Debug    # x86 APK  (x86_64)
    ./gradlew assembleDebug       # both
"""

import argparse
import gzip
import io
import lzma
import os
import struct
import sys
import tarfile
import urllib.request
from pathlib import Path

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR        = Path(__file__).parent.resolve()
PROJECT_ROOT      = SCRIPT_DIR.parent
GIT_TEMPLATES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "git-templates"
ASSETS_DIR        = PROJECT_ROOT / "app" / "src" / "main" / "assets"
CACERT_URL        = "https://curl.se/ca/cacert.pem"

# ── Termux apt ────────────────────────────────────────────────────────────────
TERMUX_APT = "https://packages.termux.dev/apt/termux-main"

# Map Android ABI → Termux architecture name
ABI_TO_ARCH = {
    "arm64-v8a": "aarch64",
    "x86_64":    "x86_64",
}

def get_dest_dir(abi: str) -> Path:
    return PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / abi

def get_packages_url(abi: str) -> str:
    arch = ABI_TO_ARCH[abi]
    return f"{TERMUX_APT}/dists/stable/main/binary-{arch}/Packages.gz"

# Legacy defaults for functions that still reference global DEST_DIR/PACKAGES_GZ_URL
DEST_DIR        = get_dest_dir("arm64-v8a")
PACKAGES_GZ_URL = get_packages_url("arm64-v8a")

# ── SONAME patches ───────────────────────────────────────────────────────────
# Every (search, replace) pair MUST have identical byte length.
# Replacement is null-padded so all ELF offsets remain valid.
SONAME_PATCHES: list[tuple[bytes, bytes]] = [
    # libz
    (b'libz.so.1\x00',              b'libz.so\x00\x00\x00'),
    # libpthread (merged into bionic on Android 12+)
    (b'libpthread.so.0\x00',        b'libpthread.so\x00\x00\x00'),
    # c-ares
    (b'libcares.so.2\x00',          b'libcares.so\x00\x00'),
    # nghttp2
    (b'libnghttp2.so.14\x00',       b'libnghttp2.so\x00\x00\x00\x00'),
    # OpenSSL
    (b'libssl.so.3\x00',            b'libssl.so\x00\x00\x00'),
    (b'libcrypto.so.3\x00',         b'libcrypto.so\x00\x00\x00'),
    # Brotli
    (b'libbrotlidec.so.1\x00',      b'libbrotlidec.so\x00\x00\x00'),
    (b'libbrotlienc.so.1\x00',      b'libbrotlienc.so\x00\x00\x00'),
    (b'libbrotlicommon.so.1\x00',   b'libbrotlicommon.so\x00\x00\x00'),
    # QUIC
    (b'libngtcp2.so.16\x00',              b'libngtcp2.so\x00\x00\x00\x00'),
    (b'libngtcp2_crypto_ossl.so.0\x00',   b'libngtcp2_crypto_ossl.so\x00\x00\x00'),
    (b'libnghttp3.so.9\x00',              b'libnghttp3.so\x00\x00\x00'),
    # ICU v78
    (b'libicudata.so.78\x00',       b'libicudata.so\x00\x00\x00\x00'),
    (b'libicui18n.so.78\x00',       b'libicui18n.so\x00\x00\x00\x00'),
    (b'libicuuc.so.78\x00',         b'libicuuc.so\x00\x00\x00\x00'),
    # ICU v73 (fallback for older Termux snapshots)
    (b'libicudata.so.73\x00',       b'libicudata.so\x00\x00\x00\x00'),
    (b'libicui18n.so.73\x00',       b'libicui18n.so\x00\x00\x00\x00'),
    (b'libicuuc.so.73\x00',         b'libicuuc.so\x00\x00\x00\x00'),
    # SQLite
    (b'libsqlite3.so.0\x00',        b'libsqlite3.so\x00\x00\x00'),
]

# ── Packages to download ──────────────────────────────────────────────────────
# (termux_package_name, [filenames_to_extract])
# For nodejs-lts, the binary inside is called "node" → renamed to libnode.so
PACKAGES: list[tuple[str, list[str]]] = [
    ("nodejs-lts", ["node"]),               # node binary → libnode.so
    ("c-ares",     ["libcares.so"]),
    ("libnghttp2", ["libnghttp2.so"]),
    ("openssl",    ["libssl.so", "libcrypto.so"]),
    ("brotli",     ["libbrotlidec.so", "libbrotlienc.so", "libbrotlicommon.so"]),
    ("libicu",     ["libicui18n.so", "libicuuc.so", "libicudata.so"]),
    ("sqlite",     ["libsqlite3.so"]),
    ("libngtcp2",  ["libngtcp2.so", "libngtcp2_crypto_ossl.so"]),  # QUIC
    ("libnghttp3", ["libnghttp3.so"]),       # optional: QUIC
    # git + dependencies for SillyTavern extension updates
    ("libcurl",    ["libcurl.so"]),         # git HTTPS transport
    ("libssh2",    ["libssh2.so"]),         # libcurl SSH/HTTPS dependency
    ("pcre2",      ["libpcre2-8.so"]),      # git pattern matching
    ("libexpat",   ["libexpat.so"]),        # git config/xml parsing
    ("libiconv",   ["libiconv.so"]),        # character encoding
]

# Additional SONAME patches for new packages (git/curl dependencies)
SONAME_PATCHES_EXTRA: list[tuple[bytes, bytes]] = [
    (b'libssh2.so.1\x00',           b'libssh2.so\x00\x00'),
    (b'libcurl.so.4\x00',           b'libcurl.so\x00\x00\x00'),
    (b'libpcre2-8.so.0\x00',        b'libpcre2-8.so\x00\x00\x00'),
    (b'libexpat.so.1\x00',          b'libexpat.so\x00\x00\x00'),
    (b'libiconv.so.2\x00',          b'libiconv.so\x00\x00\x00'),
]

# Termux packages that contain libc++_shared.so with vtable symbols
LIBCXX_PACKAGES = ["libllvm", "libc++", "nodejs-lts"]

# Android system libraries — never bundle these
SYSTEM_LIBS = {
    "libandroid.so", "liblog.so", "libdl.so", "libc.so", "libm.so",
    "libz.so", "libstdc++.so", "libpthread.so", "librt.so",
    "ld-android.so", "libc++_shared.so", "libdl_android.so",
}


# ═══════════════════════════════════════════════════════════════════════════════
# Utility helpers
# ═══════════════════════════════════════════════════════════════════════════════

def fetch(url: str, desc: str = "") -> bytes:
    """HTTP GET with a simple progress message."""
    if desc:
        print(f"  ↓ {desc}")
    req = urllib.request.Request(url, headers={"User-Agent": "standroid-setup/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def decompress_zst(data: bytes) -> bytes:
    """Decompress zstandard data.
    Tries the 'zstandard' Python package first, then the 'zstd' CLI."""
    try:
        import zstandard  # pip install zstandard
        return zstandard.ZstdDecompressor().decompress(data, max_output_size=512 * 1024 * 1024)
    except ImportError:
        pass
    import subprocess
    result = subprocess.run(
        ["zstd", "-d", "-", "--stdout"],
        input=data, capture_output=True, timeout=120,
    )
    if result.returncode == 0:
        return result.stdout
    raise RuntimeError(
        "zstd decompression failed.\n"
        "  Install with: pip install zstandard\n"
        "  or:           pip3 install zstandard"
    )


def extract_deb(deb: bytes) -> bytes | None:
    """Extract the data.tar.* member from a .deb (ar) archive."""
    pos = 8  # skip '!<arch>\n'
    while pos < len(deb):
        hdr = deb[pos:pos + 60]
        if len(hdr) < 60:
            break
        name = hdr[0:16].decode(errors="ignore").strip().rstrip("/")
        try:
            size = int(hdr[48:58].strip())
        except ValueError:
            break
        raw  = deb[pos + 60 : pos + 60 + size]
        pos += 60 + size + (size % 2)

        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            return lzma.decompress(raw)
        if name.endswith(".gz"):
            return gzip.decompress(raw)
        if name.endswith(".zst"):
            return decompress_zst(raw)
        return raw  # bare .tar

    return None


def extract_files_from_tar(tar_bytes: bytes, wanted: list[str], dest: Path) -> list[str]:
    """Extract wanted file basenames from tar_bytes into dest.
    The special name 'node' is renamed to 'libnode.so'.
    Returns list of destination filenames that were written.

    Termux packages store .so files as versioned names (libcares.so.2) and
    unversioned names as symlinks.  We skip symlinks/hardlinks but DO match
    the versioned file names (libcares.so.2 matches wanted "libcares.so").
    """
    done: list[str] = []
    # Build a name→member map for all regular files (skip sym/hard links)
    members: list = []
    with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tf:
        for m in tf.getmembers():
            if m.issym() or m.islnk():
                continue   # symlinks have no extractable content
            if m.size < 1024:
                continue   # skip tiny files / metadata
            bname = Path(m.name).name
            for w in wanted:
                # "node" → exact name match only
                # "libfoo.so" → exact OR versioned (libfoo.so.2, libfoo.so.2.1.0)
                if w == "node":
                    match = (bname == "node")
                else:
                    match = (bname == w or bname.startswith(w + "."))
                if not match:
                    continue
                dest_name = "libnode.so" if w == "node" else w
                f = tf.extractfile(m)
                if not f:
                    break
                content = f.read()
                # ELF guard: never let a script overwrite a binary we already have
                is_elf = content[:4] == b"\x7fELF"
                out = dest / dest_name
                if out.exists() and not is_elf:
                    break  # keep existing ELF, skip this non-ELF hit
                out.write_bytes(content)
                kb = len(content) // 1024
                print(f"    ✅ {dest_name} ({kb} KB)")
                if dest_name not in done:
                    done.append(dest_name)
                break  # matched — move to next tar member
    return done


def patch_sonames(path: Path) -> None:
    """Apply SONAME_PATCHES to an ELF binary in-place (byte-exact, no size change).

    The replace bytes in SONAME_PATCHES may have fewer or more null bytes than
    the search bytes.  We auto-pad with nulls so the replacement is always the
    exact same length as the search string — ELF offsets never change.
    """
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        return
    original_size = len(data)
    changed: list[str] = []
    for search, replace_raw in SONAME_PATCHES:
        if len(replace_raw) > len(search):
            print(f"    ⚠ SONAME patch skipped (replace longer than search): {search!r}")
            continue
        # Pad replace to exactly len(search) bytes with null bytes
        replace = replace_raw.ljust(len(search), b'\x00')
        count = data.count(search)
        if count:
            data = data.replace(search, replace)
            clean = search.rstrip(b'\x00').decode(errors='replace')
            changed.append(f"{clean}×{count}")
    if len(data) != original_size:
        print(f"    ❌ BUG: file size changed in {path.name} — skipping write")
        return
    path.write_bytes(data)
    if changed:
        print(f"    🔧 Patched: {', '.join(changed)}")


def get_dt_needed(path: Path) -> list[str]:
    """Return DT_NEEDED library names from an ELF binary (pure Python, no readelf)."""
    try:
        data = path.read_bytes()
    except OSError:
        return []
    if data[:4] != b"\x7fELF":
        return []

    ei_class, ei_data = data[4], data[5]
    fmt = "<" if ei_data == 1 else ">"

    if ei_class == 2:   # 64-bit
        e_phoff     = struct.unpack_from(f"{fmt}Q", data, 32)[0]
        e_phentsize = struct.unpack_from(f"{fmt}H", data, 54)[0]
        e_phnum     = struct.unpack_from(f"{fmt}H", data, 56)[0]
    else:               # 32-bit
        e_phoff     = struct.unpack_from(f"{fmt}I", data, 28)[0]
        e_phentsize = struct.unpack_from(f"{fmt}H", data, 42)[0]
        e_phnum     = struct.unpack_from(f"{fmt}H", data, 44)[0]

    loads: list[tuple[int, int, int]] = []
    dyn_off = dyn_sz = 0
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(f"{fmt}I", data, o)[0]
        if ei_class == 2:
            p_off = struct.unpack_from(f"{fmt}Q", data, o + 8)[0]
            p_va  = struct.unpack_from(f"{fmt}Q", data, o + 16)[0]
            p_fsz = struct.unpack_from(f"{fmt}Q", data, o + 32)[0]
        else:
            p_off = struct.unpack_from(f"{fmt}I", data, o + 4)[0]
            p_va  = struct.unpack_from(f"{fmt}I", data, o + 8)[0]
            p_fsz = struct.unpack_from(f"{fmt}I", data, o + 16)[0]
        if p_type == 1:
            loads.append((p_va, p_off, p_fsz))
        elif p_type == 2:
            dyn_off, dyn_sz = p_off, p_fsz

    if not dyn_off:
        return []

    def va2off(va: int) -> int:
        for vaddr, foff, fsz in loads:
            if vaddr <= va < vaddr + fsz:
                return foff + (va - vaddr)
        return va

    esz = 16 if ei_class == 2 else 8
    dyn = data[dyn_off : dyn_off + dyn_sz]
    strtab_va: int | None = None
    needed_vals: list[int] = []
    for i in range(0, len(dyn) - esz + 1, esz):
        tag = struct.unpack_from(f"{fmt}{'q' if ei_class == 2 else 'i'}", dyn, i)[0]
        val = struct.unpack_from(f"{fmt}{'Q' if ei_class == 2 else 'I'}", dyn, i + (8 if ei_class == 2 else 4))[0]
        if tag == 0:
            break
        elif tag == 5:
            strtab_va = val
        elif tag == 1:
            needed_vals.append(val)

    if strtab_va is None:
        return []
    soff = va2off(strtab_va)
    strtab = data[soff : soff + 131_072]
    result: list[str] = []
    for v in needed_vals:
        end = strtab.find(b"\x00", v)
        if end < 0:
            end = v + 128
        result.append(strtab[v:end].decode(errors="replace"))
    return result


# ═══════════════════════════════════════════════════════════════════════════════
# Main logic
# ═══════════════════════════════════════════════════════════════════════════════

def load_termux_index_for_abi(abi: str) -> dict[str, dict[str, str]]:
    """Download and parse the Termux Packages.gz index for the given ABI."""
    arch = ABI_TO_ARCH[abi]
    url = get_packages_url(abi)
    print(f"📦 Loading Termux package index ({arch})...")
    raw  = fetch(url, "Packages.gz")
    text = gzip.decompress(raw).decode(errors="ignore")

    packages: dict[str, dict[str, str]] = {}
    current: dict[str, str] = {}
    for line in text.splitlines():
        if line.startswith("Package: "):
            current = {"Package": line[9:].strip()}
        elif ": " in line and current:
            key, _, val = line.partition(": ")
            current[key.strip()] = val.strip()
        elif not line.strip() and "Package" in current:
            packages[current["Package"]] = current
            current = {}
    if "Package" in current:
        packages[current["Package"]] = current

    print(f"  Loaded {len(packages)} packages.")
    return packages


def load_termux_index() -> dict[str, dict[str, str]]:
    """Download and parse the Termux aarch64 Packages.gz index (legacy default)."""
    return load_termux_index_for_abi("arm64-v8a")


def extract_git_binary(index: dict, dest: Path) -> bool:
    """Download the git package and extract the main binary as libgit.so,
    plus key helpers (git-remote-http, git-remote-https) needed for HTTPS clones."""
    print("\n  [git]")
    deb = download_deb("git", index)
    if deb is None:
        return False
    tar_bytes = extract_deb(deb)
    if tar_bytes is None:
        print("  ⚠ Could not extract git deb")
        return False

    GIT_BINARY_MAP = {
        "git":                "libgit.so",
        "git-remote-https":   "libgit-remote-https.so",
        "git-remote-http":    "libgit-remote-http.so",
        "git-receive-pack":   "libgit-receive-pack.so",
        "git-upload-pack":    "libgit-upload-pack.so",
        "git-upload-archive": "libgit-upload-archive.so",
    }

    extracted: list[str] = []
    with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tf:
        for m in tf.getmembers():
            if m.issym() or m.islnk():
                continue
            if m.size < 1024:
                continue
            bname = Path(m.name).name
            if bname not in GIT_BINARY_MAP:
                continue
            f = tf.extractfile(m)
            if not f:
                continue
            content = f.read()
            if content[:4] != b"\x7fELF":
                continue
            dest_name = GIT_BINARY_MAP[bname]
            out = dest / dest_name
            out.write_bytes(content)
            kb = len(content) // 1024
            print(f"    ✅ {dest_name} ({kb} KB)")
            patch_sonames(out)
            extracted.append(dest_name)

    if not extracted:
        print("  ⚠ No git binaries found in package")
        return False
    return True


def extract_git_templates(index: dict, assets_dest: Path) -> bool:
    """Extract git template files into app assets directory."""
    print("\n  [git templates]")
    deb = download_deb("git", index)
    if deb is None:
        return False
    tar_bytes = extract_deb(deb)
    if tar_bytes is None:
        return False

    templates_found = 0
    assets_dest.mkdir(parents=True, exist_ok=True)

    with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tf:
        for m in tf.getmembers():
            if "share/git-core/templates" not in m.name:
                continue
            if m.issym() or m.islnk():
                continue
            idx = m.name.find("templates/")
            if idx < 0:
                continue
            rel = m.name[idx + len("templates/"):]
            if not rel:
                continue
            out = assets_dest / rel
            if m.isdir():
                out.mkdir(parents=True, exist_ok=True)
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            f = tf.extractfile(m)
            if f:
                out.write_bytes(f.read())
                templates_found += 1

    if templates_found > 0:
        print(f"    ✅ Extracted {templates_found} template files → {assets_dest.relative_to(PROJECT_ROOT)}")
        return True
    else:
        print("  ⚠ No template files found in git package")
        return False


def setup_abi(abi: str, force: bool) -> None:
    """Download and stage all native libs for a single ABI."""
    dest_dir = get_dest_dir(abi)
    dest_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n{'=' * 50}")
    print(f"  Setting up: {abi}")
    print(f"{'=' * 50}")

    libnode = dest_dir / "libnode.so"
    if libnode.exists() and not force:
        print(f"ℹ  {libnode.relative_to(PROJECT_ROOT)} already exists.")
        print("   Use --force to re-download.")
        verify_deps(dest_dir)
        return

    index = load_termux_index_for_abi(abi)

    print("\n📦 Downloading packages...")
    for pkg_name, wanted_files in PACKAGES:
        print(f"\n  [{pkg_name}]")
        deb = download_deb(pkg_name, index)
        if deb is None:
            continue
        tar_bytes = extract_deb(deb)
        if tar_bytes is None:
            print(f"  ⚠ Could not extract data.tar from {pkg_name}")
            continue
        extracted = extract_files_from_tar(tar_bytes, wanted_files, dest_dir)
        if not extracted:
            print(f"  ⚠ No target files found in {pkg_name}")
            continue
        for fname in extracted:
            patch_sonames(dest_dir / fname)

    print(f"\n  [libc++_shared.so]")
    if not fetch_termux_libcxx(index, dest_dir):
        print("  ⚠ Could not fetch Termux libc++_shared.so — vtable errors may occur at runtime")

    # git binaries (ABI-specific)
    print("\n📦 Downloading git binary...")
    extract_git_binary(index, dest_dir)

    # git templates are ABI-independent — extract only once
    if not GIT_TEMPLATES_DIR.exists() or not any(GIT_TEMPLATES_DIR.iterdir()):
        extract_git_templates(index, GIT_TEMPLATES_DIR)
    else:
        print("\n  [git templates] Already extracted — skipping.")

    verify_deps(dest_dir)

    rel = dest_dir.relative_to(PROJECT_ROOT)
    print(f"""
══════════════════════════════════════════
✅  Native libs staged in:
    {rel}
══════════════════════════════════════════""")


def download_deb(pkg_name: str, index: dict) -> bytes | None:
    """Resolve and download a .deb file from the Termux index."""
    if pkg_name not in index:
        print(f"  ⚠ {pkg_name}: not found in index — skipping")
        return None
    filename = index[pkg_name].get("Filename", "")
    if not filename:
        print(f"  ⚠ {pkg_name}: no Filename in index — skipping")
        return None
    url = f"{TERMUX_APT}/{filename}"
    try:
        return fetch(url, f"{pkg_name}  ({Path(url).name})")
    except Exception as exc:
        print(f"  ⚠ Download failed for {pkg_name}: {exc}")
        return None


def fetch_termux_libcxx(index: dict, dest: Path) -> bool:
    """Replace NDK libc++_shared.so with Termux version that has vtable symbols."""
    for pkg_name in LIBCXX_PACKAGES:
        print(f"  [libc++_shared] trying package: {pkg_name}")
        deb = download_deb(pkg_name, index)
        if deb is None:
            continue
        tar_bytes = extract_deb(deb)
        if tar_bytes is None:
            continue
        try:
            with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tf:
                for m in tf.getmembers():
                    if "libc++_shared.so" not in m.name:
                        continue
                    if m.issym() or m.size < 100_000:
                        continue
                    f = tf.extractfile(m)
                    if f:
                        content = f.read()
                        out = dest / "libc++_shared.so"
                        out.write_bytes(content)
                        has_vtbl = b"_ZTVNSt6__ndk1" in content
                        kb = len(content) // 1024
                        print(f"    ✅ libc++_shared.so from {pkg_name} ({kb} KB, vtable={'✓' if has_vtbl else '✗ WARNING'})")
                        return True
        except Exception as exc:
            print(f"  ⚠ Error extracting libc++_shared from {pkg_name}: {exc}")
    return False


def verify_deps(dest: Path) -> None:
    """Print any unsatisfied DT_NEEDED entries."""
    existing = {f.name for f in dest.glob("*.so")}
    all_ok   = True
    print("\n🔍 Verifying DT_NEEDED dependencies...")
    for so in sorted(dest.glob("*.so")):
        for dep in get_dt_needed(so):
            if not dep:
                continue  # skip empty strings (ELF parser artifact)
            if dep not in existing and dep not in SYSTEM_LIBS:
                print(f"  ⚠ MISSING: {so.name} → {dep}")
                all_ok = False
    if all_ok:
        print("  ✅ All dependencies satisfied!")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download & stage native libs for STANDROID"
    )
    parser.add_argument(
        "--abi",
        default="arm64-v8a",
        choices=list(ABI_TO_ARCH.keys()) + ["all"],
        help="Target ABI (default: arm64-v8a). Use 'all' for both arm64-v8a and x86_64."
    )
    parser.add_argument("--force", action="store_true",
                        help="Re-download even if files already exist")
    args = parser.parse_args()

    abis = list(ABI_TO_ARCH.keys()) if args.abi == "all" else [args.abi]

    for abi in abis:
        setup_abi(abi, args.force)

    print(f"""
══════════════════════════════════════════
Next steps — build with Gradle:
    ./gradlew assembleArmDebug    # ARM APK  (arm64-v8a + armeabi-v7a)
    ./gradlew assembleX86Debug    # x86 APK  (x86_64)
    ./gradlew assembleDebug       # both

Install:
    adb install -r app/build/outputs/apk/arm/debug/app-arm-debug.apk
    adb install -r app/build/outputs/apk/x86/debug/app-x86-debug.apk
══════════════════════════════════════════""")


if __name__ == "__main__":
    main()
