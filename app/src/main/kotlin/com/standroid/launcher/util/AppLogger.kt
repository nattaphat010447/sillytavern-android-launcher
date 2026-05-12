package com.standroid.launcher.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lightweight logger that:
 *  - delegates to [android.util.Log] for Logcat output
 *  - writes asynchronously to `filesDir/logs/standroid.log`
 *
 * All Node.js stdout/stderr lines are routed through this logger so they
 * appear in both Logcat and the on-device log file.
 */
object AppLogger {

    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L   // 2 MB — rotate beyond this
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private lateinit var logFile: File
    private val queue = LinkedBlockingQueue<String>(4096)
    private val running = AtomicBoolean(false)

    fun init(ctx: Context) {
        val logDir = File(ctx.filesDir, "logs").also { it.mkdirs() }
        logFile = File(logDir, "standroid.log")
        startWriter()
    }

    fun v(tag: String, msg: String) { log("V", tag, msg) }
    fun d(tag: String, msg: String) { log("D", tag, msg) }
    fun i(tag: String, msg: String) { log("I", tag, msg) }
    fun w(tag: String, msg: String) { log("W", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val full = if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg
        log("E", tag, full)
    }

    private fun log(level: String, tag: String, msg: String) {
        when (level) {
            "V" -> Log.v(tag, msg)
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
        }
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        queue.offer(line)   // non-blocking; drops if full
    }

    /** Returns the last [lines] lines from the log file (for LogViewer). */
    fun tail(lines: Int = 200): List<String> = runCatching {
        if (!::logFile.isInitialized || !logFile.exists()) return emptyList()
        logFile.readLines().takeLast(lines)
    }.getOrDefault(emptyList())

    private fun startWriter() {
        if (running.getAndSet(true)) return
        thread(isDaemon = true, name = "AppLogger-writer") {
            while (running.get()) {
                val line = queue.take()   // blocks until available
                try {
                    if (::logFile.isInitialized) {
                        if (logFile.length() > MAX_LOG_SIZE) logFile.delete()
                        PrintWriter(FileWriter(logFile, true)).use { it.println(line) }
                    }
                } catch (_: Exception) { /* swallow — log writing must never crash the app */ }
            }
        }
    }
}
