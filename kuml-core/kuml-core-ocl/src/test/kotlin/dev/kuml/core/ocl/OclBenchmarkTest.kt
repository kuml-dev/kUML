package dev.kuml.core.ocl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import kotlin.time.measureTime

/**
 * Evaluation-time benchmark for representative OCL constraints (V3.2.24).
 *
 * This is **not** a micro-benchmark harness (no JMH dependency exists in this
 * repo — see the V3.2.24 wave spec's "KMP-Benchmark" Stolperfalle: primary
 * measurement target is the JVM, `js`/`wasmJs` perf is secondary and this
 * module is JVM-only besides). It is a coarse **regression anchor**: each
 * test warms up the JIT with repeated evaluations, times a further batch, and
 * asserts a generous upper bound so a future accidental O(n²) regression in
 * [OclEvaluator]/[OclParser] fails the build instead of silently landing.
 * The bounds are intentionally loose (10x+ headroom over locally observed
 * timings) to avoid CI flakiness on shared/slower runners.
 *
 * Results are also printed to stdout so a human can track the trend across
 * releases by scanning CI logs (no persisted baseline file — see `kuml-llm/
 * kuml-llm-bench` for a project example of a persisted-baseline benchmark
 * report, which is out of scope for this coarse anchor).
 */
class OclBenchmarkTest :
    FunSpec({

        fun classWithAttributes(count: Int): UmlClass =
            UmlClass(
                id = "Bench",
                name = "Bench",
                attributes =
                    (1..count).map { i ->
                        UmlProperty(id = "Bench::attr$i", name = "attr$i", type = UmlTypeRef("String"), isStatic = i % 3 == 0)
                    },
            )

        fun evalTimed(
            self: Any,
            expr: String,
            iterations: Int,
        ): kotlin.time.Duration {
            val tokens = OclLexer.tokenize(expr)
            val ast = OclParser(tokens).parse()
            val evaluator = OclEvaluator(self)
            // Warm-up: let the JIT compile the hot evaluator/parser paths before measuring.
            repeat(iterations / 4) { evaluator.eval(ast) }
            return measureTime {
                repeat(iterations) { evaluator.eval(ast) }
            }
        }

        test("small collection (10 elements): forAll evaluates well within budget") {
            val self = classWithAttributes(10)
            val duration = evalTimed(self, "self.attributes->forAll(a | a.name.size() > 0)", iterations = 2000)
            println("[OCL benchmark] forAll/10 elements x2000 evaluations: $duration")
            duration.inWholeMilliseconds shouldBeLessThan 2000L
        }

        test("large collection (10_000 elements): forAll evaluates within budget (regression anchor)") {
            val self = classWithAttributes(10_000)
            val duration = evalTimed(self, "self.attributes->forAll(a | a.name.size() > 0)", iterations = 50)
            println("[OCL benchmark] forAll/10_000 elements x50 evaluations: $duration")
            duration.inWholeMilliseconds shouldBeLessThan 5000L
        }

        test("large collection (10_000 elements): select+collect+size pipeline within budget") {
            val self = classWithAttributes(10_000)
            val duration =
                evalTimed(
                    self,
                    "self.attributes->select(a | a.isStatic)->collect(a | a.name.toUpper())->size()",
                    iterations = 50,
                )
            println("[OCL benchmark] select+collect+size/10_000 elements x50 evaluations: $duration")
            duration.inWholeMilliseconds shouldBeLessThan 5000L
        }

        test("String standard-library ops (substring/indexOf/concat) — 100k evaluations within budget") {
            val self = classWithAttributes(1)
            val duration =
                evalTimed(
                    self,
                    "'the quick brown fox jumps over the lazy dog'.substring(5, 9).concat('!').indexOf('quick')",
                    iterations = 100_000,
                )
            println("[OCL benchmark] String stdlib ops x100_000 evaluations: $duration")
            duration.inWholeMilliseconds shouldBeLessThan 5000L
        }
    })
