package dev.kuml.cli

/** Symbolic exit codes for the kUML CLI. */
internal object ExitCodes {
    /** Script compilation or model evaluation failed. */
    internal const val SCRIPT_ERROR: Int = 2

    /** An I/O error occurred while reading the input or writing the output. */
    internal const val IO_ERROR: Int = 3

    /** One or more OCL constraint violations were found. */
    internal const val VALIDATION_VIOLATIONS: Int = 4

    /** `--check` mode found files that need formatting. */
    internal const val FMT_CHECK_FAILED: Int = 5

    /** `kuml simulate --expected` produced a trace that differs from the goldfile. */
    internal const val TRACE_DIFF: Int = 6

    // ── `kuml update` (V2.0.1) ────────────────────────────────────────────────
    //
    // Distinct codes per outcome so shell/CI scripts can react:
    //   `kuml update check && echo "up to date" || handle-update $?`
    //
    // We pick 10/11 (instead of 6/7) to leave room for future per-subcommand
    // diagnostic codes without colliding with anything in the 0–9 range.

    /** `kuml update check` found a newer **stable** release on GitHub. */
    internal const val UPDATE_AVAILABLE: Int = 10

    /** `kuml update check` found a newer **pre-release** (with no newer stable). */
    internal const val PRERELEASE_AVAILABLE: Int = 11

    /**
     * `kuml update check` could not reach GitHub (DNS, timeout, non-2xx) and
     * had no usable cached entry to fall back to. Distinct from `SCRIPT_ERROR`
     * because callers may want to suppress this in CI without masking real
     * script failures.
     */
    internal const val ONLINE_ERROR: Int = 1

    // ── `kuml trace` (V2.0.39) ──────────────────────────────────────────────────

    /** `kuml trace replay` produced a replayed trace that differs from the original. */
    internal const val TRACE_REPLAY_MISMATCH: Int = 7

    /** `kuml trace replay` was given an Activity-flavoured trace (not supported). */
    internal const val TRACE_UNSUPPORTED_FLAVOUR: Int = 8

    // ── `kuml run` (Welle D) ─────────────────────────────────────────────────

    /** `kuml run --adapter mcp` could not bind the requested port. */
    internal const val RUN_PORT_BUSY: Int = 20

    /** `kuml run --restore` was rejected by the MigrationPolicy. */
    internal const val RUN_MIGRATION_REJECTED: Int = 21

    // ── `kuml sandbox` (V2.0.40) ─────────────────────────────────────────────

    /** `kuml sandbox validate` found one or more sandbox policy violations. */
    internal const val SANDBOX_VIOLATIONS: Int = 12

    /** A guard evaluation timed out during sandboxed simulate. */
    internal const val SANDBOX_TIMEOUT: Int = 13
}
