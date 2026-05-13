package com.standroid.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.standroid.launcher.databinding.ActivitySetupBinding
import com.standroid.launcher.setup.NpmInstaller
import com.standroid.launcher.setup.STInstaller
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs
import com.standroid.launcher.util.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-launch setup wizard.
 *
 * Presents two options:
 *  - **Download New** — clones SillyTavern via JGit then runs npm install
 *  - **Import ZIP** — extracts an existing SillyTavern backup then runs npm install
 *
 * On completion, navigates back to [MainActivity] which will show the Start button.
 */
class SetupActivity : AppCompatActivity() {

    private val TAG = "SetupActivity"
    private lateinit var binding: ActivitySetupBinding

    private val stInstaller  by lazy { STInstaller(this) }
    private val npmInstaller by lazy { NpmInstaller(this) }

    private val permHelper = com.standroid.launcher.util.Permissions(this) { _ ->
        // Proceed regardless of Notification permission status
    }

    private val zipPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importZipAndInstall(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stDir = File(filesDir, "SillyTavern")

        binding.btnDownloadNew.setOnClickListener {
            binding.btnDownloadNew.visibility = View.GONE
            binding.btnImportZip.visibility = View.GONE
            startSetup(stDir)
        }

        binding.btnImportZip.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            zipPickerLauncher.launch(intent)
        }

        binding.btnRetry.setOnClickListener { startSetup(stDir) }

        setupLogAutoScroll()
        permHelper.requestIfNeeded()
    }

    private fun importZipAndInstall(uri: Uri) {
        val stDir = File(filesDir, "SillyTavern")

        binding.btnDownloadNew.visibility = View.GONE
        binding.btnImportZip.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.progressBar.progress = 0
        appendLog("Importing SillyTavern from ZIP…\n")

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    stDir.deleteRecursively()
                    stDir.mkdirs()

                    val cursor = contentResolver.query(uri, null, null, null, null)
                    var totalSize = 0L
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (sizeIndex != -1) totalSize = it.getLong(sizeIndex)
                        }
                    }

                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        java.util.zip.ZipInputStream(inputStream.buffered(64 * 1024)).use { zis ->
                            var entry = zis.nextEntry
                            var bytesRead = 0L
                            var lastPercent = -1

                            while (entry != null) {
                                val pathParts = entry.name.split("/")
                                val stripped = if (pathParts.size > 1 && pathParts[0].startsWith("SillyTavern", ignoreCase = true)) {
                                    entry.name.substringAfter('/')
                                } else {
                                    entry.name
                                }

                                if (stripped.isNotEmpty()) {
                                    val target = File(stDir, stripped)
                                    if (entry.isDirectory) {
                                        target.mkdirs()
                                    } else {
                                        target.parentFile?.mkdirs()
                                        java.io.FileOutputStream(target).use { out ->
                                            val buf = ByteArray(8192)
                                            var n: Int
                                            while (zis.read(buf).also { n = it } != -1) {
                                                out.write(buf, 0, n)
                                                bytesRead += n
                                                if (totalSize > 0) {
                                                    val pct = Math.min(((bytesRead.toDouble() / (totalSize * 2.5)) * 100).toInt(), 99)
                                                    if (pct != lastPercent && pct % 2 == 0) {
                                                        lastPercent = pct
                                                        withContext(Dispatchers.Main) {
                                                            binding.progressBar.isIndeterminate = false
                                                            binding.progressBar.progress = pct
                                                            binding.tvProgressPercent.text = "$pct%"
                                                            binding.tvSetupStep.text = "Extracting ZIP... $pct%"
                                                        }
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        binding.progressBar.isIndeterminate = true
                                                        binding.tvSetupStep.text = "Extracting ZIP..."
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.progress = 100
                    binding.tvProgressPercent.text = "100%"
                    binding.tvSetupStep.text = "ZIP extracted. Installing dependencies..."
                    appendLog("SillyTavern ZIP extracted successfully.\n")
                }

                runNpmCiAndFinish(stDir)

            }.onFailure { err ->
                AppLogger.e(TAG, "Import failed", err)
                withContext(Dispatchers.Main) {
                    showError("Import failed: ${err.message}")
                }
            }
        }
    }

    // ── Setup flow ────────────────────────────────────────────────────

    private fun startSetup(stDir: File) {
        if (!Network.isConnected(this)) {
            showError("No internet connection. Please connect and retry.")
            return
        }

        binding.btnRetry.visibility = View.GONE
        binding.progressBar.progress = 0
        appendLog("Starting SillyTavern setup…\n")

        lifecycleScope.launch {
            runCatching {
                val version = stInstaller.install(destDir = stDir) { step, percent ->
                    runOnUiThread {
                        binding.tvSetupStep.text = step
                        if (percent >= 0) {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.progress = percent
                            binding.tvProgressPercent.text = "$percent%"
                        } else {
                            binding.progressBar.isIndeterminate = true
                            binding.tvProgressPercent.text = ""
                        }
                        appendLog("$step${if (percent >= 0) " ($percent%)" else ""}\n")
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.progress = 100
                    binding.tvSetupStep.text = "Installing dependencies..."
                    appendLog("SillyTavern repository cloned ($version).\n")
                }

                runNpmCiAndFinish(stDir)

            }.onFailure { err ->
                AppLogger.e(TAG, "Setup failed", err)
                withContext(Dispatchers.Main) {
                    showError("Setup failed: ${err.message}")
                }
            }
        }
    }

    private fun runNpmCiAndFinish(stDir: File) {
        lifecycleScope.launch {
            val loadingMessages = listOf(
                "Fetching the magic spells...",
                "Feeding the AI hamsters...",
                "Installing lots of dependencies...",
                "This part takes a while, please wait...",
                "Almost there..."
            )
            var msgIndex = 0
            val hintJob = launch(Dispatchers.Main) {
                while (true) {
                    binding.tvSetupStep.text = loadingMessages[msgIndex % loadingMessages.size]
                    msgIndex++
                    kotlinx.coroutines.delay(5000)
                }
            }

            runCatching {
                val npmOk = npmInstaller.install(
                    stDir = stDir,
                    onLog = { line ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            appendLog("$line\n")
                            binding.tvProgressPercent.text = "Working..."
                        }
                    },
                )

                hintJob.cancel()

                if (!npmOk) error("npm install failed after all retries")

                AppPrefs.isStInstalled = true
                AppPrefs.stDirPath = stDir.absolutePath

                withContext(Dispatchers.Main) {
                    binding.tvSetupStep.text = "Setup Complete!"
                    appendLog("Setup complete! Starting server…\n")
                }

                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
            }.onFailure { err ->
                AppLogger.e(TAG, "Dependencies install failed", err)
                withContext(Dispatchers.Main) {
                    showError("NPM install failed: ${err.message}")
                }
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────

    private var autoScrollLog = true

    private fun setupLogAutoScroll() {
        binding.scrollLog.viewTreeObserver.addOnScrollChangedListener {
            val sv = binding.scrollLog
            val child = sv.getChildAt(0) ?: return@addOnScrollChangedListener
            autoScrollLog = sv.scrollY + sv.height >= child.height - 8
        }
    }

    private fun appendLog(text: String) {
        binding.tvLogOutput.append(text)
        if (autoScrollLog) {
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun showError(msg: String) {
        binding.tvSetupStep.text = msg
        binding.btnRetry.visibility = View.VISIBLE
        appendLog("\n⚠ $msg\n")
    }
}
