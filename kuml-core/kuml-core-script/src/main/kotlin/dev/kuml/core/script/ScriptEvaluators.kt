package dev.kuml.core.script

/**
 * Selects the [ScriptEvaluator] for the MCP server based on the
 * `KUML_MCP_SANDBOX_MODE` environment variable.
 *
 * | Value            | Behaviour                                                          |
 * |------------------|--------------------------------------------------------------------|
 * | `pool`           | **Default.** Warm-worker pool (Welle 3): each script runs in an     |
 * |                  | isolated, pre-started child JVM — interactive latency, use-once.    |
 * | `child-process`  | Cold-start child JVM per call (Welle 2): isolated but ~1.5 s each.  |
 * | `in-process`     | Evaluate in the server JVM (no isolation — explicit opt-out).       |
 * | (unset / other)  | Same as `pool`.                                                     |
 *
 * The warm-pool sizing is tuned by `KUML_MCP_SANDBOX_POOL_SIZE` (idle warm
 * workers held, default 3) and `KUML_MCP_SANDBOX_MAX_WORKERS` (hard ceiling on
 * concurrently-live worker JVMs — the fork-bomb guard, default 2× pool size).
 *
 * ## OS-native isolation (Welle 4)
 *
 * On top of the process/heap/timeout containment, worker child processes are
 * additionally launched inside an OS-enforced cage: `sandbox-exec` with a strict
 * seatbelt profile on **macOS** (deny network, writes confined to a per-worker
 * temp dir, reads of the top secret stores denied). This is what stops a *full
 * RCE* — even a script that defeats the regex denylist cannot exfiltrate files
 * or open a network socket. `KUML_MCP_SANDBOX_OS_ISOLATION` controls strictness:
 * `required` (default on macOS — fail closed if the cage cannot be applied) or
 * `best-effort` (default on platforms where OS isolation is not yet implemented,
 * so they keep running with process/heap/timeout containment). See [OsSandbox].
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
    public const val MODE_POOL: String = "pool"

    /** Resolves the evaluator from the environment (secure default: warm pool). */
    public fun forCurrentConfig(): ScriptEvaluator = forMode(System.getenv(ENV_VAR))

    /**
     * Resolves the evaluator for an explicit [mode] string (testable).
     *
     * If a [PooledScriptEvaluator] is created it is registered with a JVM
     * shutdown hook so its worker processes are terminated even on abrupt exit —
     * belt-and-braces on top of the MCP server's own shutdown path.
     */
    public fun forMode(mode: String?): ScriptEvaluator =
        when (mode?.trim()?.lowercase()) {
            MODE_IN_PROCESS -> InProcessScriptEvaluator
            MODE_CHILD_PROCESS -> ChildProcessScriptEvaluator()
            // Default and explicit "pool" both use the warm-worker pool.
            else -> PooledScriptEvaluator().also { registerShutdownHook(it) }
        }

    private fun registerShutdownHook(closeable: AutoCloseable) {
        Runtime.getRuntime().addShutdownHook(
            Thread({ runCatching { closeable.close() } }, "kuml-worker-pool-shutdown"),
        )
    }
}
