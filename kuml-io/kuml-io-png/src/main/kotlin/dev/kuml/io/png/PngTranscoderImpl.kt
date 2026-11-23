package dev.kuml.io.png

import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * Dünner Wrapper um den Apache Batik [PNGTranscoder].
 *
 * Konvertiert einen SVG-String zu einem PNG-Byte-Array gemäß den übergebenen [PngRenderOptions].
 * Headless-Modus wird explizit aktiviert, damit Tests in CI ohne Display laufen.
 */
internal class PngTranscoderImpl {
    fun transcode(
        svg: String,
        options: PngRenderOptions,
    ): ByteArray {
        // Headless explizit setzen — Batik unterstützt headless, aber explizit ist sicherer
        System.setProperty("java.awt.headless", "true")

        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, options.widthPx.toFloat())
        // PAL Welle D (Punkt 11): derive the height from the viewBox so the PNG keeps exactly the viewBox aspect ratio.
        viewBoxHeightPx(svg = svg, widthPx = options.widthPx)?.let {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, it.toFloat())
        }

        // Background und Transparent sind exklusiv (Stolperstein 3)
        if (!options.transparent && options.backgroundColor != null) {
            transcoder.addTranscodingHint(
                PNGTranscoder.KEY_BACKGROUND_COLOR,
                java.awt.Color(options.backgroundColor.rgb),
            )
        }

        val input = TranscoderInput(StringReader(svg))
        val out = ByteArrayOutputStream()
        val output = TranscoderOutput(out)
        transcoder.transcode(input, output)
        return out.toByteArray()
    }

    internal companion object {
        /** Only the first, bounded prefix of the document is scanned (no ReDoS / full-document regex). */
        private const val ROOT_SCAN_LIMIT = 4096
        private const val MAX_HEIGHT_PX = 32_768
        private val SVG_TAG = Regex("""<svg\b[^>]*>""")
        private val VIEW_BOX = Regex("""viewBox\s*=\s*"([^"]*)"""")

        /** Height in px for [widthPx] derived from the root viewBox, or null when absent/invalid (fallback: Batik default). */
        fun viewBoxHeightPx(
            svg: String,
            widthPx: Int,
        ): Int? {
            val tag = SVG_TAG.find(svg.take(ROOT_SCAN_LIMIT))?.value ?: return null
            val parts =
                VIEW_BOX
                    .find(tag)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.split(Regex("[\\s,]+"))
                    ?: return null
            if (parts.size != 4) return null
            val w = parts[2].toDoubleOrNull() ?: return null
            val h = parts[3].toDoubleOrNull() ?: return null
            if (!w.isFinite() || !h.isFinite() || w <= 0.0 || h <= 0.0) return null
            val px = Math.round(widthPx * h / w).toInt()
            return px.coerceIn(1, MAX_HEIGHT_PX)
        }
    }
}
