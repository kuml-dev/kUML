package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ConstructorMappingTest :
    FunSpec({

        test("primary constructor without val/var params does not create properties") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Event.kt" to "class Event(name: String)",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val event = classes.find { it.name == "Event" }
            event shouldNotBe null
            // 'name' is NOT val/var → should NOT appear as attribute
            event?.attributes?.any { it.name == "name" } shouldBe false
        }

        test("secondary constructor gets constructor stereotype") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Person.kt" to
                            """
                            class Person(val name: String) {
                                constructor(name: String, age: Int) : this(name)
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val person = classes.find { it.name == "Person" }
            person shouldNotBe null
            val ctorOps = person?.operations?.filter { it.stereotypes.contains("constructor") }
            ctorOps?.size shouldBe 2 // primary + secondary
        }
    })
