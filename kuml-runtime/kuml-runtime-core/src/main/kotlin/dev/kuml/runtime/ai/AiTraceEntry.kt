// File: kuml-runtime-core/src/main/kotlin/dev/kuml/runtime/ai/AiTraceEntry.kt
// Package is dev.kuml.runtime (same as TraceEntry) because Kotlin sealed classes
// require sub-class declarations to be in the same package.
package dev.kuml.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI-patch lifecycle trace entries (V3.0.25).
 *
 * Each entry carries the AI session id + (where applicable) the patch id, so an
 * OTLP exporter can group spans under a session-root span and child-patch spans.
 *
 * The classes extend [TraceEntry] via additional sub-class declarations in the
 * runtime-core module; see [TraceEntry] for the sealed contract.
 *
 * Serialization: all sub-types use [SerialName] values that match the OTLP span
 * names defined in the V3.0.25 trace schema. External OTLP consumers (e.g.
 * Langfuse hook in V3.0.26) rely on these strings — do not rename.
 *
 * Sub-types are serializable via the [TraceEntry] polymorphic codec in
 * [KumlRuntimeJson] (classDiscriminator = "type").
 */
@Serializable
public sealed class AiTraceEntry : TraceEntry() {
    public abstract val sessionId: String

    /**
     * Emitted when a [dev.kuml.ai.tools.patch.PatchApplyEngine] is created.
     * Carries [baseModelFingerprint] so observers can tie the session to a
     * specific model version.
     */
    @Serializable
    @SerialName("ai.session.started")
    public data class SessionStarted(
        override val seqNo: Long,
        override val timestamp: String,
        override val sessionId: String,
        /** SHA-256 hex of the working model at session start. */
        public val baseModelFingerprint: String,
    ) : AiTraceEntry()

    /**
     * Emitted after every PatchValidator.validate() call.
     * [phase] is "OK" when validation passed all phases, otherwise it names
     * the first failing phase (STRUCTURAL | SANDBOX | TYPE_CHECK | RENDER).
     */
    @Serializable
    @SerialName("ai.patch.validated")
    public data class Validated(
        override val seqNo: Long,
        override val timestamp: String,
        override val sessionId: String,
        public val patchId: String,
        /** e.g. "uml.class", matches ModelPatch sub-type. */
        public val patchKind: String,
        /** "STRUCTURAL" | "SANDBOX" | "TYPE_CHECK" | "RENDER" | "OK" */
        public val phase: String,
        public val errorCount: Int,
    ) : AiTraceEntry()

    /**
     * Emitted when a patch has been successfully validated AND applied to the
     * AgentEditingContext working model.
     */
    @Serializable
    @SerialName("ai.patch.applied")
    public data class Applied(
        override val seqNo: Long,
        override val timestamp: String,
        override val sessionId: String,
        public val patchId: String,
        public val patchKind: String,
        /** The primary element id affected by this patch. */
        public val elementId: String,
    ) : AiTraceEntry()

    /**
     * Emitted when a patch is explicitly rejected (either via rejectOne or
     * as part of rejectAll). Does NOT imply a model mutation.
     */
    @Serializable
    @SerialName("ai.patch.rejected")
    public data class Rejected(
        override val seqNo: Long,
        override val timestamp: String,
        override val sessionId: String,
        public val patchId: String,
        public val reason: String?,
    ) : AiTraceEntry()

    /**
     * Emitted when the entire session is aborted via rejectAll(). The working
     * model has been restored to the pre-session snapshot at this point.
     */
    @Serializable
    @SerialName("ai.session.aborted")
    public data class SessionAborted(
        override val seqNo: Long,
        override val timestamp: String,
        override val sessionId: String,
        public val rejectedPatchIds: List<String>,
        public val reason: String?,
    ) : AiTraceEntry()
}
