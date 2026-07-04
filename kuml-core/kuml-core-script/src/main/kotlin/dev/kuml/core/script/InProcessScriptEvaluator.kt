package dev.kuml.core.script

/**
 * [ScriptEvaluator] that evaluates scripts **in the current JVM** via
 * [KumlScriptHost] — the historical behaviour before the child-process
 * sandbox (Welle 2) was introduced.
 *
 * This provides **no process isolation**: a hostile script that slips past
 * [KumlScriptGuard] runs with the server's full privileges, and an infinite
 * loop or OOM takes down the server. It exists as an explicit, opt-in fallback
 * (`KUML_MCP_SANDBOX_MODE=in-process`) and for environments where launching a
 * child JVM is impossible. The secure default is [ChildProcessScriptEvaluator].
 *
 * V0.23.3.
 */
internal object InProcessScriptEvaluator : ScriptEvaluator {
    override fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript = ScriptEvaluationCore.evaluateAndExtract(source, fileName)
}
