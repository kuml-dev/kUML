package dev.kuml.core.script.interpreter

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.InProcessScriptEvaluator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The decisive test for Welle 9: the interpreter must produce a [KumlDiagram]
 * **equivalent to the one the embedded-Kotlin-compiler path produces** for the
 * *same, unmodified* real vault script.
 *
 * `01 UML Klasse – Order Domain.md` is the richest UML class-diagram example in
 * the vault (5 classes, an interface, an enum, a 1:n association, a composition,
 * a dependency, a generalization, inline `extends`/`implements`, operations with
 * parameters + return types, an OCL constraint, an anchored comment, and the
 * `Visibility` / `AggregationKind` enums). If the two paths agree here, the
 * interpreter faithfully reconstructs the model without the compiler.
 */
class InterpreterVsCompilerTest :
    StringSpec({
        fun loadScript(name: String): String =
            requireNotNull(
                InterpreterVsCompilerTest::class.java.getResourceAsStream("/interpreter/$name"),
            ) { "missing test resource: $name" }.bufferedReader().use { it.readText() }

        "interpreter reproduces the compiler's KumlDiagram for the real Order-Domain vault script" {
            val source = loadScript("order-domain.kuml.kts")

            val compilerResult = InProcessScriptEvaluator.evaluate(source, "order-domain.kuml.kts")
            val interpreterResult = InterpreterScriptEvaluator.evaluate(source, "order-domain.kuml.kts")

            require(compilerResult is EvaluatedScript.Success) { "compiler path failed: $compilerResult" }
            require(interpreterResult is EvaluatedScript.Success) { "interpreter path failed: $interpreterResult" }

            val compiled = compilerResult.diagram as ExtractedDiagram.Uml
            val interpreted = interpreterResult.diagram as ExtractedDiagram.Uml

            // The strongest possible equivalence: the full immutable KumlDiagram data
            // classes must be equal (name, type, config, and every element with its ID,
            // attributes, operations, relationships, constraints, comments…).
            interpreted.diagram shouldBe compiled.diagram
        }

        "both paths agree on the diagram name and element count (coarse sanity, independent of full equality)" {
            val source = loadScript("order-domain.kuml.kts")
            val compiled =
                (InProcessScriptEvaluator.evaluate(source) as EvaluatedScript.Success).diagram as ExtractedDiagram.Uml
            val interpreted =
                (InterpreterScriptEvaluator.evaluate(source) as EvaluatedScript.Success).diagram as ExtractedDiagram.Uml

            interpreted.diagram.name shouldBe compiled.diagram.name
            interpreted.diagram.type shouldBe compiled.diagram.type
            interpreted.diagram.elements.size shouldBe compiled.diagram.elements.size
            interpreted.diagram.elements.map { it.id } shouldBe compiled.diagram.elements.map { it.id }
        }

        "interpreter reproduces the compiler's KumlDiagram for a second, simpler class diagram (breadth)" {
            val source = loadScript("animals.kuml.kts")

            val compilerResult = InProcessScriptEvaluator.evaluate(source, "animals.kuml.kts")
            val interpreterResult = InterpreterScriptEvaluator.evaluate(source, "animals.kuml.kts")

            require(compilerResult is EvaluatedScript.Success) { "compiler path failed: $compilerResult" }
            require(interpreterResult is EvaluatedScript.Success) { "interpreter path failed: $interpreterResult" }

            val compiled = compilerResult.diagram as ExtractedDiagram.Uml
            val interpreted = interpreterResult.diagram as ExtractedDiagram.Uml

            // Includes a diagram-level display-option assignment (showOperations = false)
            // in the config, inline extends on two classes, and a named association.
            interpreted.diagram shouldBe compiled.diagram
        }
    })
