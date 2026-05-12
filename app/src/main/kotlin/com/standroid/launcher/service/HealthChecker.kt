package com.standroid.launcher.service

import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Polls the SillyTavern root endpoint until it responds with HTTP 2xx
 * or the timeout elapses.
 *
 * Designed to be called from a coroutine:
 * ```
 * val ready = healthChecker.waitUntilReady(port = 8000)
 * ```
 */
class HealthChecker {

    private val TAG = "HealthChecker"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /**
     * Polls `/api/ping` every [intervalMs] ms up to [timeoutMs] total.
     * Returns true when ST answers HTTP 2xx, false on timeout.
     */
    suspend fun waitUntilReady(
        port: Int     = 8000,
        timeoutMs: Long  = 120_000,
        intervalMs: Long = 1_000,
    ): Boolean {
        // ST does not expose /api/ping — probe the root page instead.
        // A 200 OK on "/" means the web server is fully up.
        val url = "http://127.0.0.1:$port/"
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (probe(url)) {
                AppLogger.i(TAG, "ST ready on port $port after $attempt probe(s)")
                return true
            }
            AppLogger.v(TAG, "Probe #$attempt — not ready yet")
            delay(intervalMs)
        }

        AppLogger.w(TAG, "Timeout waiting for ST on port $port after ${timeoutMs / 1000}s")
        return false
    }

    /** Single HTTP probe — true if response code is 2xx. */
    private fun probe(url: String): Boolean = runCatching {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
