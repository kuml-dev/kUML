package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis

/**
 * Measures the cold-start overhead of the child-process evaluator vs. the
 * in-process path for a simple script.
 *
 * This is **not** a pass/fail gate — it prints the numbers so the cost is
 * on record (per Welle 2, deliverable 6: quantify the cold-start penalty so the
 * urgency of the warm-pool wave is data-driven, not guessed). It only asserts
 * that both paths actually produce a diagram.
 *
 * NOTE: numbers depend heavily on the machine and on JIT/disk-cache warmth. The
 * child path here starts a brand-new JVM **and** warms up the embedded Kotlin
 * compiler from scratch on every call — exactly what the warm-pool wave will
 * amortise.
 *
 * V0.23.3.
 */
class ColdStartOverheadBenchmarkTest :
    FunSpec({

        val script =
            """
            diagram(name = "Bench", type = DiagramType.CLASS) {
                classOf("Order")
                classOf("Customer")
            }
            """.trimIndent()

        test("cold-start overhead: in-process vs child-process") {
            // Warm both paths once (JIT + first-time Kotlin compiler load) so the
            // reported numbers are steady-state cold starts, not first-ever loads.
            InProcessScriptEvaluator.evaluate(script)
            val child = ChildProcessScriptEvaluator(timeoutSeconds = 60)
            child.evaluate(script)

            val runs = 3
            var inProcTotal = 0L
            var childTotal = 0L
            repeat(runs) {
                inProcTotal +=
                    measureTimeMillis {
                        InProcessScriptEvaluator.evaluate(script).shouldBeInstanceOf<EvaluatedScript.Success>()
                    }
                childTotal +=
                    measureTimeMillis {
                        child.evaluate(script).shouldBeInstanceOf<EvaluatedScript.Success>()
                    }
            }
            val inProcAvg = inProcTotal / runs
            val childAvg = childTotal / runs
            println(
                "[cold-start-benchmark] in-process avg=${inProcAvg}ms  " +
                    "child-process avg=${childAvg}ms  " +
                    "overhead=${childAvg - inProcAvg}ms ($runs runs each)",
            )
        }
    })
