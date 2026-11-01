package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
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

        val ibdScript =
            """
            sysml2Model(name = "M") {
                portDef(name = "Sig")
                partDef(name = "Inner") { port(name = "ein", typeId = "Sig", direction = dev.kuml.sysml2.PortDirection.In) }
                val owner = partDef(name = "Steuerung") {
                    port(name = "eingang", typeId = "Sig", direction = dev.kuml.sysml2.PortDirection.In)
                    part(name = "logik", typeId = "Inner")
                    connect(name = "c1", typeId = "Sig", sourceEndId = "gibtsNicht", targetEndId = "logik.ein")
                }
                ibd(name = "Wiring", owner = owner)
            }
            """.trimIndent()

        val parScript =
            """
            sysml2Model(name = "P") {
                val druck = attributeDef(name = "Druck", unitSymbol = "Pa")
                val c = constraintDef(name = "Mix", expression = "a = b")
                parDiagram(name = "Par") {
                    include(c)
                    include(druck)
                }
            }
            """.trimIndent()

        test("IBD501 and PAR442 are structure findings of the shared service (so the desktop sees them too)") {
            val ibd = ValidationService.validate(script = ibdScript).filter { it.code == "IBD501" }
            ibd.size shouldBe 1
            ibd.single().severity shouldBe Severity.WARNING
            ibd.single().format() shouldContain "gibtsNicht"
            val par = ValidationService.validate(script = parScript).filter { it.code == "PAR442" }
            par.size shouldBe 1
            par.single().severity shouldBe Severity.WARNING
            par.single().anchorId shouldBe "Druck"
        }

        test("kuml validate reports IBD501 and PAR442 exactly once and still exits 0 (warnings)") {
            for ((script, code) in listOf(ibdScript to "IBD501", parScript to "PAR442")) {
                val file = java.io.File.createTempFile("struct", ".kuml.kts")
                try {
                    file.writeText(script)
                    val result = KumlCli().test(listOf("validate", file.absolutePath))
                    result.output.split("[$code]").size - 1 shouldBe 1
                    result.output shouldContain "WARN  [$code]"
                    result.statusCode shouldBe 0
                } finally {
                    file.delete()
                }
            }
        }

        test("a clean script has no findings") {
            ValidationService.validate(script = "classDiagram(name = \"A\") { classOf(name = \"A\") }").shouldBeEmpty()
        }
    })
