package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlEnumeration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtEnumerationMapperTest :
    FunSpec({

        test("enum class becomes UmlEnumeration with correct literals") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Color.kt" to "enum class Color { RED, GREEN, BLUE }",
                    ),
                )
            val success = TestSupport.success(result)
            val enums = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlEnumeration>()
            val color = enums.find { it.name == "Color" }
            color shouldNotBe null
            color?.literals?.map { it.name } shouldContainExactlyInAnyOrder listOf("RED", "GREEN", "BLUE")
        }

        test("enum entry with body emits REV-K-030 diagnostic") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Ops.kt" to
                            """
                            enum class Ops {
                                ADD {
                                    override fun apply(a: Int, b: Int) = a + b
                                };
                                abstract fun apply(a: Int, b: Int): Int
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val hasK030 = success.diagnostics.any { it.code == "REV-K-030" }
            hasK030 shouldBe true
        }
    })
