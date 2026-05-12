package com.standroid.launcher.setup

import android.content.Context
import com.standroid.launcher.service.NodeRunner
import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

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
     * Runs `npm ci` in [stDir].
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

            // args = [<npm-cli.js or "npm">, install, ...]
            // Using official update script flags
            val args = buildList {
                add(npmScript)
                addAll(listOf(
                    "install", 
                    "--no-save", 
                    "--no-audit", 
                    "--no-fund",
                    "--loglevel=error", 
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

            val exitCode = proc.waitFor()
            nodeRunner.setLogListener(null)

            if (exitCode == 0) {
                AppLogger.i(TAG, "npm install succeeded")
                onLog("Dependencies installed successfully.")
                return@withContext true
            }

            val backoffMs = (2.0.pow(attempt) * 5000).toLong()
            AppLogger.w(TAG, "npm install failed (exit $exitCode) — retry in ${backoffMs}ms")
            onLog("Install failed (attempt ${attempt + 1}). Retrying in ${backoffMs / 1000}s…")
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
     * 2. npm previously cached in filesDir from a prior download
     * 3. Download npm from the npm registry and cache it in filesDir
     * 4. Fall back to bare "npm" on PATH (unlikely to work on stock Android)
     *
     * This function performs blocking IO and must be called from an IO coroutine.
     */
    private fun resolveNpmScript(stDir: File, onLog: (String) -> Unit): String {
        // 1. Vendored npm inside the ST repository tree
        val vendored = File(stDir, "node_modules/npm/bin/npm-cli.js")
        if (vendored.exists()) return vendored.absolutePath

        // 2. npm previously downloaded and cached
        val cached = File(ctx.filesDir, "npm_pkg/package/bin/npm-cli.js")
        if (cached.exists()) return cached.absolutePath

        // 3. Download npm from npm registry
        AppLogger.i(TAG, "npm-cli.js not found — downloading npm from registry…")
        onLog("Downloading npm package manager…")
        val downloaded = downloadNpmFromRegistry(onLog)
        if (downloaded != null) return downloaded

        // 4. Last resort: hope PATH has npm (Termux / dev environment)
        AppLogger.w(TAG, "npm download failed — falling back to PATH npm")
        return "npm"
    }

    /**
     * Downloads the latest npm tarball from the public npm registry and extracts
     * it to [ctx.filesDir]/npm_pkg/.  Returns the path to npm-cli.js, or null.
     *
     * The registry tarball unpacks to   package/bin/npm-cli.js  inside the archive,
     * so the cached path is   filesDir/npm_pkg/package/bin/npm-cli.js.
     */
    private fun downloadNpmFromRegistry(onLog: (String) -> Unit): String? {
        return try {
            // --- 1. Fetch tarball URL from registry metadata ---
            val metaConn = URL("https://registry.npmjs.org/npm/latest")
                .openConnection() as HttpURLConnection
            metaConn.connectTimeout = 15_000
            metaConn.readTimeout   = 30_000
            val meta = metaConn.inputStream.bufferedReader().readText()

            // Parse "tarball":"<url>" with a simple index search (no JSON library needed)
            val key   = "\"tarball\":\""
            val start = meta.indexOf(key)
            if (start < 0) {
                AppLogger.e(TAG, "tarball URL not found in npm registry response")
                return null
            }
            val urlStart  = start + key.length
            val urlEnd    = meta.indexOf('"', urlStart)
            val tarballUrl = meta.substring(urlStart, urlEnd)
            AppLogger.i(TAG, "npm tarball: $tarballUrl")

            // --- 2. Download tarball ---
            onLog("Downloading npm from $tarballUrl …")
            val dlConn = URL(tarballUrl).openConnection() as HttpURLConnection
            dlConn.connectTimeout = 15_000
            dlConn.readTimeout   = 120_000
            val bytes = dlConn.inputStream.readBytes()
            AppLogger.i(TAG, "npm tarball downloaded: ${bytes.size} bytes")

            // --- 3. Extract .tgz ---
            val destDir = File(ctx.filesDir, "npm_pkg")
            onLog("Extracting npm…")
            extractTgz(bytes, destDir)

            // --- 4. Verify ---
            val cliJs = File(destDir, "package/bin/npm-cli.js")
            if (cliJs.exists()) {
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
     * Extracts a .tgz archive ([data]) into [destDir].
     *
     * Supports regular files and directories; symlinks are skipped.
     * Uses only standard Java IO — no external tar library needed.
     */
    private fun extractTgz(data: ByteArray, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val hdr = ByteArray(512)

            while (true) {
                // Read exactly 512-byte header
                var off = 0
                while (off < 512) {
                    val r = gz.read(hdr, off, 512 - off)
                    if (r < 0) return
                    off += r
                }
                // Two consecutive all-zero blocks = end-of-archive
                if (hdr.all { it == 0.toByte() }) break

                val name      = hdr.sliceArray(0..99)
                    .toString(Charsets.UTF_8).trimEnd('\u0000')
                // tar size field is octal digits + spaces/nulls — keep only '0'..'7'
                val sizeOctal = hdr.sliceArray(124..135)
                    .toString(Charsets.UTF_8).filter { it in '0'..'7' }
                val typeFlag  = hdr[156].toInt().toChar()
                val fileSize  = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)

                // Blocks used by this entry (for alignment skipping)
                val blockBytes = ((fileSize + 511) / 512) * 512

                when {
                    typeFlag == '5' || name.endsWith('/') -> {
                        // Directory
                        File(destDir, name).mkdirs()
                    }
                    typeFlag == '0' || typeFlag == '\u0000' || typeFlag == '7' -> {
                        // Regular file
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        var remaining = fileSize
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val r = gz.read(buf, 0, toRead)
                                if (r < 0) return
                                out.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                        // Skip padding to next 512-byte boundary
                        val padding = blockBytes - fileSize
                        if (padding > 0) skipFully(gz, padding)
                    }
                    else -> {
                        // Symlink, hard-link, etc — skip file data
                        if (blockBytes > 0) skipFully(gz, blockBytes)
                    }
                }
            }
        }
    }

    /** Reads and discards exactly [n] bytes from [stream]. */
    private fun skipFully(stream: GZIPInputStream, n: Long) {
        val buf = ByteArray(4096)
        var remaining = n
        while (remaining > 0) {
            val r = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) return
            remaining -= r
        }
    }
}

/** Kotlin doesn't have Double.pow extension in stdlib for older targets — provide one. */
private fun Double.pow(n: Int): Double {
    var result = 1.0
    repeat(n) { result *= this }
    return result
}
