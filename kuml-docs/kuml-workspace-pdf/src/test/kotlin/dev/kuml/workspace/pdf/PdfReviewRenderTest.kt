package dev.kuml.workspace.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import javax.imageio.ImageIO

/** Writes handbook pages as PNG to build/sample-output/pdf-review/ for the manual visual review. */
class PdfReviewRenderTest :
    FunSpec({
        test("handbook pages rendered for visual review") {
            val dir = File("build/sample-output/pdf-review").also { it.mkdirs() }
            val ws = TestSupport.workspace(dir = TestSupport.tempDir())
            val f = File(dir, "handbook.pdf")
            val facts =
                DiagramFacts(
                    elements = listOf(ElementRow(kind = "Class", name = "Vorstand", detail = "3 attributes")),
                    traces = emptyList(),
                    notes = emptyList(),
                )
            TestSupport.export(
                ws = ws,
                options = TestSupport.options { copy(draft = true) },
                source = TestSupport.source(facts = facts),
                target = f,
            )
            TestSupport.open(f).use { pdf ->
                pdf.numberOfPages shouldBeGreaterThan 3
                val r = PDFRenderer(pdf)
                for (
                i in 0 until
                    minOf(
                        pdf.numberOfPages,
                        8,
                    )
                ) {
                    ImageIO.write(r.renderImageWithDPI(i, 80f), "png", File(dir, "page-${i + 1}.png"))
                }
            }
        }
    })
