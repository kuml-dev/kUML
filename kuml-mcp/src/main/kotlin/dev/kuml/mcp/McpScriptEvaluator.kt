package dev.kuml.mcp

import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.core.script.ScriptEvaluator
import dev.kuml.core.script.ScriptEvaluators
import dev.kuml.core.script.ScriptSecurityException

/**
 * The single [ScriptEvaluator] shared by every script-tracking MCP tool.
 *
 * Wave 2 of the MCP-Sandbox architecture routes **all six** untrusted script
 * entry points — the five authoring tools (render / validate / list / describe
 * / generate) **and** the `kuml.run.*` runtime session manager — through this
 * one evaluator instead of calling `KumlScriptHost.eval` directly. That is the
 * whole point of the abstraction: a single, swappable, sandboxed seam.
 *
 * The concrete implementation (child-process sandbox vs. in-process) is chosen
 * once at startup from `KUML_MCP_SANDBOX_MODE` (secure default: child-process).
 *
 * V0.23.3.
 */
internal object McpScriptEvaluator {
    /** Resolved once at class-load from the environment. */
    private val evaluator: ScriptEvaluator = ScriptEvaluators.forCurrentConfig()

    /**
     * Evaluates [script] and returns the extracted diagram, translating an
     * [EvaluatedScript.Failure] into the exceptions the MCP layer already
     * knows how to surface:
     *
     *  - GUARD    → [ScriptSecurityException]
     *  - anything else → [ScriptEvaluationException] (with the sanitised message)
     */
    internal fun extract(
        script: String,
        fileName: String = "script.kuml.kts",
    ): ExtractedDiagram =
        when (val result = evaluator.evaluate(script, fileName)) {
            is EvaluatedScript.Success -> result.diagram
            is EvaluatedScript.Failure ->
                when (result.kind) {
                    dev.kuml.core.script.FailureKind.GUARD ->
                        throw ScriptSecurityException(result.message)
                    else ->
                        throw ScriptEvaluationException(result.message)
                }
        }

    /**
     * Like [extract] but returns the raw [EvaluatedScript] so callers that must
     * distinguish failure kinds (e.g. the runtime session manager, which
     * returns a structured `SessionResult.Error` rather than throwing) can do so.
     */
    internal fun evaluate(
        script: String,
        fileName: String = "script.kuml.kts",
    ): EvaluatedScript = evaluator.evaluate(script, fileName)
}
