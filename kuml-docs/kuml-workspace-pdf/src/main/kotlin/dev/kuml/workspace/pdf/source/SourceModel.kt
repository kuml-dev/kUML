package dev.kuml.workspace.pdf.source

/**
 * The neutral, PDFBox-free view of an OKF document body. [dev.kuml.workspace.pdf.markdown.MarkdownToBlocks]
 * produces it once; the PDF composer maps it to its layout blocks, the LaTeX exporter writes it as LaTeX.
 * Nothing in this model has been loaded from disk (images are only referenced) and nothing has been executed.
 */
public sealed interface SourceLink {
    /** Anchor inside the exported document. */
    public data class Internal(
        public val anchorId: String,
    ) : SourceLink

    /** Absolute `http`, `https` or `mailto` URI; every other scheme never becomes a link. */
    public data class External(
        public val uri: String,
    ) : SourceLink
}

public sealed interface SourceInline {
    public data class Text(
        public val text: String,
        public val bold: Boolean = false,
        public val italic: Boolean = false,
        public val code: Boolean = false,
        public val strike: Boolean = false,
        public val link: SourceLink? = null,
    ) : SourceInline

    public data class FootnoteRef(
        public val number: Int,
    ) : SourceInline
}

public data class SourceFootnote(
    public val number: Int,
    public val text: String,
)

public sealed interface SourceBlock {
    public data class Heading(
        public val level: Int,
        public val text: String,
        public val anchorId: String,
    ) : SourceBlock

    public data class Paragraph(
        public val inlines: List<SourceInline>,
        public val footnotes: List<SourceFootnote> = emptyList(),
    ) : SourceBlock

    public data class ListBlock(
        public val ordered: Boolean,
        public val items: List<List<SourceBlock>>,
        public val depth: Int = 0,
    ) : SourceBlock

    public data class Table(
        public val header: List<String>,
        public val rows: List<List<String>>,
    ) : SourceBlock

    /** [info] is the sanitised info string of the fence (untrusted text, consumers must escape it). */
    public data class CodeBlock(
        public val text: String,
        public val info: String? = null,
    ) : SourceBlock

    public data class Quote(
        public val blocks: List<SourceBlock>,
        public val label: String? = null,
    ) : SourceBlock

    /** Placeholder for the ```` ```kuml ```` block with this index in the document. */
    public data class DiagramRef(
        public val blockIndex: Int,
    ) : SourceBlock

    /** A paragraph that consists of one image. [destination] is untrusted and not yet resolved. */
    public data class ImageRef(
        public val destination: String,
        public val alt: String,
        public val line: Int,
    ) : SourceBlock

    /** An HTML block. Never interpreted; the consumer decides how to show the text. */
    public data class RawHtml(
        public val text: String,
        public val line: Int,
    ) : SourceBlock
}

/** Markdown constructs that the neutral model cannot carry faithfully. */
public enum class SourceConstruct {
    RAW_HTML,
    FOOTNOTE_SYNTAX,
    MATH,
    STRIKETHROUGH,
    REFERENCE_LINK,
    LIST_TOO_DEEP,
    TABLE_TRUNCATED,
    CODE_TRUNCATED,
    INLINE_IMAGE,
}

public data class SourceDiagnostic(
    public val documentPath: String,
    public val line: Int,
    public val construct: SourceConstruct,
    public val detail: String,
)

public data class SourceDocument(
    public val relativePath: String,
    public val title: String,
    public val level: Int,
    public val anchorId: String,
    public val blocks: List<SourceBlock>,
    public val diagnostics: List<SourceDiagnostic>,
)
