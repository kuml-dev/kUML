package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtPropertyMapperTest :
    FunSpec({

        test("val property is read-only") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Foo.kt" to "class Foo { val x: Int = 0 }",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val prop = classes.find { it.name == "Foo" }?.attributes?.find { it.name == "x" }
            prop shouldNotBe null
            prop!!.isReadOnly shouldBe true
        }

        test("var property is writable") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Bar.kt" to "class Bar { var count: Int = 0 }",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val prop = classes.find { it.name == "Bar" }?.attributes?.find { it.name == "count" }
            prop shouldNotBe null
            prop!!.isReadOnly shouldBe false
        }

        test("lateinit var gets lateinit stereotype") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Service.kt" to "class Service { lateinit var name: String }",
                    ),
                )
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val prop = classes.find { it.name == "Service" }?.attributes?.find { it.name == "name" }
            prop shouldNotBe null
            prop!!.stereotypes shouldContain "lateinit"
        }
    })
