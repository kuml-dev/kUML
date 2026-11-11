package dev.kuml.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Nacharbeit 73 (a): the W003 `actual` of a state machine is measured on the final image, so it equals the SVG
 * viewBox ratio. Geometry only, no text widths.
 */
class StmFinalAspectCliTest :
    FunSpec({
        fun check(
            body: String,
            header: String,
        ) {
            val dir = Files.createTempDirectory("kuml-stm-aspect").toFile()
            try {
                val input = File(dir, "a.kuml.kts").also { it.writeText(header + "\n" + body) }
                val out = dir.toPath().resolve("o.svg")
                val outcome = RenderPipeline.run(input = input, output = out, format = "svg", width = 800, themeName = "kuml")
                val box =
                    Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""")
                        .find(File(out.toString()).readText())!!
                        .groupValues
                        .drop(1)
                        .map { it.toDouble() }
                val ratio = box[0] / box[1]
                val findings = outcome.layoutFindings.filter { it.code == "KUML-LAYOUT-W003" }
                withClue(outcome.layoutFindings.toString()) {
                    findings.size shouldBe 1
                    val actual = Regex("""actual ([0-9.]+)""").find(findings.single().message)!!.groupValues[1].toDouble()
                    actual shouldBe (ratio plusOrMinus 0.01)
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        test("SysML 2 state machine: reported actual equals the viewBox ratio") {
            check(
                header = "import dev.kuml.sysml2.dsl.sysml2Model",
                body =
                    """
                    sysml2Model(name = "Cycle") {
                        val i = stateDef(name = "Start", isInitial = true)
                        val off = stateDef(name = "Off")
                        val run = stateDef(name = "Running")
                        val stp = stateDef(name = "Stopped")
                        transition(name = "i", source = i, target = off)
                        transition(name = "t1", source = off, target = run, trigger = "start")
                        transition(name = "t2", source = run, target = stp, trigger = "stop")
                        transition(name = "t3", source = stp, target = off, trigger = "reset")
                        stmDiagram(name = "Cycle") {
                            layout { direction = Down; aspectRatio = 1.0 }
                            include(state = i)
                            include(state = off)
                            include(state = run)
                            include(state = stp)
                        }
                    }
                    """.trimIndent(),
            )
        }

        test("UML state machine: reported actual equals the viewBox ratio") {
            check(
                header = "",
                body =
                    """
                    stateDiagram(name = "Flow") {
                        val s = initialState()
                        val a = state(name = "A")
                        val b = state(name = "B")
                        val c = state(name = "C")
                        transition(source = s, target = a)
                        transition(source = a, target = b)
                        transition(source = b, target = c)
                        layout { direction = Down; aspectRatio = 4.0 }
                    }
                    """.trimIndent(),
            )
        }
    })
