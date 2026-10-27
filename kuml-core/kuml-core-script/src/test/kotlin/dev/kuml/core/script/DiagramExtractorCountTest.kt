package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics

class DiagramExtractorCountTest :
    FunSpec({

        fun count(code: String): Int {
            val result = KumlScriptHost.eval(code = code)
            val success = result as ResultWithDiagnostics.Success
            val rv: ResultValue = success.value.returnValue
            return DiagramExtractor.countRenderableDiagrams(rv)
        }

        test("a single UML diagram as last expression counts 1") {
            count("""classDiagram(name = "A") { classOf(name = "X") }""") shouldBe 1
        }

        test("a C4 model with three diagrams counts 3") {
            count(
                """
                c4Model(name = "M") {
                    val p = person(name = "P")
                    val s = softwareSystem(name = "S")
                    systemContextDiagram(name = "D1") { include(p, s) }
                    systemContextDiagram(name = "D2") { include(p, s) }
                    systemContextDiagram(name = "D3") { include(p, s) }
                }
                """.trimIndent(),
            ) shouldBe 3
        }

        test("two UML diagrams bound to vals count 2") {
            count(
                """
                val a = classDiagram(name = "A") { classOf(name = "X") }
                val b = classDiagram(name = "B") { classOf(name = "Y") }
                """.trimIndent(),
            ) shouldBe 2
        }

        test("a model returned AND bound to a val is counted once") {
            count(
                """
                val m = c4Model(name = "M") {
                    val p = person(name = "P")
                    val s = softwareSystem(name = "S")
                    systemContextDiagram(name = "D1") { include(p, s) }
                    systemContextDiagram(name = "D2") { include(p, s) }
                }
                m
                """.trimIndent(),
            ) shouldBe 2
        }

        test("an ERM model without an explicit diagram counts 1 (default diagram)") {
            count(
                """
                ermModel("E") {
                    entity("Order") { id() }
                }
                """.trimIndent(),
            ) shouldBe 1
        }
    })
