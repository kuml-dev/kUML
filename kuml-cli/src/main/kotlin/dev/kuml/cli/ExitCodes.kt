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
}
