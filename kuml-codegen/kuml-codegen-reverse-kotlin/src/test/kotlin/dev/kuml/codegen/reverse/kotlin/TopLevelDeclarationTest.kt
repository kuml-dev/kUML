package dev.kuml.codegen.reverse.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TopLevelDeclarationTest :
    FunSpec({

        test("top-level val property emits REV-K-012 info diagnostic") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "Config.kt" to
                            """
                            const val MAX_RETRIES = 3
                            class Service
                            """.trimIndent(),
                    ),
                )
            // Service class should be there; top-level const should emit REV-K-012
            val success = TestSupport.success(result)
            success.diagnostics.any { it.code == "REV-K-012" } shouldBe true
            val classes = (success.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
            classes.any { it.name == "Service" } shouldBe true
        }
    })
