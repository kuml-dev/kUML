package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldNotBe

class KtDataClassClassifierTest :
    FunSpec({

        test("data sealed value inner classes get correct stereotype combinations") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Types.kt" to
                            """
                            data class DataClass(val x: Int)
                            sealed class SealedBase
                            value class ValueWrapper(val v: Int)
                            class Outer {
                                inner class InnerOne
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()

            val dc = classes.find { it.name == "DataClass" }
            dc shouldNotBe null
            dc!!.stereotypes shouldContain "data"

            val sealed = classes.find { it.name == "SealedBase" }
            sealed shouldNotBe null
            sealed!!.stereotypes shouldContain "sealed"

            val vw = classes.find { it.name == "ValueWrapper" }
            vw shouldNotBe null
            vw!!.stereotypes shouldContain "value"

            val inner = classes.find { it.name == "InnerOne" }
            inner shouldNotBe null
            inner!!.stereotypes shouldContain "inner"

            // plain class should NOT get data/sealed/value/inner stereotypes
            val outer = classes.find { it.name == "Outer" }
            outer shouldNotBe null
            outer!!.stereotypes shouldNotContain "data"
            outer.stereotypes shouldNotContain "sealed"
        }
    })
