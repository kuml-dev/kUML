package dev.kuml.workspace.pdf.markdown

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.model.LinkTarget
import java.io.File

/** Outcome of resolving a link found in Markdown. */
internal sealed interface ResolvedTarget {
    data class Link(
        val target: LinkTarget,
    ) : ResolvedTarget

    /** Not clickable: plain text, with an optional suffix such as "(not in export)". */
    data class PlainText(
        val suffix: String?,
    ) : ResolvedTarget
}

/**
 * Resolves Markdown links and Obsidian wikilinks for the PDF.
 *
 * Only `http`, `https` and `mailto` become external links; every other scheme (`javascript:`, `file:`,
 * `data:`, ...) stays plain text and can never become a PDF action.
 */
internal class InternalLinkResolver(
    private val allDocuments: List<OkfDocument>,
    private val inExport: Set<String>,
    private val notInExportLabel: String,
) {
    private val byPath = allDocuments.associateBy { it.relativePath }
    private val byBasename: Map<String, List<OkfDocument>> =
        allDocuments.groupBy { it.file.nameWithoutExtension.lowercase() }

    fun resolve(
        from: OkfDocument,
        rawTarget: String,
    ): ResolvedTarget {
        val target = rawTarget.trim()
        if (target.isEmpty()) return ResolvedTarget.PlainText(suffix = null)
        if (target.startsWith("wikilink:")) return wikilink(target.removePrefix("wikilink:"))
        val scheme =
            SCHEME
                .find(target)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        if (scheme != null) {
            return if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                ResolvedTarget.Link(target = LinkTarget.External(uri = target))
            } else {
                ResolvedTarget.PlainText(suffix = null)
            }
        }
        if (target.startsWith("#")) {
            return ResolvedTarget.Link(target = LinkTarget.Internal(anchorId = docAnchor(from.relativePath)))
        }
        val withoutFragment = target.substringBefore('#').trim()
        val combined =
            if (withoutFragment.startsWith("/")) {
                File(withoutFragment.removePrefix("/"))
            } else {
                val base = File(from.relativePath).parentFile
                if (base != null) File(base, withoutFragment) else File(withoutFragment)
            }
        val normalized = combined.normalize().path.replace(File.separatorChar, '/')
        if (normalized.startsWith("../") || normalized == "..") return ResolvedTarget.PlainText(suffix = null)
        return documentLink(normalized)
    }

    private fun wikilink(name: String): ResolvedTarget {
        val base =
            name
                .substringBefore('#')
                .trim()
                .substringAfterLast('/')
                .removeSuffix(".md")
                .lowercase()
        val matches = byBasename[base].orEmpty()
        if (matches.size != 1) return ResolvedTarget.PlainText(suffix = null)
        return documentLink(matches.single().relativePath)
    }

    private fun documentLink(path: String): ResolvedTarget {
        val doc = byPath[path] ?: return ResolvedTarget.PlainText(suffix = null)
        return if (doc.relativePath in inExport) {
            ResolvedTarget.Link(target = LinkTarget.Internal(anchorId = docAnchor(doc.relativePath)))
        } else {
            ResolvedTarget.PlainText(suffix = "($notInExportLabel)")
        }
    }

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        fun docAnchor(relativePath: String): String = "doc:$relativePath"
    }
}
