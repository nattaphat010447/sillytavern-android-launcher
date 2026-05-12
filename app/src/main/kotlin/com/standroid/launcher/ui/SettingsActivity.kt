package com.standroid.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.standroid.launcher.databinding.ActivitySettingsBinding
import com.standroid.launcher.setup.NpmInstaller
import com.standroid.launcher.setup.STInstaller
import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"
    private lateinit var binding: ActivitySettingsBinding

    private val exportPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportDataToUri(uri)
            }
        }
    }

    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        binding.btnExportData.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "SillyTavern_Backup.zip")
            }
            exportPickerLauncher.launch(intent)
        }

        binding.btnAdvancedSettings.setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
    }

    private fun showProgressDialog(title: String, message: String) {
        if (progressDialog == null) {
            progressDialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .create()
        } else {
            progressDialog?.setTitle(title)
            progressDialog?.setMessage(message)
        }
        progressDialog?.show()
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
    }

    private fun updateProgressDialog(message: String) {
        runOnUiThread {
            progressDialog?.setMessage(message)
        }
    }

    private fun checkForUpdates() {
        val stDir = File(filesDir, "SillyTavern")
        if (!stDir.exists() || !File(stDir, ".git").exists()) {
            Toast.makeText(this, "SillyTavern Git repo not found. Cannot update.", Toast.LENGTH_SHORT).show()
            return
        }

        showProgressDialog("Updating SillyTavern", "Fetching latest code from GitHub...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Git.open(stDir).use { git ->
                    
                    // 1. Fetch latest changes
                    git.fetch().call()

                    updateProgressDialog("Applying updates (Hard Reset)...")
                    // 2. Hard Reset to ensure no conflicts (will discard local uncommitted changes, 
                    // but ST data is gitignored so it's safe).
                    git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("origin/staging")
                        .call()

                    updateProgressDialog("Installing new dependencies (npm install)...")
                    // 3. Run NPM Install
                    val npmInstaller = NpmInstaller(this@SettingsActivity)
                    val npmOk = npmInstaller.install(
                        stDir = stDir,
                        onLog = { line -> updateProgressDialog("NPM: $line") }
                    )

                    withContext(Dispatchers.Main) {
                        hideProgressDialog()
                        if (npmOk) {
                            Toast.makeText(this@SettingsActivity, "Updated successfully!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@SettingsActivity, "Update succeeded but npm install failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update ST", e)
                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Toast.makeText(this@SettingsActivity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportDataToUri(uri: Uri) {
        val stDir = File(filesDir, "SillyTavern")
        if (!stDir.exists()) {
            Toast.makeText(this, "No SillyTavern data found to export.", Toast.LENGTH_SHORT).show()
            return
        }

        showProgressDialog("Exporting Data", "Calculating files to backup...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // First pass: count files and size to show progress
                val filesToZip = mutableListOf<File>()
                var totalBytes = 0L
                stDir.walkTopDown().forEach { file ->
                    if (file.isFile && !file.absolutePath.contains("/.git/") && !file.absolutePath.contains("/node_modules/")) {
                        filesToZip.add(file)
                        totalBytes += file.length()
                    }
                }

                updateProgressDialog("Compressing ${filesToZip.size} files...")

                var processedBytes = 0L
                var lastPercent = -1

                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zos ->
                        for (file in filesToZip) {
                            val entryName = file.absolutePath.substringAfter(stDir.absolutePath + "/")
                            val zipEntry = ZipEntry(entryName)
                            zos.putNextEntry(zipEntry)
                            
                            file.inputStream().use { fis ->
                                val buffer = ByteArray(8192)
                                var length: Int
                                while (fis.read(buffer).also { length = it } >= 0) {
                                    zos.write(buffer, 0, length)
                                    processedBytes += length
                                    
                                    if (totalBytes > 0) {
                                        val pct = ((processedBytes.toDouble() / totalBytes) * 100).toInt()
                                        if (pct != lastPercent && pct % 5 == 0) { // Update UI every 5%
                                            lastPercent = pct
                                            updateProgressDialog("Compressing data... $pct%")
                                        }
                                    }
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Toast.makeText(this@SettingsActivity, "Export successful!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
