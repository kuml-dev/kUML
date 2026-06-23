package dev.kuml.plugin.loader.registry

import kotlinx.serialization.Serializable

/**
 * A single user review for a plugin entry in the registry.
 *
 * Validation (e.g. clamping [rating] to 1–5) is intentionally deferred to display
 * time via [dev.kuml.plugin.loader.registry.PluginStatsFormat.clampStars] so that
 * malformed-but-structurally-valid JSON still deserializes correctly.
 */
@Serializable
public data class PluginReview(
    val author: String,
    val rating: Int,
    val comment: String,
    /** ISO-8601 date string, e.g. `"2026-06-23"`. Used for chronological sorting. */
    val date: String,
)
