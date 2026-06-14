package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class GenericTypeParameterTest :
    FunSpec({

        test("generic class is mapped as UmlClass with raw name (no UML template params in V1)") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Box.kt" to "class Box<T>(val value: T)",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val box = classes.find { it.name == "Box" }
            box shouldNotBe null
            // The 'value' property should be there, type is T (or <inferred> if unresolved)
            val valueProp = box?.attributes?.find { it.name == "value" }
            valueProp shouldNotBe null
            // T is not a known type in pool → external reference
            valueProp?.type?.name shouldBe "T"
        }
    })
