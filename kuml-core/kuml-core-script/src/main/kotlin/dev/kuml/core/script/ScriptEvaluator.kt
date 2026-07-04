package dev.kuml.core.script

/**
 * Abstraction over kUML script evaluation for untrusted channels (the MCP
 * server's authoring tools and the `kuml.run.*` runtime tools).
 *
 * ## Why this interface exists (Welle 2 of the MCP-Sandbox architecture)
 *
 * Historically every MCP tool called [KumlScriptHost.eval] **directly, in the
 * MCP server's own JVM**. Because [KumlScriptHost] compiles scripts with
 * `dependenciesFromCurrentContext(wholeClasspath = true)` and no classloader
 * isolation, a hostile script gains the full privileges of the server process
 * (see the Sandbox architecture note). The regex [KumlScriptGuard] is a cheap
 * first layer but is fundamentally bypassable.
 *
 * This interface inserts a seam so the evaluation can happen **out of process**
 * ([ChildProcessScriptEvaluator]) — a separate JVM with a wall-clock timeout
 * and a heap cap, so that a runaway or malicious script cannot take down the
 * server or exhaust its memory. [InProcessScriptEvaluator] preserves the old
 * behaviour as a fallback.
 *
 * ## Contract
 *
 * Implementations MUST:
 *  1. Run [KumlScriptGuard.validate] on the source **before** any evaluation
 *     (defence-in-depth layer 1 is preserved, not replaced).
 *  2. Evaluate the script and run [DiagramExtractor.extractAny] on the result.
 *  3. Return [EvaluatedScript.Success] with the extracted diagram, or
 *     [EvaluatedScript.Failure] with a **sanitised** message on any error.
 *
 * Implementations MUST NOT throw for ordinary script errors (compile failures,
 * missing diagram, guard rejection) — those are returned as
 * [EvaluatedScript.Failure]. They may throw only for programming errors.
 *
 * V0.23.3.
 */
public interface ScriptEvaluator {
    /**
     * Evaluates a kUML DSL [source] script and extracts its diagram/model.
     *
     * @param source the raw `*.kuml.kts` script text.
     * @param fileName a virtual name used only in diagnostics.
     */
    public fun evaluate(
        source: String,
        fileName: String = "script.kuml.kts",
    ): EvaluatedScript
}

/** Result of a [ScriptEvaluator.evaluate] call. */
public sealed class EvaluatedScript {
    public data class Success(
        val diagram: ExtractedDiagram,
    ) : EvaluatedScript()

    /**
     * A failure. [message] is already sanitised for return to an MCP client —
     * it never contains absolute file paths, stack traces, or classpath
     * internals. [kind] classifies the failure so callers (and tests) can
     * distinguish a guard rejection from a timeout from a compile error.
     */
    public data class Failure(
        val kind: FailureKind,
        val message: String,
    ) : EvaluatedScript()
}

/** Coarse classification of an evaluation failure. */
public enum class FailureKind {
    /** Rejected by [KumlScriptGuard] before evaluation. */
    GUARD,

    /** Compilation or evaluation reported errors, or produced no diagram. */
    EVALUATION,

    /** The child process exceeded the wall-clock timeout and was killed. */
    TIMEOUT,

    /** The sandbox mechanism itself failed (could not start / crashed / bad IPC). */
    SANDBOX,
}
