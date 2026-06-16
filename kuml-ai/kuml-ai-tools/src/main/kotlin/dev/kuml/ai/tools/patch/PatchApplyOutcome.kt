package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.patch.validation.PatchValidationResult
import kotlinx.serialization.Serializable

/**
 * Sealed envelope returned by [PatchApplyEngine.applyOne].
 *
 * Drives the V3.0.24-UI:
 *  - [Applied]          → green check mark
 *  - [ValidationFailed] → red banner with per-error detail
 *  - [ApplyFailed]      → yellow warning (unexpected internal error)
 */
@Serializable
public sealed interface PatchApplyOutcome {
    public val patchId: String

    /** The patch was validated and applied successfully. */
    @Serializable
    public data class Applied(
        override val patchId: String,
        val validation: PatchValidationResult.Valid,
        val applyResult: PatchApplyResult.Success,
    ) : PatchApplyOutcome

    /** The patch failed one of the validation phases — model was NOT mutated. */
    @Serializable
    public data class ValidationFailed(
        override val patchId: String,
        val validation: PatchValidationResult.Invalid,
    ) : PatchApplyOutcome

    /**
     * Validation passed but the context's applyPatch() threw (e.g. ID conflict
     * race with another tool call). This is defensive — in normal usage the
     * validator's structural checks prevent this.
     */
    @Serializable
    public data class ApplyFailed(
        override val patchId: String,
        val reason: String,
    ) : PatchApplyOutcome
}
