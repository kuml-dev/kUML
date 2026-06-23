package dev.kuml.ai.tools.patch.compliance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Compliance-log event emitted by [FileComplianceSink] on every meaningful AI-patch
 * lifecycle transition.
 *
 * ## Privacy guarantee
 * No field in any sub-type may carry raw prompt text, model responses, or free-form
 * user content. All identifiers are opaque IDs (ULIDs), timestamps, or controlled
 * vocabulary strings. The [PatchRejected.reasonCode] field uses a fixed set of
 * machine-readable codes — never the `reason: String?` from [AiTraceEntry.Rejected]
 * which may contain LLM-derived text.
 *
 * The JSON discriminator key is `"type"` (kotlinx default for sealed interfaces).
 * The [ComplianceJson] singleton encodes/decodes this hierarchy.
 */
@Serializable
public sealed interface ComplianceEvent {
    /** Stable ULID identifying the engine session. */
    public val sessionId: String

    /** Identifier of the user/agent owning the session (e.g. OS username). */
    public val ownerId: String

    /** ISO-8601 UTC instant. */
    public val timestamp: String

    /** Emitted when a [dev.kuml.ai.tools.patch.PatchApplyEngine] session begins. */
    @Serializable
    @SerialName("session.opened")
    public data class SessionOpened(
        override val sessionId: String,
        override val ownerId: String,
        override val timestamp: String,
        /** LLM provider id (e.g. "anthropic", "openai"). */
        val providerId: String,
        /** Model id string (e.g. "claude-sonnet-4-6"). */
        val modelId: String,
        /** SHA-256 hex fingerprint of the model at session start (no content). */
        val baseFingerprint: String,
    ) : ComplianceEvent

    /** Emitted when a patch is successfully validated and applied to the working model. */
    @Serializable
    @SerialName("patch.applied")
    public data class PatchApplied(
        override val sessionId: String,
        override val ownerId: String,
        override val timestamp: String,
        /** ULID of the patch. */
        val patchId: String,
        /** Patch kind string (e.g. "uml.class", "rename"). */
        val patchKind: String,
        /** Primary element id touched by this patch. */
        val elementId: String,
    ) : ComplianceEvent

    /** Emitted when a patch is rejected for any reason. */
    @Serializable
    @SerialName("patch.rejected")
    public data class PatchRejected(
        override val sessionId: String,
        override val ownerId: String,
        override val timestamp: String,
        /** ULID of the patch. */
        val patchId: String,
        /** Patch kind string. */
        val patchKind: String,
        /**
         * Machine-readable rejection code. Controlled vocabulary:
         * - `OWNERSHIP_MISMATCH` — patch owner != session owner
         * - `CONFLICT` — same element touched within the 5-second conflict window
         * - `VALIDATION_FAILED` — structural/sandbox/type-check/render validation failed
         * - `USER_REJECTED` — explicit user/agent rejectOne call
         * - `SESSION_ABORTED` — session-level rejectAll call
         *
         * Never contains free-form text or LLM-generated content.
         */
        val reasonCode: String,
    ) : ComplianceEvent

    /** Emitted when a session ends (either completed or aborted). */
    @Serializable
    @SerialName("session.closed")
    public data class SessionClosed(
        override val sessionId: String,
        override val ownerId: String,
        override val timestamp: String,
        val appliedCount: Int,
        val rejectedCount: Int,
    ) : ComplianceEvent
}

/** Controlled-vocabulary reason codes for [ComplianceEvent.PatchRejected.reasonCode]. */
public object ReasonCode {
    public const val OWNERSHIP_MISMATCH: String = "OWNERSHIP_MISMATCH"
    public const val CONFLICT: String = "CONFLICT"
    public const val VALIDATION_FAILED: String = "VALIDATION_FAILED"
    public const val USER_REJECTED: String = "USER_REJECTED"
    public const val SESSION_ABORTED: String = "SESSION_ABORTED"
}
