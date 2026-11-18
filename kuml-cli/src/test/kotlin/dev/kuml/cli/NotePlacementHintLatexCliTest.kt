package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Nacharbeit 105: the LaTeX path of the CLI evaluates the BPMN placement hint. A hint that cannot be honoured (here: a side
 * hint on a note without any drawn anchor) reaches the render outcome as `KUML-NOTE-W004`; honoured hints and NEAR stay silent.
 */
class NotePlacementHintLatexCliTest :
    FunSpec({
        fun script(annotation: String): String =
            """
            import dev.kuml.bpmn.dsl.*
            import dev.kuml.bpmn.model.*
            bpmnModel(name = "Orders") {
                process(id = "p", name = "Order") {
                    val start = startEvent(name = "Start")
                    val done = endEvent(name = "Done")
                    sequenceFlow(from = start, to = done)
                    $annotation
                }
                diagram(name = "Orders", processId = "p")
            }
            """.trimIndent()

        fun renderLatex(script: String): Pair<RenderOutcome, String> {
            val dir = Files.createTempDirectory("kuml-hint-latex").toFile()
            try {
                val input = File(dir, "d.kuml.kts").also { it.writeText(script) }
                val out = dir.toPath().resolve("o.tex")
                val outcome = RenderPipeline.run(input = input, output = out, format = "latex", width = 800, themeName = "kuml")
                return outcome to File(out.toString()).readText()
            } finally {
                dir.deleteRecursively()
            }
        }

        fun w004(o: RenderOutcome) = o.noteFindings.filter { it.code == "KUML-NOTE-W004" }

        test("latex: a LEFT hint on a note without a drawn anchor is reported as one W004, without the note body") {
            val (outcome, tex) =
                renderLatex(
                    script("""textAnnotation(text = "Loose secret body", id = "n1", placement = NotePlacement.LEFT)"""),
                )
            val findings = w004(outcome)
            withClue(outcome.noteFindings.toString()) {
                findings.size shouldBe 1
                findings.single().message shouldContain "wanted LEFT"
                findings.single().message.contains("secret") shouldBe false
            }
            tex shouldContain "kuml-note-text"
        }

        test("latex: honoured RIGHT and BELOW hints and NEAR print no W004") {
            for (hint in listOf("RIGHT", "BELOW", "NEAR")) {
                val (outcome, tex) =
                    renderLatex(script("""textAnnotation(text = "About start", start, id = "n1", placement = NotePlacement.$hint)"""))
                withClue("$hint ${outcome.noteFindings}") { w004(outcome) shouldBe emptyList() }
                if (hint == "RIGHT") tex shouldContain "current bounding box.east"
                if (hint == "BELOW") tex shouldContain "current bounding box.south"
            }
        }
    })
