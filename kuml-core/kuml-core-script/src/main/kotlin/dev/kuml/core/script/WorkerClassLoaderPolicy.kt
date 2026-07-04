package dev.kuml.core.script

/**
 * Resolves whether the sandbox **worker child process** should install the
 * [AllowlistClassLoader] as the base classloader for script evaluation (Welle 7,
 * layer B of the MCP-Sandbox architecture).
 *
 * Controlled by the environment variable [ENV_VAR]
 * (`KUML_MCP_SANDBOX_CLASSLOADER`):
 *
 * | Value       | Behaviour                                                        |
 * |-------------|------------------------------------------------------------------|
 * | `enforced`  | **Default (in the worker).** Evaluate the script with an          |
 * |             | [AllowlistClassLoader] as base loader — deny non-allowlisted      |
 * |             | packages (`java.net.*`, `ProcessBuilder`, `java.lang.reflect.*`,  |
 * |             | `sun.*`, …) before the script body ever links them.              |
 * | `disabled`  | Evaluate with the ordinary (full) classloader — an explicit       |
 * |             | escape hatch for debugging a suspected false-positive block.      |
 * | (unset)     | Same as `enforced` **in the worker path**.                        |
 *
 * ## Scope — worker only, never in-process
 *
 * This policy is read **only** by [ScriptWorkerMain] (the sandbox child JVM). The
 * in-process trusted path ([InProcessScriptEvaluator]) never constructs an
 * allowlist loader and never consults this flag — that path is the deliberate
 * unguarded fallback for operators who trust their own CLI script. Keeping the
 * flag confined here guarantees the allowlist cannot leak into the trusted path.
 *
 * V0.23.3 — Welle 7.
 */
internal object WorkerClassLoaderPolicy {
    const val ENV_VAR: String = "KUML_MCP_SANDBOX_CLASSLOADER"
    const val MODE_ENFORCED: String = "enforced"
    const val MODE_DISABLED: String = "disabled"

    /** True if the worker should enforce the allowlist (secure default). */
    fun enforcedFromEnv(): Boolean = enforcedFor(System.getenv(ENV_VAR))

    /** Testable resolver: default-enforced, only `disabled` opts out. */
    fun enforcedFor(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            MODE_DISABLED -> false
            else -> true // enforced, unset, or anything unrecognised → fail safe (enforce)
        }

    /**
     * The base classloader for script evaluation in the worker, or null when the
     * allowlist is disabled. When enforced, wraps the current thread's context
     * classloader (which carries the full worker classpath) in an
     * [AllowlistClassLoader] so only allowlisted packages are visible to the
     * compiled script.
     */
    fun evaluationClassLoader(enforced: Boolean): ClassLoader? =
        if (!enforced) {
            null
        } else {
            val parent =
                Thread.currentThread().contextClassLoader
                    ?: WorkerClassLoaderPolicy::class.java.classLoader
            AllowlistClassLoader(parent)
        }
}
