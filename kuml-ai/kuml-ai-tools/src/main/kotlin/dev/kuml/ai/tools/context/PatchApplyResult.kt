package dev.kuml.ai.tools.context

import kotlinx.serialization.Serializable

/**
 * Strict result envelope every @Tool-mutation returns. The sealed shape keeps
 * the LLM's output schema small and deterministic — the only options are
 * Success or Failure, with a single string field on each variant.
 *
 * Koog 0.7.3 BasicJsonSchemaGenerator handles sealed @Serializable result
 * types via the polymorphic kotlinx.serialization layer.
 */
@Serializable
public sealed interface PatchApplyResult {
    @Serializable
    public data class Success(
        val elementId: String,
        val patchId: String,
        val warnings: List<String> = emptyList(),
    ) : PatchApplyResult

    @Serializable
    public data class Failure(
        val reason: String,
        val hint: String? = null,
    ) : PatchApplyResult
}
