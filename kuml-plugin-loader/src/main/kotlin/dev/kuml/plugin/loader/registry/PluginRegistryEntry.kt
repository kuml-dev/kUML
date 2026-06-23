package dev.kuml.plugin.loader.registry

import kotlinx.serialization.Serializable

/**
 * One entry in the `plugins/index.json` registry index.
 *
 * Published at `https://plugins.kuml.dev/plugins/index.json` (V3.0.30).
 *
 * V3.1.12 adds optional rating/review/download-count fields. All new fields default
 * so that existing registry JSON without those fields parses without error.
 *
 * V3.1.13 adds optional [screenshotUrls] (defaulted → backward compatible).
 *
 * **Important**: [downloads] is a URL string used by `PluginUpgradeCommand` to fetch
 * the plugin JAR. The numeric download statistic is a separate field: [downloadCount].
 */
@Serializable
public data class PluginRegistryEntry(
    val id: String,
    val category: String,
    val name: String,
    val version: String,
    val kumlVersionRange: String = "",
    val manifest: String,
    /** URL to the plugin release artefacts — used by upgrade/download. Not a count. */
    val downloads: String,
    val signaturePublicKey: String? = null,
    val maintainer: String = "",
    val homepage: String = "",
    /** Total number of times this plugin has been downloaded. (V3.1.12) */
    val downloadCount: Long = 0,
    /** Aggregate rating in the range 0.0–5.0, or `null` when no ratings exist yet. */
    val rating: Double? = null,
    /** Number of individual ratings that form [rating]. */
    val ratingCount: Int = 0,
    /** All reviews submitted for this plugin. */
    val reviews: List<PluginReview> = emptyList(),
    /**
     * Relative or absolute screenshot URLs for this plugin (V3.1.13).
     * Served from `plugins.kuml.dev/screenshots/<id>/<n>.png`. May be empty.
     */
    val screenshotUrls: List<String> = emptyList(),
) {
    /**
     * Returns up to [limit] reviews sorted by [PluginReview.date] descending
     * (most recent first).
     */
    public fun recentReviews(limit: Int = 3): List<PluginReview> = reviews.sortedByDescending { it.date }.take(limit)
}
