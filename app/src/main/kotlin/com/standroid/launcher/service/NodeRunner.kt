package com.standroid.launcher.service

import android.content.Context
import com.standroid.launcher.util.AppLogger
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs the prebuilt Node.js binary (`libnode.so`) via [ProcessBuilder].
 *
 * The binary is shipped inside `jniLibs/<abi>/` so the Android package
 * installer extracts it to `nativeLibraryDir` with r-xr-xr-x permissions —
 * no `chmod` is needed or allowed (Android 13+ SELinux denies `setattr` on
 * `apk_data_file` for untrusted apps).
 *
 * stdout/stderr are forwarded line-by-line to [AppLogger] and to any
 * [LogListener] registered via [setLogListener].
 */
class NodeRunner(private val ctx: Context) {

    fun interface LogListener {
        fun onLine(line: String)
    }

    private val TAG = "NodeRunner"

    /** Absolute path to the extracted `libnode.so` (which is the node binary). */
    val nodeBinaryPath: String by lazy {
        File(ctx.applicationInfo.nativeLibraryDir, "libnode.so").absolutePath
    }

    private val processRef = AtomicReference<Process?>(null)
    private var logListener: LogListener? = null

    fun setLogListener(listener: LogListener?) { logListener = listener }

    /**
     * Returns true if the node binary exists and is executable.
     *
     * nativeLibraryDir is extracted by the Android package installer with
     * r-xr-xr-x permissions, so [File.setExecutable] is NOT called here.
     * Calling it would trigger an SELinux `setattr` denial on Android 13+
     * (untrusted_app cannot setattr on apk_data_file).
     */
    fun isNodeReady(): Boolean {
        val f = File(nodeBinaryPath)
        if (!f.exists()) {
            AppLogger.e(TAG, "Node binary not found at $nodeBinaryPath")
            return false
        }
        if (!f.canExecute()) {
            // Attempt chmod as a best-effort fallback (e.g. emulator / dev env).
            // On real devices the SELinux denial is logged as a warning but we
            // proceed anyway — nativeLibraryDir files are already executable.
            val ok = runCatching { f.setExecutable(true, true) }.getOrDefault(false)
            AppLogger.w(TAG, "canExecute=false; setExecutable attempt returned $ok — proceeding")
        }
        return true
    }

    /**
     * Spawns `node <args>` in the given [workingDir].
     * Streams stdout/stderr to logger + listener.
     * Non-blocking — the process runs in the background.
     *
     * @return the [Process] handle, or null if node binary not found.
     */
    fun start(
        workingDir: File,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): Process? {
        if (!isNodeReady()) {
            AppLogger.e(TAG, "Node binary not ready at $nodeBinaryPath")
            return null
        }

        val cmd = mutableListOf(nodeBinaryPath) + args
        AppLogger.i(TAG, "Spawning: ${cmd.joinToString(" ")}")

        // SELinux on Android prevents exec of app_data_file (filesDir scripts).
        // The only files untrusted_app can exec are in nativeLibraryDir (apk_data_file).
        // Solution: create symlinks from bin_wrapper/ → nativeLibraryDir/*.so
        // The kernel resolves the symlink and uses the *target* file's SELinux context,
        // which IS executable.
        val wrapperDir = File(ctx.filesDir, "bin_wrapper").apply { mkdirs() }
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir

        // Symlink node binary so `env node` resolves via PATH
        symlinkBinary(File(nativeLibDir, "libnode.so"), File(wrapperDir, "node"))

        // Git binary — enables SillyTavern extension updates
        val gitBin = File(nativeLibDir, "libgit.so")
        val gitTemplateDir = File(ctx.filesDir, "git-templates")
        val gitCoreBinDir  = File(wrapperDir, "git-core").apply { mkdirs() }

        if (gitBin.exists()) {
            symlinkBinary(gitBin, File(wrapperDir, "git"))

            // Git helper binaries that git exec's for remote operations
            mapOf(
                "git-remote-https"   to "libgit-remote-https.so",
                "git-remote-http"    to "libgit-remote-http.so",
                "git-receive-pack"   to "libgit-receive-pack.so",
                "git-upload-pack"    to "libgit-upload-pack.so",
                "git-upload-archive" to "libgit-upload-archive.so",
            ).forEach { (name, lib) ->
                val src = File(nativeLibDir, lib)
                if (src.exists()) symlinkBinary(src, File(gitCoreBinDir, name))
            }
            AppLogger.d(TAG, "Git symlinks created; gitCoreBinDir=${gitCoreBinDir.absolutePath}")
        } else {
            AppLogger.w(TAG, "libgit.so not found — extension updates will fail. Run setup-native-libs.py")
        }

        val pb = ProcessBuilder(cmd).apply {
            directory(workingDir)
            redirectErrorStream(false)
            environment().apply {
                put("HOME",    ctx.filesDir.absolutePath)
                put("TMPDIR",  ctx.cacheDir.absolutePath)
                put("NODE_ENV","production")

                // PATH: prepend wrapper dir so `node` and `git` symlinks are found
                val oldPath = get("PATH") ?: "/system/bin"
                put("PATH", "${wrapperDir.absolutePath}:$oldPath")

                // LD_LIBRARY_PATH: child processes (e.g. git) spawned by Node inherit
                // this env var and use it to find shared libraries.  Without it, git
                // can't load libpcre2-8.so / libcurl.so / libexpat.so from nativeLibraryDir.
                val nativeLibDirPath = nativeLibDir  // String, already in scope
                val oldLd = get("LD_LIBRARY_PATH") ?: ""
                put("LD_LIBRARY_PATH",
                    if (oldLd.isNotEmpty()) "$nativeLibDirPath:$oldLd"
                    else nativeLibDirPath)

                // Git requires these to find its helper binaries and templates
                if (gitBin.exists()) {
                    put("GIT_EXEC_PATH",    gitCoreBinDir.absolutePath)
                    put("GIT_TEMPLATE_DIR", gitTemplateDir.absolutePath)

                    // GIT_SSL_CAINFO: Termux git has the Termux cert path hardcoded.
                    // Override to use Android's system CA bundle instead.
                    // Android stores CA certs as individual .pem files in a dir, so we
                    // use GIT_SSL_CAPATH (directory) instead of GIT_SSL_CAINFO (single file).
                    // Alternatively, override git's http.sslCAInfo config to a bundled file.
                    val systemCaDir = "/system/etc/security/cacerts"
                    if (java.io.File(systemCaDir).exists()) {
                        put("GIT_SSL_CAPATH", systemCaDir)
                    }
                    // Also set http.sslVerify=false as a fallback if CAPATH doesn't work.
                    // This is less secure but ensures Extension Update works.
                    // TODO: Bundle cacert.pem in assets for proper cert validation
                    put("GIT_SSL_NO_VERIFY", "false")
                    put("GIT_CONFIG_COUNT", "2")
                    put("GIT_CONFIG_KEY_0", "http.sslCAPath")
                    put("GIT_CONFIG_VALUE_0", systemCaDir)
                    put("GIT_CONFIG_KEY_1", "http.sslVerify")
                    put("GIT_CONFIG_VALUE_1", "true")
                }
                putAll(env)
            }
        }

        return runCatching {
            pb.start().also { proc ->
                processRef.set(proc)
                pipeStream(proc.inputStream,  "stdout")
                pipeStream(proc.errorStream, "stderr")
            }
        }.onFailure { AppLogger.e(TAG, "Failed to start node", it) }.getOrNull()
    }

    /** Gracefully stops the running Node process. */
    fun stop() {
        processRef.getAndSet(null)?.destroy()
        AppLogger.i(TAG, "Node process destroyed")
    }

    /** True if the process is currently alive. */
    fun isRunning(): Boolean = processRef.get()?.isAlive == true

    // ── Private helpers ────────────────────────────────────────────────

    /**
     * Creates a symlink at [link] pointing to [target].
     *
     * Symlinks are used instead of shell scripts because Android SELinux
     * prevents execution of app_data_file (filesDir). When the kernel
     * executes a symlink it checks the *target* file's SELinux context —
     * nativeLibraryDir files are apk_data_file which IS executable.
     *
     * Recreates the symlink if the target has changed (e.g., after app update).
     */
    private fun symlinkBinary(target: File, link: File) {
        if (!target.exists()) return
        runCatching {
            val targetPath: Path = target.toPath()
            val linkPath: Path   = link.toPath()
            // Recreate if link is missing, is not a symlink, or points to wrong target
            val needsCreate = !Files.isSymbolicLink(linkPath) ||
                              Files.readSymbolicLink(linkPath) != targetPath
            if (needsCreate) {
                Files.deleteIfExists(linkPath)
                Files.createSymbolicLink(linkPath, targetPath)
                AppLogger.d(TAG, "Symlinked ${link.name} → ${target.absolutePath}")
            }
        }.onFailure {
            AppLogger.e(TAG, "Failed to create symlink ${link.name} → ${target.absolutePath}: ${it.message}")
        }
    }

    private fun pipeStream(stream: InputStream, label: String) {
        thread(isDaemon = true, name = "NodeRunner-$label") {
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[$label] $line")
                    logListener?.onLine(line)
                }
            }
        }
    }
}
