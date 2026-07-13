package com.standroid.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.standroid.launcher.R
import com.standroid.launcher.databinding.ActivityFileExplorerBinding
import com.standroid.launcher.databinding.ItemFileEntryBinding
import com.standroid.launcher.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Full file explorer rooted at filesDir/SillyTavern/.
 *
 * Features:
 *  - Navigate into folders; back stack + breadcrumb
 *  - Long-press or three-dot menu → bottom sheet: Copy / View & Edit / Zip & Share / Delete
 *  - Copy clipboard with Paste button in toolbar
 *  - Protected folders (node_modules, .git): 🔒 icon, navigable, Copy/Delete/Paste blocked
 *  - Zip & Share: zips file or folder → Android share chooser
 */
class FileExplorerActivity : AppCompatActivity() {

    private val TAG = "FileExplorerActivity"
    private lateinit var binding: ActivityFileExplorerBinding

    private lateinit var rootDir: File
    private var currentDir: File? = null

    private val dirStack = ArrayDeque<File>()
    private var adapter: FileAdapter? = null
    private var clipboardFile: File? = null

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootDir = File(filesDir, "SillyTavern")
        if (!rootDir.exists()) {
            Toast.makeText(this, "SillyTavern is not installed.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.title = "File Browser"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnPaste.setOnClickListener { doPaste() }

        setupRecycler()
        navigateTo(rootDir, addToStack = false)
    }

    override fun onBackPressed() {
        if (dirStack.size > 1) {
            dirStack.removeLast()
            val parent = dirStack.last()
            currentDir = parent
            loadDirectory(parent)
            updateBreadcrumb()
            updatePasteButton()
        } else {
            super.onBackPressed()
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────

    private fun navigateTo(dir: File, addToStack: Boolean = true) {
        if (addToStack) dirStack.addLast(dir)
        else { dirStack.clear(); dirStack.addLast(dir) }
        currentDir = dir
        loadDirectory(dir)
        updateBreadcrumb()
        updatePasteButton()
    }

    private fun loadDirectory(dir: File) {
        val entries = (dir.listFiles() ?: emptyArray<File>())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (entries.isEmpty()) {
            binding.recyclerFiles.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.recyclerFiles.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
        }
        adapter?.submitList(entries)
    }

    // ── Breadcrumb ─────────────────────────────────────────────────────

    private fun updateBreadcrumb() {
        val bar = binding.breadcrumbBar
        bar.removeAllViews()

        dirStack.forEachIndexed { index, dir ->
            if (index > 0) {
                bar.addView(TextView(this).apply {
                    text = " › "
                    setTextColor(ContextCompat.getColor(this@FileExplorerActivity, R.color.text_disabled))
                    textSize = 12f
                })
            }
            val label = if (index == 0) "SillyTavern" else dir.name
            val isLast = index == dirStack.size - 1
            bar.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(4, 0, 4, 0)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(
                    this@FileExplorerActivity,
                    if (isLast) R.color.purple_glow else R.color.text_muted
                ))
                if (!isLast) {
                    val stackIndex = index
                    setOnClickListener {
                        while (dirStack.size > stackIndex + 1) dirStack.removeLast()
                        val target = dirStack.last()
                        currentDir = target
                        loadDirectory(target)
                        updateBreadcrumb()
                        updatePasteButton()
                    }
                }
            })
        }

        binding.root.post {
            val hsv = bar.parent as? android.widget.HorizontalScrollView
            hsv?.fullScroll(View.FOCUS_RIGHT)
        }
    }

    // ── RecyclerView ───────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = FileAdapter(
            onItemClick = { file -> onFileClick(file) },
            onMenuClick = { file, anchor -> showContextMenu(file, anchor) },
            onItemLongClick = { file -> showContextMenu(file, null) }
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter
        binding.recyclerFiles.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL).also {
                ContextCompat.getDrawable(this, android.R.drawable.divider_horizontal_dim_dark)
                    ?.let { d -> it.setDrawable(d) }
            }
        )
    }

    private fun onFileClick(file: File) {
        when {
            file.isDirectory -> navigateTo(file)
            isImage(file)    -> openImagePreview(file)
            isText(file)     -> openFileEditor(file)
            else             -> openExternally(file)
        }
    }

    private fun openFileEditor(file: File) {
        startActivity(Intent(this, FileEditorActivity::class.java).apply {
            putExtra(FileEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        })
    }

    private fun openImagePreview(file: File) {
        startActivity(Intent(this, ImagePreviewActivity::class.java).apply {
            putExtra(ImagePreviewActivity.EXTRA_FILE_PATH, file.absolutePath)
        })
    }

    /**
     * Opens [file] in an external app via Intent.ACTION_VIEW.
     * If no app can handle it, shows a dialog with a Share fallback.
     */
    private fun openExternally(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) {
            AppLogger.w(TAG, "No handler for file: ${file.name}")
            showNoHandlerDialog(file)
        } catch (e: Exception) {
            AppLogger.e(TAG, "openExternally failed", e)
            showNoHandlerDialog(file)
        }
    }

    private fun showNoHandlerDialog(file: File) {
        styledDialog()
            .setTitle("Can't open file")
            .setMessage("No app on this device can open \"${file.name}\".\n\nYou can share it instead.")
            .setPositiveButton("Share") { _, _ -> shareFileDirectly(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Falls back to ACTION_SEND share chooser when no viewer is available. */
    private fun shareFileDirectly(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share \"${file.name}\" via…"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "shareFileDirectly failed", e)
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Context menu (bottom sheet) ────────────────────────────────────

    private fun showContextMenu(file: File, anchor: View?) {
        val protected = isProtected(file)
        val sheet = BottomSheetDialog(this)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 24, 0, 32)
        }

        fun menuItem(label: String, color: Int = R.color.text_primary, action: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@FileExplorerActivity, color))
                setPadding(48, 32, 48, 32)
                setOnClickListener { sheet.dismiss(); action() }
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ContextCompat.getDrawable(context, ta.getResourceId(0, 0))
                ta.recycle()
            }
        }

        // Header
        container.addView(TextView(this).apply {
            text = file.name
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@FileExplorerActivity, R.color.text_muted))
            setPadding(48, 16, 48, 8)
        })

        if (protected) {
            container.addView(TextView(this).apply {
                text = "🔒 Protected — read only"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@FileExplorerActivity, R.color.text_disabled))
                setPadding(48, 0, 48, 8)
            })
        }

        // Divider
        container.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(48, 0, 48, 16) }
            setBackgroundColor(ContextCompat.getColor(this@FileExplorerActivity, R.color.bg_card_stroke))
        })

        // Copy — hidden for protected
        if (!protected) {
            container.addView(menuItem("📋  Copy") {
                clipboardFile = file
                updatePasteButton()
                Toast.makeText(this, "Copied: ${file.name}", Toast.LENGTH_SHORT).show()
            })
        }

        // View / Edit — files only
        if (!file.isDirectory) {
            container.addView(menuItem("✏️  View / Edit") {
                openFileEditor(file)
            })
        }

        // Zip & Share — available for everything including protected
        container.addView(menuItem("🗜️  Zip & Share") {
            doZipAndShare(file)
        })

        // Delete — hidden for protected
        if (!protected) {
            container.addView(menuItem("🗑️  Delete", R.color.error_red) {
                confirmDelete(file)
            })
        }

        sheet.setContentView(container)
        sheet.show()
    }

    // ── Zip & Share ────────────────────────────────────────────────────

    /**
     * Creates cacheDir/exports/<name>.zip from [source] (file or folder),
     * then fires an ACTION_SEND chooser via FileProvider URI.
     */
    private fun doZipAndShare(source: File) {
        Toast.makeText(this, "Zipping ${source.name}…", Toast.LENGTH_SHORT).show()
        try {
            val exportsDir = File(cacheDir, "exports").also { it.mkdirs() }
            val zipName = "${source.nameWithoutExtension}.zip"
            val zipFile = File(exportsDir, zipName).also { if (it.exists()) it.delete() }

            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                if (source.isDirectory) zipDirectory(source, source.name, zos)
                else zipSingleFile(source, source.name, zos)
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zipName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share \"$zipName\" via…"))

        } catch (e: Exception) {
            AppLogger.e(TAG, "Zip failed", e)
            styledDialog()
                .setTitle("Zip Failed")
                .setMessage("Could not zip \"${source.name}\":\n${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Recursively adds [dir] into [zos], preserving folder structure. */
    private fun zipDirectory(dir: File, basePath: String, zos: ZipOutputStream) {
        val children = dir.listFiles() ?: return
        if (children.isEmpty()) {
            zos.putNextEntry(ZipEntry("$basePath/"))
            zos.closeEntry()
            return
        }
        for (child in children) {
            if (child.isDirectory) zipDirectory(child, "$basePath/${child.name}", zos)
            else zipSingleFile(child, "$basePath/${child.name}", zos)
        }
    }

    /** Streams [file] into [zos] under [entryName]. */
    private fun zipSingleFile(file: File, entryName: String, zos: ZipOutputStream) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    // ── Copy / Paste ───────────────────────────────────────────────────

    private fun updatePasteButton() {
        val hasClip = clipboardFile != null && clipboardFile!!.exists()
        binding.btnPaste.visibility = if (hasClip) View.VISIBLE else View.GONE
        if (hasClip) binding.btnPaste.text = "Paste \"${clipboardFile!!.name}\""
    }

    private fun doPaste() {
        val src = clipboardFile ?: return
        val dir = currentDir ?: return

        if (!src.exists()) {
            Toast.makeText(this, "Source no longer exists.", Toast.LENGTH_SHORT).show()
            clipboardFile = null; updatePasteButton(); return
        }

        if (isProtected(dir)) {
            styledDialog()
                .setTitle("Cannot paste here")
                .setMessage("\"${dir.name}\" is a protected folder.\n\nPasting files here is not allowed.")
                .setPositiveButton("OK", null).show()
            return
        }

        if (src.isDirectory) {
            val srcCanon = src.canonicalPath
            val destDirCanon = dir.canonicalPath
            if (destDirCanon == srcCanon || destDirCanon.startsWith("$srcCanon/")) {
                styledDialog()
                    .setTitle("Cannot paste here")
                    .setMessage("Cannot paste \"${src.name}\" into itself or one of its sub-folders.")
                    .setPositiveButton("OK", null).show()
                return
            }
        }

        val dest = File(dir, src.name)
        when {
            dest.canonicalPath == src.canonicalPath -> performCopy(src, uniqueDestName(dir, src.name))
            dest.exists() -> styledDialog()
                .setTitle("File already exists")
                .setMessage("\"${src.name}\" already exists here.\nOverwrite?")
                .setPositiveButton("Overwrite") { _, _ -> performCopyOverwrite(src, dest) }
                .setNegativeButton("Cancel", null).show()
            else -> performCopy(src, dest)
        }
    }

    private fun performCopy(src: File, dest: File) {
        try {
            if (src.isDirectory) src.copyRecursively(dest, overwrite = false)
            else src.copyTo(dest, overwrite = false)
            loadDirectory(currentDir!!)
            Toast.makeText(this, "Pasted: ${dest.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Copy failed", e)
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performCopyOverwrite(src: File, dest: File) {
        try {
            if (src.isDirectory) { dest.deleteRecursively(); src.copyRecursively(dest, overwrite = true) }
            else {
                val tmp = File(dest.parent, "${dest.name}.tmp_${System.currentTimeMillis()}")
                src.copyTo(tmp, overwrite = false)
                dest.delete()
                tmp.renameTo(dest)
            }
            loadDirectory(currentDir!!)
            Toast.makeText(this, "Pasted: ${dest.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Copy (overwrite) failed", e)
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun uniqueDestName(dir: File, name: String): File {
        val dotIdx = name.lastIndexOf('.')
        val base = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext  = if (dotIdx > 0) name.substring(dotIdx) else ""
        var candidate = File(dir, "$base (copy)$ext")
        var counter = 2
        while (candidate.exists()) { candidate = File(dir, "$base (copy $counter)$ext"); counter++ }
        return candidate
    }

    // ── Delete ─────────────────────────────────────────────────────────

    private fun confirmDelete(file: File) {
        styledDialog()
            .setTitle("Delete permanently?")
            .setMessage("\"${file.name}\" and all contents will be permanently deleted.\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    loadDirectory(currentDir!!)
                    Toast.makeText(this, "Deleted: ${file.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Delete failed", e)
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun styledDialog() =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_STANDROID_Dialog)

    // ── Adapter ────────────────────────────────────────────────────────

    inner class FileAdapter(
        private val onItemClick: (File) -> Unit,
        private val onMenuClick: (File, View) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private var items: List<File> = emptyList()

        fun submitList(list: List<File>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemFileEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(file: File) {
                val protected = isProtected(file)
                b.tvFileName.text = file.name
                b.tvFileIcon.text = iconFor(file)

                b.tvFileMeta.text = if (file.isDirectory) {
                    val count = file.listFiles()?.size ?: 0
                    if (protected) "Protected · $count item${if (count != 1) "s" else ""}"
                    else "$count item${if (count != 1) "s" else ""}"
                } else {
                    formatSize(file.length())
                }

                val textColor = ContextCompat.getColor(b.root.context,
                    if (protected) R.color.text_disabled else R.color.text_primary)
                val metaColor = ContextCompat.getColor(b.root.context,
                    if (protected) R.color.text_disabled else R.color.text_muted)
                b.tvFileName.setTextColor(textColor)
                b.tvFileMeta.setTextColor(metaColor)
                b.tvFileIcon.alpha = if (protected) 0.45f else 1.0f

                b.root.setOnClickListener { onItemClick(file) }
                b.root.setOnLongClickListener { onItemLongClick(file); true }
                b.btnFileMenu.setOnClickListener { onMenuClick(file, b.btnFileMenu) }
            }
        }
    }

    companion object {

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp"
        )

        private val TEXT_EXTENSIONS = setOf(
            "js", "ts", "jsx", "tsx", "mjs", "cjs",
            "json", "yaml", "yml",
            "md", "txt", "log",
            "html", "htm", "css", "scss", "sass",
            "xml", "csv", "ini", "env", "sh", "bash",
            "properties", "conf", "config",
            "py", "rb", "lua", "php", "java", "kt", "swift", "go", "rs", "c", "cpp", "h"
        )

        /** Returns true if the file is a known image format. */
        fun isImage(file: File): Boolean =
            file.extension.lowercase() in IMAGE_EXTENSIONS

        /**
         * Returns true if the file should open as text.
         * Known text extension → always text.
         * Unknown/no extension → sniff for null bytes.
         */
        fun isText(file: File): Boolean {
            val ext = file.extension.lowercase()
            if (ext in TEXT_EXTENSIONS) return true
            return !looksLikeBinary(file)
        }

        /** Sniffs first 512 bytes for null bytes — reliable binary heuristic. */
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

        private val PROTECTED_NAMES = setOf("node_modules", ".git")

        fun isProtected(file: File): Boolean {
            var f: File? = file
            while (f != null) {
                if (f.name in PROTECTED_NAMES) return true
                f = f.parentFile
            }
            return false
        }

        private fun iconFor(file: File): String = when {
            file.isDirectory && isProtected(file) -> "🔒"
            file.isDirectory -> "📁"
            file.name.endsWith(".js") || file.name.endsWith(".ts") -> "📜"
            file.name.endsWith(".json") -> "🔧"
            file.name.endsWith(".yaml") || file.name.endsWith(".yml") -> "⚙️"
            file.name.endsWith(".md") -> "📖"
            file.name.endsWith(".txt") -> "📄"
            file.name.endsWith(".log") -> "🪵"
            file.name.endsWith(".png") || file.name.endsWith(".jpg") ||
                    file.name.endsWith(".jpeg") || file.name.endsWith(".webp") -> "🖼️"
            file.name.endsWith(".zip") || file.name.endsWith(".tar") ||
                    file.name.endsWith(".gz") -> "🗜️"
            else -> "📃"
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "${DecimalFormat("#.#").format(kb)} KB"
            val mb = kb / 1024.0
            return "${DecimalFormat("#.##").format(mb)} MB"
        }
    }
}
