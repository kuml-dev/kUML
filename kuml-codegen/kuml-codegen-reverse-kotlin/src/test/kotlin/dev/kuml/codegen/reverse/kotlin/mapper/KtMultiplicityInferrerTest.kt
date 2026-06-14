package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtMultiplicityInferrerTest :
    FunSpec({

        test("Int maps to 1..1, Int? to 0..1, List<Int> to 0..*") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Props.kt" to
                            """
                            class Props {
                                val single: Int = 0
                                val optional: Int? = null
                                val many: List<Int> = emptyList()
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val props = classes.find { it.name == "Props" }
            props shouldNotBe null

            val single = props?.attributes?.find { it.name == "single" }
            single?.multiplicity shouldBe Multiplicity(lower = 1, upper = 1)

            val optional = props?.attributes?.find { it.name == "optional" }
            optional?.multiplicity shouldBe Multiplicity(lower = 0, upper = 1)

            val many = props?.attributes?.find { it.name == "many" }
            many?.multiplicity shouldBe Multiplicity(lower = 0, upper = null)
        }
    })
