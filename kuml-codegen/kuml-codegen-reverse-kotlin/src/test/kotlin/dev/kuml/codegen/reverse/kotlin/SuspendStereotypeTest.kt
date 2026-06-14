package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe

class SuspendStereotypeTest :
    FunSpec({

        test("suspend function gets suspend stereotype") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Repo.kt" to
                            """
                            class Repo {
                                suspend fun fetch(): String = ""
                            }
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val op = classes.find { it.name == "Repo" }?.operations?.find { it.name == "fetch" }
            op shouldNotBe null
            op!!.stereotypes shouldContain "suspend"
        }
    })
