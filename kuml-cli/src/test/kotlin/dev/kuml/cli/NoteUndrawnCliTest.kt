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
        test("the printed warnings name the script line for W001 as well as for W002") {
            val dir = Files.createTempDirectory("kuml-undrawn-print").toFile()
            val original = System.err
            val captured = java.io.ByteArrayOutputStream()
            try {
                val input = File(dir, "nested.kuml.kts").also { it.writeText(script) }
                System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
                RenderPipeline.run(
                    input = input,
                    output = dir.toPath().resolve("o.svg"),
                    format = "svg",
                    width = 800,
                    themeName = "kuml",
                )
            } finally {
                System.setErr(original)
                dir.deleteRecursively()
            }
            val lines = captured.toString(Charsets.UTF_8).lines()
            withClue(lines.toString()) {
                lines.single { it.contains("[KUML-NOTE-W001]") }.endsWith("(line 9)") shouldBe true
                lines.single { it.contains("[KUML-NOTE-W002]") }.endsWith("(line 8)") shouldBe true
            }
        }
        test("a missed placement hint names the line the multi-line comment call starts on (W004)") {
            // The resource is the PAL document 46 IBD with BELOW hints; the `a13-46` note below the edge has no room (line 42 = `comment(`).
            val script = checkNotNull(javaClass.getResource("/notes/hint-miss.kuml.kts")).readText()
            val dir = Files.createTempDirectory("kuml-hint-line").toFile()
            try {
                val input = File(dir, "hint.kuml.kts").also { it.writeText(script) }
                val outcome =
                    RenderPipeline.run(
                        input = input,
                        output = dir.toPath().resolve("o.svg"),
                        format = "svg",
                        width = 800,
                        themeName = "kuml",
                    )
                // PAL Welle X4: a13e-46 (ABOVE) sits beside its anchor, which the strict W004 now reports too; the line check is for a13-46.
                val miss = outcome.noteFindings.single { it.code == "KUML-NOTE-W004" && it.subjectId == "a13-46" }
                miss.sourceLine shouldBe 42
            } finally {
                dir.deleteRecursively()
            }
        }
    })
