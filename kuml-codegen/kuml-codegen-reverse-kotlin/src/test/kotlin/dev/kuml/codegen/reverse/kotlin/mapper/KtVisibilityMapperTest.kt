package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.TestSupport
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KtVisibilityMapperTest :
    FunSpec({

        test("all four visibility modifiers map to correct UML Visibility values") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Vis.kt" to
                            """
                            public class PublicClass
                            private class PrivateClass
                            protected class ProtectedClass
                            internal class InternalClass
                            """.trimIndent(),
                    ),
                )
            val success = TestSupport.success(result)
            val diagram = success.model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()

            classes.find { it.name == "PublicClass" }?.visibility shouldBe Visibility.PUBLIC
            classes.find { it.name == "PrivateClass" }?.visibility shouldBe Visibility.PRIVATE
            classes.find { it.name == "ProtectedClass" }?.visibility shouldBe Visibility.PROTECTED
            classes.find { it.name == "InternalClass" }?.visibility shouldBe Visibility.PACKAGE
        }
    })
