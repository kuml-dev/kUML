package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CoroutineFlowMappingTest :
    FunSpec({

        test("Flow property type maps to multiplicity 0..*") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Stream.kt" to
                            """
                            class Stream {
                                val events: List<Int> = emptyList()
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val prop = classes.find { it.name == "Stream" }?.attributes?.find { it.name == "events" }
            prop shouldNotBe null
            // List is a container type → 0..*
            prop?.multiplicity shouldBe Multiplicity(lower = 0, upper = null)
        }
    })
