package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtClassMapperTest :
    FunSpec({

        test("simple class becomes UmlClass with correct name and isAbstract false") {
            val result = TestSupport.runEngine(mapOf("Foo.kt" to "class Foo"))
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val foo = classes.find { it.name == "Foo" }
            foo shouldNotBe null
            foo!!.isAbstract shouldBe false
            foo.stereotypes shouldNotContain "data"
        }

        test("abstract class sets isAbstract true") {
            val result = TestSupport.runEngine(mapOf("Foo.kt" to "abstract class Foo"))
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val foo = classes.find { it.name == "Foo" }
            foo shouldNotBe null
            foo!!.isAbstract shouldBe true
        }

        test("data class gets data stereotype and maps primary-ctor val param as attribute") {
            val result = TestSupport.runEngine(mapOf("Foo.kt" to "data class Foo(val x: Int)"))
            val success = TestSupport.success(result)
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            val foo = classes.find { it.name == "Foo" }
            foo shouldNotBe null
            foo!!.stereotypes shouldContain "data"
            foo.attributes.map { it.name } shouldContain "x"
        }
    })
