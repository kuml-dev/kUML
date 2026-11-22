package dev.kuml.cli

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.bridge.note.PlacementHintMiss
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/** PAL Welle B: `port(labelAt = ...)` renders; an honoured hint raises no finding; a missed hint becomes `KUML-IBD-W001` with its line. */
class PortLabelAtCliTest :
    FunSpec({
        val script =
            """
            import dev.kuml.sysml2.PortDirection
            import dev.kuml.sysml2.PortLabelAt
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Hint") {
                val sig = portDef("Signal")
                val a = partDef("Alpha") { port(name = "out1", typeId = sig.id, direction = PortDirection.Out, labelAt = PortLabelAt.After) }
                val b = partDef("Beta") { port(name = "in1", typeId = sig.id, direction = PortDirection.In, labelAt = PortLabelAt.Before) }
                val sys =
                    partDef("System") {
                        part(name = "a", typeId = a.id)
                        part(name = "b", typeId = b.id)
                        connect(name = "w", typeId = sig.id, sourceEndId = "System::a::out1", targetEndId = "System::b::in1")
                    }
                ibd("Sys", owner = sys)
            }
            """.trimIndent()

        test("an honoured labelAt hint renders without a KUML-IBD-W001 finding") {
            val dir = Files.createTempDirectory("kuml-labelat").toFile()
            try {
                val input = File(dir, "h.kuml.kts").also { it.writeText(script) }
                val outcome =
                    RenderPipeline.run(
                        input = input,
                        output = dir.toPath().resolve("o.svg"),
                        format = "svg",
                        width = 800,
                        themeName = "kuml",
                    )
                withClue(outcome.noteFindings.toString()) {
                    outcome.noteFindings.none { it.code == "KUML-IBD-W001" } shouldBe true
                }
                File(dir, "o.svg").readText().contains("out1") shouldBe true
            } finally {
                dir.deleteRecursively()
            }
        }

        test("a missed port label hint becomes KUML-IBD-W001 with the script line, never a note code") {
            val finding =
                NoteChecks
                    .placementMisses(
                        listOf(
                            PlacementHintMiss(
                                noteId = "port:A::p",
                                wanted = NotePlacement.NEAR,
                                placed = NotePlacement.NEAR,
                                sourceLine = 7,
                                code = PlacementHintMiss.PORT_LABEL_HINT_NOT_HONOURED,
                                customMessage = "port label hint not honoured: A::p, wanted labelAt = Before, fell back to Auto",
                            ),
                        ),
                    ).single()
            finding.code shouldBe "KUML-IBD-W001"
            finding.sourceLine shouldBe 7
            finding.message shouldBe "port label hint not honoured: A::p, wanted labelAt = Before, fell back to Auto"
        }
    })
