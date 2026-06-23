package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * V3.1.12 — Unit tests for [PluginStatsFormat].
 *
 * All assertions use [Locale.ROOT] semantics (decimal point, comma thousands
 * separator) since [PluginStatsFormat] uses [Locale.ROOT] throughout.
 */
class PluginStatsFormatTest :
    StringSpec({

        // ── compactDownloads ───────────────────────────────────────────────────

        "compactDownloads: 0 returns '0'" {
            PluginStatsFormat.compactDownloads(0) shouldBe "0"
        }

        "compactDownloads: 950 returns '950'" {
            PluginStatsFormat.compactDownloads(950) shouldBe "950"
        }

        "compactDownloads: 999 returns '999' (boundary below 1k)" {
            PluginStatsFormat.compactDownloads(999) shouldBe "999"
        }

        "compactDownloads: 1000 returns '1.0k'" {
            PluginStatsFormat.compactDownloads(1_000) shouldBe "1.0k"
        }

        "compactDownloads: 1847 returns '1.8k'" {
            PluginStatsFormat.compactDownloads(1_847) shouldBe "1.8k"
        }

        "compactDownloads: 12400 returns '12.4k'" {
            PluginStatsFormat.compactDownloads(12_400) shouldBe "12.4k"
        }

        "compactDownloads: 999999 returns '1000.0k' (boundary below 1M)" {
            // 999 999 / 1000 = 999.999 → formatted as 1000.0k (rounds up)
            PluginStatsFormat.compactDownloads(999_999) shouldBe "1000.0k"
        }

        "compactDownloads: 1000000 returns '1.0M'" {
            PluginStatsFormat.compactDownloads(1_000_000) shouldBe "1.0M"
        }

        "compactDownloads: 2300000 returns '2.3M'" {
            PluginStatsFormat.compactDownloads(2_300_000) shouldBe "2.3M"
        }

        "compactDownloads: negative returns '0'" {
            PluginStatsFormat.compactDownloads(-5) shouldBe "0"
        }

        // ── fullDownloads ──────────────────────────────────────────────────────

        "fullDownloads: 0 returns '0'" {
            PluginStatsFormat.fullDownloads(0) shouldBe "0"
        }

        "fullDownloads: 1847 returns '1,847'" {
            PluginStatsFormat.fullDownloads(1_847) shouldBe "1,847"
        }

        "fullDownloads: 1000000 returns '1,000,000'" {
            PluginStatsFormat.fullDownloads(1_000_000) shouldBe "1,000,000"
        }

        // ── ratingLine ─────────────────────────────────────────────────────────

        "ratingLine: null rating returns 'no ratings yet'" {
            PluginStatsFormat.ratingLine(null, 0) shouldBe "no ratings yet"
        }

        "ratingLine: 4.3 with 12 ratings" {
            PluginStatsFormat.ratingLine(4.3, 12) shouldBe "4.3/5.0 (12 ratings)"
        }

        "ratingLine: 5.0 with 1 rating (singular form)" {
            PluginStatsFormat.ratingLine(5.0, 1) shouldBe "5.0/5.0 (1 rating)"
        }

        "ratingLine: 0.0 with 0 ratings (explicitly set to zero)" {
            PluginStatsFormat.ratingLine(0.0, 0) shouldBe "0.0/5.0 (0 ratings)"
        }

        // ── stars ──────────────────────────────────────────────────────────────

        "stars: null returns five empty stars" {
            PluginStatsFormat.stars(null) shouldBe "☆☆☆☆☆"
        }

        "stars: 5.0 returns five filled stars" {
            PluginStatsFormat.stars(5.0) shouldBe "★★★★★"
        }

        "stars: 0.0 returns five empty stars" {
            PluginStatsFormat.stars(0.0) shouldBe "☆☆☆☆☆"
        }

        "stars: 4.3 rounds to 4 filled stars" {
            PluginStatsFormat.stars(4.3) shouldBe "★★★★☆"
        }

        "stars: 4.5 rounds to 5 filled stars (round-half-up)" {
            PluginStatsFormat.stars(4.5) shouldBe "★★★★★"
        }

        "stars: 2.0 returns two filled and three empty stars" {
            PluginStatsFormat.stars(2.0) shouldBe "★★☆☆☆"
        }

        "stars: result is always exactly 5 glyphs" {
            listOf(null, 0.0, 1.0, 2.5, 3.0, 4.3, 5.0).forEach { r ->
                val s = PluginStatsFormat.stars(r)
                s.codePointCount(0, s.length) shouldBe 5
            }
        }

        // ── clampStars ─────────────────────────────────────────────────────────

        "clampStars: 0 clamps to 1" {
            PluginStatsFormat.clampStars(0) shouldBe 1
        }

        "clampStars: -1 clamps to 1" {
            PluginStatsFormat.clampStars(-1) shouldBe 1
        }

        "clampStars: 6 clamps to 5" {
            PluginStatsFormat.clampStars(6) shouldBe 5
        }

        "clampStars: 3 stays 3" {
            PluginStatsFormat.clampStars(3) shouldBe 3
        }

        "clampStars: 1 stays 1 (lower boundary)" {
            PluginStatsFormat.clampStars(1) shouldBe 1
        }

        "clampStars: 5 stays 5 (upper boundary)" {
            PluginStatsFormat.clampStars(5) shouldBe 5
        }

        // ── truncate ───────────────────────────────────────────────────────────

        "truncate: short comment unchanged" {
            PluginStatsFormat.truncate("Hello", 80) shouldBe "Hello"
        }

        "truncate: exactly maxLen chars unchanged" {
            val s = "x".repeat(80)
            PluginStatsFormat.truncate(s, 80) shouldBe s
        }

        "truncate: longer than maxLen appends ellipsis" {
            val s = "a".repeat(81)
            val result = PluginStatsFormat.truncate(s, 80)
            result shouldBe "a".repeat(80) + "…"
        }
    })
