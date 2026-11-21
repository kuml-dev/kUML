package dev.kuml.web.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Nacharbeit 128 - hostile labels reach the web LaTeX output only in escaped form: no label can start a TeX
 * command, read a file or leave its node. The web path calls the same renderers as the CLI; this pins it.
 */
class WebRenderPipelineLatexHostileTest :
    FunSpec({
        beforeSpec { EngineRegistration.ensure() }

        // Written into a Kotlin string literal of the script: `\\` is a backslash, `\n` a real newline.
        val hostile = "\\\\input{/etc/passwd}\\\\write18{rm -rf /}\\\\immediate\\\\write 100% #1 {x} a_b ^ ~ & line\\nbreak"

        fun tex(script: String): String {
            val result = WebRenderPipeline.render(script = script, format = "latex", themeName = null, layoutOverride = null)
            return result.shouldBeInstanceOf<WebRenderResult.Latex>().tex
        }

        fun assertNeutralised(out: String) {
            // Every TeX control word reaches the output only behind \textbackslash{} ...
            out shouldNotContain "\\input{"
            out shouldNotContain "\\write18"
            out shouldNotContain "\\immediate"
            out shouldContain "\\textbackslash{}input\\{/etc/passwd\\}"
            out shouldContain "\\textbackslash{}write18\\{rm -rf /\\}"
            // ... and the percent sign never opens a comment from label text.
            out.lines().filter { "100" in it && "write" in it }.forEach { line ->
                Regex("""(?<!\\)%""").containsMatchIn(line) shouldBe false
            }
        }

        test("UML class names and attributes") {
            assertNeutralised(
                tex(
                    """
                    classDiagram(name = "T") {
                        classOf(name = "$hostile") { attribute(name = "$hostile", type = "Int") }
                    }
                    """.trimIndent(),
                ),
            )
        }

        test("ERM Martin entity, attribute and note") {
            assertNeutralised(
                tex(
                    """
                    ermModel(name = "S") {
                        val a = entity(name = "$hostile") {
                            id()
                            attribute(name = "$hostile", type = ErmDataType.Varchar(20), nullable = false)
                        }
                        note("$hostile", a)
                        diagram(name = "D", notation = ErmNotation.MARTIN)
                    }
                    """.trimIndent(),
                ),
            )
        }

        test("C4 person and system") {
            assertNeutralised(
                tex(
                    """
                    c4Model(name = "S") {
                        val u = person(name = "$hostile")
                        val s = softwareSystem(name = "$hostile")
                        relationship(source = u, target = s) { technology = "HTTPS" }
                        systemContextDiagram(name = "V") { include(u, s) }
                    }
                    """.trimIndent(),
                ),
            )
        }

        test("BPMN task name") {
            assertNeutralised(
                tex(
                    """
                    import dev.kuml.bpmn.dsl.*
                    import dev.kuml.bpmn.model.*
                    bpmnModel(name = "M") {
                        process(id = "p", name = "P") {
                            val s = startEvent(name = "go")
                            val t = task(name = "$hostile", type = TaskType.USER)
                            val e = endEvent(name = "end")
                            sequenceFlow(from = s, to = t)
                            sequenceFlow(from = t, to = e)
                        }
                        diagram(name = "D", processId = "p")
                    }
                    """.trimIndent(),
                ),
            )
        }

        test("a 10000 character label is escaped completely, not truncated") {
            val long = "A_".repeat(5_000)
            val out = tex("classDiagram(name = \"T\") {\n    classOf(name = \"$long\")\n}")
            out shouldContain "A\\_".repeat(5_000)
        }
    })
