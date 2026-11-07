package dev.kuml.workspace.pdf.compose

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.model.Block
import dev.kuml.workspace.pdf.model.Footnote
import dev.kuml.workspace.pdf.model.Inline
import dev.kuml.workspace.pdf.model.LinkTarget
import dev.kuml.workspace.pdf.source.SourceBlock
import dev.kuml.workspace.pdf.source.SourceInline
import dev.kuml.workspace.pdf.source.SourceLink

/**
 * Maps the neutral [SourceBlock] tree to the layout blocks of the PDF. Images are loaded here (PDFBox belongs to
 * exactly one document); an image that cannot be embedded falls back to its alt text, as before.
 */
internal object SourceToPdfBlocks {
    fun map(
        blocks: List<SourceBlock>,
        from: OkfDocument,
        images: LocalImages,
    ): List<Block> = blocks.flatMap { one(b = it, from = from, images = images) }

    private fun one(
        b: SourceBlock,
        from: OkfDocument,
        images: LocalImages,
    ): List<Block> =
        when (b) {
            is SourceBlock.Heading -> listOf(Block.Heading(level = b.level, text = b.text, anchorId = b.anchorId))
            is SourceBlock.Paragraph ->
                listOf(
                    Block.Paragraph(
                        inlines = b.inlines.map(::inline),
                        footnotes = b.footnotes.map { Footnote(number = it.number, text = it.text) },
                    ),
                )
            is SourceBlock.ListBlock ->
                listOf(
                    Block.ListBlock(
                        ordered = b.ordered,
                        items = b.items.map { map(blocks = it, from = from, images = images) },
                        depth = b.depth,
                    ),
                )
            is SourceBlock.Table -> listOf(Block.Table(header = b.header, rows = b.rows))
            is SourceBlock.CodeBlock -> listOf(Block.CodeBlock(text = b.text))
            is SourceBlock.Quote -> listOf(Block.Quote(blocks = map(blocks = b.blocks, from = from, images = images), label = b.label))
            is SourceBlock.DiagramRef -> listOf(Block.DiagramRef(blockIndex = b.blockIndex))
            is SourceBlock.ImageRef -> {
                val img = images.forMarkdown(from = from, rel = b.destination)
                when {
                    img != null -> listOf(img.copy(alt = b.alt))
                    b.alt.isNotEmpty() -> listOf(Block.Paragraph(inlines = listOf(Inline.Text(text = b.alt))))
                    else -> emptyList()
                }
            }
            is SourceBlock.RawHtml -> listOf(Block.Paragraph(inlines = listOf(Inline.Text(text = b.text))))
        }

    private fun inline(i: SourceInline): Inline =
        when (i) {
            is SourceInline.Text ->
                Inline.Text(text = i.text, bold = i.bold, italic = i.italic, code = i.code, link = i.link?.let(::link))
            is SourceInline.FootnoteRef -> Inline.FootnoteRef(number = i.number)
        }

    private fun link(l: SourceLink): LinkTarget =
        when (l) {
            is SourceLink.Internal -> LinkTarget.Internal(anchorId = l.anchorId)
            is SourceLink.External -> LinkTarget.External(uri = l.uri)
        }
}
