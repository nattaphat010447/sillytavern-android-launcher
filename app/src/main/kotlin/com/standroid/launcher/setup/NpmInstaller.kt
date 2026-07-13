package com.standroid.launcher.setup

import android.content.Context
import com.standroid.launcher.service.NodeRunner
import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs `npm install --omit=dev` inside the SillyTavern directory using the
 * prebuilt Node binary.
 *
 * Includes retry logic (up to [maxAttempts] attempts with exponential back-off)
 * and automatic npm download if the vendored npm-cli.js is not yet present.
 *
 * Note: [NodeRunner.start] prepends the node binary path automatically —
 * args passed here must NOT include the node binary path.
 */
class NpmInstaller(private val ctx: Context) {

    private val TAG = "NpmInstaller"
    private val nodeRunner = NodeRunner(ctx)

    /**
     * Runs `npm install --omit=dev` in [stDir].
     * Streams output lines to [onLog].
     *
     * @return true on success, false if all attempts fail.
     */
    suspend fun install(
        stDir: File,
        onLog: (String) -> Unit,
        maxAttempts: Int = 3,
    ): Boolean = withContext(Dispatchers.IO) {
        // Find (or download) npm-cli.js — blocking IO is fine inside withContext(IO)
        val npmScript = resolveNpmScript(stDir, onLog)
        AppLogger.i(TAG, "npm script resolved to: $npmScript")

        repeat(maxAttempts) { attempt ->
            AppLogger.i(TAG, "npm install — attempt ${attempt + 1}/$maxAttempts")
            onLog("npm install — attempt ${attempt + 1}/$maxAttempts")

            val args = buildList {
                add(npmScript)
                addAll(listOf(
                    "install",
                    "--no-save",
                    "--no-audit",
                    "--no-fund",
                    "--loglevel=http",
                    "--no-progress",
                    "--omit=dev",
                    "--ignore-scripts"
                ))
            }

            nodeRunner.setLogListener { line ->
                onLog(line)
                AppLogger.d(TAG, line)
            }

            val proc = nodeRunner.start(workingDir = stDir, args = args) ?: run {
                AppLogger.e(TAG, "Node binary not available")
                return@withContext false
            }

            val exitCode = try {
                runInterruptible { proc.waitFor() }
            } catch (e: CancellationException) {
                proc.destroy()
                nodeRunner.setLogListener(null)
                throw e
            }
            nodeRunner.setLogListener(null)

            if (exitCode == 0) {
                AppLogger.i(TAG, "npm install succeeded")
                onLog("Dependencies installed successfully.")
                return@withContext true
            }

            val backoffMs = (2.0.pow(attempt) * 5000).toLong()
            AppLogger.w(TAG, "npm install failed (exit $exitCode) — retry in ${backoffMs}ms")
            onLog("Install failed (attempt ${attempt + 1}). Retrying in ${backoffMs / 1000}s\u2026")
            delay(backoffMs)
        }

        AppLogger.e(TAG, "npm install failed after $maxAttempts attempts")
        false
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Returns an absolute path to npm-cli.js (or the string "npm" as last resort).
     *
     * Priority:
     * 1. npm vendored inside the ST repository (after first successful install)
     * 2. npm previously cached in filesDir — only if integrity check passes
     * 3. Download npm from the npm registry and cache it in filesDir
     * 4. Fall back to bare "npm" on PATH (unlikely to work on stock Android)
     */
    private fun resolveNpmScript(stDir: File, onLog: (String) -> Unit): String {
        // 1. Vendored npm inside the ST repository tree
        val vendored = File(stDir, "node_modules/npm/bin/npm-cli.js")
        if (vendored.exists()) return vendored.absolutePath

        // 2. Previously downloaded and cached — verify integrity before trusting it
        val npmPkgDir = File(ctx.filesDir, "npm_pkg")
        val cached = File(npmPkgDir, "package/bin/npm-cli.js")
        if (cached.exists()) {
            if (isNpmCacheIntact(npmPkgDir)) {
                AppLogger.i(TAG, "Using cached npm at ${cached.absolutePath}")
                return cached.absolutePath
            } else {
                AppLogger.w(TAG, "Cached npm_pkg failed integrity check — deleting and re-downloading")
                onLog("Cached npm package appears corrupt. Re-downloading\u2026")
                npmPkgDir.deleteRecursively()
            }
        }

        // 3. Download from npm registry
        AppLogger.i(TAG, "npm-cli.js not found — downloading npm from registry\u2026")
        onLog("Downloading npm package manager\u2026")
        val downloaded = downloadNpmFromRegistry(onLog)
        if (downloaded != null) return downloaded

        // 4. Last resort
        AppLogger.w(TAG, "npm download failed — falling back to PATH npm")
        return "npm"
    }

    /**
     * Checks that the extracted npm package:
     *  a) was extracted by the current extractor version (version stamp), AND
     *  b) contains the nested modules that Node.js actually requires at runtime.
     *
     * The version stamp (`.extract_version = 3`) is written after every successful
     * extraction with the TarArchiveInputStream-based extractor.  Any cache missing
     * the stamp or carrying an older version number is treated as corrupt and will
     * be deleted so a fresh download runs.
     */
    private fun isNpmCacheIntact(npmPkgDir: File): Boolean {
        // Increment when the extractor changes in a way that affects on-disk layout.
        val EXTRACT_VERSION = 3
        val stampFile = File(npmPkgDir, ".extract_version")
        val stampOk = runCatching {
            stampFile.readText().trim().toInt() == EXTRACT_VERSION
        }.getOrDefault(false)
        if (!stampOk) {
            AppLogger.w(TAG, "npm cache stamp missing or outdated — treating as corrupt")
            return false
        }

        val nodeModules = File(npmPkgDir, "package/node_modules")
        if (!nodeModules.isDirectory) return false

        // Verify the exact nested paths that Node.js resolves at runtime per the
        // MODULE_NOT_FOUND require stack in the error log:
        //   @npmcli/installed-package-contents/node_modules/npm-bundled
        //     → require('npm-normalize-package-bin')
        //   which Node looks up in:
        //     @npmcli/installed-package-contents/node_modules/npm-normalize-package-bin
        val criticalPaths = listOf(
            "@npmcli/installed-package-contents/node_modules/npm-normalize-package-bin",
            "@npmcli/installed-package-contents/node_modules/npm-bundled",
            "@npmcli/installed-package-contents"
        )
        return criticalPaths.all { rel ->
            val dir = File(nodeModules, rel)
            // toRealPath() follows symlinks — throws if the target is broken or missing
            val ok = runCatching { dir.toPath().toRealPath() }.isSuccess && dir.exists()
            if (!ok) AppLogger.w(TAG, "npm integrity: missing $rel")
            ok
        }
    }

    /**
     * Downloads the latest npm tarball from the public npm registry and extracts
     * it to [ctx.filesDir]/npm_pkg/.  Returns the path to npm-cli.js, or null.
     */
    private fun downloadNpmFromRegistry(onLog: (String) -> Unit): String? {
        return try {
            // 1. Fetch tarball URL from registry metadata
            val metaConn = URL("https://registry.npmjs.org/npm/latest")
                .openConnection() as HttpURLConnection
            metaConn.connectTimeout = 15_000
            metaConn.readTimeout   = 30_000
            val meta = metaConn.inputStream.bufferedReader().readText()

            val key   = "\"tarball\":\""
            val start = meta.indexOf(key)
            if (start < 0) {
                AppLogger.e(TAG, "tarball URL not found in npm registry response")
                return null
            }
            val urlStart  = start + key.length
            val tarballUrl = meta.substring(urlStart, meta.indexOf('"', urlStart))
            AppLogger.i(TAG, "npm tarball: $tarballUrl")

            // 2. Download tarball
            onLog("Downloading npm from $tarballUrl \u2026")
            val dlConn = URL(tarballUrl).openConnection() as HttpURLConnection
            dlConn.connectTimeout = 15_000
            dlConn.readTimeout   = 120_000
            val bytes = dlConn.inputStream.readBytes()
            AppLogger.i(TAG, "npm tarball downloaded: ${bytes.size} bytes")

            // 3. Extract .tgz
            val destDir = File(ctx.filesDir, "npm_pkg")
            onLog("Extracting npm\u2026")
            extractTgz(bytes, destDir)

            // 4. Verify and stamp
            val cliJs = File(destDir, "package/bin/npm-cli.js")
            if (cliJs.exists()) {
                runCatching { File(destDir, ".extract_version").writeText("3") }
                AppLogger.i(TAG, "npm cached at ${cliJs.absolutePath}")
                onLog("npm ready.")
                cliJs.absolutePath
            } else {
                AppLogger.e(TAG, "npm-cli.js not found after extraction")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "npm download failed: ${e.message}")
            null
        }
    }

    /**
     * Extracts a .tgz archive ([data]) into [destDir] using Apache Commons Compress.
     *
     * Commons Compress handles all tar format variants correctly:
     *  - **PAX extended headers** (typeFlag `'x'`/`'g'`) — used by npm's `tar` module
     *    for paths longer than 100 characters.  The hand-rolled parser silently
     *    truncated these, causing `MODULE_NOT_FOUND` for deeply-nested modules.
     *  - **GNU LongLink** (`@LongLink`) — older GNU long-name extension
     *  - **USTAR prefix field** — classic 155+1+100 byte split
     *  - **Symbolic links** — `entry.isSymbolicLink` → `createSymlinkSafe()`
     *
     * Security: symlink targets that escape [destDir] are skipped.
     */
    private fun extractTgz(data: ByteArray, destDir: File) {
        destDir.mkdirs()
        val destPath: Path = destDir.canonicalFile.toPath()

        TarArchiveInputStream(
            GzipCompressorInputStream(ByteArrayInputStream(data))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val entryName = entry.name

                when {
                    entry.isDirectory -> {
                        File(destDir, entryName).mkdirs()
                    }
                    entry.isSymbolicLink -> {
                        // linkName is the symlink target (may be relative)
                        if (entryName.isNotEmpty() && entry.linkName.isNotEmpty()) {
                            createSymlinkSafe(destPath, entryName, entry.linkName)
                        }
                    }
                    else -> {
                        // Regular file (also catches hard links by copying content)
                        val outFile = File(destDir, entryName)
                        // Path-traversal guard
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                            outFile.canonicalPath != destDir.canonicalPath) {
                            AppLogger.w(TAG, "Skipping entry outside destDir: $entryName")
                            entry = tar.nextEntry
                            continue
                        }
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> tar.copyTo(out) }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    /**
     * Creates a symlink at [destRoot]/[entryName] pointing to [linkTarget].
     *
     * [linkTarget] may be relative (e.g. `../foo/index.js`), resolved relative
     * to the symlink's own parent — same semantics as the OS at runtime.
     *
     * Security: symlinks whose resolved target escapes [destRoot] are skipped.
     */
    private fun createSymlinkSafe(destRoot: Path, entryName: String, linkTarget: String) {
        runCatching {
            val linkPath: Path = destRoot.resolve(entryName).normalize()

            if (!linkPath.startsWith(destRoot)) {
                AppLogger.w(TAG, "Skipping symlink outside destDir: $entryName")
                return
            }

            linkPath.parent?.let { Files.createDirectories(it) }

            val resolvedTarget: Path = (linkPath.parent
                ?.resolve(linkTarget) ?: destRoot.resolve(linkTarget)).normalize()

            if (!resolvedTarget.startsWith(destRoot)) {
                AppLogger.w(TAG, "Skipping symlink that escapes destDir: $entryName -> $linkTarget")
                return
            }

            Files.deleteIfExists(linkPath)
            Files.createSymbolicLink(linkPath, linkPath.fileSystem.getPath(linkTarget))
            AppLogger.d(TAG, "Symlink: $entryName -> $linkTarget")
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to create symlink $entryName -> $linkTarget: ${e.message}")
        }
    }
}

/** Kotlin doesn't have Double.pow extension in stdlib for older targets — provide one. */
private fun Double.pow(n: Int): Double {
    var result = 1.0
    repeat(n) { result *= this }
    return result
}
