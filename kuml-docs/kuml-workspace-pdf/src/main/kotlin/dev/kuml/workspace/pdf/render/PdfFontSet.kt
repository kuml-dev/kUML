package dev.kuml.workspace.pdf.render

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * The bundled fonts (Inter and JetBrains Mono, SIL OFL 1.1) loaded into one [PDDocument].
 *
 * Fonts are embedded as subsets, so a PDF only carries the glyphs it uses. A [PDFont] belongs to
 * exactly one document, therefore one [PdfFontSet] is created per document.
 */
internal class PdfFontSet private constructor(
    val regular: PDType0Font,
    val semiBold: PDType0Font,
    val italic: PDType0Font,
    val mono: PDType0Font,
) {
    /**
     * Returns [text] with control characters removed and every code point the [font] has no glyph
     * for replaced by `?`. PDFBox throws an [IllegalArgumentException] for such code points, which
     * must never abort an export.
     */
    fun glyphSafe(
        text: String,
        font: PDFont,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code || cp == '\t'.code -> out.append(' ')
                Character.isISOControl(cp) -> Unit
                hasGlyph(font = font, codePoint = cp) -> out.appendCodePoint(cp)
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    internal fun hasGlyph(
        font: PDFont,
        codePoint: Int,
    ): Boolean =
        try {
            // PDType0Font.hasGlyph(int) takes the code point in the font's encoding; the
            // string-based check is the reliable one for Identity-H subset fonts.
            font.encode(String(Character.toChars(codePoint)))
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    companion object {
        private const val FONT_DIR = "/dev/kuml/workspace/pdf/fonts/"

        private fun resourceBytes(name: String): ByteArray =
            (
                PdfFontSet::class.java.getResourceAsStream(FONT_DIR + name)
                    ?: error("Bundled font resource missing: $name")
            ).use { it.readBytes() }

        /** Loads and embeds (as subsets) the four bundled fonts into [doc]. */
        fun load(doc: PDDocument): PdfFontSet =
            PdfFontSet(
                regular = PDType0Font.load(doc, resourceBytes("Inter-Regular.ttf").inputStream(), true),
                semiBold = PDType0Font.load(doc, resourceBytes("Inter-SemiBold.ttf").inputStream(), true),
                italic = PDType0Font.load(doc, resourceBytes("Inter-Italic.ttf").inputStream(), true),
                mono = PDType0Font.load(doc, resourceBytes("JetBrainsMono-Regular.ttf").inputStream(), true),
            )

        @Volatile
        private var awtRegistered = false

        /**
         * Registers Inter with the AWT [GraphicsEnvironment] once per JVM, so Batik resolves the
         * SVG `font-family: Inter` to the same face the PDF text uses. Without this, Batik falls
         * back to a system font and glyph metrics (and therefore text placement) deviate.
         */
        @Synchronized
        fun registerAwtFontsOnce() {
            if (awtRegistered) return
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            for (name in listOf("Inter-Regular.ttf", "Inter-SemiBold.ttf", "Inter-Italic.ttf")) {
                runCatching {
                    val font = Font.createFont(Font.TRUETYPE_FONT, resourceBytes(name).inputStream())
                    env.registerFont(font)
                }
            }
            awtRegistered = true
        }
    }
}
