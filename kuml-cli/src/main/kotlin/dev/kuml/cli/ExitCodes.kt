package dev.kuml.cli

/** Symbolic exit codes for the kUML CLI. */
internal object ExitCodes {
    /** Missing or invalid CLI arguments (mirrors the POSIX / Clikt convention). */
    internal const val USAGE: Int = 2

    /** Script compilation or model evaluation failed. */
    internal const val SCRIPT_ERROR: Int = 3

    /** An I/O error occurred while reading the input or writing the output. */
    internal const val IO_ERROR: Int = 4

    /** One or more OCL constraint violations were found. */
    internal const val VALIDATION_VIOLATIONS: Int = 5

    /** `--check` mode found files that need formatting. */
    internal const val FMT_CHECK_FAILED: Int = 6

    /** `kuml simulate --expected` produced a trace that differs from the goldfile. */
    internal const val TRACE_DIFF: Int = 7

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
    internal const val TRACE_REPLAY_MISMATCH: Int = 8

    /** `kuml trace replay` was given an Activity-flavoured trace (not supported). */
    internal const val TRACE_UNSUPPORTED_FLAVOUR: Int = 9

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

    // ── `kuml reverse` (V3.0.9) ──────────────────────────────────────────────

    /** `kuml reverse --lang <id>` referenced an unknown reverse engine. */
    internal const val REVERSE_ENGINE_NOT_FOUND: Int = 14

    /** `kuml reverse` engine returned a ReverseResult.Failure with ERROR diagnostics. */
    internal const val REVERSE_ANALYSIS_FAILED: Int = 15

    /** `kuml reverse` could not detect any source files in the given directory. */
    internal const val REVERSE_NO_SOURCES: Int = 16

    // ── `kuml import/export` format availability (V3.0.17) ───────────────────

    /**
     * The requested format requires a distribution that is not available in this build.
     * Example: `--format xmi` requires the Fat-JAR distribution (EMF is JVM-only and
     * not included in the Native Image binary).
     */
    internal const val FORMAT_NOT_AVAILABLE: Int = 24

    // ── `kuml plugin` (V3.0.29) ──────────────────────────────────────────────────

    /** Plugin ID not found in [dev.kuml.plugin.loader.registry.PluginRegistry]. */
    internal const val PLUGIN_NOT_FOUND: Int = 40

    /** Plugin's kumlVersionRange excludes the current kUML runtime version. */
    internal const val PLUGIN_VERSION_INCOMPATIBLE: Int = 41

    /** Plugin attempted an operation it lacks a declared permission for. */
    internal const val PLUGIN_PERMISSION_DENIED: Int = 42

    /** Plugin JAR signature verification failed (V3.0.30). */
    internal const val PLUGIN_SIGNATURE_INVALID: Int = 43

    // ── `kuml chain` (V3.0.4 / V3.0.5) ──────────────────────────────────────

    /** `kuml chain verify` — on-chain modelHash differs from the local model's hash. */
    internal const val CHAIN_HASH_MISMATCH: Int = 50

    /** `kuml chain connect/verify/events` — could not connect to or read from the chain. */
    internal const val CHAIN_CONNECT_ERROR: Int = 51

    /** `kuml chain verify-sig` — EIP-712 model signature failed to verify (or malformed .sig file). */
    internal const val CHAIN_INVALID_SIGNATURE: Int = 52

    /** `kuml chain verify-sig --expected-signer` — signature valid but recovered signer ≠ expected. */
    internal const val CHAIN_SIGNER_MISMATCH: Int = 53
}
