package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtTypeResolverTest :
    FunSpec({

        test("internal classifier reference resolves to internal id") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Domain.kt" to
                            """
                            class Address(val street: String)
                            class Person(val address: Address)
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val diagram = success.model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            val person = classes.find { it.name == "Person" }
            val addressProp = person?.attributes?.find { it.name == "address" }
            addressProp shouldNotBe null
            // Should resolve to an internal id (kt:Address or kt:<pkg>.Address)
            addressProp?.type?.referencedId shouldNotBe null
            addressProp?.type?.referencedId?.startsWith("kt:") shouldBe true
        }

        test("nullable type results in nullable name with question mark") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Nullable.kt" to "class Nullable { val x: String? = null }",
                    ),
                )
            val success = TestSupport.success(result)
            val diagram = success.model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            val prop = classes.find { it.name == "Nullable" }?.attributes?.find { it.name == "x" }
            prop shouldNotBe null
            prop?.type?.name shouldBe "String?"
        }
    })
