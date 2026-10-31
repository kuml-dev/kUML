package dev.kuml.workspace.pdf.model

import dev.kuml.workspace.pdf.render.DrawnSvg
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject

/** Target of a link: an anchor inside the exported PDF or an external URI. */
internal sealed interface LinkTarget {
    data class Internal(
        val anchorId: String,
    ) : LinkTarget

    data class External(
        val uri: String,
    ) : LinkTarget
}

internal sealed interface Inline {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: LinkTarget? = null,
    ) : Inline

    data class FootnoteRef(
        val number: Int,
    ) : Inline
}

internal data class Footnote(
    val number: Int,
    val text: String,
)

internal sealed interface Block {
    data class Heading(
        val level: Int,
        val text: String,
        val anchorId: String,
    ) : Block

    data class Paragraph(
        val inlines: List<Inline>,
        val footnotes: List<Footnote> = emptyList(),
    ) : Block

    data class ListBlock(
        val ordered: Boolean,
        val items: List<List<Block>>,
        val depth: Int = 0,
    ) : Block

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val title: String? = null,
    ) : Block

    data class CodeBlock(
        val text: String,
    ) : Block

    data class Quote(
        val blocks: List<Block>,
        val label: String? = null,
    ) : Block

    /** Placeholder that the composer replaces by [Diagram] or [DiagramFailed]. */
    data class DiagramRef(
        val blockIndex: Int,
    ) : Block

    data class Diagram(
        val title: String,
        val documentPath: String,
        val blockIndex: Int,
        val line: Int,
        val drawn: DrawnSvg,
    ) : Block

    data class DiagramFailed(
        val title: String,
        val reason: String,
    ) : Block

    data class ImageBlock(
        val image: PDImageXObject,
        val widthPx: Int,
        val heightPx: Int,
        val alt: String,
    ) : Block

    data class AiDraftBox(
        val text: String,
    ) : Block

    data class Note(
        val text: String,
    ) : Block

    data object PageBreak : Block
}

internal data class CoverData(
    val title: String,
    val subtitle: String?,
    val author: String?,
    val organization: String?,
    val version: String?,
    /** "Stand" from the manifest; an unparsable date is dropped, never invented. */
    val date: java.time.LocalDate?,
    val status: String?,
    /** Export date from the injected clock. */
    val exportedOn: java.time.LocalDate,
    /** Number of documents contained in this output. */
    val documentCount: Int,
    val producer: String,
    val logo: PDImageXObject?,
    val openItems: Int?,
    val openItemsAnchor: String?,
)

internal data class Section(
    val title: String,
    val level: Int,
    val anchorId: String,
    val blocks: List<Block>,
)

/** One output PDF before layout. */
internal data class ComposedDocument(
    val fileStem: String,
    val title: String,
    val cover: CoverData?,
    val tocEnabled: Boolean,
    val sections: List<Section>,
    val diagramsOnly: Boolean = false,
)
