package dev.kuml.workspace.latex

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.pdf.source.SourceBlock
import dev.kuml.workspace.pdf.source.SourceDocument
import dev.kuml.workspace.pdf.source.SourceInline
import dev.kuml.workspace.pdf.source.SourceLink

/**
 * Writes the neutral [SourceBlock] tree as LaTeX. Every piece of document text goes through [LatexEscaper];
 * code is set line by line (no `verbatim`, so no end marker can be forged), links are limited to
 * http/https/mailto, labels are counters.
 */
internal class LatexBlockWriter(
    private val ctx: WriterContext,
) {
    val labels: LatexLabels get() = ctx.labels

    private val figures = LatexFigureCollector(ctx)
    private val images = LatexImageCollector(ctx)
    private lateinit var doc: OkfDocument
    private var docLevel = 1

    /** Nesting of list and quote environments. LaTeX allows 6 in total, 4 per list type; we stop at 4. */
    private var depth = 0
    private var flattenNoted = false

    /** Writes a sectioning command for [index] (1 = chapter ... 6 = subparagraph). */
    fun heading(
        index: Int,
        title: String,
        anchorId: String?,
        out: StringBuilder,
    ) {
        out
            .append('\\')
            .append(SECTIONS[index.coerceIn(1, SECTIONS.size) - 1])
            .append('{')
            .append(ctx.text(title))
            .append("}\n")
        if (anchorId != null) out.append("\\label{").append(ctx.label(anchorId)).append("}\n")
        out.append('\n')
    }

    fun document(
        document: OkfDocument,
        source: SourceDocument,
        out: StringBuilder,
    ) {
        doc = document
        docLevel = source.level
        depth = 0
        flattenNoted = false
        heading(index = source.level, title = source.title, anchorId = source.anchorId, out = out)
        blocks(blocks = source.blocks, out = out)
        source.diagnostics.forEach {
            ctx.issue(level = LatexIssueLevel.NOTE, source = "${document.relativePath}:${it.line}", message = it.detail)
        }
        ctx.flushUnknown(document.relativePath)
    }

    private fun blocks(
        blocks: List<SourceBlock>,
        out: StringBuilder,
    ) {
        for (b in blocks) block(b = b, out = out)
    }

    private fun block(
        b: SourceBlock,
        out: StringBuilder,
    ) {
        when (b) {
            is SourceBlock.Heading ->
                heading(index = docLevel + b.level.coerceAtLeast(2) - 1, title = b.text, anchorId = null, out = out)
            is SourceBlock.Paragraph -> paragraph(p = b, out = out)
            is SourceBlock.ListBlock -> list(l = b, out = out)
            is SourceBlock.Table -> table(t = b, out = out)
            is SourceBlock.CodeBlock -> codeBlock(c = b, out = out)
            is SourceBlock.Quote -> quote(q = b, out = out)
            is SourceBlock.DiagramRef -> figures.include(doc = doc, blockIndex = b.blockIndex, out = out)
            is SourceBlock.ImageRef -> images.include(doc = doc, ref = b, out = out)
            is SourceBlock.RawHtml -> out.append(ctx.text(b.text)).append("\n\n")
        }
    }

    // ---- text --------------------------------------------------------------------------------------------

    private fun paragraph(
        p: SourceBlock.Paragraph,
        out: StringBuilder,
    ) {
        val sb = StringBuilder()
        for (i in p.inlines) {
            when (i) {
                is SourceInline.Text -> sb.append(run(i))
                is SourceInline.FootnoteRef ->
                    p.footnotes.firstOrNull { it.number == i.number }?.let {
                        sb
                            .append(
                                "\\footnote{\\texttt{",
                            ).append(LatexEscaper.breakableCode(raw = it.text, unknown = ctx.unknown))
                            .append("}}")
                    }
            }
        }
        if (sb.isNotBlank()) out.append(sb.toString().trim()).append("\n\n")
    }

    private fun run(t: SourceInline.Text): String {
        var s = if (t.code) "\\texttt{${ctx.code(t.text)}}" else ctx.text(t.text)
        if (s.isEmpty()) return ""
        if (t.bold) s = "\\textbf{$s}"
        if (t.italic) s = "\\emph{$s}"
        return when (val l = t.link) {
            null -> s
            is SourceLink.Internal -> "\\hyperref[${ctx.label(l.anchorId)}]{$s}"
            is SourceLink.External -> {
                val url = LatexEscaper.url(l.uri)
                if (url == null) {
                    ctx.issue(level = LatexIssueLevel.NOTE, source = doc.relativePath, message = "link scheme not exported")
                    s
                } else {
                    "\\href{$url}{$s}"
                }
            }
        }
    }

    // ---- lists and quotes ----------------------------------------------------------------------------------

    private fun noteFlatten() {
        if (!flattenNoted) {
            flattenNoted = true
            ctx.issue(
                level = LatexIssueLevel.NOTE,
                source = doc.relativePath,
                message = "nesting deeper than $MAX_DEPTH levels is flattened",
            )
        }
    }

    private fun list(
        l: SourceBlock.ListBlock,
        out: StringBuilder,
    ) {
        if (l.items.isEmpty()) return
        if (depth >= MAX_DEPTH) {
            noteFlatten()
            l.items.forEachIndexed { n, item ->
                out.append("\\par\\noindent ").append(if (l.ordered) "${n + 1}.~" else "\\textbullet{}~").append('\n')
                blocks(blocks = item, out = out)
            }
            return
        }
        val env = if (l.ordered) "enumerate" else "itemize"
        out.append("\\begin{").append(env).append("}\n")
        depth++
        for (item in l.items) {
            val body = StringBuilder()
            blocks(blocks = item, out = body)
            out.append("\\item ").append(body.toString().trim().ifEmpty { "\\mbox{}" }).append("\n")
        }
        depth--
        out.append("\\end{").append(env).append("}\n\n")
    }

    private fun quote(
        q: SourceBlock.Quote,
        out: StringBuilder,
    ) {
        if (depth >= MAX_DEPTH) {
            noteFlatten()
            blocks(blocks = q.blocks, out = out)
            return
        }
        out.append("\\begin{quote}\n")
        depth++
        q.label?.let { out.append("\\textbf{").append(ctx.text(it.replaceFirstChar { c -> c.uppercase() })).append("}\\par\n") }
        blocks(blocks = q.blocks, out = out)
        depth--
        out.append("\\end{quote}\n\n")
    }

    // ---- tables and code ---------------------------------------------------------------------------------------

    private fun table(
        t: SourceBlock.Table,
        out: StringBuilder,
    ) {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        if (cols == 0) return
        val col = ">{\\raggedright\\arraybackslash}p{${fraction(COLUMN_SHARE / cols)}\\linewidth}"
        val spec = col.repeat(cols)

        fun row(
            cells: List<String>,
            bold: Boolean,
        ): String =
            (0 until cols).joinToString(" & ") { i ->
                val c = cells.getOrNull(i).orEmpty()
                if (bold && c.isNotEmpty()) "\\textbf{${ctx.text(c)}}" else "{${ctx.text(c)}}"
            } + " \\\\ \\hline\n"

        val head = row(cells = t.header, bold = true)
        if (depth == 0) {
            out
                .append("\\begin{longtable}{")
                .append(spec)
                .append("}\n\\hline\n")
                .append(head)
                .append("\\endfirsthead\n\\hline\n")
            out.append(head).append("\\endhead\n")
            t.rows.forEach { out.append(row(cells = it, bold = false)) }
            out.append("\\end{longtable}\n\n")
        } else {
            out
                .append("\\par\\medskip\\noindent\\begin{tabular}{")
                .append(spec)
                .append("}\n\\hline\n")
                .append(head)
            t.rows.forEach { out.append(row(cells = it, bold = false)) }
            out.append("\\end{tabular}\\par\\medskip\n\n")
        }
    }

    private fun codeBlock(
        c: SourceBlock.CodeBlock,
        out: StringBuilder,
    ) {
        c.info?.let { out.append("\\kumlcodeinfo{").append(ctx.text(it)).append("}\n") }
        out.append("\\begin{kumlcode}\n")
        out.append(
            c.text.split('\n').joinToString("\\par\n") { line ->
                if (line.isBlank()) "\\mbox{}" else ctx.code(line)
            },
        )
        out.append("\n\\end{kumlcode}\n\n")
    }

    private companion object {
        const val MAX_DEPTH = 4
        const val COLUMN_SHARE = 0.9
        val SECTIONS = listOf("chapter", "section", "subsection", "subsubsection", "paragraph", "subparagraph")
    }
}
