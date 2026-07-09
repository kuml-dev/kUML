package dev.kuml.core.script.interpreter

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Resource bounds (DoS guards) for the execution-free DSL interpreter.
 *
 * The interpreter has **no** RCE risk — it never compiles or runs bytecode
 * (see [InterpreterScriptEvaluator]). But without bounds a *pathological* input
 * could still hurt the host process:
 *
 *  - a multi-megabyte source string can exhaust memory during lexing/parsing;
 *  - a deeply nested builder call (`a { b { c { … } } }`) can blow the JVM
 *    stack with an **uncaught** `StackOverflowError` in the recursive-descent
 *    parser;
 *  - a huge (but syntactically flat) input can burn CPU linearly.
 *
 * These limits make the interpreter safe to expose to anonymous / untrusted
 * input. Defaults are generous enough for every real vault example and the
 * happy-path test fixtures, but small enough that a hostile input is rejected
 * cheaply and *without* an exception escaping the evaluator.
 *
 * V0.27.x — interpreter resource bounds.
 *
 * @property maxSourceChars hard cap on the raw source length in characters.
 *   Inputs larger than this are rejected before lexing (no allocation of a
 *   token list for the oversized input). Default 100_000 — larger than any
 *   real single-diagram script by orders of magnitude.
 * @property maxNestingDepth maximum recursion depth of the parser's descent
 *   (nested calls / blocks / argument expressions). Exceeding it throws a
 *   [DslParseException] *before* the JVM stack overflows. Default 64 — far
 *   deeper than any legitimate diagram nests.
 * @property timeout wall-clock budget for the whole parse + interpret step.
 *   Since the grammar has no loops this mainly guards huge linear inputs.
 *   Default 5 seconds.
 */
public data class InterpreterLimits(
    val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
    val maxNestingDepth: Int = DEFAULT_MAX_NESTING_DEPTH,
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(maxSourceChars > 0) { "maxSourceChars must be positive, was $maxSourceChars" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive, was $maxNestingDepth" }
        require(timeout.isPositive()) { "timeout must be positive, was $timeout" }
    }

    public companion object {
        private const val DEFAULT_MAX_SOURCE_CHARS = 100_000
        private const val DEFAULT_MAX_NESTING_DEPTH = 64
        private val DEFAULT_TIMEOUT = 5.seconds

        /** The default, production-safe bounds. */
        public val DEFAULT: InterpreterLimits = InterpreterLimits()
    }
}
