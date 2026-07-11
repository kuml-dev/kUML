package dev.kuml.desktop.workspace

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfWorkspace
import java.io.File
import java.net.URI

/**
 * Resolves and dispatches a Markdown link click inside a rendered [OkfDocument] (V3.6.4).
 *
 * A clean `fun interface` seam so a future cross-link resolver (FT-6) can swap in a
 * richer implementation without touching [dev.kuml.desktop.workspace.MarkdownDocPane].
 */
fun interface WorkspaceLinkHandler {
    fun onLink(target: String)
}

private val EXTERNAL_SCHEME_ALLOWLIST = setOf("http", "https", "mailto")

/**
 * Default [WorkspaceLinkHandler]: external links are opened via the system
 * browser/mail client, restricted to a scheme allowlist; internal relative links
 * are resolved **only** against the already-scanned [OkfWorkspace.documents] set —
 * never against the filesystem directly.
 *
 * This is a security-relevant seam: a crafted link target such as
 * `../../../../etc/passwd` must never cause an arbitrary file read. Internal
 * navigation is a pure in-memory lookup by normalized `relativePath`, reusing
 * [dev.kuml.workspace.WorkspaceScanner]'s existing scan (hidden-dir/symlink/DoS
 * guarantees) instead of adding a second, unguarded read path.
 */
class DefaultWorkspaceLinkHandler(
    private val workspace: OkfWorkspace,
    private val currentDoc: () -> OkfDocument?,
    private val onNavigate: (OkfDocument) -> Unit,
    private val openExternal: (URI) -> Unit = { uri -> java.awt.Desktop.getDesktop().browse(uri) },
) : WorkspaceLinkHandler {
    override fun onLink(target: String) {
        val scheme = schemeOf(target)
        if (scheme != null) {
            if (scheme.lowercase() in EXTERNAL_SCHEME_ALLOWLIST) {
                runCatching { openExternal(URI(target)) }
            }
            // Any other scheme (file:, javascript:, data:, custom, ...) is refused — no-op.
            return
        }
        resolveInternal(target)?.let(onNavigate)
    }

    /** Extracts a URI scheme, guarding against Windows drive letters ("C:\...") being mistaken for one. */
    private fun schemeOf(target: String): String? {
        val idx = target.indexOf(':')
        if (idx <= 0) return null
        val candidate = target.substring(0, idx)
        return candidate.takeIf { it.length > 1 && it.all { c -> c.isLetter() } }
    }

    /**
     * Resolves [target] relative to the currently selected document's directory
     * (a leading `/` is treated as workspace-root-relative), lexically normalizes
     * it (no filesystem I/O), and matches it against the scanned document set only.
     * Anything that normalizes outside the workspace root, or doesn't match a
     * scanned document, is unresolved.
     */
    private fun resolveInternal(target: String): OkfDocument? {
        val doc = currentDoc() ?: return null
        val withoutFragment = target.substringBefore('#').trim()
        if (withoutFragment.isBlank()) return null

        val combined =
            if (withoutFragment.startsWith("/")) {
                File(withoutFragment.removePrefix("/"))
            } else {
                val baseRelDir = File(doc.relativePath).parentFile
                if (baseRelDir != null) File(baseRelDir, withoutFragment) else File(withoutFragment)
            }

        val normalizedPath = combined.normalize().path.replace(File.separatorChar, '/')
        if (normalizedPath.startsWith("../") || normalizedPath == "..") return null

        return workspace.documents.firstOrNull { it.relativePath == normalizedPath }
    }
}
