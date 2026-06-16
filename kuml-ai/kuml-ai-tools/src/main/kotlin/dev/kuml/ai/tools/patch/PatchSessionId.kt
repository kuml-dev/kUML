package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.ModelPatch
import kotlinx.serialization.Serializable

/**
 * Stable ULID identifying one [PatchApplyEngine] instance — i.e. one user-visible
 * AI conversation. Re-used as `kuml.ai.session.id` in OTLP attributes.
 *
 * Uses the same Crockford base32 ULID generator as [ModelPatch.newId] (26 chars).
 * V3.0.26 Cost-Telemetry will correlate tokens to sessions via this id.
 */
@Serializable
@JvmInline
public value class PatchSessionId(
    public val value: String,
) {
    public companion object {
        public fun newSession(): PatchSessionId = PatchSessionId(ModelPatch.newId())
    }
}
