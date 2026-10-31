package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class ValidationServiceTest :
    FunSpec({
        val noteScript =
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

        test("an unknown note anchor is KUML-NOTE-W001 with the anchor id and no note body") {
            val f = ValidationService.validate(script = noteScript).single { it.code == "KUML-NOTE-W001" }
            f.severity shouldBe Severity.WARNING
            f.anchorId shouldBe "Gibtsnicht"
            f.anchorName shouldBe null
            f.diagramId shouldBe "S"
            f.format() shouldNotContain "SECRET-BODY"
            f.format() shouldContain "Gibtsnicht"
        }

        test("a known anchor that cannot be drawn names the element and its id") {
            val script =
                """
                sysml2Model(name = "N") {
                    val a = stateDef(name = "A", isInitial = true)
                    val v = partDef(name = "Vehicle")
                    stmDiagram(name = "S") {
                        include(a)
                        comment(text = "x", v, id = "n1")
                    }
                }
                """.trimIndent()
            val f = ValidationService.validate(script = script).filter { it.anchorId != null }
            f.forEach { it.format() shouldContain "(${it.anchorId})" }
        }

        test("a typo on line 5 is reported with line and column") {
            val script =
                """
                classDiagram(name = "A") {
                    val a = classOf(name = "A")
                    val b = classOf(name = "B")
                    association(source = a, target = b)
                    val c = classOfX(name = "C")
                }
                """.trimIndent()
            val f = ValidationService.validate(script = script).first()
            f.severity shouldBe Severity.ERROR
            f.line shouldBe 5
            f.format() shouldStartWith "Zeile 5, Spalte "
        }

        test("a type error reads 'Argument type mismatch in call to fn(...): expected X, found Y' with line and column") {
            val script =
                """
                classDiagram(name = "A") {
                    classOf(name = 5)
                }
                """.trimIndent()
            val f = ValidationService.validate(script = script).first()
            f.format() shouldBe
                "Zeile 2, Spalte 20: [KUML-SCRIPT-E002] Argument type mismatch in call to 'classOf(...)': expected String, found Int"
        }

        test("layout options a diagram art ignores are KUML-LAYOUT-W001, unknown ids KUML-LAYOUT-W004") {
            val script =
                """
                import dev.kuml.blueprint.dsl.blueprint
                import dev.kuml.blueprint.model.Sentiment
                blueprint(name = "Service") {
                    phase(name = "Request") { customer(name = "Fills in", sentiment = Sentiment.NEUTRAL) }
                    blueprintDiagram(name = "Blueprint") { layout { direction = Right; first("ghost") } }
                }
                """.trimIndent()
            val codes = ValidationService.validate(script = script).map { it.code }
            codes.count { it == "KUML-LAYOUT-W001" } shouldBe 2
            codes.count { it == "KUML-LAYOUT-W004" } shouldBe 1
        }

        test("a clean script has no findings") {
            ValidationService.validate(script = "classDiagram(name = \"A\") { classOf(name = \"A\") }").shouldBeEmpty()
        }
    })
