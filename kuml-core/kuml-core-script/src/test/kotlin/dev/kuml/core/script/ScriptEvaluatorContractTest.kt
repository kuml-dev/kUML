package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Interface-contract tests run against **both** [ScriptEvaluator] implementations.
 *
 * Every case here must hold identically whether the script runs in-process or in
 * a child JVM. That is the whole promise of the [ScriptEvaluator] seam: the two
 * paths are behaviourally equivalent for legitimate and for rejected input.
 *
 * The timeout case is child-process-only (an in-process infinite loop cannot be
 * killed) and lives in [ChildProcessScriptEvaluatorTest].
 *
 * V0.23.3 — Welle 2.
 */
class ScriptEvaluatorContractTest :
    FunSpec({

        val minimalUml = """diagram(name = "Hello", type = DiagramType.CLASS) {}"""

        val classDiagram =
            """
            diagram(name = "Shop", type = DiagramType.CLASS) {
                classOf("Order")
                classOf("Customer")
            }
            """.trimIndent()

        val sysml2Stm =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Light") {
                val initial = stateDef("Initial", isInitial = true)
                val red = stateDef("Red")
                transition("init", initial, red)
                stmDiagram("Light STM") {
                    include(initial)
                    include(red)
                }
            }
            """.trimIndent()

        val evaluators: List<Pair<String, ScriptEvaluator>> =
            listOf(
                "in-process" to InProcessScriptEvaluator,
                "child-process" to ChildProcessScriptEvaluator(timeoutSeconds = 60),
            )

        evaluators.forEach { (name, evaluator) ->

            context("[$name] success cases") {
                test("minimal UML diagram evaluates to an ExtractedDiagram.Uml") {
                    val result = evaluator.evaluate(minimalUml)
                    result.shouldBeInstanceOf<EvaluatedScript.Success>()
                    result.diagram.shouldBeInstanceOf<ExtractedDiagram.Uml>()
                }

                test("class diagram preserves elements across the boundary") {
                    val result = evaluator.evaluate(classDiagram)
                    result.shouldBeInstanceOf<EvaluatedScript.Success>()
                    val uml = result.diagram.shouldBeInstanceOf<ExtractedDiagram.Uml>()
                    // Order + Customer must survive serialization (child path)
                    uml.diagram.elements
                        .mapNotNull { (it as? dev.kuml.uml.UmlClass)?.name }
                        .toSet() shouldBe setOf("Order", "Customer")
                }

                test("SysML 2 STM model round-trips as ExtractedDiagram.Sysml2") {
                    val result = evaluator.evaluate(sysml2Stm)
                    result.shouldBeInstanceOf<EvaluatedScript.Success>()
                    val s2 = result.diagram.shouldBeInstanceOf<ExtractedDiagram.Sysml2>()
                    s2.model.diagrams.isEmpty() shouldBe false
                }
            }

            context("[$name] failure cases") {
                test("guard rejection is a GUARD failure") {
                    val hostile = """diagram(name = "x") { }; ProcessBuilder("id").start()"""
                    val result = evaluator.evaluate(hostile)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.GUARD
                }

                test("compile error is an EVALUATION failure") {
                    val broken = """diagram(name = "x", type = DiagramType.CLASS) { thisIsNotAValidBuilder() }"""
                    val result = evaluator.evaluate(broken)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.EVALUATION
                }

                test("script producing no diagram is an EVALUATION failure") {
                    val noDiagram = """val x = 1 + 1"""
                    val result = evaluator.evaluate(noDiagram)
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.kind shouldBe FailureKind.EVALUATION
                }

                test("failure message does not leak an absolute temp path") {
                    // A "no renderable diagram" error includes the script's name
                    // in DiagramExtractor's message — that name must be the
                    // caller-supplied virtual one, never the server-internal
                    // temp file path.
                    val noDiagram = """val x = 1 + 1"""
                    val result = evaluator.evaluate(noDiagram, fileName = "myscript.kuml.kts")
                    val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                    failure.message shouldContain "myscript.kuml.kts"
                    failure.message.contains("kuml-eval-") shouldBe false
                }
            }
        }
    })
