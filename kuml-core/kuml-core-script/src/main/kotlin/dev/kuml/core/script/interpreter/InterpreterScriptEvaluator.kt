package dev.kuml.core.script.interpreter

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.FailureKind
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.ScriptEvaluator
import dev.kuml.core.script.ScriptSecurityException

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
 * V0.23.3 — Welle 9.
 */
public object InterpreterScriptEvaluator : ScriptEvaluator {
    override fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript {
        // Layer 1 (kept for consistency; redundant here — see class KDoc).
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(
                FailureKind.GUARD,
                e.message ?: "kUML script rejected by security guard.",
            )
        }

        val script =
            try {
                DslParser.parse(source)
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
