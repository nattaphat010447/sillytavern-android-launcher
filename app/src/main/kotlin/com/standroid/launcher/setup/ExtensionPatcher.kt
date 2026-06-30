package com.standroid.launcher.setup

import android.content.Context
import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies the STANDROID extension patch to a SillyTavern installation.
 *
 * The patch replaces simpleGit (requires native git binary) with isomorphic-git
 * (pure JS, no binary needed) in src/endpoints/extensions.js.
 *
 * This implementation uses pure Kotlin string replacement, eliminating the
 * Node.js dependency that caused silent failures during initial setup (Node is
 * not yet available right after git clone, before npm install).
 *
 * v3 change: /update route now uses fetch + force-reset instead of git.pull(),
 * which fixes the "refusing to merge unrelated histories" error.
 */
class ExtensionPatcher(private val ctx: Context) {

    companion object {
        private const val TAG = "ExtensionPatcher"
        private const val PATCH_MARKER_V3 = "// PATCHED BY STANDROID v3"
    }

    /**
     * Applies the patch to extensions.js using pure Kotlin string replacement.
     * Safe to call multiple times — idempotent.
     *
     * @param stDir Root directory of the SillyTavern installation
     * @return true if patch is in place (freshly applied or already applied), false on failure
     */
    suspend fun applyPatch(stDir: File): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(stDir, "src/endpoints/extensions.js")

        if (!targetFile.exists()) {
            AppLogger.w(TAG, "extensions.js not found at ${targetFile.absolutePath} — skipping patch")
            return@withContext false
        }

        try {
            var content = targetFile.readText()

            if (content.contains(PATCH_MARKER_V3)) {
                AppLogger.i(TAG, "Patch v3 already applied — skipping")
                return@withContext true
            }

            AppLogger.i(TAG, "Applying patch v3 to ${targetFile.absolutePath}")

            AppLogger.d(TAG, "Step 1/6: Adding isomorphic-git imports")
            content = addIsomorphicGitImports(content)

            AppLogger.d(TAG, "Step 2/6: Replacing checkIfRepoIsUpToDate")
            content = replaceFunction(
                content,
                name = "checkIfRepoIsUpToDate",
                pattern = Regex(
                    """async function checkIfRepoIsUpToDate\(extensionPath\) \{[\s\S]*?\n\}""",
                ),
                templateName = "check-up-to-date.js",
            )

            AppLogger.d(TAG, "Step 3/6: Replacing /update route (fetch + force-reset)")
            content = replaceRoute(content, "update")

            AppLogger.d(TAG, "Step 4/6: Replacing /branches route")
            content = replaceRoute(content, "branches", required = false)

            AppLogger.d(TAG, "Step 5/6: Replacing /switch route")
            content = replaceRoute(content, "switch", required = false)

            AppLogger.d(TAG, "Step 6/6: Replacing /version route")
            content = replaceRoute(content, "version", required = false)

            targetFile.writeText(content)

            // Verify
            val written = targetFile.readText()
            return@withContext if (written.contains("isomorphic-git") && written.contains(PATCH_MARKER_V3)) {
                AppLogger.i(TAG, "✓ Patch v3 applied successfully")
                true
            } else {
                AppLogger.e(TAG, "Patch verification failed — isomorphic-git marker not found after write")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply patch: ${e.message}", e)
            return@withContext false
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun addIsomorphicGitImports(content: String): String {
        // Find the last top-level import statement and insert our imports after it
        val lastImportEnd = Regex("""^import\s.+?;?\s*$""", RegexOption.MULTILINE)
            .findAll(content)
            .lastOrNull()
            ?.range
            ?.last
            ?: throw IllegalStateException("Could not locate import block in extensions.js")

        val template = loadTemplate("imports.js")
        return content.substring(0, lastImportEnd + 1) +
            "\n" + template +
            content.substring(lastImportEnd + 1)
    }

    private fun replaceFunction(
        content: String,
        name: String,
        pattern: Regex,
        templateName: String,
        required: Boolean = true,
    ): String {
        val template = loadTemplate(templateName)
        return if (pattern.containsMatchIn(content)) {
            content.replace(pattern, Regex.escapeReplacement(template))
        } else if (required) {
            throw IllegalStateException("Could not find function '$name' in extensions.js")
        } else {
            AppLogger.d(TAG, "Function '$name' not found — skipping (may not exist in this ST version)")
            content
        }
    }

    private fun replaceRoute(content: String, route: String, required: Boolean = true): String {
        val pattern = Regex(
            """router\.post\('/$route',[\s\S]*?^\}\);""",
            RegexOption.MULTILINE,
        )
        val template = loadTemplate("$route-route.js")
        return if (pattern.containsMatchIn(content)) {
            content.replace(pattern, Regex.escapeReplacement(template))
        } else if (required) {
            throw IllegalStateException("Could not find /$route route in extensions.js")
        } else {
            AppLogger.d(TAG, "/$route route not found — skipping (may not exist in this ST version)")
            content
        }
    }

    private fun loadTemplate(name: String): String =
        ctx.assets.open("patches/templates/$name").use { it.bufferedReader().readText() }
}
