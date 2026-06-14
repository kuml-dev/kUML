package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class NestedClassTest :
    FunSpec({

        test("nested class is emitted as top-level classifier with REV-K-020 diagnostic") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Outer.kt" to
                            """
                            class Outer {
                                class Inner
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()

            // Both Outer and Inner should be in model
            classes shouldHaveAtLeastSize 2
            classes.find { it.name == "Outer" } shouldNotBe null
            classes.find { it.name == "Inner" } shouldNotBe null

            // REV-K-020 info diagnostic should be present
            val hasK020 = success.diagnostics.any { it.code == "REV-K-020" }
            hasK020 shouldBe true
        }
    })
