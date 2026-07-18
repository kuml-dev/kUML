package dev.kuml.workspace

import java.io.File

/** A single resolved cross-link between two documents in a [WorkspaceGraphIndex] (ADR-0011 FT-6). */
public data class ResolvedLink(
    public val from: OkfDocument,
    public val to: OkfDocument,
    public val line: Int,
)

/**
 * Bidirectional cross-link index over a scanned [OkfWorkspace] (ADR-0011 FT-6).
 *
 * [WorkspaceScanner] already extracts every Markdown link in [OkfDocument.links],
 * and [dev.kuml.desktop.workspace.DefaultWorkspaceLinkHandler] (kUML Desktop)
 * already resolves a single clicked link for forward navigation. What was
 * still missing — the actual "Knowledge-Graph-Index" ADR-0011 describes as
 * the differentiator over a flat document tree — is a workspace-wide,
 * precomputed index that also answers the reverse question: *which documents
 * link to this one?* ("backlinks"), so relationships between documents are
 * navigable in both directions, not only by clicking a forward link.
 *
 * Resolution mirrors [OkfValidator]'s link-integrity check and
 * `DefaultWorkspaceLinkHandler`'s click resolution: external links
 * (`scheme://`), `mailto:`, and anchor-only (`#...`) targets are skipped;
 * everything else is resolved relative to the *source* document's directory,
 * lexically normalized (no filesystem I/O), and matched against
 * [OkfWorkspace.documents] by normalized relative path only — a target that
 * normalizes outside the workspace root, or doesn't match a scanned document,
 * simply doesn't resolve rather than falling back to reading the filesystem.
 */
public class WorkspaceGraphIndex private constructor(
    private val forward: Map<String, List<ResolvedLink>>,
    private val backward: Map<String, List<ResolvedLink>>,
) {
    /** Documents [doc] links to, in source order. Empty if [doc] has no resolvable outgoing links. */
    public fun outgoing(doc: OkfDocument): List<ResolvedLink> = forward[doc.relativePath].orEmpty()

    /** Documents that link to [doc] ("backlinks"), in workspace scan order. Empty if none do. */
    public fun backlinks(doc: OkfDocument): List<ResolvedLink> = backward[doc.relativePath].orEmpty()

    public companion object {
        public fun build(ws: OkfWorkspace): WorkspaceGraphIndex {
            val byPath: Map<String, OkfDocument> = ws.documents.associateBy { it.relativePath }
            val forward = mutableMapOf<String, MutableList<ResolvedLink>>()
            val backward = mutableMapOf<String, MutableList<ResolvedLink>>()

            for (doc in ws.documents) {
                for (link in doc.links) {
                    val target = resolveTarget(doc, link.target, byPath) ?: continue
                    val resolved = ResolvedLink(from = doc, to = target, line = link.line)
                    forward.getOrPut(doc.relativePath) { mutableListOf() }.add(resolved)
                    backward.getOrPut(target.relativePath) { mutableListOf() }.add(resolved)
                }
            }
            return WorkspaceGraphIndex(forward = forward, backward = backward)
        }

        private fun resolveTarget(
            doc: OkfDocument,
            rawTarget: String,
            byPath: Map<String, OkfDocument>,
        ): OkfDocument? {
            if (rawTarget.contains("://") || rawTarget.startsWith("mailto:")) return null
            val withoutFragment = rawTarget.substringBefore('#').trim()
            if (withoutFragment.isBlank()) return null // anchor-only link within the same document

            val combined =
                if (withoutFragment.startsWith("/")) {
                    File(withoutFragment.removePrefix("/"))
                } else {
                    val baseDir = File(doc.relativePath).parentFile
                    if (baseDir != null) File(baseDir, withoutFragment) else File(withoutFragment)
                }
            val normalized = combined.normalize().path.replace(File.separatorChar, '/')
            if (normalized.startsWith("../") || normalized == "..") return null
            return byPath[normalized]
        }
    }
}
