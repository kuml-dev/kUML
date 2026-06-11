package dev.kuml.runtime.sandbox

/**
 * Sealed hierarchy of sandbox policy violations.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public sealed class SandboxException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** A function call references a name not in [SandboxPolicy.allowedFunctions]. */
    public class DisallowedFunction(
        public val name: String,
    ) : SandboxException("Function '$name' is not in the sandbox allowlist")

    /** The instance would exceed [SandboxPolicy.maxVariableCount]. */
    public class VariableLimitExceeded(
        public val limit: Int,
    ) : SandboxException("Variable count limit ($limit) exceeded")

    /** A string value at [key] would exceed [SandboxPolicy.maxStringLength]. */
    public class StringLengthExceeded(
        public val key: String,
        public val limit: Int,
    ) : SandboxException("String value for key '$key' exceeds length limit ($limit)")

    /** More effects than [SandboxPolicy.maxEffectsPerAction] in a single action body. */
    public class TooManyEffects(
        public val limit: Int,
    ) : SandboxException("Effect count per action exceeds limit ($limit)")

    /** Expression nesting depth exceeds [SandboxPolicy.maxExpressionDepth]. */
    public class ExpressionTooDeep(
        public val limit: Int,
    ) : SandboxException("Expression depth exceeds limit ($limit)")

    /** The action body could not be parsed as a valid effect sequence. */
    public class ParseFailure(
        cause: Throwable,
    ) : SandboxException("Effect parse failure: ${cause.message}", cause)

    /** An assignment targets a reserved variable name. */
    public class ReservedVariableName(
        public val name: String,
    ) : SandboxException("Cannot assign to reserved variable name '$name'")
}
