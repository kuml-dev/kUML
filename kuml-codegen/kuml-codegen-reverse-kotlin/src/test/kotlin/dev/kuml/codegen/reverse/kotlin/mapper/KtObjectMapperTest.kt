package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe

class KtObjectMapperTest :
    FunSpec({

        test("object declaration gets object stereotype") {
            val result = TestSupport.runEngine(mapOf("Singleton.kt" to "object Singleton"))
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val singleton = classes.find { it.name == "Singleton" }
            singleton shouldNotBe null
            singleton!!.stereotypes shouldContain "object"
        }

        test("companion object gets object and companion stereotypes") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "MyClass.kt" to
                            """
                            class MyClass {
                                companion object {
                                    val VERSION = "1.0"
                                }
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val companion = classes.find { it.stereotypes.contains("companion") }
            companion shouldNotBe null
            companion!!.stereotypes shouldContain "object"
            companion.stereotypes shouldContain "companion"
        }
    })
