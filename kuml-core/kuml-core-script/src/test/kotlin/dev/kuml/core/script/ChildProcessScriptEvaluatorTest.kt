package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis

/**
 * Child-process-specific behaviour that the in-process evaluator cannot provide:
 * hard timeout on a runaway script, and robustness against a broken/hostile
 * worker launch. These are the concrete security wins of Welle 2.
 *
 * V0.23.3.
 */
class ChildProcessScriptEvaluatorTest :
    FunSpec({

        test("infinite loop is killed by the wall-clock timeout") {
            // This script passes KumlScriptGuard (no forbidden pattern — the
            // denylist has NO loop pattern), so it is the canonical example of a
            // DoS that ONLY the timeout of this wave can stop. In-process this
            // would hang the server forever.
            val infiniteLoop =
                """
                diagram(name = "loop", type = DiagramType.CLASS) {}
                while (true) { }
                """.trimIndent()

            // Sanity: the guard really does not reject it.
            KumlScriptGuard.validate(infiniteLoop) // must not throw

            val evaluator = ChildProcessScriptEvaluator(timeoutSeconds = 3)

            lateinit var result: EvaluatedScript
            val elapsed = measureTimeMillis { result = evaluator.evaluate(infiniteLoop) }

            val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
            failure.kind shouldBe FailureKind.TIMEOUT
            failure.message shouldContain "timed out"
            // Terminated close to the 3 s deadline, well under a 30 s ceiling —
            // proves the parent stays responsive and the child was actually killed.
            elapsed shouldBeLessThan 30_000L
        }

        test("parent stays responsive after a timeout — next evaluation succeeds") {
            // First: a runaway child that will be killed at 3 s.
            val strict = ChildProcessScriptEvaluator(timeoutSeconds = 3)
            strict.evaluate("""diagram(name = "loop", type = DiagramType.CLASS) {}; while (true) {}""")
            // Then: a well-behaved evaluation must succeed — the parent was not
            // damaged by killing the child. Uses a generous timeout so the
            // assertion is about *parent survival*, not about cold-start timing.
            val ok =
                ChildProcessScriptEvaluator(timeoutSeconds = 60)
                    .evaluate("""diagram(name = "ok", type = DiagramType.CLASS) {}""")
            ok.shouldBeInstanceOf<EvaluatedScript.Success>()
        }

        test("a broken worker launch fails closed (SANDBOX), never falls back to in-process") {
            // Point the evaluator at a non-existent java binary. Starting the
            // child must fail, and the result must be a SANDBOX failure — NOT a
            // silent in-process evaluation of the (untrusted) script.
            val evaluator =
                ChildProcessScriptEvaluator(
                    timeoutSeconds = 5,
                    javaBinary = "/nonexistent/java-binary-that-does-not-exist",
                )
            val result = evaluator.evaluate("""diagram(name = "x", type = DiagramType.CLASS) {}""")
            val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
            failure.kind shouldBe FailureKind.SANDBOX
        }

        test("a child that emits non-JSON on stdout yields a SANDBOX failure, not a crash") {
            // /bin/echo prints its args and exits 0 — a stand-in for a worker
            // that writes garbage instead of a WorkerResponse. The parent must
            // classify the unparseable line as SANDBOX and stay alive.
            val echo = listOf("/bin/echo", "/usr/bin/echo").firstOrNull { java.io.File(it).canExecute() }
            if (echo != null) {
                val evaluator = ChildProcessScriptEvaluator(timeoutSeconds = 10, javaBinary = echo)
                val result = evaluator.evaluate("""diagram(name = "x", type = DiagramType.CLASS) {}""")
                val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                failure.kind shouldBe FailureKind.SANDBOX
            }
        }

        test("guard rejection short-circuits before launching a child") {
            // A hostile script is rejected by the parent-side guard without ever
            // spending a JVM launch.
            val evaluator = ChildProcessScriptEvaluator(timeoutSeconds = 30)
            val result =
                evaluator.evaluate("""diagram(name = "x") {}; Runtime.getRuntime().exec("id")""")
            val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
            failure.kind shouldBe FailureKind.GUARD
        }
    })
