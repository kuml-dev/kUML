package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

/** Nacharbeit 45: a render reports the notes a diagram declared but did not draw (`KUML-NOTE-W002`), with the script line. */
class NoteUndrawnCliTest :
    FunSpec({
        // Script lines: 1 import, 2 blank, 3 sysml2Model, 4 wheel, 5 axle, 6 car, 7 owner comment, 8 nested comment, 9 unknown, 10 ibd
        val script =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Nested") {
                val wheel = partDef("Wheel")
                val axle = partDef("Axle") { part(name = "wheel", typeId = wheel.id) }
                val car = partDef("Car") { part(name = "axle", typeId = axle.id) }
                comment(text = "SECRET-OWNER-BODY", "Car", id = "owner")
                comment(text = "SECRET-NESTED-BODY", "Axle::wheel", id = "nested")
                comment(text = "SECRET-UNKNOWN-BODY", "Nope", id = "unknown")
                ibd("Car internals", owner = car)
            }
            """.trimIndent()

        test("render reports W002 for the nested note with its script line, W001 for the unknown anchor, nothing for the drawn note") {
            val dir = Files.createTempDirectory("kuml-undrawn").toFile()
            try {
                val input = File(dir, "nested.kuml.kts").also { it.writeText(script) }
                val outcome =
                    RenderPipeline.run(
                        input = input,
                        output = dir.toPath().resolve("o.svg"),
                        format = "svg",
                        width = 800,
                        themeName = "kuml",
                    )
                val notes = outcome.noteFindings.filter { it.code.startsWith("KUML-NOTE-W00") && it.code != "KUML-NOTE-W003" }
                withClue(notes.toString()) {
                    notes.map { it.code to it.subjectId } shouldBe listOf("KUML-NOTE-W001" to "unknown", "KUML-NOTE-W002" to "nested")
                    notes.single { it.code == "KUML-NOTE-W002" }.message shouldBe "note not drawn: nested, anchor Axle::wheel (nested)"
                    notes.single { it.code == "KUML-NOTE-W002" }.sourceLine shouldBe 8
                    notes.single { it.code == "KUML-NOTE-W001" }.sourceLine shouldBe 9
                }
                notes.forEach { it.message shouldNotContain "SECRET" }
                File(dir, "o.svg").readText() shouldNotContain "SECRET-NESTED-BODY"
            } finally {
                dir.deleteRecursively()
            }
        }

        test("the validation service carries the note line into the finding") {
            val findings = ValidationService.validate(script = script)
            findings.single { it.code == "KUML-NOTE-W001" }.line shouldBe 9
        }
    })
