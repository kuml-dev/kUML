package dev.kuml.workspace.pdf.writer

import dev.kuml.workspace.pdf.PdfExportOptions
import dev.kuml.workspace.pdf.labels.PdfLabels
import dev.kuml.workspace.pdf.layout.LayoutResult
import dev.kuml.workspace.pdf.model.LinkTarget
import dev.kuml.workspace.pdf.render.PdfFontSet
import dev.kuml.workspace.pdf.security.TextSanitizer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.util.Matrix
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.GregorianCalendar

/** What the finisher needs to know about the output besides the layout. */
internal class FinishInfo(
    val title: String,
    val headerTitle: String,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val version: String?,
    val date: LocalDate?,
    val hasCover: Boolean,
    val languageTag: String,
)

/**
 * Final pass over a laid-out [PDDocument]: running header and footer, draft watermark, bookmarks,
 * link annotations and document metadata (Info dictionary plus XMP). All text is written through
 * the PDFBox text API with glyph-safe strings; nothing is ever spliced into a raw content stream.
 */
internal object PdfDocumentFinisher {
    fun finish(
        doc: PDDocument,
        fonts: PdfFontSet,
        labels: PdfLabels,
        options: PdfExportOptions,
        layout: LayoutResult,
        info: FinishInfo,
    ) {
        decoratePages(doc = doc, fonts = fonts, labels = labels, options = options, layout = layout, info = info)
        addLinks(doc = doc, layout = layout)
        addBookmarks(doc = doc, layout = layout)
        addMetadata(doc = doc, options = options, info = info)
    }

    private fun decoratePages(
        doc: PDDocument,
        fonts: PdfFontSet,
        labels: PdfLabels,
        options: PdfExportOptions,
        layout: LayoutResult,
        info: FinishInfo,
    ) {
        val total = doc.numberOfPages
        val gray = Color(0x66, 0x66, 0x66)
        for (i in 0 until total) {
            val page = doc.getPage(i)
            val w = page.mediaBox.width
            val h = page.mediaBox.height
            val margin = if (options.page == dev.kuml.workspace.pdf.PageFormat.A3) 70.87f else 56.69f
            val isCover = info.hasCover && i == 0
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                if (options.headerFooter && !isCover) {
                    val s = 8f

                    fun text(
                        t: String,
                        x: Float,
                        y: Float,
                    ) {
                        val safe = fonts.glyphSafe(text = t, font = fonts.regular)
                        cs.beginText()
                        cs.setFont(fonts.regular, s)
                        cs.setNonStrokingColor(gray)
                        cs.newLineAtOffset(x, y)
                        cs.showText(safe)
                        cs.endText()
                    }

                    fun textWidth(t: String) = fonts.regular.getStringWidth(fonts.glyphSafe(text = t, font = fonts.regular)) / 1000f * s
                    val topY = h - margin / 2f
                    val room = w - 2 * margin
                    var sec = if (layout.pageSection[i].orEmpty() != info.headerTitle) layout.pageSection[i].orEmpty() else ""
                    while (sec.length > 1 && textWidth(sec) > room * 0.5f) sec = sec.dropLast(1)
                    var left = info.headerTitle
                    val secW = if (sec.isEmpty()) 0f else textWidth(sec) + 16f
                    while (left.length > 1 && textWidth(left) > room - secW) left = left.dropLast(1)
                    text(t = left, x = margin, y = topY)
                    if (sec.isNotEmpty()) text(t = sec, x = w - margin - textWidth(sec), y = topY)
                    val footLeft = listOfNotNull(info.version?.let { "${labels.version} $it" }, info.date?.toString()).joinToString(" · ")
                    val botY = margin / 2f - 2f
                    if (footLeft.isNotEmpty()) text(t = footLeft, x = margin, y = botY)
                    val pn = labels.page(n = i + 1, total = total)
                    text(t = pn, x = w - margin - textWidth(pn), y = botY)
                }
                if (options.draft) watermark(cs = cs, fonts = fonts, labels = labels, w = w, h = h)
            }
        }
    }

    private fun watermark(
        cs: PDPageContentStream,
        fonts: PdfFontSet,
        labels: PdfLabels,
        w: Float,
        h: Float,
    ) {
        val gs = PDExtendedGraphicsState()
        gs.nonStrokingAlphaConstant = 0.12f
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(gs)
        val size = 96f
        val text = labels.draftMark
        val tw = fonts.semiBold.getStringWidth(text) / 1000f * size
        val m = Matrix.getRotateInstance(Math.PI / 4, w / 2f, h / 2f)
        cs.beginText()
        cs.setFont(fonts.semiBold, size)
        cs.setNonStrokingColor(Color(0x80, 0x80, 0x80))
        cs.setTextMatrix(m)
        cs.newLineAtOffset(-tw / 2f, -size / 3f)
        cs.showText(text)
        cs.endText()
        cs.restoreGraphicsState()
    }

    private fun addLinks(
        doc: PDDocument,
        layout: LayoutResult,
    ) {
        for (l in layout.links) {
            if (l.pageIndex !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(l.pageIndex)
            val h = page.mediaBox.height
            val ann = PDAnnotationLink()
            ann.rectangle = PDRectangle(l.x, h - l.yTop - l.height, l.width, l.height)
            ann.borderStyle = PDBorderStyleDictionary().also { it.width = 0f }
            when (val t = l.target) {
                is LinkTarget.External -> ann.action = PDActionURI().also { it.uri = t.uri }
                is LinkTarget.Internal -> {
                    val pos = layout.anchors[t.anchorId] ?: continue
                    val dest = PDPageXYZDestination()
                    dest.page = doc.getPage(pos.pageIndex.coerceIn(0, doc.numberOfPages - 1))
                    dest.top = (doc.getPage(pos.pageIndex.coerceIn(0, doc.numberOfPages - 1)).mediaBox.height - pos.y).toInt()
                    ann.action = PDActionGoTo().also { it.destination = dest }
                }
            }
            page.annotations.add(ann)
        }
    }

    private fun addBookmarks(
        doc: PDDocument,
        layout: LayoutResult,
    ) {
        val entries = layout.headings.filter { it.level <= 3 }
        if (entries.isEmpty()) return
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        val stack = ArrayList<Pair<Int, PDOutlineItem>>()
        for (e in entries) {
            val item = PDOutlineItem()
            item.title = e.text.take(200)
            val page = doc.getPage(e.pageIndex.coerceIn(0, doc.numberOfPages - 1))
            val dest = PDPageXYZDestination()
            dest.page = page
            dest.top = (page.mediaBox.height - e.y).toInt()
            item.destination = dest
            while (stack.isNotEmpty() && stack.last().first >= e.level) stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) outline.addLast(item) else stack.last().second.addLast(item)
            stack += e.level to item
        }
        outline.openNode()
    }

    private fun addMetadata(
        doc: PDDocument,
        options: PdfExportOptions,
        info: FinishInfo,
    ) {
        val created =
            GregorianCalendar.from((info.date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC))

        // Frontmatter values are untrusted: strip control characters (invalid in XMP/XML) and bound the length.
        fun clean(v: String) = TextSanitizer.clean(text = v, maxLength = 500).replace('\n', ' ').replace('\t', ' ')
        val title = clean(info.title)
        val author = info.author?.let { clean(it) }
        val subject = info.subject?.let { clean(it) }
        val keywords = info.keywords?.let { clean(it) }
        val di = doc.documentInformation
        di.title = title
        author?.let { di.author = it }
        subject?.let { di.subject = it }
        keywords?.let { di.keywords = it }
        di.creator = options.producerVersion
        di.producer = options.producerVersion
        di.creationDate = created
        di.modificationDate = created

        val xmp = XMPMetadata.createXMPMetadata()
        val dc = xmp.createAndAddDublinCoreSchema()
        dc.title = title
        author?.let { dc.addCreator(it) }
        subject?.let { dc.addDescription("x-default", it) }
        val basic = xmp.createAndAddXMPBasicSchema()
        basic.createDate = created
        basic.creatorTool = options.producerVersion
        val pdf = xmp.createAndAddAdobePDFSchema()
        pdf.producer = options.producerVersion
        keywords?.let { pdf.keywords = it }
        val out = ByteArrayOutputStream()
        XmpSerializer().serialize(xmp, out, true)
        val meta = PDMetadata(doc)
        meta.importXMPMetadata(out.toByteArray())
        doc.documentCatalog.metadata = meta
        doc.documentCatalog.language = info.languageTag
    }
}
