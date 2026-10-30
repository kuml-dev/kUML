package dev.kuml.desktop.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class DesktopRenderPipelineNoteWarningsTest :
    FunSpec({

        val script =
            """
            sysml2Model(name = "N") {
                val a = stateDef(name = "A", isInitial = true)
                val b = stateDef(name = "B")
                transition(name = "t", source = a, target = b)
                stmDiagram(name = "S") {
                    include(a)
                    include(b)
                    comment(text = "SECRET-BODY", "Gibtsnicht", id = "n1")
                }
            }
            """.trimIndent()

        test("validate warns with the anchor name instead of OKF-E-007-style silence") {
            val result = DesktopRenderPipeline.validate(script = script)
            result.ok shouldBe true
            val warnings = result.diagnostics.filter { it.severity == "WARNING" && it.message.contains("KUML-NOTE-W001") }
            warnings shouldHaveSize 1
            warnings.single().message shouldContain "Gibtsnicht"
            warnings.single().message shouldNotContain "SECRET-BODY"
        }

        test("render still returns the SVG and carries the note warning") {
            val result = DesktopRenderPipeline.render(script = script, themeName = "plain").shouldBeInstanceOf<DesktopRenderResult.Svg>()
            result.svg shouldContain "<svg"
            result.warnings shouldHaveSize 1
            result.warnings.single() shouldContain "Gibtsnicht"
        }

        test("a clean script has no warnings") {
            val clean =
                script.replace(
                    "comment(text = \"SECRET-BODY\", \"Gibtsnicht\", id = \"n1\")",
                    "comment(text = \"ok\", id = \"n1\")",
                )
            DesktopRenderPipeline
                .render(script = clean, themeName = "plain")
                .shouldBeInstanceOf<DesktopRenderResult.Svg>()
                .warnings shouldBe emptyList()
        }
    })
