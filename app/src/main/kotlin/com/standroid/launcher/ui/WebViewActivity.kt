package com.standroid.launcher.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
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
        
        // Enable hardware acceleration features in WebView
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // B4: waivedWhenNotVisible=false — keep renderer at IMPORTANT priority even when
            // the activity is in the background so the Node server response is processed quickly
            // when the user returns to the app.
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
