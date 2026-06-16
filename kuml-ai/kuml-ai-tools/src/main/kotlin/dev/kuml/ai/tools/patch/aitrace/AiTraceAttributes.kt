package dev.kuml.ai.tools.patch.aitrace

/**
 * OpenTelemetry attribute key constants for AI-patch spans (V3.0.25).
 *
 * These constants mirror the keys used in [dev.kuml.runtime.trace.otlp.AiOtlpMapping].
 * External OTLP consumers (e.g. Langfuse hook in V3.0.26) should read these keys
 * via this object rather than hardcoding the strings, because V3.0.26 may extend
 * the attribute vocabulary.
 */
public object AiTraceAttributes {
    public const val SESSION_ID: String = "kuml.ai.session.id"
    public const val PATCH_ID: String = "kuml.ai.patch.id"
    public const val PATCH_KIND: String = "kuml.ai.patch.kind"
    public const val PATCH_PHASE: String = "kuml.ai.patch.phase"
    public const val PATCH_ERR_COUNT: String = "kuml.ai.patch.error.count"
    public const val BASE_FINGERPRINT: String = "kuml.ai.base.fingerprint"
    public const val REJECTED_COUNT: String = "kuml.ai.rejected.count"
}
