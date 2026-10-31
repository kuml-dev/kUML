package dev.kuml.workspace.pdf.layout

import dev.kuml.workspace.pdf.render.PdfFontSet
import org.apache.pdfbox.pdmodel.font.PDFont

/** Text measurement port so the shortening logic is testable without fonts. */
internal interface TextMeasurer {
    fun width(
        text: String,
        sizePt: Float,
    ): Float

    fun hasGlyph(c: Char): Boolean
}

/** [TextMeasurer] on top of a PDFBox font; measures the glyph-safe string that is actually drawn. */
internal class PdfTextMeasurer(
    private val font: PDFont,
    private val fonts: PdfFontSet,
) : TextMeasurer {
    override fun width(
        text: String,
        sizePt: Float,
    ): Float = font.getStringWidth(fonts.glyphSafe(text = text, font = font)) / 1000f * sizePt

    override fun hasGlyph(c: Char): Boolean = fonts.hasGlyph(font = font, codePoint = c.code)
}

/** Single-line text shortening for headers: ellipsis at the end, never a line break. */
internal object HeaderText {
    private const val WORD_LOSS = 0.2f

    private fun ellipsis(m: TextMeasurer) = if (m.hasGlyph('…')) "…" else "..."

    /** Shortens [text] to [maxWidth] (points at [sizePt]); returns it unchanged when it fits. */
    fun truncate(
        text: String,
        maxWidth: Float,
        sizePt: Float,
        m: TextMeasurer,
    ): String {
        if (m.width(text = text, sizePt = sizePt) <= maxWidth) return text
        val ell = ellipsis(m)
        val cut = cutIndex(title = text, maxWidth = maxWidth, sizePt = sizePt, m = m, ell = ell, prefix = "", suffix = "")
        return text.substring(0, cut).trimEnd() + ell
    }

    /**
     * Fits `prefix + title + suffix` into [maxWidth] by shortening only [title]. When even an empty title
     * does not fit, prefix and suffix stay and the title becomes the ellipsis.
     */
    fun fit(
        prefix: String,
        title: String,
        suffix: String,
        maxWidth: Float,
        sizePt: Float,
        m: TextMeasurer,
    ): String {
        if (m.width(text = prefix + title + suffix, sizePt = sizePt) <= maxWidth) return prefix + title + suffix
        val ell = ellipsis(m)
        val cut = cutIndex(title = title, maxWidth = maxWidth, sizePt = sizePt, m = m, ell = ell, prefix = prefix, suffix = suffix)
        val shortened = if (cut <= 0) ell else title.substring(0, cut).trimEnd() + ell
        return prefix + shortened + suffix
    }

    /** Longest title prefix length (binary search) such that prefix + title[0,n) + ellipsis + suffix fits. */
    private fun cutIndex(
        title: String,
        maxWidth: Float,
        sizePt: Float,
        m: TextMeasurer,
        ell: String,
        prefix: String,
        suffix: String,
    ): Int {
        var lo = 0
        var hi = title.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (m.width(text = prefix + title.substring(0, mid).trimEnd() + ell + suffix, sizePt = sizePt) <=
                maxWidth
            ) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        if (lo <= 0 || lo >= title.length || title[lo] == ' ') return lo
        if (Character.isHighSurrogate(title[lo - 1])) return lo - 1
        // prefer the end of the last word when that loses at most 20 % of the width
        val space = title.lastIndexOf(' ', lo - 1)
        if (space > 0) {
            val loss = m.width(text = title.substring(space, lo), sizePt = sizePt)
            if (loss <= maxWidth * WORD_LOSS) return space
        }
        return lo
    }
}
