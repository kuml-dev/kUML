package dev.kuml.core.script.interpreter

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.FailureKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * Resource-bound (DoS-guard) tests for the execution-free interpreter.
 *
 * The interpreter has no RCE risk, but a pathological input could still exhaust
 * CPU/memory or overflow the parser stack. These tests pin down the three cheap,
 * deterministic guards: the input-size cap, the parse recursion-depth guard, and
 * the fact that neither a `StackOverflowError` nor an `OutOfMemoryError` ever
 * escapes [InterpreterScriptEvaluator.evaluate].
 *
 * A wall-clock-timeout test is intentionally omitted: a reliable timeout
 * assertion would depend on machine speed and be flaky. The timeout *wiring*
 * (single-thread executor + `Future.get(timeout)` + `cancel(true)` +
 * `shutdownNow()` in `finally`) is exercised implicitly by every test here
 * (all inputs run through the executor path). The house rule is "no flaky
 * timing tests", so a real timeout race is not asserted.
 */
class InterpreterResourceBoundsTest :
    FunSpec({

        /** Builds a syntactically-nested chain of builder-call blocks `depth` levels deep. */
        fun nestedSource(depth: Int): String {
            val sb = StringBuilder()
            sb.append("classDiagram(name = \"N\") {\n")
            repeat(depth) { sb.append("classOf(name = \"c\") {\n") }
            repeat(depth) { sb.append("}\n") }
            sb.append("}\n")
            return sb.toString()
        }

        test("oversized input is rejected with a size-mentioning failure — no exception") {
            val limits = InterpreterLimits(maxSourceChars = 200)
            val source = "classDiagram(name = \"" + "A".repeat(500) + "\") { }"

            val result = InterpreterScriptEvaluator.evaluate(source, "test.kuml.kts", limits)

            result.shouldBeInstanceOf<EvaluatedScript.Failure>()
            result.kind shouldBe FailureKind.EVALUATION
            result.message shouldContain "too large"
            result.message shouldContain "200"
        }

        test("input at the exact size limit is not rejected by the size cap") {
            // A valid minimal diagram whose length is <= the limit must pass the cap
            // (it may still succeed or fail later, but never for the size reason).
            val source = "classDiagram(name = \"Ok\") { }"
            val limits = InterpreterLimits(maxSourceChars = source.length)

            val result = InterpreterScriptEvaluator.evaluate(source, "test.kuml.kts", limits)

            if (result is EvaluatedScript.Failure) {
                result.message.contains("too large") shouldBe false
            }
        }

        test("deeply nested input exceeding maxNestingDepth fails as a parse error — never a StackOverflowError") {
            val limits = InterpreterLimits(maxNestingDepth = 8)
            val source = nestedSource(depth = 200)

            // The call itself must not throw StackOverflowError/OutOfMemoryError.
            val result = InterpreterScriptEvaluator.evaluate(source, "test.kuml.kts", limits)

            result.shouldBeInstanceOf<EvaluatedScript.Failure>()
            result.kind shouldBe FailureKind.EVALUATION
            result.message shouldContain "nesting depth"
            result.message shouldContain "8"
        }

        test("nesting just within maxNestingDepth is not rejected by the depth guard") {
            // depth 3 of nested blocks, generous limit → the depth guard must not fire.
            val limits = InterpreterLimits(maxNestingDepth = 64)
            val source = nestedSource(depth = 3)

            val result = InterpreterScriptEvaluator.evaluate(source, "test.kuml.kts", limits)

            // May succeed or fail for semantic reasons, but never for depth.
            if (result is EvaluatedScript.Failure) {
                result.message.contains("nesting depth") shouldBe false
            }
        }

        test("default limits accept a normal happy-path fixture-shaped script") {
            val source =
                """
                classDiagram(name = "Animals") {
                    val animal = classOf(name = "Animal") {
                        isAbstract = true
                        attribute(name = "name", type = "String")
                    }
                    val dog = classOf(name = "Dog") {
                        extends(general = animal)
                    }
                }
                """.trimIndent()

            val result = InterpreterScriptEvaluator.evaluate(source, "animals.kuml.kts", InterpreterLimits.DEFAULT)

            result.shouldBeInstanceOf<EvaluatedScript.Success>()
        }

        test("InterpreterLimits.DEFAULT exposes the documented defaults") {
            InterpreterLimits.DEFAULT.maxSourceChars shouldBe 100_000
            InterpreterLimits.DEFAULT.maxNestingDepth shouldBe 64
            InterpreterLimits.DEFAULT.timeout shouldBe 5.seconds
        }
    })
