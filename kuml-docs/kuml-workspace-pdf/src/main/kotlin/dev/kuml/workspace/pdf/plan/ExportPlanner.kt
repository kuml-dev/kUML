package dev.kuml.workspace.pdf.plan

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceDocumentResolver
import dev.kuml.workspace.WorkspaceGraphIndex
import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PdfExportException
import java.io.File
import java.nio.file.Files

internal data class PlannedChapter(
    val doc: OkfDocument,
    val depth: Int,
)

internal data class ExportPlan(
    val indexDoc: OkfDocument?,
    val chapters: List<PlannedChapter>,
    val glossary: List<OkfDocument>,
    val appendix: List<OkfDocument>,
    val warnings: List<String>,
) {
    /** Every document of the export in reading order. */
    val all: List<OkfDocument> get() = chapters.map { it.doc } + glossary + appendix
}

/** Decides which documents are exported and in which order (reading order follows `index.md`). */
internal object ExportPlanner {
    private const val MAX_DEPTH = 3

    fun plan(
        ws: OkfWorkspace,
        scope: ExportScope,
    ): ExportPlan {
        val inScope = scopeFilter(ws = ws, scope = scope)
        val index = ws.documents.firstOrNull { it.relativePath == "index.md" }
        val graph = WorkspaceGraphIndex.build(ws)
        val warnings = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        val chapters = mutableListOf<PlannedChapter>()

        fun isGlossary(d: OkfDocument) = d.relativePath.startsWith("glossary/") || d.frontmatter.type.equals("Glossary", ignoreCase = true)

        fun visit(
            d: OkfDocument,
            depth: Int,
        ) {
            if (d.relativePath == "index.md" || isGlossary(d) || !inScope(d) || !seen.add(d.relativePath)) {
                return
            }
            chapters += PlannedChapter(doc = d, depth = depth)
            if (d.file.name == "index.md" && depth < MAX_DEPTH) {
                graph.outgoing(d).forEach { visit(d = it.to, depth = depth + 1) }
            }
        }
        if (index != null) {
            graph.outgoing(index).forEach { visit(d = it.to, depth = 0) }
        } else {
            warnings += "No index.md at the workspace root; documents are ordered alphabetically."
        }
        val rest = ws.documents.filter { it.relativePath != "index.md" && inScope(it) && it.relativePath !in seen }
        val glossary = rest.filter { isGlossary(it) }.sortedBy { it.relativePath }
        val appendixDocs = rest.filter { !isGlossary(it) }.sortedBy { it.relativePath }
        val plan =
            if (index == null) {
                ExportPlan(
                    indexDoc = null,
                    chapters =
                        appendixDocs.map {
                            PlannedChapter(doc = it, depth = 0)
                        },
                    glossary = glossary,
                    appendix = emptyList(),
                    warnings = warnings,
                )
            } else {
                ExportPlan(indexDoc = index, chapters = chapters, glossary = glossary, appendix = appendixDocs, warnings = warnings)
            }
        if (plan.all.isEmpty()) {
            throw PdfExportException(
                code = PdfExportException.Code.EMPTY_SCOPE,
                message = "The export scope contains no documents",
            )
        }
        return plan
    }

    private fun scopeFilter(
        ws: OkfWorkspace,
        scope: ExportScope,
    ): (OkfDocument) -> Boolean =
        when (scope) {
            ExportScope.All -> { _ -> true }
            is ExportScope.Folder -> {
                val rel = scope.relativePath.replace('\\', '/').trim('/')
                val normalized = File(rel).normalize().path.replace(File.separatorChar, '/')
                if (rel.isEmpty() || rel.startsWith("..") || normalized.startsWith("..") || File(rel).isAbsolute || rel.contains(':')) {
                    throw PdfExportException(
                        code = PdfExportException.Code.PATH_OUTSIDE_ROOT,
                        message = "Scope folder is outside the workspace root",
                    )
                }
                var cur = ws.root.toPath()
                for (seg in normalized.split('/')) {
                    cur = cur.resolve(seg)
                    if (Files.isSymbolicLink(cur)) {
                        throw PdfExportException(
                            code = PdfExportException.Code.PATH_OUTSIDE_ROOT,
                            message = "Scope folder passes through a symbolic link",
                        )
                    }
                }
                if (!Files.isDirectory(
                        cur,
                    )
                ) {
                    throw PdfExportException(code = PdfExportException.Code.EMPTY_SCOPE, message = "Scope folder does not exist")
                }
                val prefix = "$normalized/"
                ({ d: OkfDocument -> d.relativePath.startsWith(prefix) })
            }
            is ExportScope.Documents -> {
                val allowed = HashSet<String>()
                for (p in scope.relativePaths) {
                    val file =
                        WorkspaceDocumentResolver.resolve(root = ws.root, requested = p).getOrElse {
                            val reason = (it as? WorkspaceDocumentResolver.DocumentResolutionException)?.rejection ?: "invalid"
                            throw PdfExportException(
                                code = PdfExportException.Code.PATH_OUTSIDE_ROOT,
                                message = "Scope document rejected: $reason",
                            )
                        }
                    allowed += file.relativeTo(ws.root).path.replace(File.separatorChar, '/')
                }
                ({ d: OkfDocument -> d.relativePath in allowed })
            }
        }
}
