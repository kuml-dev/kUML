package dev.kuml.core.script

/**
 * Selects the [ScriptEvaluator] for the MCP server based on the
 * `KUML_MCP_SANDBOX_MODE` environment variable.
 *
 * | Value            | Behaviour                                                        |
 * |------------------|------------------------------------------------------------------|
 * | `child-process`  | **Default.** Evaluate each script in an isolated child JVM.      |
 * | `in-process`     | Evaluate in the server JVM (no isolation — explicit opt-out).    |
 * | (unset / other)  | Same as `child-process`.                                          |
 *
 * ## Fail-closed default (design decision, Welle 2 point 7)
 *
 * The default is `child-process`, and the [ChildProcessScriptEvaluator] is
 * **fail-closed**: if a child JVM cannot be started, it returns a SANDBOX
 * failure rather than silently re-running the script in-process. The reasoning:
 *
 *  - The whole point of the sandbox is that untrusted script may be hostile.
 *    "The sandbox is unavailable, so run the untrusted code unsandboxed" is the
 *    worst possible reaction to a sandbox failure — it converts a availability
 *    problem into a full-privilege RCE. A security control that disables itself
 *    on error is not a security control.
 *  - The switch to in-process is therefore **operator-explicit**: an operator
 *    who genuinely cannot run child JVMs (locked-down container, no `java` on
 *    the launch path) sets `KUML_MCP_SANDBOX_MODE=in-process` **knowingly**,
 *    accepting the reduced protection. The server never makes that trade
 *    silently on their behalf.
 *
 * This keeps the failure mode legible: with the default, a broken sandbox
 * surfaces as clear per-call SANDBOX errors (visible, debuggable) rather than
 * as an invisible downgrade to "no protection".
 *
 * V0.23.3.
 */
public object ScriptEvaluators {
    public const val ENV_VAR: String = "KUML_MCP_SANDBOX_MODE"
    public const val MODE_IN_PROCESS: String = "in-process"
    public const val MODE_CHILD_PROCESS: String = "child-process"

    /** Resolves the evaluator from the environment (secure default: child-process). */
    public fun forCurrentConfig(): ScriptEvaluator = forMode(System.getenv(ENV_VAR))

    /** Resolves the evaluator for an explicit [mode] string (testable). */
    public fun forMode(mode: String?): ScriptEvaluator =
        when (mode?.trim()?.lowercase()) {
            MODE_IN_PROCESS -> InProcessScriptEvaluator
            // Default and explicit child-process both use the sandbox.
            else -> ChildProcessScriptEvaluator()
        }
}
