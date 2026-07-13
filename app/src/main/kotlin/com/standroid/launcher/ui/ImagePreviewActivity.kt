package com.standroid.launcher.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.standroid.launcher.databinding.ActivityImagePreviewBinding
import com.standroid.launcher.util.AppLogger
import java.io.File
import java.text.DecimalFormat

/**
 * Read-only in-app image preview.
 *
 * Loads the image directly from filesDir via BitmapFactory (no FileProvider needed
 * for display — the app owns those files). Uses inSampleSize downscaling to avoid OOM
 * on large images. A share/open-externally action is available in the toolbar overflow.
 */
class ImagePreviewActivity : AppCompatActivity() {

    private val TAG = "ImagePreviewActivity"
    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            Toast.makeText(this, "No file path provided.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        setupToolbar()
        loadImage()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.title = file.name
        binding.toolbar.subtitle = "Image preview  ·  ${formatSize(file.length())}"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadImage() {
        try {
            // First pass: read dimensions only
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)

            // Calculate inSampleSize to keep bitmap within ~2048×2048
            val maxDim = 2048
            var sampleSize = 1
            var w = opts.outWidth; var h = opts.outHeight
            while (w > maxDim || h > maxDim) { sampleSize *= 2; w /= 2; h /= 2 }

            // Second pass: decode with downscaling
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: throw IllegalStateException("BitmapFactory returned null")

            binding.imageView.setImageBitmap(bitmap)
            binding.tvImageMeta.text = "${opts.outWidth} × ${opts.outHeight} px  ·  ${file.extension.uppercase()}"

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decode image: ${file.absolutePath}", e)
            Toast.makeText(this, "Cannot display image: ${e.message}", Toast.LENGTH_LONG).show()
            // Fall back to opening externally
            openExternally()
            finish()
        }
    }

    /** Fires ACTION_VIEW via FileProvider so external apps can handle the image. */
    private fun openExternally() {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            AppLogger.e(TAG, "No handler for image", e)
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
            return "${DecimalFormat("#.##").format(kb / 1024.0)} MB"
        }
    }
}
