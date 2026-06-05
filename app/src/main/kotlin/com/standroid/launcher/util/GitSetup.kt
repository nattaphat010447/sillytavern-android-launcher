package com.standroid.launcher.util

import android.content.Context
import java.io.File

/**
 * Copies git template files from app assets (git-templates/) into
 * filesDir/git-templates/ on first run.
 *
 * The NodeRunner git wrapper sets GIT_TEMPLATE_DIR to this path so that
 * `git clone` / `git init` can find the hook templates they expect.
 */
object GitSetup {

    private const val TAG = "GitSetup"
    private const val ASSET_PREFIX = "git-templates"

    /**
     * Idempotent — safe to call on every app start.
     * Only copies files when the destination doesn't already exist.
     */
    fun ensureTemplates(ctx: Context) {
        val destRoot = File(ctx.filesDir, "git-templates")
        if (destRoot.exists()) return  // already copied

        try {
            copyAssetsDir(ctx, ASSET_PREFIX, destRoot)
            AppLogger.i(TAG, "Git templates extracted to ${destRoot.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract git templates", e)
        }
    }

    private fun copyAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val list = ctx.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // It's a file — copy it
            destDir.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        // It's a directory
        destDir.mkdirs()
        for (child in list) {
            copyAssetsDir(ctx, "$assetPath/$child", File(destDir, child))
        }
    }
}
