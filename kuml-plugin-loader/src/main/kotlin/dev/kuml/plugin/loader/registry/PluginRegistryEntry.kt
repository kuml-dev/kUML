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
 * V3.1.14 replaces the single `signaturePublicKey` field with [signingKeys] — a list of
 * [PluginSigningKey] objects that enables key rotation. Old registry JSON that still
 * carries `signaturePublicKey` is automatically migrated by [PluginRegistryEntrySerializer]:
 * the legacy value is wrapped as a single `PluginSigningKey(keyId="legacy", …)` so that
 * no data is lost and existing plugins continue to verify correctly.
 *
 * **Important**: [downloads] is a URL string used by `PluginUpgradeCommand` to fetch
 * the plugin JAR. The numeric download statistic is a separate field: [downloadCount].
 */
@Serializable(with = PluginRegistryEntrySerializer::class)
public data class PluginRegistryEntry(
    val id: String,
    val category: String,
    val name: String,
    val version: String,
    val kumlVersionRange: String = "",
    val manifest: String,
    /** URL to the plugin release artefacts — used by upgrade/download. Not a count. */
    val downloads: String,
    /**
     * Signing keys for this plugin entry (V3.1.14).
     * Replaces the legacy `signaturePublicKey` single-field.
     * May be empty if the plugin is unsigned.
     */
    val signingKeys: List<PluginSigningKey> = emptyList(),
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

    /**
     * Returns all signing keys that are currently usable for verification.
     *
     * A key is usable when its status is [KeyStatus.ACTIVE] and the current date
     * falls within its `[validFrom, validUntil]` window. See [PluginSigningKey.isUsable].
     *
     * @param today injectable for deterministic testing; defaults to `LocalDate.now()`.
     */
    public fun activeKeys(today: java.time.LocalDate = java.time.LocalDate.now()): List<PluginSigningKey> =
        signingKeys.filter { it.isUsable(today) }

    /**
     * Human-readable summary of the key roster, suitable for CLI/desktop display.
     *
     * Counts keys by **effective** status (an `ACTIVE` key with a past `validUntil`
     * is counted as expired, consistent with [PluginSigningKey.isUsable]).
     *
     * Examples:
     * - `"2 active, 1 revoked"`
     * - `"1 active"`
     * - `"no keys"`
     *
     * Zero-count groups are omitted.
     */
    public fun keyStatusSummary(today: java.time.LocalDate = java.time.LocalDate.now()): String {
        if (signingKeys.isEmpty()) return "no keys"

        var active = 0
        var revoked = 0
        var expired = 0

        for (key in signingKeys) {
            when {
                key.status == KeyStatus.REVOKED -> revoked++
                key.status == KeyStatus.EXPIRED -> expired++
                key.isUsable(today) -> active++
                else -> expired++ // ACTIVE but date-expired
            }
        }

        val parts =
            buildList {
                if (active > 0) add("$active active")
                if (revoked > 0) add("$revoked revoked")
                if (expired > 0) add("$expired expired")
            }
        return if (parts.isEmpty()) "no keys" else parts.joinToString(", ")
    }
}
