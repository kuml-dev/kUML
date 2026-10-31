package dev.kuml.workspace.pdf.layout

import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PdfExportException
import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.PdfLayoutSummary
import dev.kuml.workspace.pdf.ScaleWarning
import dev.kuml.workspace.pdf.ScaleWarningCode
import dev.kuml.workspace.pdf.labels.PdfLabels
import dev.kuml.workspace.pdf.model.Block
import dev.kuml.workspace.pdf.model.ComposedDocument
import dev.kuml.workspace.pdf.model.Inline
import dev.kuml.workspace.pdf.model.LinkTarget
import dev.kuml.workspace.pdf.model.Section
import dev.kuml.workspace.pdf.render.PdfFontSet
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.min

/** Where an anchor lives: 0-based page index and distance from the page top. */
internal data class AnchorPos(
    val pageIndex: Int,
    val y: Float,
)

internal data class HeadingEntry(
    val level: Int,
    val text: String,
    val anchorId: String,
    val pageIndex: Int,
    val y: Float,
)

internal data class PendingLink(
    val pageIndex: Int,
    val x: Float,
    val yTop: Float,
    val width: Float,
    val height: Float,
    val target: LinkTarget,
)

internal class LayoutResult(
    val pageCount: Int,
    val anchors: Map<String, AnchorPos>,
    val headings: List<HeadingEntry>,
    val links: List<PendingLink>,
    val pageSection: Map<Int, String>,
    val warnings: List<ScaleWarning>,
    val frontPages: Int,
    val summary: PdfLayoutSummary = PdfLayoutSummary.EMPTY,
    /** Extra bookmarks (level 4) for tiled diagrams; deliberately not part of [headings] (TOC size). */
    val figureMarks: List<HeadingEntry> = emptyList(),
)

/** Typography constants (points). */
internal object Typo {
    const val BODY = 10.5f
    const val LEADING = 15f
    const val H1 = 20f
    const val H2 = 15f
    const val H3 = 12f
    const val CODE = 9f
    const val SMALL = 8f
    const val PARA_GAP = 6f
    val TEXT = Color(0x1A, 0x1A, 0x1A)
    val LINK = Color(0x1F, 0x4E, 0x8C)
    val GRAY = Color(0x66, 0x66, 0x66)
    val CODE_BG = Color(0xF4, 0xF4, 0xF4)
    val RULE = Color(0xCC, 0xCC, 0xCC)
    val HEAD_BG = Color(0xEE, 0xF1, 0xF6)
    val ERROR = Color(0xB0, 0x30, 0x30)
}

/**
 * Lays a [ComposedDocument] out into a [PDDocument], page by page, drawing straight into PDFBox content
 * streams. Cover and table-of-contents pages are reserved up front (their size is known from the
 * heading count), filled after the body, so page numbers are exact without iteration.
 */
internal class PdfLayoutEngine(
    private val doc: PDDocument,
    private val fonts: PdfFontSet,
    private val labels: PdfLabels,
    private val options: PdfExportOptions,
    private val cancelled: () -> Boolean,
) {
    private val format: PageFormat = options.page
    private val geometry = PageGeometry()
    private val baseMargin = geometry.margin(format)
    private var margin = baseMargin
    private var a3Diagrams = 0
    private var tiledDiagrams = 0
    private var tilePages = 0
    private var illegibleDiagrams = 0
    private val figureMarks = mutableListOf<HeadingEntry>()
    private var diagramsOnlyMode = false
    private val anchors = HashMap<String, AnchorPos>()
    private val headings = mutableListOf<HeadingEntry>()
    private val links = mutableListOf<PendingLink>()
    private val pageSection = HashMap<Int, String>()
    private val warnings = mutableListOf<ScaleWarning>()
    private val widthCache = HashMap<PDFont, HashMap<Int, Float>>()

    private var page: PDPage? = null
    private var cs: PDPageContentStream? = null
    private var pageIndex = -1
    private var pageW = 0f
    private var pageH = 0f
    private var y = 0f
    private var currentSection = ""
    private var frontPages = 0

    // ---- page management ------------------------------------------------------------------------

    private fun pageSize(
        landscape: Boolean,
        f: PageFormat = format,
    ): Pair<Float, Float> = if (landscape) f.heightPt to f.widthPt else f.widthPt to f.heightPt

    private fun addPage(
        landscape: Boolean,
        f: PageFormat = format,
    ): PDPage {
        if (doc.numberOfPages >= options.limits.maxPages) {
            throw PdfExportException(
                code = PdfExportException.Code.LIMIT_PAGES,
                message = "Export would exceed the page limit of ${options.limits.maxPages}",
            )
        }
        if (cancelled()) throw PdfExportException(code = PdfExportException.Code.CANCELLED, message = "Export cancelled")
        val (w, h) = pageSize(landscape = landscape, f = f)
        val p = PDPage(PDRectangle(w, h))
        doc.addPage(p)
        return p
    }

    private fun newPage(
        landscape: Boolean = options.orientation == PageOrientation.LANDSCAPE,
        f: PageFormat = format,
    ) {
        closePage()
        val p = addPage(landscape = landscape, f = f)
        page = p
        margin = geometry.margin(f)
        val (w, h) = pageSize(landscape = landscape, f = f)
        pageW = w
        pageH = h
        pageIndex = doc.numberOfPages - 1
        cs = PDPageContentStream(doc, p)
        y = margin
        pageSection[pageIndex] = currentSection
    }

    private fun closePage() {
        cs?.close()
        cs = null
        page = null
        margin = baseMargin
    }

    private val contentWidth get() = pageW - 2 * margin
    private val bottom get() = pageH - margin

    /** Makes sure [h] points fit on the current page, opening a new one if not. */
    private fun ensure(h: Float) {
        if (page == null) newPage()
        if (y + h > bottom) newPage()
    }

    private fun fresh() {
        if (page == null || y > margin + 0.5f) newPage()
    }

    private fun pdfY(yTop: Float) = pageH - yTop

    // ---- text primitives --------------------------------------------------------------------------

    private fun width(
        font: PDFont,
        size: Float,
        text: String,
    ): Float {
        val cache = widthCache.getOrPut(font) { HashMap() }
        var sum = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sum += cache.getOrPut(cp) { font.getStringWidth(String(Character.toChars(cp))) }
        }
        return sum / 1000f * size
    }

    private fun drawText(
        text: String,
        x: Float,
        baselineTop: Float,
        font: PDFont,
        size: Float,
        color: Color,
    ) {
        val s = fonts.glyphSafe(text = text, font = font)
        if (s.isEmpty()) return
        val c = cs ?: return
        c.beginText()
        c.setFont(font, size)
        c.setNonStrokingColor(color)
        c.newLineAtOffset(x, pdfY(baselineTop))
        c.showText(s)
        c.endText()
    }

    private fun fillRect(
        x: Float,
        top: Float,
        w: Float,
        h: Float,
        color: Color,
    ) {
        val c = cs ?: return
        c.setNonStrokingColor(color)
        c.addRect(x, pdfY(top + h), w, h)
        c.fill()
    }

    private fun strokeRect(
        x: Float,
        top: Float,
        w: Float,
        h: Float,
        color: Color,
    ) {
        val c = cs ?: return
        c.setStrokingColor(color)
        c.setLineWidth(0.6f)
        c.addRect(x, pdfY(top + h), w, h)
        c.stroke()
    }

    private fun hLine(
        x1: Float,
        x2: Float,
        top: Float,
        color: Color = Typo.RULE,
    ) {
        val c = cs ?: return
        c.setStrokingColor(color)
        c.setLineWidth(0.5f)
        c.moveTo(x1, pdfY(top))
        c.lineTo(x2, pdfY(top))
        c.stroke()
    }

    // ---- rich paragraphs ----------------------------------------------------------------------------

    private data class Run(
        val font: PDFont,
        val size: Float,
        val color: Color,
        val link: LinkTarget?,
        val raise: Float,
    )

    private data class Word(
        val text: String,
        val run: Run,
        val spaceBefore: Boolean,
    )

    private class Piece(
        val text: String,
        val run: Run,
        val x: Float,
        val w: Float,
    )

    private fun runFor(inline: Inline.Text): Run {
        val font =
            when {
                inline.code -> fonts.mono
                inline.bold -> fonts.semiBold
                inline.italic -> fonts.italic
                else -> fonts.regular
            }
        val size = if (inline.code) Typo.CODE + 0.5f else Typo.BODY
        return Run(font = font, size = size, color = if (inline.link != null) Typo.LINK else Typo.TEXT, link = inline.link, raise = 0f)
    }

    private fun words(
        inlines: List<Inline>,
        base: Float = Typo.BODY,
        color: Color? = null,
    ): List<Word> {
        val out = mutableListOf<Word>()
        var pendingSpace = false
        for (inl in inlines) {
            when (inl) {
                is Inline.Text -> {
                    var run = runFor(inl)
                    if (base != Typo.BODY) run = run.copy(size = if (inl.code) base - 1f else base)
                    if (color != null && inl.link == null) run = run.copy(color = color)
                    val txt = inl.text
                    var i = 0
                    while (i < txt.length) {
                        if (txt[i] == ' ' || txt[i] == '\n' || txt[i] == '\t') {
                            pendingSpace = true
                            i++
                            continue
                        }
                        var j = i
                        while (j < txt.length && txt[j] != ' ' && txt[j] != '\n' && txt[j] != '\t') j++
                        out += Word(text = txt.substring(i, j), run = run, spaceBefore = pendingSpace && out.isNotEmpty())
                        pendingSpace = false
                        i = j
                    }
                }
                is Inline.FootnoteRef ->
                    out +=
                        Word(
                            text = "[${inl.number}]",
                            run = Run(font = fonts.regular, size = Typo.SMALL, color = Typo.LINK, link = null, raise = 3f),
                            spaceBefore = false,
                        )
            }
        }
        return out
    }

    private fun wrap(
        words: List<Word>,
        maxW: Float,
    ): List<List<Piece>> {
        val lines = mutableListOf<MutableList<Piece>>()
        var cur = mutableListOf<Piece>()
        var x = 0f

        fun flush() {
            if (cur.isNotEmpty()) lines += cur
            cur = mutableListOf()
            x = 0f
        }
        for (w in words) {
            val safe = fonts.glyphSafe(text = w.text, font = w.run.font)
            val ww = width(font = w.run.font, size = w.run.size, text = safe)
            val sp = if (w.spaceBefore && cur.isNotEmpty()) width(font = w.run.font, size = w.run.size, text = " ") else 0f
            if (cur.isNotEmpty() && x + sp + ww > maxW) flush()
            val gap = if (cur.isEmpty()) 0f else sp
            if (ww <= maxW) {
                cur += Piece(text = safe, run = w.run, x = x + gap, w = ww)
                x += gap + ww
            } else {
                // Hard-break a word that is wider than the line (URLs, identifiers).
                var rest = safe
                while (rest.isNotEmpty()) {
                    if (x > 0f) flush()
                    var n = 1
                    while (n < rest.length && width(font = w.run.font, size = w.run.size, text = rest.substring(0, n + 1)) <= maxW) n++
                    val part = rest.substring(0, n)
                    cur += Piece(text = part, run = w.run, x = 0f, w = width(font = w.run.font, size = w.run.size, text = part))
                    rest = rest.substring(n)
                    flush()
                }
            }
        }
        flush()
        return lines
    }

    private fun drawLine(
        pieces: List<Piece>,
        x0: Float,
        top: Float,
        leading: Float,
    ) {
        for (p in pieces) {
            val base = top + (leading - p.run.size) / 2f + p.run.size * 0.82f - p.run.raise
            drawText(text = p.text, x = x0 + p.x, baselineTop = base, font = p.run.font, size = p.run.size, color = p.run.color)
            p.run.link?.let {
                links += PendingLink(pageIndex = pageIndex, x = x0 + p.x, yTop = top, width = p.w, height = leading, target = it)
            }
        }
    }

    private fun paragraph(
        p: Block.Paragraph,
        x0: Float,
        width: Float,
        marker: String? = null,
        sizeBase: Float = Typo.BODY,
        color: Color? = null,
    ) {
        val lines = wrap(words = words(inlines = p.inlines, base = sizeBase, color = color), maxW = width)
        if (lines.isEmpty() && marker == null) return
        val leading = if (sizeBase == Typo.BODY) Typo.LEADING else sizeBase * 1.4f
        val fnWords =
            p.footnotes.map { fn ->
                wrap(words = words(inlines = listOf(Inline.Text(text = "[${fn.number}] ${fn.text}")), base = Typo.SMALL), maxW = width)
            }
        val fnHeight = fnWords.sumOf { it.size.toDouble() }.toFloat() * (Typo.SMALL * 1.4f)
        val total = lines.size.coerceAtLeast(1) * leading + fnHeight
        val usable = bottom - margin
        if (page == null || (y + total > bottom && total <= usable)) newPage()
        if (marker != null) {
            drawText(
                text = marker,
                x = x0 - 14f,
                baselineTop = y + (leading - Typo.BODY) / 2f + Typo.BODY * 0.82f,
                font = fonts.regular,
                size = Typo.BODY,
                color = Typo.TEXT,
            )
        }
        for (line in lines) {
            if (y + leading > bottom) newPage()
            drawLine(pieces = line, x0 = x0, top = y, leading = leading)
            y += leading
        }
        for (fl in fnWords) {
            for (line in fl) {
                if (y + Typo.SMALL * 1.4f > bottom) newPage()
                drawLine(pieces = line, x0 = x0, top = y, leading = Typo.SMALL * 1.4f)
                y += Typo.SMALL * 1.4f
            }
        }
        y += Typo.PARA_GAP
    }

    // ---- headings ---------------------------------------------------------------------------------------

    private fun headingSize(level: Int) =
        when (level) {
            1 -> Typo.H1
            2 -> Typo.H2
            3 -> Typo.H3
            else -> Typo.BODY
        }

    private fun drawHeading(
        level: Int,
        text: String,
        anchorId: String,
    ) {
        val size = headingSize(level)
        val leading = size * 1.35f
        // keep-with-next: heading plus at least two body lines must fit
        ensure(leading + Typo.LEADING * 2 + 6f)
        y += if (level <= 2) 8f else 4f
        anchors[anchorId] = AnchorPos(pageIndex = pageIndex, y = y)
        headings += HeadingEntry(level = level, text = text, anchorId = anchorId, pageIndex = pageIndex, y = y)
        val lines =
            wrap(
                words =
                    listOf(
                        Word(
                            text = text,
                            run = Run(font = fonts.semiBold, size = size, color = Typo.TEXT, link = null, raise = 0f),
                            spaceBefore = false,
                        ),
                    ),
                maxW = contentWidth,
            ).let { l ->
                wrap(
                    words =
                        text.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
                            Word(
                                text = w,
                                run = Run(font = fonts.semiBold, size = size, color = Typo.TEXT, link = null, raise = 0f),
                                spaceBefore =
                                    i > 0,
                            )
                        },
                    maxW = contentWidth,
                ).ifEmpty { l }
            }
        for (line in lines) {
            drawLine(pieces = line, x0 = margin, top = y, leading = leading)
            y += leading
        }
        if (level == 1) {
            hLine(x1 = margin, x2 = margin + contentWidth, top = y + 1f)
            y += 6f
        }
        y += 4f
    }

    // ---- blocks -----------------------------------------------------------------------------------------

    private fun blocks(
        list: List<Block>,
        x0: Float,
        width: Float,
        sectionLevel: Int,
    ) {
        for (b in list) {
            if (cancelled()) throw PdfExportException(code = PdfExportException.Code.CANCELLED, message = "Export cancelled")
            when (b) {
                is Block.Heading ->
                    drawHeading(
                        level = shiftLevel(sectionLevel = sectionLevel, h = b.level),
                        text = b.text,
                        anchorId = b.anchorId,
                    )
                is Block.Paragraph -> paragraph(p = b, x0 = x0, width = width)
                is Block.ListBlock -> listBlock(b = b, x0 = x0, width = width, sectionLevel = sectionLevel)
                is Block.Table -> table(header = b.header, rows = b.rows, title = b.title, x0 = x0, width = width)
                is Block.CodeBlock -> code(text = b.text, x0 = x0, width = width)
                is Block.Quote -> quote(b = b, x0 = x0, width = width, sectionLevel = sectionLevel)
                is Block.Diagram -> diagram(b = b, x0 = x0, width = width)
                is Block.DiagramFailed -> failedDiagram(b = b, x0 = x0, width = width)
                is Block.ImageBlock -> image(b = b, x0 = x0, width = width)
                is Block.AiDraftBox -> aiBox(text = b.text, x0 = x0, width = width)
                is Block.Note ->
                    paragraph(
                        p = Block.Paragraph(inlines = listOf(Inline.Text(text = b.text, italic = true))),
                        x0 = x0,
                        width = width,
                        sizeBase =
                            Typo.SMALL + 1f,
                        color = Typo.GRAY,
                    )
                is Block.PageBreak -> fresh()
                is Block.DiagramRef -> Unit
            }
        }
    }

    private fun listBlock(
        b: Block.ListBlock,
        x0: Float,
        width: Float,
        sectionLevel: Int,
    ) {
        val indent = 18f
        b.items.forEachIndexed { i, item ->
            val marker = if (b.ordered) "${i + 1}." else "•"
            var first = true
            for (blk in item) {
                if (blk is Block.Paragraph) {
                    paragraph(p = blk, x0 = x0 + indent, width = width - indent, marker = if (first) marker else null)
                    y -= Typo.PARA_GAP - 2f
                } else {
                    blocks(list = listOf(blk), x0 = x0 + indent, width = width - indent, sectionLevel = sectionLevel)
                }
                first = false
            }
        }
        y += Typo.PARA_GAP - 2f
    }

    private fun quote(
        b: Block.Quote,
        x0: Float,
        width: Float,
        sectionLevel: Int,
    ) {
        if (page == null) newPage()
        val startPage = pageIndex
        val startY = y
        b.label?.let {
            paragraph(
                p = Block.Paragraph(inlines = listOf(Inline.Text(text = it.uppercase(), bold = true))),
                x0 = x0 + 12f,
                width = width - 12f,
                sizeBase = Typo.SMALL + 1f,
                color = Typo.GRAY,
            )
        }
        blocks(list = b.blocks, x0 = x0 + 12f, width = width - 12f, sectionLevel = sectionLevel)
        if (startPage == pageIndex) {
            val c = cs
            if (c != null) {
                c.setStrokingColor(Typo.RULE)
                c.setLineWidth(2f)
                c.moveTo(x0 + 3f, pdfY(startY))
                c.lineTo(x0 + 3f, pdfY(y - Typo.PARA_GAP))
                c.stroke()
            }
        }
    }

    private fun code(
        text: String,
        x0: Float,
        width: Float,
    ) {
        val size = Typo.CODE
        val lead = size * 1.4f
        val charW = width(font = fonts.mono, size = size, text = "M")
        val perLine = ((width - 12f) / charW).toInt().coerceAtLeast(8)
        val lines = text.lines().flatMap { l -> if (l.length <= perLine) listOf(l) else l.chunked(perLine) }
        var idx = 0
        if (page == null) newPage()
        while (idx < lines.size) {
            if (y + lead * 2 + 8f > bottom) newPage()
            val room = ((bottom - y - 8f) / lead).toInt().coerceAtLeast(1)
            val chunk = lines.subList(idx, min(lines.size, idx + room))
            val h = chunk.size * lead + 8f
            fillRect(x = x0, top = y, w = width, h = h, color = Typo.CODE_BG)
            chunk.forEachIndexed { i, l ->
                drawText(text = l, x = x0 + 6f, baselineTop = y + 4f + i * lead + size, font = fonts.mono, size = size, color = Typo.TEXT)
            }
            y += h
            idx += chunk.size
        }
        y += Typo.PARA_GAP
    }

    private fun aiBox(
        text: String,
        x0: Float,
        width: Float,
    ) {
        val inner = width - 16f
        val all = wrap(words = words(inlines = listOf(Inline.Text(text = text)), base = Typo.BODY - 0.5f), maxW = inner)
        // The box must fit on one page: lines beyond that would be drawn below the page margin.
        val maxLines = ((bottom - margin - 26f) / 14f).toInt().coerceAtLeast(1)
        val lines = all.take(maxLines)
        val h = 18f + lines.size * 14f + 8f
        ensure(h)
        val top = y
        fillRect(x = x0, top = top, w = width, h = h, color = Color(0xFF, 0xF8, 0xE1))
        strokeRect(x = x0, top = top, w = width, h = h, color = Color(0xF4, 0xB4, 0x00))
        drawText(
            text = labels.aiDraft,
            x = x0 + 8f,
            baselineTop = top + 12f,
            font = fonts.semiBold,
            size = Typo.SMALL + 1f,
            color = Typo.GRAY,
        )
        var ly = top + 18f
        for (line in lines) {
            drawLine(pieces = line, x0 = x0 + 8f, top = ly, leading = 14f)
            ly += 14f
        }
        y += h + Typo.PARA_GAP
    }

    private fun table(
        header: List<String>,
        rows: List<List<String>>,
        title: String?,
        x0: Float,
        width: Float,
    ) {
        val cols = header.size.coerceAtLeast(1)
        val weights =
            (0 until cols).map { c ->
                (
                    (rows.map { it.getOrNull(c).orEmpty().length } + header.getOrElse(c) { "" }.length).maxOrNull()
                        ?: 6
                ).coerceIn(6, 40).toFloat()
            }
        val totalW = weights.sum()
        val colW = weights.map { it / totalW * width }
        val size = Typo.BODY - 1f
        val lead = size * 1.4f
        val pad = 3f

        fun cellLines(
            text: String,
            c: Int,
            bold: Boolean,
        ): List<List<Piece>> {
            val font = if (bold) fonts.semiBold else fonts.regular
            val ws =
                text.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
                    Word(text = w, run = Run(font = font, size = size, color = Typo.TEXT, link = null, raise = 0f), spaceBefore = i > 0)
                }
            return wrap(words = ws, maxW = colW[c] - 2 * pad)
        }

        fun drawRow(
            cells: List<String>,
            bold: Boolean,
            bg: Color?,
        ) {
            val laid = (0 until cols).map { c -> cellLines(text = cells.getOrElse(c) { "" }, c = c, bold = bold) }
            val maxLines = (laid.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1).coerceAtMost(((bottom - margin - 2 * pad) / lead).toInt())
            val h = maxLines * lead + 2 * pad
            if (!bold && y + h > bottom) {
                // a tall row does not fit: continue on a new page with the repeated table header
                newPage()
                drawRow(cells = header, bold = true, bg = Typo.HEAD_BG)
            }
            ensure(h)
            if (bg != null) fillRect(x = x0, top = y, w = width, h = h, color = bg)
            var cx = x0
            for (c in 0 until cols) {
                laid[c].take(maxLines).forEachIndexed { li, line ->
                    drawLine(pieces = line, x0 = cx + pad, top = y + pad + li * lead, leading = lead)
                }
                cx += colW[c]
            }
            hLine(x1 = x0, x2 = x0 + width, top = y + h)
            y += h
        }
        if (page == null) newPage()
        // keep header with at least the first row
        val first = rows.firstOrNull()
        val titleH = if (title != null) 18f else 0f
        ensure(titleH + 2 * lead + 4 * pad + (if (first != null) lead else 0f))
        if (title != null) {
            drawText(text = title, x = x0, baselineTop = y + 12f, font = fonts.semiBold, size = Typo.BODY, color = Typo.TEXT)
            y += titleH
        }
        drawRow(cells = header, bold = true, bg = Typo.HEAD_BG)
        for (r in rows) {
            if (y + lead + 2 * pad > bottom) {
                newPage()
                drawRow(cells = header, bold = true, bg = Typo.HEAD_BG)
            }
            drawRow(cells = r, bold = false, bg = null)
        }
        y += Typo.PARA_GAP
    }

    private fun image(
        b: Block.ImageBlock,
        x0: Float,
        width: Float,
    ) {
        var w = b.widthPx * 0.75f
        var h = b.heightPx * 0.75f
        val maxH = (bottom - margin) * 0.7f
        val s = min(1f, min(width / w, maxH / h))
        w *= s
        h *= s
        ensure(h + Typo.PARA_GAP)
        cs?.drawImage(b.image, x0, pdfY(y + h), w, h)
        y += h + Typo.PARA_GAP
    }

    private fun failedDiagram(
        b: Block.DiagramFailed,
        x0: Float,
        width: Float,
    ) {
        val lines =
            wrap(
                words = words(inlines = listOf(Inline.Text(text = "${labels.diagramFailed}: ${b.title}")), base = Typo.BODY),
                maxW =
                    width - 16f,
            )
        val h = lines.size * Typo.LEADING + 14f
        ensure(h)
        fillRect(x = x0, top = y, w = width, h = h, color = Color(0xFD, 0xEC, 0xEC))
        strokeRect(x = x0, top = y, w = width, h = h, color = Typo.ERROR)
        var ly = y + 7f
        for (line in lines) {
            drawLine(pieces = line, x0 = x0 + 8f, top = ly, leading = Typo.LEADING)
            ly += Typo.LEADING
        }
        y += h + Typo.PARA_GAP
    }

    // ---- diagrams ---------------------------------------------------------------------------------------

    private fun figureTitle(
        b: Block.Diagram,
        suffix: String,
        maxW: Float,
    ): String =
        HeaderText.fit(
            prefix = "${labels.figure}: ",
            title = b.title,
            suffix = suffix,
            maxWidth = maxW,
            sizePt = Typo.BODY,
            m = PdfTextMeasurer(font = fonts.semiBold, fonts = fonts),
        )

    private fun diagram(
        b: Block.Diagram,
        x0: Float,
        width: Float,
    ) {
        val w = b.drawn.width
        val h = b.drawn.height
        val titleH = geometry.titleH
        val srcH = geometry.srcH
        val plan =
            PagePlanner.plan(
                w = w,
                h = h,
                base = format,
                orientation = options.orientation,
                strategy = options.effectivePageStrategy,
                tiling = options.tiling,
                minScale = options.minScale,
                g = geometry,
            )
        if (plan.illegible) {
            illegibleDiagrams++
            warnings +=
                ScaleWarning(
                    documentPath = b.documentPath,
                    blockIndex = b.blockIndex,
                    title = b.title,
                    scale = plan.scale.toDouble(),
                    suggestion = if (!options.tiling) labels.enableTiling else labels.splitDiagram,
                    code = ScaleWarningCode.ILLEGIBLE,
                    minScale = options.minScale.toDouble(),
                    message = labels.illegible(scale = plan.scale.toDouble(), min = options.minScale.toDouble()),
                )
        }
        if (plan.format != format) a3Diagrams++
        if (plan.mode == PlanMode.TILED) {
            tiledDiagrams++
            tilePages += plan.tiles
            drawTiles(b = b, plan = plan)
            return
        }
        val special = plan.landscape || plan.format != format
        if (special) {
            newPage(landscape = plan.landscape, f = plan.format)
        } else {
            // place in the remaining space when it does not shrink below the fresh-page scale
            val remainingScale = if (page == null) 0f else min(1f, min(contentWidth / w, (bottom - y - titleH - srcH) / h))
            if (page == null || remainingScale < plan.scale * 0.999f) newPage(landscape = false)
        }
        val avail = contentWidth
        val scale =
            if (special) {
                plan.scale.coerceAtLeast(0.01f)
            } else {
                min(1f, min(avail / w, (bottom - y - titleH - srcH) / h)).coerceAtLeast(0.01f)
            }
        drawText(
            text = figureTitle(b = b, suffix = "", maxW = avail),
            x = margin,
            baselineTop = y + 13f,
            font = fonts.semiBold,
            size = Typo.BODY,
            color = Typo.TEXT,
        )
        y += titleH
        val dw = w * scale
        val dh = h * scale
        val c = cs ?: return
        c.saveGraphicsState()
        c.transform(Matrix(scale, 0f, 0f, scale, margin + (avail - dw) / 2f, pdfY(y + dh)))
        c.drawForm(b.drawn.form)
        c.restoreGraphicsState()
        y += dh + 4f
        drawText(
            text = "${labels.source}: ${b.documentPath}:${b.line}",
            x = margin,
            baselineTop = y + 9f,
            font = fonts.regular,
            size = Typo.SMALL,
            color = Typo.GRAY,
        )
        y += srcH
        if (special) closePage()
    }

    /** One page per tile, row by row; neighbouring tiles overlap by 10 mm and carry registration marks. */
    private fun drawTiles(
        b: Block.Diagram,
        plan: PagePlan,
    ) {
        val g = geometry
        val n = plan.tiles
        val cols = plan.cols
        val rows = plan.rows
        val cw = g.contentW(f = plan.format, landscape = plan.landscape)
        val ch = g.contentH(f = plan.format, landscape = plan.landscape)
        val ov = g.overlap
        val s = plan.scale
        val dw = b.drawn.width * s
        val dh = b.drawn.height * s
        val locW = if (cols * 4f + (cols - 1) > MAX_LOCATOR_W || rows * 3f + (rows - 1) > g.titleH - 4f) 0f else cols * 4f + (cols - 1)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c + 1
                newPage(landscape = plan.landscape, f = plan.format)
                val m = margin
                if (i == 1 && !diagramsOnlyMode) {
                    figureMarks += HeadingEntry(level = 4, text = b.title, anchorId = "", pageIndex = pageIndex, y = m)
                }
                val reserve = if (locW > 0f) locW + 20f else 0f
                drawText(
                    text =
                        figureTitle(
                            b = b,
                            suffix = labels.tileSuffix(i = i, n = n, row = r + 1, col = c + 1),
                            maxW = cw - reserve,
                        ),
                    x = m,
                    // a little higher than on single pages: the registration marks reach 8.5 pt above the content
                    baselineTop = m + 10f,
                    font = fonts.semiBold,
                    size = Typo.BODY,
                    color = Typo.TEXT,
                )
                if (locW > 0f) drawLocator(left = m + cw - 12f - locW, top = m + 2f, cols = cols, rows = rows, curC = c, curR = r)
                val contentTop = m + g.titleH
                val ox = c * (cw - ov)
                val oy = r * (ch - ov)
                val centerX = if (cols == 1) (cw - dw) / 2f else 0f
                val stream = cs ?: return
                stream.saveGraphicsState()
                stream.addRect(m, pdfY(contentTop + ch), cw, ch)
                stream.clip()
                stream.transform(Matrix(s, 0f, 0f, s, m - ox + centerX, pdfY(contentTop - oy + dh)))
                stream.drawForm(b.drawn.form)
                stream.restoreGraphicsState()
                drawRegistrationMarks(
                    left = m,
                    top = contentTop,
                    cw = cw,
                    ch = ch,
                    ov = ov,
                    hasLeft = c > 0,
                    hasRight = c < cols - 1,
                    hasTop = r > 0,
                    hasBottom = r < rows - 1,
                )
                if (i == n) {
                    drawText(
                        text = "${labels.source}: ${b.documentPath}:${b.line}",
                        x = m,
                        baselineTop = contentTop + ch + 14.5f,
                        font = fonts.regular,
                        size = Typo.SMALL,
                        color = Typo.GRAY,
                    )
                }
                closePage()
            }
        }
    }

    private fun drawLocator(
        left: Float,
        top: Float,
        cols: Int,
        rows: Int,
        curC: Int,
        curR: Int,
    ) {
        val stream = cs ?: return
        stream.saveGraphicsState()
        stream.setLineWidth(0.25f)
        stream.setStrokingColor(Typo.GRAY)
        stream.setNonStrokingColor(Typo.TEXT)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = left + c * 5f
                val t = top + r * 4f
                stream.addRect(x, pdfY(t + 3f), 4f, 3f)
                if (r == curR && c == curC) stream.fillAndStroke() else stream.stroke()
            }
        }
        stream.restoreGraphicsState()
    }

    private fun drawRegistrationMarks(
        left: Float,
        top: Float,
        cw: Float,
        ch: Float,
        ov: Float,
        hasLeft: Boolean,
        hasRight: Boolean,
        hasTop: Boolean,
        hasBottom: Boolean,
    ) {
        val stream = cs ?: return
        val points = LinkedHashSet<Pair<Float, Float>>()
        val xs = mutableListOf<Pair<Float, Float>>() // x ranges of vertical strips
        val ys = mutableListOf<Pair<Float, Float>>()
        if (hasRight) xs += (left + cw - ov) to (left + cw)
        if (hasLeft) xs += left to (left + ov)
        if (hasBottom) ys += (top + ch - ov) to (top + ch)
        if (hasTop) ys += top to (top + ov)
        for ((x1, x2) in xs) {
            for (yy in listOf(top, top + ch)) {
                points += x1 to yy
                points += x2 to yy
            }
        }
        for ((y1, y2) in ys) {
            for (xx in listOf(left, left + cw)) {
                points += xx to y1
                points += xx to y2
            }
        }
        if (points.isEmpty()) return
        stream.saveGraphicsState()
        stream.setLineWidth(0.25f)
        stream.setStrokingColor(Color(128, 128, 128))
        val arm = MARK_SIZE / 2f
        for ((px, py) in points) {
            stream.moveTo(px - arm, pdfY(py))
            stream.lineTo(px + arm, pdfY(py))
            stream.moveTo(px, pdfY(py) - arm)
            stream.lineTo(px, pdfY(py) + arm)
            stream.stroke()
        }
        stream.restoreGraphicsState()
    }

    // ---- main entry ---------------------------------------------------------------------------------------

    private fun shiftLevel(
        sectionLevel: Int,
        h: Int,
    ) = min(maxOf(sectionLevel + h - 1, sectionLevel + 1), 6)

    private fun countEntries(sections: List<Section>): Int {
        var n = 0
        for (s in sections) {
            if (s.level <= 3) n++
            for (b in s.blocks) {
                if (b is Block.Heading && shiftLevel(sectionLevel = s.level, h = b.level) <= 3) n++
            }
        }
        return n
    }

    fun layout(composed: ComposedDocument): LayoutResult {
        diagramsOnlyMode = composed.diagramsOnly
        val tocEntryCount = if (composed.tocEnabled) countEntries(composed.sections) else 0
        val perPage = ((pageSize(landscape = false).second - 2 * margin - 48f) / TOC_LINE).toInt().coerceAtLeast(1)
        val tocPages = if (tocEntryCount == 0) 0 else ceil(tocEntryCount / perPage.toFloat()).toInt()
        val coverPages = if (composed.cover != null) 1 else 0
        frontPages = coverPages + tocPages
        repeat(frontPages) { addPage(landscape = false) }
        pageIndex = frontPages - 1

        for (section in composed.sections) {
            currentSection = section.title
            if (section.level == 1 || composed.diagramsOnly) closePage()
            if (page == null && !composed.diagramsOnly) newPage()
            if (composed.diagramsOnly) {
                anchors[section.anchorId] = AnchorPos(pageIndex = doc.numberOfPages, y = margin)
                headings +=
                    HeadingEntry(level = 1, text = section.title, anchorId = section.anchorId, pageIndex = doc.numberOfPages, y = margin)
                // the diagram block opens its own page, so the heading position refers to that page
                closePage()
            } else {
                drawHeading(level = section.level.coerceAtMost(6), text = section.title, anchorId = section.anchorId)
            }
            blocks(list = section.blocks, x0 = margin, width = contentWidth, sectionLevel = section.level)
        }
        closePage()
        if (doc.numberOfPages == 0) newPage().also { closePage() }

        if (composed.cover != null) drawCover(cover = composed.cover)
        if (tocPages > 0) drawToc(entries = headings.filter { it.level <= 3 }, firstPage = coverPages, pages = tocPages, perPage = perPage)
        return LayoutResult(
            pageCount = doc.numberOfPages,
            anchors = anchors,
            headings = headings,
            links = links,
            pageSection = pageSection,
            warnings = warnings,
            frontPages = frontPages,
            summary =
                PdfLayoutSummary(
                    a3Diagrams = a3Diagrams,
                    tiledDiagrams = tiledDiagrams,
                    tilePages = tilePages,
                    illegibleDiagrams = illegibleDiagrams,
                ),
            figureMarks = figureMarks,
        )
    }

    // ---- cover & TOC ------------------------------------------------------------------------------------------

    private fun drawCover(cover: dev.kuml.workspace.pdf.model.CoverData) {
        val p = doc.getPage(0)
        val (w, h) = p.mediaBox.width to p.mediaBox.height
        pageW = w
        pageH = h
        pageIndex = 0
        cs = PDPageContentStream(doc, p)
        var top = margin + 40f
        cover.logo?.let { img ->
            val s = min(140f / img.width, 70f / img.height).coerceAtMost(1f)
            cs?.drawImage(img, margin, pdfY(top + img.height * s), img.width * s, img.height * s)
            top += img.height * s + 30f
        }
        top = maxOf(top, h * 0.28f)
        val titleWords = cover.title.split(' ').filter { it.isNotEmpty() }
        val tl =
            wrap(
                words =
                    titleWords.mapIndexed { i, wd ->
                        Word(
                            text = wd,
                            run = Run(font = fonts.semiBold, size = 28f, color = Typo.TEXT, link = null, raise = 0f),
                            spaceBefore =
                                i > 0,
                        )
                    },
                maxW = w - 2 * margin,
            )
        for (line in tl) {
            drawLine(pieces = line, x0 = margin, top = top, leading = 36f)
            top += 36f
        }
        cover.subtitle?.let {
            top += 6f
            val sl =
                wrap(
                    words =
                        it.split(' ').filter { s -> s.isNotEmpty() }.mapIndexed { i, wd ->
                            Word(
                                text = wd,
                                run = Run(font = fonts.regular, size = 14f, color = Typo.GRAY, link = null, raise = 0f),
                                spaceBefore =
                                    i > 0,
                            )
                        },
                    maxW = w - 2 * margin,
                )
            for (line in sl) {
                drawLine(pieces = line, x0 = margin, top = top, leading = 20f)
                top += 20f
            }
        }
        top += 16f
        hLine(x1 = margin, x2 = w - margin, top = top)
        top += 20f
        val rows =
            listOfNotNull(
                cover.author?.let { labels.author to it },
                cover.organization?.let { labels.organization to it },
                cover.version?.let { labels.version to it },
                cover.date?.let { labels.dateLabel to labels.formatDate(it) },
                cover.status?.let { labels.status to it },
                labels.exportedOn to labels.formatDate(cover.exportedOn),
                labels.documents to cover.documentCount.toString(),
            )
        val labelW = rows.maxOf { width(font = fonts.regular, size = 11f, text = it.first) } + 16f
        for ((k, v) in rows) {
            drawText(text = k, x = margin, baselineTop = top + 11f, font = fonts.regular, size = 11f, color = Typo.GRAY)
            val vl =
                wrap(
                    words =
                        v.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, wd ->
                            Word(
                                text = wd,
                                run = Run(font = fonts.regular, size = 11f, color = Typo.TEXT, link = null, raise = 0f),
                                spaceBefore = i > 0,
                            )
                        },
                    maxW = w - 2 * margin - labelW,
                )
            for (line in vl) {
                drawLine(pieces = line, x0 = margin + labelW, top = top, leading = 16f)
                top += 16f
            }
            top += 2f
        }
        if (cover.openItems != null && cover.openItemsAnchor != null) {
            val pos = anchors[cover.openItemsAnchor]
            val label = labels.openItemsLine(count = cover.openItems, page = (pos?.pageIndex ?: 0) + 1)
            top += 10f
            drawText(text = label, x = margin, baselineTop = top + 11f, font = fonts.regular, size = 11f, color = Typo.LINK)
            links +=
                PendingLink(
                    pageIndex = 0,
                    x = margin,
                    yTop = top,
                    width = width(font = fonts.regular, size = 11f, text = label),
                    height = 14f,
                    target = LinkTarget.Internal(anchorId = cover.openItemsAnchor),
                )
        }
        drawText(
            text = labels.createdWith(cover.producer),
            x = margin,
            baselineTop = pageH - margin,
            font = fonts.regular,
            size = Typo.SMALL,
            color = Typo.GRAY,
        )
        closePage()
    }

    private fun drawToc(
        entries: List<HeadingEntry>,
        firstPage: Int,
        pages: Int,
        perPage: Int,
    ) {
        var i = 0
        for (pg in 0 until pages) {
            val p = doc.getPage(firstPage + pg)
            pageW = p.mediaBox.width
            pageH = p.mediaBox.height
            pageIndex = firstPage + pg
            pageSection[pageIndex] = labels.contents
            cs = PDPageContentStream(doc, p)
            var top = margin
            if (pg == 0) {
                drawText(
                    text = labels.contents,
                    x = margin,
                    baselineTop = top + Typo.H1,
                    font = fonts.semiBold,
                    size = Typo.H1,
                    color = Typo.TEXT,
                )
                top += 48f
            } else {
                top += 0f
            }
            var count = 0
            while (i < entries.size && count < perPage) {
                val e = entries[i]
                val indent = (e.level - 1) * 14f
                val size = if (e.level == 1) Typo.BODY else Typo.BODY - 0.5f
                val font = if (e.level == 1) fonts.semiBold else fonts.regular
                val num = (e.pageIndex + 1).toString()
                val numW = width(font = fonts.regular, size = size, text = num)
                val maxTitle = pageW - 2 * margin - indent - numW - 12f
                val title =
                    HeaderText.truncate(
                        text = e.text,
                        maxWidth = maxTitle,
                        sizePt = size,
                        m = PdfTextMeasurer(font = font, fonts = fonts),
                    )
                drawText(text = title, x = margin + indent, baselineTop = top + 12f, font = font, size = size, color = Typo.TEXT)
                drawText(
                    text = num,
                    x = pageW - margin - numW,
                    baselineTop = top + 12f,
                    font = fonts.regular,
                    size = size,
                    color = Typo.TEXT,
                )
                links +=
                    PendingLink(
                        pageIndex = pageIndex,
                        x = margin,
                        yTop = top,
                        width = pageW - 2 * margin,
                        height = TOC_LINE,
                        target = LinkTarget.Internal(anchorId = e.anchorId),
                    )
                top += TOC_LINE
                i++
                count++
            }
            closePage()
        }
    }

    companion object {
        const val TOC_LINE: Float = 18f
        const val MARK_SIZE: Float = 17f
        const val MAX_LOCATOR_W: Float = 60f
    }
}
