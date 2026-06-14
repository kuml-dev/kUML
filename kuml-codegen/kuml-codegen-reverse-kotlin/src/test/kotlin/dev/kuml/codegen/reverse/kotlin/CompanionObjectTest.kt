package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CompanionObjectTest :
    FunSpec({

        test("companion object members are attached to a separate companion classifier") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Widget.kt" to
                            """
                            class Widget {
                                val name: String = ""
                                companion object {
                                    val DEFAULT_SIZE = 10
                                }
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()

            // Should have at least Widget + Companion
            classes shouldHaveAtLeastSize 2

            val companion = classes.find { it.stereotypes.contains("companion") }
            companion shouldNotBe null
            companion?.attributes?.any { it.name == "DEFAULT_SIZE" } shouldBe true

            val widget = classes.find { it.name == "Widget" }
            widget shouldNotBe null
            widget?.attributes?.any { it.name == "name" } shouldBe true
        }
    })
