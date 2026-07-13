package com.standroid.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.standroid.launcher.R
import com.standroid.launcher.databinding.ActivityFileEditorBinding
import com.standroid.launcher.service.STForegroundService
import com.standroid.launcher.util.AppLogger
import java.io.File

/**
 * Full-screen text editor for any file in the SillyTavern directory.
 *
 * Behaviour:
 *  - Files > 1 MB or detected binary → opened read-only with a banner
 *  - Unsaved changes → "Discard changes?" dialog on back
 *  - Saving config.yaml while ST is running → offers to restart server
 */
class FileEditorActivity : AppCompatActivity() {

    private val TAG = "FileEditorActivity"
    private lateinit var binding: ActivityFileEditorBinding

    private lateinit var file: File
    private var isReadOnly = false
    private var isDirty = false
    private var originalContent = ""

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            Toast.makeText(this, "No file path provided.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        loadFile()
        setupDirtyTracking()
    }

    override fun onBackPressed() {
        if (isDirty) {
            styledDialog()
                .setTitle("Discard changes?")
                .setMessage("You have unsaved changes to \"${file.name}\".\n\nDiscard and go back?")
                .setPositiveButton("Discard") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Keep editing", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.title = file.name
        binding.toolbar.subtitle = shortenPath(file)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSave.setOnClickListener { saveFile() }
    }

    private fun loadFile() {
        val sizeBytes = file.length()
        val isBinary = looksLikeBinary(file)
        val isProtectedPath = FileExplorerActivity.isProtected(file)

        when {
            isProtectedPath -> {
                isReadOnly = true
                binding.tvReadOnlyBanner.text = "🔒 Protected folder — read only"
                binding.tvReadOnlyBanner.visibility = android.view.View.VISIBLE
                binding.btnSave.isEnabled = false
                binding.btnSave.alpha = 0.45f

                val content = try { file.readText() } catch (e: Exception) {
                    "[Cannot display file content: ${e.message}]"
                }
                binding.etFileContent.setText(content)
                binding.etFileContent.isEnabled = false
                originalContent = content
            }
            sizeBytes > MAX_EDITABLE_BYTES || isBinary -> {
                isReadOnly = true
                binding.tvReadOnlyBanner.visibility = android.view.View.VISIBLE
                binding.btnSave.isEnabled = false
                binding.btnSave.alpha = 0.45f

                // Still try to show text content for binary-ish files
                val content = try {
                    if (sizeBytes > MAX_READABLE_BYTES) {
                        val truncated = file.inputStream().bufferedReader().use {
                            it.readText().take(MAX_READABLE_BYTES.toInt())
                        }
                        "$truncated\n\n[... file truncated at ${formatSize(MAX_READABLE_BYTES)} ...]"
                    } else {
                        file.readText()
                    }
                } catch (e: Exception) {
                    "[Cannot display file content: ${e.message}]"
                }
                binding.etFileContent.setText(content)
                binding.etFileContent.isEnabled = false
                originalContent = content
            }
            else -> {
                isReadOnly = false
                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to read file", e)
                    Toast.makeText(this, "Read error: ${e.message}", Toast.LENGTH_LONG).show()
                    ""
                }
                binding.etFileContent.setText(content)
                originalContent = content
                isDirty = false
            }
        }
    }

    private fun setupDirtyTracking() {
        binding.etFileContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isReadOnly) return
                val newDirty = s?.toString() != originalContent
                if (newDirty != isDirty) {
                    isDirty = newDirty
                    updateSaveButton()
                }
            }
        })
    }

    private fun updateSaveButton() {
        binding.btnSave.alpha = if (isDirty) 1.0f else 0.6f
    }

    // ── Save ───────────────────────────────────────────────────────────

    private fun saveFile() {
        if (isReadOnly) return
        val newContent = binding.etFileContent.text.toString()
        try {
            file.writeText(newContent)
            originalContent = newContent
            isDirty = false
            updateSaveButton()
            Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show()

            // config.yaml special case: offer server restart
            if (file.name == "config.yaml" && STForegroundService.isServiceRunning) {
                styledDialog()
                    .setTitle("Restart Required")
                    .setMessage("config.yaml has been saved.\n\nRestart SillyTavern to apply changes?")
                    .setPositiveButton("Restart") { _, _ ->
                        startService(STForegroundService.stopIntent(this))
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            ContextCompat.startForegroundService(
                                this,
                                STForegroundService.startIntent(this)
                            )
                        }, 500)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save file", e)
            styledDialog()
                .setTitle("Save Error")
                .setMessage("Could not save \"${file.name}\":\n${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun styledDialog() =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_STANDROID_Dialog)

    /** Returns a display-friendly relative path from SillyTavern root. */
    private fun shortenPath(f: File): String {
        val stRoot = File(filesDir, "SillyTavern").absolutePath
        val abs = f.parent ?: return f.name
        return if (abs.startsWith(stRoot)) {
            "SillyTavern${abs.removePrefix(stRoot)}"
        } else {
            abs
        }
    }

    /** Sniffs first 512 bytes for null bytes — a reliable binary heuristic. */
    private fun looksLikeBinary(f: File): Boolean {
        return try {
            f.inputStream().use { stream ->
                val buf = ByteArray(512)
                val read = stream.read(buf)
                (0 until read).any { buf[it] == 0.toByte() }
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"

        /** Files larger than this are opened read-only. */
        private const val MAX_EDITABLE_BYTES = 1_048_576L  // 1 MB

        /** Maximum bytes to load even in read-only view. */
        private const val MAX_READABLE_BYTES = 2_097_152L  // 2 MB

        private fun formatSize(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return "%.1f MB".format(mb)
        }
    }
}
