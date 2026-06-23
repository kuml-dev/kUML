package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

/**
 * V3.1.12 — Tests for [PluginRegistryEntry] model additions:
 * - Backward compat: old JSON without new fields still parses (downloadCount=0, rating=null, …)
 * - New fields parse correctly when present
 * - [PluginRegistryEntry.recentReviews] returns correct slice in date-descending order
 * - Serialization roundtrip preserves new fields
 */
class PluginRegistryEntryTest :
    StringSpec({

        val json = Json { ignoreUnknownKeys = true }

        // ── base entry JSON (old format, no new fields) ────────────────────────

        val oldFormatJson =
            """
            {
              "id": "dev.kuml.plugin.elk-layout",
              "category": "layout",
              "name": "ELK Layout Engine",
              "version": "2.0.0",
              "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
              "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/"
            }
            """.trimIndent()

        val fullFormatJson =
            """
            {
              "id": "dev.kuml.plugin.pdv-theme",
              "category": "theme",
              "name": "PdV Branding Theme",
              "version": "1.0.0",
              "kumlVersionRange": ">=0.13.0",
              "manifest": "plugins/dev.kuml.plugin.pdv-theme/kuml-plugin.json",
              "downloads": "plugins/dev.kuml.plugin.pdv-theme/releases/",
              "downloadCount": 1847,
              "rating": 4.3,
              "ratingCount": 12,
              "reviews": [
                { "author": "alice", "rating": 4, "comment": "Works great.", "date": "2026-06-20" },
                { "author": "bob",   "rating": 5, "comment": "Fast.",        "date": "2026-06-18" },
                { "author": "carol", "rating": 4, "comment": "Nice one.",    "date": "2026-06-15" },
                { "author": "dave",  "rating": 4, "comment": "Solid.",       "date": "2026-06-10" }
              ]
            }
            """.trimIndent()

        // ── backward compatibility ─────────────────────────────────────────────

        "old JSON without new fields: downloadCount defaults to 0" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.downloadCount shouldBe 0
        }

        "old JSON without new fields: rating defaults to null" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.rating shouldBe null
        }

        "old JSON without new fields: ratingCount defaults to 0" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.ratingCount shouldBe 0
        }

        "old JSON without new fields: reviews defaults to empty list" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.reviews.shouldBeEmpty()
        }

        "old JSON: downloads field is still the URL string (not repurposed)" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.downloads shouldBe "plugins/dev.kuml.plugin.elk-layout/releases/"
        }

        // ── new fields parse correctly ─────────────────────────────────────────

        "new fields: downloadCount parses as Long" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.downloadCount shouldBe 1847L
        }

        "new fields: rating parses as Double" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.rating shouldBe 4.3
        }

        "new fields: ratingCount parses correctly" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.ratingCount shouldBe 12
        }

        "new fields: reviews list parses with correct size" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.reviews shouldHaveSize 4
        }

        "new fields: first review author is alice" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.reviews[0].author shouldBe "alice"
        }

        "new fields: review rating parses as Int" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            entry.reviews[0].rating shouldBe 4
        }

        // ── rating null vs 0.0 distinction ────────────────────────────────────

        "rating null is distinct from rating 0.0" {
            val noRatingJson =
                """
                {
                  "id": "x", "category": "theme", "name": "X", "version": "1.0.0",
                  "manifest": "m", "downloads": "d"
                }
                """.trimIndent()
            val zeroRatingJson =
                """
                {
                  "id": "x", "category": "theme", "name": "X", "version": "1.0.0",
                  "manifest": "m", "downloads": "d", "rating": 0.0
                }
                """.trimIndent()
            val noRating = json.decodeFromString<PluginRegistryEntry>(noRatingJson)
            val zeroRating = json.decodeFromString<PluginRegistryEntry>(zeroRatingJson)
            noRating.rating shouldBe null
            zeroRating.rating shouldBe 0.0
            noRating.rating shouldNotBe zeroRating.rating
        }

        // ── recentReviews() ────────────────────────────────────────────────────

        "recentReviews(3) returns 3 most recent entries descending by date" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            val recent = entry.recentReviews(3)
            recent shouldHaveSize 3
            recent[0].date shouldBe "2026-06-20"
            recent[1].date shouldBe "2026-06-18"
            recent[2].date shouldBe "2026-06-15"
        }

        "recentReviews(1) returns only the newest review" {
            val entry = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            val recent = entry.recentReviews(1)
            recent shouldHaveSize 1
            recent[0].author shouldBe "alice"
        }

        "recentReviews when empty returns empty list" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.recentReviews(3).shouldBeEmpty()
        }

        // ── serialization roundtrip ────────────────────────────────────────────

        "serialization roundtrip preserves downloadCount, rating, ratingCount and reviews" {
            val original = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            val encoded = Json.encodeToString(PluginRegistryEntry.serializer(), original)
            val decoded = json.decodeFromString<PluginRegistryEntry>(encoded)
            decoded.downloadCount shouldBe original.downloadCount
            decoded.rating shouldBe original.rating
            decoded.ratingCount shouldBe original.ratingCount
            decoded.reviews shouldHaveSize original.reviews.size
        }
    })
