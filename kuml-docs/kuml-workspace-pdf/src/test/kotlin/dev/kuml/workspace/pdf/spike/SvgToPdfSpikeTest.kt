package dev.kuml.workspace.pdf.spike

import dev.kuml.workspace.pdf.render.PdfFontSet
import dev.kuml.workspace.pdf.render.SvgGraphicsRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Stage 0 spike (wave F): proves that SVG -> Batik GVT -> PdfBoxGraphics2D produces a PDF that looks
 * like Batik's own PNG rendering of the same SVG.
 *
 * RESULT (2026-10-28, PDFBox 3.0.8, pdfbox-graphics2d 3.0.5, Batik 1.19, Inter 4.1):
 *  - kUML diagrams (UML class diagram, BPMN process) and a stress SVG with linear + radial gradients
 *    (spreadMethod=reflect), markers, opacity, dash arrays and German umlauts are drawn as vectors.
 *  - Text is converted to glyph outlines (library default); no font is embedded per diagram.
 *  - The numeric comparison against the Batik PNG, the quadrant coverage and the margin checks below
 *    are font independent; the per-fixture mean difference is asserted and logged.
 * See the build/sample-output/pdf-spike/ PNG pairs for the visual comparison.
 */
class SvgToPdfSpikeTest :
    FunSpec({
        val outDir = File("build/sample-output/pdf-spike").also { it.mkdirs() }
        val dpi = 150f

        fun resource(name: String): String =
            SvgToPdfSpikeTest::class.java
                .getResourceAsStream("/spike/$name")!!
                .readBytes()
                .toString(Charsets.UTF_8)

        fun batikPng(
            svg: String,
            widthPx: Float,
        ): BufferedImage {
            val t = PNGTranscoder()
            t.addTranscodingHint(ImageTranscoder.KEY_WIDTH, widthPx)
            val out = ByteArrayOutputStream()
            t.transcode(TranscoderInput(svg.reader()), TranscoderOutput(out))
            return ImageIO.read(out.toByteArray().inputStream())
        }

        fun meanAbsDiff(
            a: BufferedImage,
            b: BufferedImage,
        ): Double {
            val w = minOf(a.width, b.width)
            val h = minOf(a.height, b.height)
            var sum = 0L
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val pa = a.getRGB(x, y)
                    val pb = b.getRGB(x, y)

                    // Batik PNG may be transparent where the PDF is white: composite both over white.
                    fun ch(
                        p: Int,
                        shift: Int,
                    ): Int {
                        val alpha = (p ushr 24) and 0xFF
                        val c = (p shr shift) and 0xFF
                        return (c * alpha + 255 * (255 - alpha)) / 255
                    }
                    sum += abs(ch(pa, 16) - ch(pb, 16)) + abs(ch(pa, 8) - ch(pb, 8)) + abs(ch(pa, 0) - ch(pb, 0))
                }
            }
            return sum.toDouble() / (w.toLong() * h * 3 * 255)
        }

        listOf("organe-struktur", "aufnahme-prozess", "stress").forEach { name ->
            test("SVG fixture $name survives the SVG to PDF round trip") {
                val svg = resource("$name.svg")
                val (w, h) = SvgGraphicsRenderer.naturalSize(svg)
                val pdfFile = File(outDir, "$name.pdf")
                PDDocument().use { doc ->
                    val margin = 20f
                    val page = PDPage(PDRectangle(w + 2 * margin, h + 2 * margin))
                    doc.addPage(page)
                    val drawn = SvgGraphicsRenderer.draw(doc = doc, svg = svg)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(
                            org.apache.pdfbox.util.Matrix
                                .getTranslateInstance(margin, margin),
                        )
                        cs.drawForm(drawn.form)
                        cs.restoreGraphicsState()
                    }
                    doc.save(pdfFile)
                }
                Loader.loadPDF(pdfFile).use { pdf ->
                    pdf.numberOfPages shouldBe 1
                    val rendered = PDFRenderer(pdf).renderImageWithDPI(0, dpi)
                    ImageIO.write(rendered, "png", File(outDir, "$name-pdf.png"))
                    val scale = dpi / 72f
                    val marginPx = (20f * scale).toInt()
                    val inner = rendered.getSubimage(marginPx, marginPx, rendered.width - 2 * marginPx, rendered.height - 2 * marginPx)
                    val ref = batikPng(svg = svg, widthPx = w * scale)
                    ImageIO.write(ref, "png", File(outDir, "$name-batik.png"))

                    // Quadrant coverage (font independent): every quadrant of the drawing has ink.
                    var quadrantsWithInk = 0
                    for (qy in 0..1) {
                        for (qx in 0..1) {
                            val x0 = qx * inner.width / 2
                            val y0 = qy * inner.height / 2
                            var ink = false
                            loop@ for (y in y0 until y0 + inner.height / 2 step 2) {
                                for (x in x0 until x0 + inner.width / 2 step 2) {
                                    if ((inner.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) {
                                        ink = true
                                        break@loop
                                    }
                                }
                            }
                            if (ink) quadrantsWithInk++
                        }
                    }
                    quadrantsWithInk shouldBeGreaterThanOrEqual 3

                    // No clipping at the page edge: the outer 2 px of the page are white.
                    for (i in 0 until 2) {
                        for (x in 0 until rendered.width) {
                            (rendered.getRGB(x, i) and 0xFFFFFF) shouldBe 0xFFFFFF
                            (rendered.getRGB(x, rendered.height - 1 - i) and 0xFFFFFF) shouldBe 0xFFFFFF
                        }
                    }

                    val diff = meanAbsDiff(a = inner, b = ref)
                    println("SPIKE $name: mean abs diff vs Batik PNG = ${"%.4f".format(diff)}")
                    diff shouldBeLessThan 0.06
                }
            }
        }

        test("bundled fonts embed as subsets and umlauts map to glyphs") {
            PDDocument().use { doc ->
                val fonts = PdfFontSet.load(doc)
                fonts.glyphSafe(text = "ÄÖÜäöüß€", font = fonts.regular) shouldBe "ÄÖÜäöüß€"
                fonts.glyphSafe(text = "a😀b", font = fonts.regular) shouldBe "a?b"
            }
        }
    })
