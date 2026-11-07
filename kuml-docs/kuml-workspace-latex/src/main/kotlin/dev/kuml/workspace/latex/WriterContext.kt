package dev.kuml.workspace.latex

import dev.kuml.io.latex.LatexFamily
import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.pdf.PdfLanguage

/** State shared by the writers of one export. Single-threaded. */
internal class WriterContext(
    val workspace: OkfWorkspace,
    val language: PdfLanguage,
    val labels: LatexLabels,
    val limits: LatexKnowledgeLimits,
    val diagrams: LatexDiagramSource,
) {
    val issues = mutableListOf<LatexKnowledgeIssue>()
    val files = mutableListOf<LatexOutputFile>()
    val families = mutableSetOf<LatexFamily>()
    val unknown = LatexEscaper.Unknown()
    var figureCount = 0
    var excludedFigureCount = 0
    var imageCount = 0

    /** Counter for unique file names under `figures/`. */
    var figureNo = 0
    private val anchorLabels = LinkedHashMap<String, String>()

    /** TeX label for an anchor: derived from a counter, never from document content. */
    fun label(anchorId: String): String = anchorLabels.getOrPut(anchorId) { "kuml-l${anchorLabels.size + 1}" }

    fun issue(
        level: LatexIssueLevel,
        source: String,
        message: String,
    ) {
        issues += LatexKnowledgeIssue(level = level, source = source, message = message)
    }

    fun text(raw: String): String = LatexEscaper.text(raw = raw, unknown = unknown)

    fun code(raw: String): String = LatexEscaper.code(raw = raw, unknown = unknown)

    /** Reports the placeholders used while writing [documentPath] and starts counting again. */
    fun flushUnknown(documentPath: String) {
        if (unknown.codePoints.isEmpty()) return
        val listed = unknown.codePoints.take(MAX_LISTED).joinToString(", ") { "U+%04X".format(it) }
        issue(
            level = LatexIssueLevel.WARN,
            source = documentPath,
            message = "characters pdflatex cannot typeset were replaced by placeholders ($listed)",
        )
        unknown.codePoints.clear()
    }

    private companion object {
        const val MAX_LISTED = 5
    }
}

/** `0.67` with a dot, whatever the JVM locale. */
internal fun fraction(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

/** Lowercase `[a-z0-9-]` name part of at most 40 characters; never empty. */
internal fun slugOf(
    raw: String,
    fallback: String,
): String =
    java.text.Normalizer
        .normalize(raw, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .trim('-')
        .ifEmpty { fallback }
