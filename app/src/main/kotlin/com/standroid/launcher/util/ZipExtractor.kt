package com.standroid.launcher.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Utility for extracting ZIP files with progress tracking and validation.
 */
class ZipExtractor(private val ctx: Context) {

    fun interface ProgressCallback {
        /** [message] = human-readable description, [percent] = 0–100 or -1 for indeterminate */
        fun onProgress(message: String, percent: Int)
    }

    private val TAG = "ZipExtractor"

    /**
     * Extract a ZIP file to a destination directory.
     * 
     * @param zipFile Source ZIP file
     * @param destDir Destination directory (will be created if doesn't exist)
     * @param onProgress Progress callback
     * @return true if successful, false otherwise
     */
    suspend fun extract(
        zipFile: File,
        destDir: File,
        onProgress: ProgressCallback
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            
            val totalSize = zipFile.length()
            var extractedSize = 0L
            var lastPercent = -1
            
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var fileCount = 0
                
                while (entry != null) {
                    val entryName = entry.name
                    val outputFile = File(destDir, entryName)
                    
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        
                        FileOutputStream(outputFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                                extractedSize += len
                                
                                // Update progress
                                val percent = ((extractedSize * 100) / totalSize).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress.onProgress("Extracting: $entryName", percent)
                                }
                            }
                        }
                        fileCount++
                    }
                    
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                
                AppLogger.i(TAG, "Extracted $fileCount files to ${destDir.absolutePath}")
            }
            
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "ZIP extraction failed", e)
            false
        }
    }

    /**
     * Validate that a directory contains user data backup structure.
     * Must have at least one of: characters/, chats/, settings.json, secrets.json
     */
    fun isValidUserDataBackup(dir: File): Boolean {
        val hasCharacters = File(dir, "characters").exists()
        val hasChats = File(dir, "chats").exists()
        val hasSettings = File(dir, "settings.json").exists()
        val hasSecrets = File(dir, "secrets.json").exists()
        
        return hasCharacters || hasChats || hasSettings || hasSecrets
    }

    /**
     * Validate that a directory contains a complete SillyTavern installation.
     * Must have server.js and package.json in root.
     */
    fun isValidSillyTavernInstall(dir: File): Boolean {
        val hasServerJs = File(dir, "server.js").exists()
        val hasPackageJson = File(dir, "package.json").exists()
        
        return hasServerJs && hasPackageJson
    }

    /**
     * Copy directory contents recursively with overwrite.
     * Used for merging user data into existing installation.
     */
    suspend fun copyRecursively(
        source: File,
        dest: File,
        onProgress: ProgressCallback
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            dest.mkdirs()
            
            val files = source.walkTopDown().filter { it.isFile }.toList()
            val totalFiles = files.size
            var copiedFiles = 0
            
            files.forEach { file ->
                val relativePath = file.relativeTo(source).path
                val destFile = File(dest, relativePath)
                
                destFile.parentFile?.mkdirs()
                file.copyTo(destFile, overwrite = true)
                
                copiedFiles++
                val percent = (copiedFiles * 100 / totalFiles).coerceIn(0, 100)
                onProgress.onProgress("Copying: $relativePath", percent)
            }
            
            AppLogger.i(TAG, "Copied $copiedFiles files from ${source.absolutePath} to ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Copy failed", e)
            false
        }
    }
}
