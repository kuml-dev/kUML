package dev.kuml.runtime.sandbox

/**
 * Configuration for sandbox execution of kUML action bodies and guards.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public data class SandboxPolicy(
    /** Maximum time (ms) allowed for a single guard evaluation. */
    public val guardTimeoutMs: Long = DEFAULT_GUARD_TIMEOUT_MS,
    /** Maximum number of variables in [dev.kuml.runtime.ModelInstance.variables]. */
    public val maxVariableCount: Int = DEFAULT_MAX_VARIABLE_COUNT,
    /** Maximum length of any String value written to variables. */
    public val maxStringLength: Int = DEFAULT_MAX_STRING_LENGTH,
    /** Set of function names (dotted, e.g. `"log.info"`) allowed in action bodies. */
    public val allowedFunctions: Set<String> = DEFAULT_ALLOWED_FUNCTIONS,
    /** Maximum number of semicolon-separated effects per action body. */
    public val maxEffectsPerAction: Int = DEFAULT_MAX_EFFECTS_PER_ACTION,
    /** Maximum nesting depth of any single expression or effect AST. */
    public val maxExpressionDepth: Int = DEFAULT_MAX_EXPRESSION_DEPTH,
) {
    public companion object {
        public const val DEFAULT_GUARD_TIMEOUT_MS: Long = 100L
        public const val DEFAULT_MAX_VARIABLE_COUNT: Int = 1_024
        public const val DEFAULT_MAX_STRING_LENGTH: Int = 8_192
        public const val DEFAULT_MAX_EFFECTS_PER_ACTION: Int = 32
        public const val DEFAULT_MAX_EXPRESSION_DEPTH: Int = 32

        public val DEFAULT_ALLOWED_FUNCTIONS: Set<String> =
            setOf(
                "log.info",
                "log.warn",
                "log.error",
                "log.debug",
                "math.min",
                "math.max",
                "math.abs",
                "math.floor",
                "math.ceil",
                "math.round",
                "str.length",
                "str.toUpper",
                "str.toLower",
                "str.trim",
                "list.size",
                "list.contains",
                "list.isEmpty",
                "map.size",
                "map.containsKey",
                "map.isEmpty",
                "convert.toInt",
                "convert.toReal",
                "convert.toString",
                "convert.toBool",
            )

        /** Strict policy: short timeout, few variables, no built-ins, minimal limits. */
        public val Strict: SandboxPolicy =
            SandboxPolicy(
                guardTimeoutMs = 50L,
                maxVariableCount = 64,
                maxStringLength = 1_024,
                allowedFunctions = emptySet(),
                maxEffectsPerAction = 8,
                maxExpressionDepth = 16,
            )

        /** Permissive policy: long timeout, many variables, all default built-ins. */
        public val Permissive: SandboxPolicy =
            SandboxPolicy(
                guardTimeoutMs = 1_000L,
                maxVariableCount = 65_536,
                maxStringLength = 1_048_576,
                allowedFunctions = DEFAULT_ALLOWED_FUNCTIONS,
                maxEffectsPerAction = 256,
                maxExpressionDepth = 64,
            )
    }
}
