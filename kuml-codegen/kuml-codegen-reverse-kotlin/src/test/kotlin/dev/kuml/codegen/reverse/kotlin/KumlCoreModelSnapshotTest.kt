package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Real-world integration test against kuml-core-model sources.
 * Only runs when the local repo path is available (CI will skip).
 */
class KumlCoreModelSnapshotTest :
    FunSpec({

        val kumlCoreModelSrcPath =
            Paths.get(
                "/Users/irakli/IdeaProjects/kUML/kuml-core/kuml-core-model/src/main/kotlin",
            )

        test("kuml-core-model sources produce a stable UML model without errors") {
            if (!Files.isDirectory(kumlCoreModelSrcPath)) {
                println("SKIP: kuml-core-model source path not found: $kumlCoreModelSrcPath")
                return@test
            }

            val request =
                ReverseRequest(
                    sourceRoots = listOf(kumlCoreModelSrcPath),
                    targetModelName = "KumlCoreModel",
                )
            val result = runBlocking { KotlinSourceReverseEngine().analyze(request) }

            (result is ReverseResult.Success) shouldBe true
            val success = result as ReverseResult.Success

            // No ERROR-level diagnostics
            val errorDiags = success.diagnostics.filter { it.severity == ReverseDiagnostic.Severity.ERROR }
            errorDiags.isEmpty() shouldBe true

            // Should produce a meaningful number of classifiers
            val diagram = success.model.root as KumlDiagram
            val classifiers =
                diagram.elements.filterIsInstance<UmlClass>() +
                    diagram.elements.filterIsInstance<UmlInterface>()
            classifiers shouldHaveAtLeastSize 5

            // At least one <<data>> classifier
            val hasData = classifiers.any { it.stereotypes.contains("data") }
            hasData shouldBe true
        }
    })
