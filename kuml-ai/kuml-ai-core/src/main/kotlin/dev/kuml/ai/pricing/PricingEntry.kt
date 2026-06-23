package dev.kuml.ai.pricing

import kotlinx.serialization.Serializable

/** One pricing entry — per-MTok (per million tokens) pricing for a provider/model combination. */
@Serializable
public data class PricingEntry(
    val providerId: String,
    val modelId: String,
    /** Input (prompt) price in USD per million tokens. */
    val inputPricePerMToken: Double,
    /** Output (completion) price in USD per million tokens. */
    val outputPricePerMToken: Double,
    /** ISO date string indicating when this entry was last verified (informational). */
    val updatedAt: String = "",
)

/** Top-level document returned by [ProviderPricingService]. */
@Serializable
public data class PricingDocument(
    val schemaVersion: Int = 1,
    val source: String = "unknown",
    val generatedAt: String = "",
    val entries: List<PricingEntry> = emptyList(),
)
