package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe

class KtInterfaceMapperTest :
    FunSpec({

        test("interface becomes UmlInterface") {
            val result = TestSupport.runEngine(mapOf("Foo.kt" to "interface Foo"))
            val success = TestSupport.success(result)
            val interfaces = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlInterface>()
            interfaces.find { it.name == "Foo" } shouldNotBe null
        }

        test("fun interface gets fun stereotype") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Transformer.kt" to "fun interface Transformer { fun transform(x: Int): Int }",
                    ),
                )
            val success = TestSupport.success(result)
            val interfaces = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlInterface>()
            val tr = interfaces.find { it.name == "Transformer" }
            tr shouldNotBe null
            tr!!.stereotypes shouldContain "fun"
        }
    })
