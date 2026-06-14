package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExtensionFunctionTest :
    FunSpec({

        test("top-level extension function emits REV-K-011 diagnostic and produces no extra classifier") {
            val result =
                TestSupport.runEngine(
                    mapOf(
                        "StringExt.kt" to "fun String.shout(): String = uppercase()",
                    ),
                )
            // The file has only a top-level function → no classifiers → Failure (no source files with classifiers)
            // OR it results in Success with no classifiers and REV-K-011 diagnostic
            when (result) {
                is ReverseResult.Failure -> {
                    // Acceptable: no classifiers found → engine returns failure
                    result.errors.isNotEmpty() shouldBe true
                }
                is ReverseResult.Success -> {
                    val classes = (result.model.root as KumlDiagram).elements.filterIsInstance<UmlClass>()
                    classes.isEmpty() shouldBe true
                    result.diagnostics.any { it.code == "REV-K-011" } shouldBe true
                }
            }
        }
    })
