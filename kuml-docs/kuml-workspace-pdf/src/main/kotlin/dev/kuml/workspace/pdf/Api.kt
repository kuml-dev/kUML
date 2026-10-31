package dev.kuml.workspace.pdf

import dev.kuml.markdown.KumlCodeBlock
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import java.io.File
import java.time.Duration
import java.time.LocalDate

/** What kind of PDF output an export produces. */
public enum class PdfExportKind { HANDBOOK, PER_DOCUMENT, DIAGRAMS }

/** Paper format. Sizes are in PDF points. A3 is chosen per diagram only with [PageStrategy.AUTO]. */
public enum class PageFormat(
    public val widthPt: Float,
    public val heightPt: Float,
) {
    A4(widthPt = 595.28f, heightPt = 841.89f),
    A3(widthPt = 841.89f, heightPt = 1190.55f),
}

/** Page orientation; [AUTO] turns individual diagram pages to landscape when that gains scale. */
public enum class PageOrientation { PORTRAIT, LANDSCAPE, AUTO }

/**
 * How diagram pages are sized. [AUTO] picks A4 portrait/landscape or A3 per diagram and splits very large
 * diagrams into tiles; [FIXED] keeps the configured page format and orientation.
 */
public enum class PageStrategy { AUTO, FIXED }

/** Why a [ScaleWarning] was raised. */
public enum class ScaleWarningCode { ILLEGIBLE, }

/** Language of the generated labels (table of contents, headers, ...). */
public enum class PdfLanguage { DE, EN }

/** Which documents are part of the export. */
public sealed interface ExportScope {
    /** Every document of the workspace. */
    public data object All : ExportScope

    /** Every document below [relativePath] (a workspace-relative folder). */
    public data class Folder(
        public val relativePath: String,
    ) : ExportScope

    /** An explicit list of workspace-relative document paths. */
    public data class Documents(
        public val relativePaths: List<String>,
    ) : ExportScope
}

/** Cover-page metadata; `null` fields fall back to the `index.md` frontmatter. */
public data class ExportMetadata(
    public val title: String? = null,
    public val subtitle: String? = null,
    public val author: String? = null,
    public val version: String? = null,
    public val date: LocalDate? = null,
    public val status: String? = null,
)

/** Hard limits that keep an export bounded (denial-of-service protection). */
public data class ExportLimits(
    public val maxPages: Int = 2_000,
    public val maxDocuments: Int = 500,
    public val maxDiagrams: Int = 300,
    public val maxOutputBytes: Long = 200L * 1024 * 1024,
    public val maxSvgBytes: Int = 20 * 1024 * 1024,
    public val maxMarkdownBytesPerDocument: Int = 5 * 1024 * 1024,
    public val perDiagramTimeout: Duration = Duration.ofSeconds(60),
    public val totalTimeout: Duration = Duration.ofMinutes(15),
) {
    public companion object {
        public val DEFAULT: ExportLimits = ExportLimits()
    }
}

/** All options of one export. */
public data class PdfExportOptions(
    public val kind: PdfExportKind,
    public val scope: ExportScope = ExportScope.All,
    public val page: PageFormat = PageFormat.A4,
    public val orientation: PageOrientation = PageOrientation.AUTO,
    /** Cover page. For [PdfExportKind.PER_DOCUMENT] callers should default this to `false`. */
    public val cover: Boolean = true,
    public val toc: Boolean = true,
    public val headerFooter: Boolean = true,
    public val draft: Boolean = false,
    /** [PdfExportKind.DIAGRAMS] only: one file per diagram. */
    public val split: Boolean = false,
    public val aiNotes: Boolean = false,
    public val language: PdfLanguage? = null,
    public val metadata: ExportMetadata = ExportMetadata(),
    /** Producer string written into the PDF, e.g. `kUML 0.56.0`. */
    public val producerVersion: String,
    public val limits: ExportLimits = ExportLimits.DEFAULT,
    /** `null` = [PageStrategy.AUTO] when [orientation] is AUTO, otherwise [PageStrategy.FIXED]. */
    public val pageStrategy: PageStrategy? = null,
    /** Split diagrams that would fall below [minScale] across several pages. */
    public val tiling: Boolean = true,
    /** Smallest acceptable diagram scale, 0.25..1.0. */
    public val minScale: Float = DEFAULT_MIN_SCALE,
) {
    init {
        require(minScale in 0.25f..1.0f) { "minScale must be between 0.25 and 1.0" }
        require(!(pageStrategy == PageStrategy.AUTO && orientation != PageOrientation.AUTO)) {
            "pageStrategy AUTO requires orientation AUTO"
        }
    }

    /** The strategy that is actually applied. */
    public val effectivePageStrategy: PageStrategy
        get() = pageStrategy ?: if (orientation == PageOrientation.AUTO) PageStrategy.AUTO else PageStrategy.FIXED

    public companion object {
        public const val DEFAULT_MIN_SCALE: Float = 0.5f
    }
}

/** Port: script execution stays in the caller (`kuml-cli`), this module never runs scripts. */
public fun interface DiagramSource {
    public fun render(
        document: OkfDocument,
        blockIndex: Int,
        block: KumlCodeBlock,
    ): DiagramArtifact
}

/** Outcome of rendering one ```` ```kuml ```` block. */
public sealed interface DiagramArtifact {
    public data class Rendered(
        public val svg: String,
        public val facts: DiagramFacts?,
        public val diagramCount: Int = 1,
    ) : DiagramArtifact

    /** [reason] must not contain script content. */
    public data class Failed(
        public val reason: String,
    ) : DiagramArtifact
}

/** Facts extracted from a diagram's model: element table, traceability links, model notes. */
public data class DiagramFacts(
    public val elements: List<ElementRow>,
    public val traces: List<TraceLink>,
    public val notes: List<ModelNote>,
)

public data class ElementRow(
    public val kind: String,
    public val name: String,
    public val detail: String?,
)

public enum class TraceKind { SATISFY, VERIFY, DERIVE }

public data class TraceLink(
    public val kind: TraceKind,
    public val sourceName: String,
    public val targetName: String,
    public val diagramName: String,
)

public data class ModelNote(
    public val text: String,
    public val anchors: List<String>,
    public val open: Boolean,
)

/** Optional, off by default. The text is always rendered inside an "AI draft" box. */
public fun interface AiNotesProvider {
    public fun explain(
        document: OkfDocument,
        facts: DiagramFacts?,
    ): String?
}

public enum class ExportPhase { RENDERING, LAYOUT, WRITING }

public fun interface ExportProgressListener {
    public fun onProgress(
        done: Int,
        total: Int,
        phase: ExportPhase,
    )
}

/** A diagram that stays below the minimum scale even after all layout options (A3, tiling) were used. */
public data class ScaleWarning(
    public val documentPath: String,
    public val blockIndex: Int,
    public val title: String,
    public val scale: Double,
    /** Localized hint what the author can do. */
    public val suggestion: String,
    public val code: ScaleWarningCode = ScaleWarningCode.ILLEGIBLE,
    public val minScale: Double = 0.5,
    /** Localized, e.g. `illegible (scale 0.42 < 0.50)`. */
    public val message: String = "",
)

/** How the diagrams of an export were placed: A3 pages, tiled diagrams and unreadable ones. */
public data class PdfLayoutSummary(
    public val a3Diagrams: Int,
    public val tiledDiagrams: Int,
    public val tilePages: Int,
    public val illegibleDiagrams: Int,
) {
    public operator fun plus(o: PdfLayoutSummary): PdfLayoutSummary =
        PdfLayoutSummary(
            a3Diagrams = a3Diagrams + o.a3Diagrams,
            tiledDiagrams = tiledDiagrams + o.tiledDiagrams,
            tilePages = tilePages + o.tilePages,
            illegibleDiagrams = illegibleDiagrams + o.illegibleDiagrams,
        )

    public companion object {
        public val EMPTY: PdfLayoutSummary = PdfLayoutSummary(a3Diagrams = 0, tiledDiagrams = 0, tilePages = 0, illegibleDiagrams = 0)
    }
}

public data class PdfExportResult(
    public val files: List<File>,
    public val pageCount: Int,
    public val documentCount: Int,
    public val diagramCount: Int,
    public val warnings: List<ScaleWarning>,
    public val failures: List<OkfFinding>,
    public val layoutSummary: PdfLayoutSummary = PdfLayoutSummary.EMPTY,
)

/** Export failure with a stable [code]; the message never contains document content. */
public class PdfExportException(
    public val code: Code,
    message: String,
) : Exception(message) {
    public enum class Code {
        OUTPUT_EXISTS,
        OUTPUT_INVALID,
        PATH_OUTSIDE_ROOT,
        LIMIT_DOCUMENTS,
        LIMIT_DIAGRAMS,
        LIMIT_PAGES,
        LIMIT_OUTPUT_SIZE,
        TIMEOUT,
        CANCELLED,
        EMPTY_SCOPE,
        IO,
    }
}
