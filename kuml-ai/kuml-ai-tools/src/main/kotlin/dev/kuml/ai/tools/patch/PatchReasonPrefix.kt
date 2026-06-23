package dev.kuml.ai.tools.patch

/**
 * Shared string prefixes used in [AiTraceEntry.Rejected] reason fields to carry
 * structured rejection context across the patch pipeline.
 *
 * Both [PatchApplyEngine] (producer) and [CompositeAiTraceSink] (consumer) reference
 * these constants so that a rename in one location produces a compile-time failure
 * in the other, eliminating the silent-degradation risk of independent magic literals.
 */
public object PatchReasonPrefix {
    /** Prefix for patches rejected because the caller does not own the patch. */
    public const val OWNERSHIP_MISMATCH: String = "ownership-mismatch"

    /** Prefix for patches rejected due to a concurrent-edit conflict in the store. */
    public const val CONFLICT: String = "conflict"

    /** Prefix for patches rejected because pre-apply validation failed. */
    public const val VALIDATION: String = "validation"
}
