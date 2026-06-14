package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtFunctionMapperTest :
    FunSpec({

        test("function with params and return type maps correctly") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Calc.kt" to "class Calc { fun add(a: Int, b: Int): Int = a + b }",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val op = classes.find { it.name == "Calc" }?.operations?.find { it.name == "add" }
            op shouldNotBe null
            op!!.parameters.size shouldBe 2
            op.parameters.map { it.name } shouldContain "a"
            op.parameters.map { it.name } shouldContain "b"
            op.returnType?.name shouldBe "Int"
        }

        test("extension function on receiver type gets extension stereotype") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "StringExt.kt" to
                            """
                            class StringHelper {
                                fun String.shout(): String = uppercase()
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val op = classes.find { it.name == "StringHelper" }?.operations?.find { it.name == "shout" }
            op shouldNotBe null
            op!!.stereotypes shouldContain "extension"
        }
    })
