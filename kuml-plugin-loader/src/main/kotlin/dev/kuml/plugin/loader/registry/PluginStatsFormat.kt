package dev.kuml.plugin.loader.registry

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting helpers for plugin ratings, reviews and download statistics.
 *
 * Shared by the CLI (`kuml plugin info` / `kuml plugin search`) and the desktop
 * Plugin Manager so there is a single source of truth for display strings.
 *
 * All number formatting uses [Locale.ROOT] to produce consistent output regardless
 * of the JVM default locale (important on German-locale machines).
 */
public object PluginStatsFormat {
    /**
     * Returns a human-readable rating summary, e.g.:
     * - `"4.3/5.0 (12 ratings)"` when [rating] is non-null
     * - `"4.3/5.0 (1 rating)"` — singular form when [count] == 1
     * - `"no ratings yet"` when [rating] is `null`
     */
    public fun ratingLine(
        rating: Double?,
        count: Int,
    ): String {
        if (rating == null) return "no ratings yet"
        val formatted = String.format(Locale.ROOT, "%.1f", rating)
        val noun = if (count == 1) "rating" else "ratings"
        return "$formatted/5.0 ($count $noun)"
    }

    /**
     * Compact download count suitable for tight UI space:
     * - `< 1 000`  → plain integer, e.g. `"950"`
     * - `≥ 1 000`  → one decimal + `k`, e.g. `"1.8k"`
     * - `≥ 1 000 000` → one decimal + `M`, e.g. `"2.3M"`
     */
    public fun compactDownloads(n: Long): String =
        when {
            n < 0 -> "0"
            n < 1_000L -> n.toString()
            n < 1_000_000L -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
            else -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
        }

    /**
     * Full download count with thousands separator using [Locale.ROOT],
     * e.g. `1847 → "1,847"`.
     */
    public fun fullDownloads(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    /**
     * Five-character star glyph string: filled `★` for each full star (rounded from
     * [rating]), empty `☆` for the remainder.  Returns five `☆` when [rating] is `null`.
     *
     * Rounding: uses [kotlin.math.roundToInt] so 4.5 → 5 stars, 4.4 → 4 stars.
     */
    public fun stars(rating: Double?): String {
        if (rating == null) return "☆☆☆☆☆"
        val filled = rating.coerceIn(0.0, 5.0).roundToInt()
        return "★".repeat(filled) + "☆".repeat(5 - filled)
    }

    /**
     * Clamps a raw review [rating] value into the display-safe range `1..5`.
     * Prevents star-rendering from crashing on malformed registry data.
     */
    public fun clampStars(rating: Int): Int = rating.coerceIn(1, 5)

    /**
     * Truncates [comment] to at most [maxLen] characters, appending `…` when trimmed.
     */
    public fun truncate(
        comment: String,
        maxLen: Int = 80,
    ): String = if (comment.length <= maxLen) comment else comment.take(maxLen) + "…"
}
