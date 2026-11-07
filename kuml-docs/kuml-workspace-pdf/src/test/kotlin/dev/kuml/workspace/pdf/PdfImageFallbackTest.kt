package dev.kuml.workspace.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PdfImageFallbackTest :
    FunSpec({
        test("an image that cannot be embedded appears as its alt text; a good one is embedded") {
            val dir = TestSupport.tempDir()
            File(dir, "bilder").mkdirs()
            ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", File(dir, "bilder/ok.png"))
            val ws =
                TestSupport.workspace(
                    dir = dir,
                    extra =
                        mapOf(
                            "chapters/eins.md" to
                                "---\ntype: Article\ntitle: Erstes Kapitel\n---\n# Erstes Kapitel\n\n" +
                                "![Fehlbild Alt](../bilder/gibt-es-nicht.png)\n\n![Gutes Bild](../bilder/ok.png)\n",
                        ),
                )
            val out = File(dir, "out.pdf")
            TestSupport.export(ws = ws, options = TestSupport.options(), target = out)
            TestSupport.open(out).use { pdf ->
                TestSupport.allText(pdf) shouldContain "Fehlbild Alt"
            }
        }
    })
