package dev.kuml.ai.tools.patch.validation

import kotlinx.serialization.Serializable

/**
 * Sealed result envelope returned by [dev.kuml.ai.tools.patch.PatchValidator].
 * The sealed shape keeps the Koog tool-layer schema small and deterministic.
 *
 * V3.0.24 UI consumes this directly: Valid + Applied → green check, Invalid → red banner.
 */
@Serializable
public sealed interface PatchValidationResult {
    @Serializable
    public data class Valid(
        val warnings: List<String> = emptyList(),
    ) : PatchValidationResult

    @Serializable
    public data class Invalid(
        val errors: List<ValidationError>,
        val phase: ValidationPhase,
    ) : PatchValidationResult
}

/**
 * Discrete validation phases — listed in execution order.
 * Also used as the OTLP attribute value for `kuml.ai.patch.phase`.
 */
@Serializable
public enum class ValidationPhase {
    STRUCTURAL,
    SANDBOX,
    TYPE_CHECK,
    RENDER,
}

/** A single structured validation error produced during a patch validation phase. */
@Serializable
public data class ValidationError(
    /** Machine-readable code, e.g. "DUPLICATE_ID", "DANGLING_REFERENCE", "DISALLOWED_FUNCTION". */
    val code: String,
    val message: String,
    /** Optional hint: element id, vertex id, or signature where the error was found. */
    val locationHint: String? = null,
)
