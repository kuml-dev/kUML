package dev.kuml.core.script.interpreter

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.FailureKind
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.ScriptEvaluator
import dev.kuml.core.script.ScriptSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A [ScriptEvaluator] that **interprets** the kUML DSL instead of compiling and
 * running it (Welle 9, Option D of the MCP-Sandbox architecture).
 *
 * ## What makes this different from every other evaluator
 *
 * [dev.kuml.core.script.InProcessScriptEvaluator],
 * [dev.kuml.core.script.ChildProcessScriptEvaluator], and the pooled variant all
 * ultimately funnel the untrusted script through the **embedded Kotlin compiler**
 * (`KumlScriptHost.eval`) — Wellen 1-8 built containment layers *around* that
 * compiler (denylist, child-process, OS cage, classloader allowlist). This
 * evaluator removes the compiler from the loop entirely: it lexes → parses →
 * interprets the DSL against a **finite allowlist of builder productions**
 * ([DslInterpreter]). No bytecode from the script is ever produced or executed,
 * so remote code execution is *structurally impossible* on this path — not merely
 * filtered. `Runtime::class.java...` is not blocked; it simply is not a sentence
 * this grammar can express.
 *
 * ## Resource bounds (DoS guards)
 *
 * RCE is structurally impossible here, but a *pathological* input could still
 * hurt the host: a huge source string, or a deeply nested call that overflows
 * the parser's recursion stack with an **uncaught** `StackOverflowError`. This
 * evaluator therefore applies [InterpreterLimits] before and during evaluation:
 *
 *  1. **Input-size cap** — reject `source.length > maxSourceChars` up front,
 *     before any token list is allocated (returns a [FailureKind.EVALUATION]
 *     failure, never an exception).
 *  2. **Parse recursion-depth guard** — [DslParser] rejects input nesting past
 *     `maxNestingDepth` with a [DslParseException] *before* the JVM stack
 *     overflows.
 *  3. **Defensive fatal catch** — [StackOverflowError] is caught and surfaced as
 *     a failure so it can never propagate out of [evaluate]. `OutOfMemoryError`
 *     is deliberately **not** broadly caught (catching OOM is unsafe — the JVM
 *     may be in an unrecoverable state); the size cap is the real protection
 *     against memory exhaustion.
 *  4. **Wall-clock timeout** — parse + interpret run on a single-thread executor
 *     bounded by `timeout`; a [TimeoutException] yields a [FailureKind.TIMEOUT]
 *     failure and the worker is cancelled. The executor is always shut down.
 *
 * ## Honest scope (Welle 9 initial slice)
 *
 * This is an **experimental, opt-in** evaluation strategy, **not** the production
 * default. It covers **UML class diagrams only** — the most-used and best-
 * documented diagram type — and a pragmatic Kotlin subset (see [DslInterpreter]
 * and [DslParser] for exact limits). The production default remains the
 * fail-closed compiler path with all of Wellen 1-8's layers. An unsupported
 * construct (other diagram type, loop, arbitrary method call, string
 * interpolation) yields a clear [FailureKind.EVALUATION] error telling the caller
 * to fall back to `--eval-strategy=compiler`, never a crash or a silent fallback.
 *
 * The [KumlScriptGuard] denylist is still run first, purely for message
 * consistency with the other evaluators — but on this path it is redundant
 * defence, because the grammar already rejects everything the guard would.
 *
 * V0.23.3 — Welle 9. Resource bounds — V0.27.x.
 */
public object InterpreterScriptEvaluator : ScriptEvaluator {
    /**
     * [ScriptEvaluator] entry point — applies [InterpreterLimits.DEFAULT].
     *
     * Backward-compatible: identical signature to the original evaluator.
     */
    override fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript = evaluate(source, fileName, InterpreterLimits.DEFAULT)

    /**
     * Overload that lets callers (and tests) pass explicit resource [limits].
     */
    public fun evaluate(
        source: String,
        fileName: String,
        limits: InterpreterLimits,
    ): EvaluatedScript {
        // Guard 1 — input-size cap. Rejected before lexing/parsing so an oversized
        // input never even allocates a token list.
        if (source.length > limits.maxSourceChars) {
            return EvaluatedScript.Failure(
                FailureKind.EVALUATION,
                "Interpreter input too large: ${source.length} characters exceeds the " +
                    "limit of ${limits.maxSourceChars}.",
            )
        }

        // Layer 1 (kept for consistency; redundant here — see class KDoc).
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(
                FailureKind.GUARD,
                e.message ?: "kUML script rejected by security guard.",
            )
        }

        // Guard 4 — wall-clock timeout. Parse + interpret run on a single-thread
        // executor; the executor is always shut down in the finally block so no
        // thread leaks even on timeout.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future =
                executor.submit(
                    Callable { evaluateBounded(source, limits) },
                )
            return try {
                future.get(limits.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                EvaluatedScript.Failure(
                    FailureKind.TIMEOUT,
                    "Interpreter exceeded the time budget of ${limits.timeout}.",
                )
            } catch (e: ExecutionException) {
                // evaluateBounded is written not to throw for ordinary errors, but
                // a StackOverflowError from the parser (or any unexpected Throwable)
                // arrives wrapped here. Surface it as a failure — never propagate.
                when (val cause = e.cause) {
                    is StackOverflowError ->
                        EvaluatedScript.Failure(
                            FailureKind.EVALUATION,
                            "Interpreter aborted: input nesting overflowed the parser stack.",
                        )
                    else ->
                        EvaluatedScript.Failure(
                            FailureKind.EVALUATION,
                            "Interpreter failed unexpectedly: ${cause?.message ?: e.message ?: "unknown error"}",
                        )
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Runs the parse + interpret pipeline with all *ordinary* errors already
     * mapped to [EvaluatedScript.Failure]. Also defensively catches
     * [StackOverflowError] here (in addition to the depth guard in [DslParser])
     * so a stack overflow becomes a failure rather than an escaping error even in
     * the rare case the depth guard does not fire first.
     *
     * `OutOfMemoryError` is intentionally not caught — see class KDoc.
     */
    private fun evaluateBounded(
        source: String,
        limits: InterpreterLimits,
    ): EvaluatedScript {
        val script =
            try {
                DslParser.parse(source, limits.maxNestingDepth)
            } catch (e: DslParseException) {
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Interpreter parse error (line ${e.line}): ${e.message}",
                )
            } catch (e: DslLexException) {
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Interpreter lex error (line ${e.line}): ${e.message}",
                )
            } catch (e: StackOverflowError) {
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Interpreter aborted: input nesting overflowed the parser stack.",
                )
            }

        val diagram =
            try {
                DslInterpreter.interpret(script)
            } catch (e: DslInterpretException) {
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Interpreter error (line ${e.line}): ${e.message}",
                )
            } catch (e: IllegalArgumentException) {
                // Real builders throw IllegalArgumentException for invalid models
                // (e.g. a behavioural element rejected by ClassDiagramBuilder).
                return EvaluatedScript.Failure(
                    FailureKind.EVALUATION,
                    "Interpreter rejected the model: ${e.message}",
                )
            }

        return EvaluatedScript.Success(ExtractedDiagram.Uml(diagram))
    }
}
