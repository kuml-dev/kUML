package dev.kuml.workspace.latex

import dev.kuml.io.latex.LatexFamily
import dev.kuml.markdown.KumlCodeBlock
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.ExportMetadata
import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PdfLanguage

/**
 * Port: script execution stays in the caller (`kuml-cli`), this module never runs scripts. Implementations are
 * called one after another on the calling thread.
 */
public fun interface LatexDiagramSource {
    public fun render(
        document: OkfDocument,
        blockIndex: Int,
        block: KumlCodeBlock,
    ): LatexDiagramOutcome
}

/** Outcome of exporting one ```` ```kuml ```` block as TikZ. */
public sealed interface LatexDiagramOutcome {
    /** [tex] is a snippet written for the shared preamble (`kuml-preamble.tex`). */
    public data class Rendered(
        public val tex: String,
        public val family: LatexFamily,
        public val diagramCount: Int,
        public val diagramName: String?,
    ) : LatexDiagramOutcome

    /** The diagram family has no LaTeX output; not an error of the document. */
    public data class Skipped(
        public val reason: String,
    ) : LatexDiagramOutcome

    /** [reason] must not contain script content. */
    public data class Failed(
        public val reason: String,
    ) : LatexDiagramOutcome
}

/** Hard limits that keep an export bounded (denial-of-service protection). */
public data class LatexKnowledgeLimits(
    public val maxDocuments: Int = 500,
    public val maxDiagrams: Int = 300,
    public val maxImages: Int = 500,
    public val maxImageBytes: Long = 10L * 1024 * 1024,
    public val maxTotalImageBytes: Long = 200L * 1024 * 1024,
    public val maxImagePixels: Int = 8_000,
    public val maxMarkdownBytesPerDocument: Int = 5 * 1024 * 1024,
) {
    public companion object {
        public val DEFAULT: LatexKnowledgeLimits = LatexKnowledgeLimits()
    }
}

/** All options of one export. [generator] is written into the header comment of `main.tex`. */
public data class LatexKnowledgeOptions(
    public val scope: ExportScope = ExportScope.All,
    public val metadata: ExportMetadata = ExportMetadata(),
    public val language: PdfLanguage? = null,
    public val toc: Boolean = true,
    public val generator: String,
    public val limits: LatexKnowledgeLimits = LatexKnowledgeLimits.DEFAULT,
)

/** Severity of a [LatexKnowledgeIssue]. Only [NOTE] is informational; the other levels block `--strict`. */
public enum class LatexIssueLevel { NOTE, SKIP, WARN, ERROR }

/** [source] is `<document>.md:<line>` or just the document path; [message] never contains document content. */
public data class LatexKnowledgeIssue(
    public val level: LatexIssueLevel,
    public val source: String,
    public val message: String,
)

/** A file of the export, relative to the output directory, `/`-separated. */
public sealed interface LatexOutputFile {
    public val path: String

    public data class Text(
        override val path: String,
        public val content: String,
    ) : LatexOutputFile

    public class Binary(
        override val path: String,
        public val bytes: ByteArray,
    ) : LatexOutputFile {
        override fun equals(other: Any?): Boolean = other is Binary && other.path == path && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Binary(path=$path, bytes=${bytes.size})"
    }
}

/** Everything of one export, in memory. Nothing has been written yet. */
public data class PreparedLatexKnowledge(
    public val files: List<LatexOutputFile>,
    public val issues: List<LatexKnowledgeIssue>,
    public val documentCount: Int,
    public val figureCount: Int,
    public val excludedFigureCount: Int,
    public val imageCount: Int,
    public val warnings: List<String>,
)

/** Export failure with a stable [code]; the message never contains document content. */
public class LatexKnowledgeExportException(
    public val code: Code,
    message: String,
) : Exception(message) {
    public enum class Code { EMPTY_SCOPE, PATH_OUTSIDE_ROOT, LIMIT_DOCUMENTS, LIMIT_DIAGRAMS, LIMIT_IMAGES, IO }
}
