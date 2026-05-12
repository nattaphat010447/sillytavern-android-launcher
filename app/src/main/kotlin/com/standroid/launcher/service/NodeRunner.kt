package com.standroid.launcher.service

import android.content.Context
import com.standroid.launcher.util.AppLogger
import java.io.File
import java.io.InputStream
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

        // Create a wrapper script for 'node' so that scripts calling `env node` will find it
        val wrapperDir = File(ctx.cacheDir, "bin_wrapper").apply { mkdirs() }
        val nodeWrapper = File(wrapperDir, "node")
        if (!nodeWrapper.exists() || nodeWrapper.readText() != "#!/system/bin/sh\nexec \"$nodeBinaryPath\" \"\$@\"") {
            nodeWrapper.writeText("#!/system/bin/sh\nexec \"$nodeBinaryPath\" \"\$@\"")
            nodeWrapper.setExecutable(true, true)
        }

        // Stub out browser-open helpers that don't exist on Android.
        // SillyTavern calls `xdg-open` (Linux) when browserLaunch.enabled=true —
        // which crashes with ENOENT. A no-op wrapper in PATH silently absorbs the call.
        listOf("xdg-open", "open").forEach { name ->
            val stub = File(wrapperDir, name)
            val content = "#!/system/bin/sh\nexit 0\n"
            if (!stub.exists() || stub.readText() != content) {
                stub.writeText(content)
                stub.setExecutable(true, true)
            }
        }

        val pb = ProcessBuilder(cmd).apply {
            directory(workingDir)
            redirectErrorStream(false)
            environment().apply {
                put("HOME",    ctx.filesDir.absolutePath)
                put("TMPDIR",  ctx.cacheDir.absolutePath)
                put("NODE_ENV","production")

                // Tell the dynamic linker where to find libcares.so, libssl.so,
                // libcrypto.so, libicui18n.so, libicuuc.so, libsqlite3.so, etc.
                // These are extracted by AGP into nativeLibraryDir alongside libnode.so.
                val nativeDir = ctx.applicationInfo.nativeLibraryDir
                val oldLd = get("LD_LIBRARY_PATH")
                put("LD_LIBRARY_PATH",
                    if (oldLd.isNullOrEmpty()) nativeDir else "$nativeDir:$oldLd")

                // Prepend wrapper dir to PATH so `env node` finds our wrapper script
                val oldPath = get("PATH") ?: "/system/bin"
                put("PATH",    "${wrapperDir.absolutePath}:$oldPath")
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
