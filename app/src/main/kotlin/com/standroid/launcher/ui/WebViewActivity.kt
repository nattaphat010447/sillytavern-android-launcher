package com.standroid.launcher.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.standroid.launcher.databinding.ActivityWebviewBinding
import com.standroid.launcher.service.NodeRunner
import com.standroid.launcher.service.STForegroundService
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs
import kotlinx.coroutines.launch

/**
 * Full-screen WebView that hosts the SillyTavern UI.
 *
 * Flow:
 *  1. Show loading overlay with a real-time Node.js log panel
 *  2. Attempt to load the ST URL immediately
 *  3. On connection error (server still starting), wait 3 s and retry silently
 *  4. Once the page loads successfully, hide the overlay
 *
 * Back-button: navigates within the WebView history if possible.
 * Pull-to-refresh: triggers [WebView.reload].
 */
class WebViewActivity : AppCompatActivity() {

    private val TAG = "WebViewActivity"
    private lateinit var binding: ActivityWebviewBinding

    // ── Server-stopped handling ───────────────────────────────────────

    /**
     * Receives broadcast from STForegroundService when the server is intentionally stopped
     * (e.g. user taps "Stop" in the notification). Closes this activity so the user
     * lands back on MainActivity.
     */
    private val serverStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLogger.i(TAG, "Server stopped broadcast received — closing WebView")
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            serverStoppedReceiver,
            IntentFilter(STForegroundService.BROADCAST_SERVER_STOPPED)
        )
        // Register for Node log lines while the activity is visible
        STForegroundService.nodeLogListener = NodeRunner.LogListener { line ->
            runOnUiThread { appendNodeLog(line) }
        }
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serverStoppedReceiver)
        // Unregister so we don't leak a reference to a destroyed activity
        STForegroundService.nodeLogListener = null
    }

    /**
     * Covers the background case: if the user stopped the server while this activity
     * was in the back-stack, finish as soon as we resume.
     * Also resumes WebView rendering/JS timers.
     */
    override fun onResume() {
        super.onResume()
        if (!STForegroundService.isServiceRunning) {
            AppLogger.i(TAG, "Service not running on resume — closing WebView")
            finish()
            return
        }
        binding.webView.onResume()
    }

    /**
     * Pause WebView rendering and JS timers when the activity goes to background.
     * This reduces CPU/battery usage while the user is in another app.
     */
    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        configureWebView(binding.webView)
        setupSwipeRefresh()
        setupNodeLogScroll()
        loadImmediately()
    }

    // ── Back navigation ───────────────────────────────────────────────

    @Deprecated("Required override for minSdk < 33 compatibility shim")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // File chooser support
    private var fileUploadCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = when {
                data?.data != null -> arrayOf(data.data!!)
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                else -> null
            }
            fileUploadCallback?.onReceiveValue(uris)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    // ── Download support (SAF + Blob handling) ────────────────────────
    
    // Holds pending download info for HTTP URLs
    private data class PendingHttpDownload(
        val url: String,
        val userAgent: String,
        val mimetype: String
    )
    private var pendingHttpDownload: PendingHttpDownload? = null
    
    // Holds pending download info for Blob URLs
    private data class PendingBlobDownload(
        val base64Data: String,
        val mimetype: String
    )
    private var pendingBlobDownload: PendingBlobDownload? = null
    
    // SAF launcher for saving files
    private val saveFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Check which type of download we're handling
                    val httpDownload = pendingHttpDownload
                    val blobDownload = pendingBlobDownload
                    
                    when {
                        httpDownload != null -> {
                            saveHttpToUri(uri, httpDownload.url, httpDownload.userAgent)
                            pendingHttpDownload = null
                        }
                        blobDownload != null -> {
                            saveBase64ToUri(uri, blobDownload.base64Data, blobDownload.mimetype)
                            pendingBlobDownload = null
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@WebViewActivity,
                                    "No pending download",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@WebViewActivity,
                            "Save failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    AppLogger.e(TAG, "Save exception", e)
                }
            }
        } else {
            // User cancelled
            pendingHttpDownload = null
            pendingBlobDownload = null
        }
    }
    
    /**
     * JavaScript Interface for handling blob downloads.
     * Called from injected JavaScript that reads blob data and converts to base64.
     */
    private inner class BlobDownloader {
        @JavascriptInterface
        fun onBase64DataReady(base64: String, filename: String, mimetype: String) {
            AppLogger.i(TAG, "Blob data ready: $filename (${base64.length} chars)")
            
            // Store the blob data
            pendingBlobDownload = PendingBlobDownload(base64, mimetype)
            
            // Launch SAF dialog on main thread
            runOnUiThread {
                try {
                    saveFileLauncher.launch(filename)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to launch SAF dialog", e)
                    Toast.makeText(
                        this@WebViewActivity,
                        "Failed to open save dialog: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    pendingBlobDownload = null
                }
            }
        }
    }

    // ── Node log helpers ──────────────────────────────────────────────

    /** Auto-scroll to bottom unless the user has manually scrolled up. */
    private var autoScrollNodeLog = true

    private fun setupNodeLogScroll() {
        binding.scrollNodeLog.viewTreeObserver.addOnScrollChangedListener {
            val sv = binding.scrollNodeLog
            val child = sv.getChildAt(0) ?: return@addOnScrollChangedListener
            autoScrollNodeLog = sv.scrollY + sv.height >= child.height - 8
        }
    }

    private fun appendNodeLog(line: String) {
        binding.tvNodeLog.append("$line\n")
        if (autoScrollNodeLog) {
            binding.scrollNodeLog.post {
                binding.scrollNodeLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        // Only allow pull-to-refresh when the WebView is visible (not during initial load)
        binding.swipeRefresh.setOnRefreshListener {
            AppLogger.d(TAG, "Pull-to-refresh triggered")
            binding.webView.reload()
        }
        // Disable swipe-to-refresh while the loading overlay is shown
        binding.swipeRefresh.isEnabled = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        // Add JavaScript Interface for blob downloads
        wv.addJavascriptInterface(BlobDownloader(), "AndroidBlobDownloader")
        
        wv.webViewClient  = object : WebViewClient() {
            
            // Flag to track if the current page load encountered an error
            private var pageHasError = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Reset flag when a new page load starts
                pageHasError = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                AppLogger.d(TAG, "Page loaded: $url (hasError=$pageHasError)")
                
                // Stop the swipe-refresh spinner (covers both manual refresh and auto-reload)
                binding.swipeRefresh.isRefreshing = false

                // Only hide the loading screen if the page loaded successfully without any connection errors
                if (!pageHasError && url.startsWith("http://127.0.0.1")) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                    // Enable pull-to-refresh now that the page is loaded
                    binding.swipeRefresh.isEnabled = true
                    // B1: Switch to normal cache mode after first successful load.
                    // LOAD_NO_CACHE was needed to avoid stale error pages during webpack compile,
                    // but keeping it permanently wastes bandwidth on every navigation.
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    
                    // Inject blob registry to capture blob URLs before they expire
                    injectBlobRegistry(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                AppLogger.w(TAG, "WebView error ($errorCode): $description")
                
                if (failingUrl?.startsWith("http://127.0.0.1") == true) {
                    pageHasError = true

                    // Stop the swipe-refresh spinner on error too
                    binding.swipeRefresh.isRefreshing = false

                    // Keep the loading screen visible, hide the webview (so user doesn't see the default error page)
                    binding.loadingOverlay.visibility = View.VISIBLE
                    binding.webView.visibility = View.INVISIBLE
                    // Disable pull-to-refresh while showing the loading overlay
                    binding.swipeRefresh.isEnabled = false
                    
                    // Wait 3 seconds and silently try loading the page again
                    view?.postDelayed({ view.reload() }, 3000)
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                // Always use a generic picker intent to allow all file types
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                
                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    com.standroid.launcher.util.AppLogger.e(TAG, "Cannot open file picker", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
            
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let { msg ->
                    val logMsg = "[WebView Console] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                    when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> AppLogger.e(TAG, logMsg)
                        ConsoleMessage.MessageLevel.WARNING -> AppLogger.w(TAG, logMsg)
                        ConsoleMessage.MessageLevel.DEBUG -> AppLogger.d(TAG, logMsg)
                        else -> AppLogger.i(TAG, logMsg)
                    }
                }
                return true
            }
        }

        wv.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            @Suppress("DEPRECATION")
            databaseEnabled        = true
            allowFileAccess        = false   // No local file access needed
            mixedContentMode       = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Bypass cache so we don't get stuck on a cached error page during webpack
            cacheMode              = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            // Allow localStorage / IndexedDB to work (needed by ST)
            @Suppress("DEPRECATION")
            saveFormData           = false
        }
        
        // ── Download listener — handles character card / file exports ──
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        // Enable hardware acceleration features in WebView
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // B4: waivedWhenNotVisible=false — keep renderer at IMPORTANT priority even when
            // the activity is in the background so the Node server response is processed quickly
            // when the user returns to the app.
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
    }

    /**
     * Injects JavaScript to override URL.createObjectURL() and store blob references.
     * This prevents blob URLs from expiring before we can download them.
     */
    private fun injectBlobRegistry(webView: WebView) {
        val js = """
            (function() {
                if (window.blobRegistry) {
                    console.log('[Android] Blob registry already initialized');
                    return;
                }
                
                console.log('[Android] Initializing blob registry');
                window.blobRegistry = new Map();
                window.blobFilenames = new Map();  // blobUrl → filename from a.download
                
                // Override URL.createObjectURL to capture blob references
                const originalCreateObjectURL = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    const url = originalCreateObjectURL.call(URL, blob);
                    window.blobRegistry.set(url, blob);
                    console.log('[Android] Registered blob:', url);
                    return url;
                };
                
                // Override URL.revokeObjectURL but DON'T delete from registry
                // This keeps blobs accessible for download even after revoke
                const originalRevokeObjectURL = URL.revokeObjectURL;
                URL.revokeObjectURL = function(url) {
                    // DON'T delete from registry - we need it for downloads!
                    console.log('[Android] Revoke called but keeping blob in registry:', url);
                    originalRevokeObjectURL.call(URL, url);
                };
                
                // Intercept anchor .click() to capture the download filename
                // SillyTavern does: a.download = "preset.json"; a.click();
                const originalAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (this.href && this.href.startsWith('blob:') && this.download) {
                        window.blobFilenames.set(this.href, this.download);
                        console.log('[Android] Captured download filename:', this.download, 'for:', this.href);
                    }
                    originalAnchorClick.call(this);
                };
                
                // Also intercept dispatchEvent (some code creates and dispatches click event)
                const originalDispatchEvent = HTMLElement.prototype.dispatchEvent;
                HTMLElement.prototype.dispatchEvent = function(event) {
                    if (event.type === 'click' && this instanceof HTMLAnchorElement && 
                        this.href && this.href.startsWith('blob:') && this.download) {
                        window.blobFilenames.set(this.href, this.download);
                        console.log('[Android] Captured download filename (dispatchEvent):', this.download);
                    }
                    return originalDispatchEvent.call(this, event);
                };
                
                console.log('[Android] Blob registry initialized successfully');
            })();
        """.trimIndent()
        
        AppLogger.d(TAG, "Injecting blob registry")
        webView.evaluateJavascript(js, null)
    }
    
    /**
     * Extracts filename from Content-Disposition header or generates a fallback name.
     * Handles both standard and RFC 5987 encoded filenames.
     */
    private fun extractFilename(contentDisposition: String, mimetype: String, url: String): String {
        AppLogger.d(TAG, "Extracting filename from: $contentDisposition")
        
        // Try to parse Content-Disposition header
        // Examples:
        // - attachment; filename="character.png"
        // - attachment; filename*=UTF-8''character%20card.png
        // - inline; filename="preset.json"
        
        if (contentDisposition.isNotBlank()) {
            // Try RFC 5987 format first (filename*=UTF-8''...)
            val rfc5987Regex = """filename\*=UTF-8''([^;]+)""".toRegex(RegexOption.IGNORE_CASE)
            rfc5987Regex.find(contentDisposition)?.let { match ->
                try {
                    val decoded = URLDecoder.decode(match.groupValues[1], "UTF-8")
                    AppLogger.d(TAG, "Extracted filename (RFC 5987): $decoded")
                    return decoded
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to decode RFC 5987 filename: ${e.message}")
                }
            }
            
            // Try standard format (filename="...")
            val standardRegex = """filename=["']?([^"';]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
            standardRegex.find(contentDisposition)?.let { match ->
                val filename = match.groupValues[1].trim()
                AppLogger.d(TAG, "Extracted filename (standard): $filename")
                return filename
            }
        }
        
        // Fallback: try URLUtil.guessFileName
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimetype)
        if (guessed != "downloadfile.bin" && !guessed.matches(Regex("^[a-f0-9-]+\\.bin$"))) {
            AppLogger.d(TAG, "Using URLUtil guess: $guessed")
            return guessed
        }
        
        // Last resort: generate filename from mimetype and timestamp
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype) ?: "bin"
        val fallback = "download_${System.currentTimeMillis()}.$extension"
        AppLogger.d(TAG, "Using fallback filename: $fallback")
        return fallback
    }

    /**
     * Handles file downloads from the WebView (e.g., character card exports).
     * Supports both HTTP URLs (including localhost) and blob URLs.
     * Uses Storage Access Framework (SAF) to let user choose save location.
     */
    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        val filename = extractFilename(contentDisposition, mimetype, url)
        
        AppLogger.i(TAG, "Download requested: $filename from $url (mimetype: $mimetype)")
        AppLogger.d(TAG, "Content-Disposition: $contentDisposition")
        
        if (url.startsWith("blob:")) {
            // Blob URL - need to extract data via JavaScript
            AppLogger.d(TAG, "Handling blob URL download")
            
            // Properly escape strings for JavaScript using JSON encoding
            val escapedFilename = JSONObject.quote(filename)
            val escapedMimetype = JSONObject.quote(mimetype)
            
            // Inject JavaScript to read the blob from registry (or fallback to fetch)
            val js = """
                (function() {
                    const blobUrl = ${JSONObject.quote(url)};
                    let filename = $escapedFilename;
                    const mimetype = $escapedMimetype;
                    
                    // Try to get blob from registry first
                    let blob = window.blobRegistry ? window.blobRegistry.get(blobUrl) : null;
                    
                    if (blob) {
                        console.log('[Android] Found blob in registry:', blobUrl);
                        
                        // Priority 1: blobFilenames map (captured from a.download attribute)
                        const capturedFilename = window.blobFilenames ? window.blobFilenames.get(blobUrl) : null;
                        if (capturedFilename && capturedFilename !== '') {
                            filename = capturedFilename;
                            console.log('[Android] Using captured a.download filename:', filename);
                        // Priority 2: blob.name (for File objects, not plain Blobs)
                        } else if (blob.name && blob.name !== '' && !blob.name.match(/^[a-f0-9-]{36}/)) {
                            filename = blob.name;
                            console.log('[Android] Using blob.name:', filename);
                        } else {
                            console.log('[Android] Using fallback filename:', filename);
                        }
                        
                        // Read blob directly from registry
                        const reader = new FileReader();
                        reader.onloadend = function() {
                            if (!reader.result) {
                                console.error('[Android] FileReader result is null — blob may be empty or unreadable');
                                return;
                            }
                            const base64 = reader.result.split(',')[1];
                            if (!base64) {
                                console.error('[Android] base64 is empty after split — blob has no content');
                                return;
                            }
                            AndroidBlobDownloader.onBase64DataReady(
                                base64,
                                filename,
                                mimetype
                            );
                        };
                        reader.onerror = function() {
                            console.error('[Android] FileReader error reading blob from registry:', reader.error);
                        };
                        reader.readAsDataURL(blob);
                    } else {
                        console.log('[Android] Blob not in registry, trying fetch:', blobUrl);
                        // Fallback: try to fetch (may fail if blob expired)
                        fetch(blobUrl)
                            .then(response => response.blob())
                            .then(fetchedBlob => {
                                const reader = new FileReader();
                                reader.onloadend = function() {
                                    if (!reader.result) {
                                        console.error('[Android] FileReader result is null — fetched blob may be empty or unreadable');
                                        return;
                                    }
                                    const base64 = reader.result.split(',')[1];
                                    if (!base64) {
                                        console.error('[Android] base64 is empty after split — fetched blob has no content');
                                        return;
                                    }
                                    AndroidBlobDownloader.onBase64DataReady(
                                        base64,
                                        filename,
                                        mimetype
                                    );
                                };
                                reader.onerror = function() {
                                    console.error('[Android] FileReader error reading fetched blob:', reader.error);
                                };
                                reader.readAsDataURL(fetchedBlob);
                            })
                            .catch(err => {
                                console.error('[Android] Blob fetch failed:', err);
                                console.error('[Android] URL:', blobUrl);
                                console.error('[Android] Filename:', filename);
                            });
                    }
                })();
            """.trimIndent()
            
            AppLogger.d(TAG, "Injecting JavaScript for blob download")
            binding.webView.evaluateJavascript(js, null)
            
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            // HTTP/HTTPS URL - store info and launch SAF dialog
            AppLogger.d(TAG, "Handling HTTP URL download")
            Toast.makeText(this, "Choose save location for: $filename", Toast.LENGTH_SHORT).show()
            
            pendingHttpDownload = PendingHttpDownload(url, userAgent, mimetype)
            
            try {
                saveFileLauncher.launch(filename)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to launch SAF dialog", e)
                Toast.makeText(
                    this,
                    "Failed to open save dialog: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                pendingHttpDownload = null
            }
            
        } else {
            // Unsupported URL scheme
            AppLogger.w(TAG, "Unsupported URL scheme: $url")
            Toast.makeText(
                this,
                "Unsupported download URL: ${url.substringBefore(":")}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * Saves base64-encoded data (from blob) to the given URI.
     */
    private suspend fun saveBase64ToUri(uri: Uri, base64Data: String, mimetype: String) {
        try {
            if (base64Data.isEmpty()) {
                throw IllegalStateException("Received empty base64 data — the file content could not be read from the blob")
            }
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open output stream for URI — storage may be unavailable")
            output.use { it.write(bytes) }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebViewActivity,
                    "File saved successfully ✓",
                    Toast.LENGTH_LONG
                ).show()
            }
            AppLogger.i(TAG, "Blob download completed: ${bytes.size} bytes")

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebViewActivity,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            AppLogger.e(TAG, "Failed to save blob data", e)
        }
    }
    
    /**
     * Downloads HTTP URL content and saves to the given URI.
     */
    private suspend fun saveHttpToUri(uri: Uri, url: String, userAgent: String) {
        try {
            // Fetch file via OkHttp (works with localhost)
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "Download failed: HTTP ${response.code}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                AppLogger.e(TAG, "Download failed: HTTP ${response.code}")
                return
            }
            
            // Write response body to URI
            val body = response.body
                ?: throw IllegalStateException("HTTP response had no body")
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open output stream for URI — storage may be unavailable")
            output.use { body.byteStream().copyTo(it) }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebViewActivity,
                    "File saved successfully ✓",
                    Toast.LENGTH_LONG
                ).show()
            }
            AppLogger.i(TAG, "HTTP download completed")
            
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebViewActivity,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            AppLogger.e(TAG, "HTTP download exception", e)
        }
    }

    private fun loadImmediately() {
        val port = AppPrefs.serverPort
        val url  = "http://127.0.0.1:$port/"

        binding.loadingOverlay.visibility = View.VISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.tvWaitStatus.text = "Waiting for SillyTavern to compile (this may take a few minutes)..."
        
        // Setup timer to update the text to show it hasn't crashed
        var secondsWaited = 0
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (binding.loadingOverlay.visibility == View.VISIBLE) {
                kotlinx.coroutines.delay(1000)
                secondsWaited++
                if (secondsWaited % 5 == 0) { // Update text every 5 seconds
                    val messages = listOf(
                        "Still compiling... ($secondsWaited s)",
                        "This usually takes 30-60 seconds... ($secondsWaited s)",
                        "Almost there... ($secondsWaited s)",
                        "Warming up the AI... ($secondsWaited s)"
                    )
                    binding.tvWaitStatus.text = messages[(secondsWaited / 5) % messages.size]
                } else {
                    binding.tvWaitStatus.text = "Waiting for SillyTavern to compile... ($secondsWaited s)"
                }
            }
        }

        AppLogger.i(TAG, "Loading ST immediately: $url")
        binding.webView.loadUrl(url)
    }
}
