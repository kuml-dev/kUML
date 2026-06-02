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
}
