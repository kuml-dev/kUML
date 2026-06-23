package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.time.LocalDate

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

        val screenshotFormatJson =
            """
            {
              "id": "dev.kuml.plugin.elk-layout",
              "category": "layout",
              "name": "ELK Layout Engine",
              "version": "2.0.0",
              "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
              "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/",
              "screenshotUrls": ["screenshots/elk/1.png", "screenshots/elk/2.png"]
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

        // ── V3.1.13: screenshotUrls ────────────────────────────────────────────

        "old JSON without screenshotUrls: defaults to empty list" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.screenshotUrls.shouldBeEmpty()
        }

        "screenshotUrls parses with correct size" {
            val entry = json.decodeFromString<PluginRegistryEntry>(screenshotFormatJson)
            entry.screenshotUrls shouldHaveSize 2
        }

        "screenshotUrls first element is correct" {
            val entry = json.decodeFromString<PluginRegistryEntry>(screenshotFormatJson)
            entry.screenshotUrls[0] shouldBe "screenshots/elk/1.png"
        }

        "serialization roundtrip preserves screenshotUrls" {
            val original = json.decodeFromString<PluginRegistryEntry>(screenshotFormatJson)
            val encoded = Json.encodeToString(PluginRegistryEntry.serializer(), original)
            val decoded = json.decodeFromString<PluginRegistryEntry>(encoded)
            decoded.screenshotUrls shouldHaveSize original.screenshotUrls.size
            decoded.screenshotUrls[0] shouldBe original.screenshotUrls[0]
        }

        // ── V3.1.14: signingKeys list ──────────────────────────────────────────

        val signingKeysJson =
            """
            {
              "id": "dev.kuml.plugin.elk-layout",
              "category": "layout",
              "name": "ELK Layout Engine",
              "version": "3.0.0",
              "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
              "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/",
              "signingKeys": [
                {
                  "publicKey": "MCowBQYDK2VwAyEArevokedKey==",
                  "keyId": "2024-primary",
                  "validFrom": "2024-01-01",
                  "validUntil": "2025-03-31",
                  "status": "REVOKED"
                },
                {
                  "publicKey": "MCowBQYDK2VwAyEAactive1Key==",
                  "keyId": "2025-primary",
                  "validFrom": "2025-01-01",
                  "validUntil": "2026-09-30",
                  "status": "ACTIVE"
                },
                {
                  "publicKey": "MCowBQYDK2VwAyEAactive2Key==",
                  "keyId": "2026-primary",
                  "validFrom": "2026-07-01",
                  "status": "ACTIVE"
                }
              ]
            }
            """.trimIndent()

        val today = LocalDate.of(2026, 6, 23)

        "V3.1.14: signingKeys array with 3 entries parses to correct size" {
            val entry = json.decodeFromString<PluginRegistryEntry>(signingKeysJson)
            entry.signingKeys shouldHaveSize 3
        }

        "V3.1.14: keyStatusSummary returns '1 active, 1 revoked, 1 expired'" {
            val entry = json.decodeFromString<PluginRegistryEntry>(signingKeysJson)
            // 2025-primary: validUntil 2026-09-30 — still active on 2026-06-23 → active
            // 2026-primary: validFrom 2026-07-01 — not yet active on 2026-06-23 → expired (future)
            // 2024-primary: REVOKED → revoked
            // Expected: "1 active, 1 revoked, 1 expired"
            val summary = entry.keyStatusSummary(today)
            summary shouldBe "1 active, 1 revoked, 1 expired"
        }

        "V3.1.14: keyStatusSummary shows all three groups simultaneously" {
            // Explicit fixture with one key of each effective status to exercise all branches
            val threeGroupJson =
                """
                {
                  "id": "dev.kuml.plugin.test",
                  "category": "theme",
                  "name": "Test",
                  "version": "1.0.0",
                  "manifest": "m",
                  "downloads": "d",
                  "signingKeys": [
                    {
                      "publicKey": "MCowBQYDK2VwAyEAactiveKey==",
                      "keyId": "active-key",
                      "validFrom": "2025-01-01",
                      "validUntil": "2027-12-31",
                      "status": "ACTIVE"
                    },
                    {
                      "publicKey": "MCowBQYDK2VwAyEArevokedKey==",
                      "keyId": "revoked-key",
                      "validFrom": "2024-01-01",
                      "validUntil": "2025-03-31",
                      "status": "REVOKED"
                    },
                    {
                      "publicKey": "MCowBQYDK2VwAyEAexpiredKey==",
                      "keyId": "expired-key",
                      "validFrom": "2024-01-01",
                      "validUntil": "2024-12-31",
                      "status": "ACTIVE"
                    }
                  ]
                }
                """.trimIndent()
            val entry = json.decodeFromString<PluginRegistryEntry>(threeGroupJson)
            // On 2026-06-23: active-key usable, revoked-key REVOKED, expired-key past validUntil
            val summary = entry.keyStatusSummary(today)
            summary shouldBe "1 active, 1 revoked, 1 expired"
        }

        "V3.1.14: activeKeys returns only usable keys" {
            val entry = json.decodeFromString<PluginRegistryEntry>(signingKeysJson)
            // On 2026-06-23: 2025-primary is active (validFrom<=today<=validUntil)
            // 2026-primary: validFrom 2026-07-01 > today → not yet active
            // 2024-primary: REVOKED → not active
            val active = entry.activeKeys(today)
            active shouldHaveSize 1
            active[0].keyId shouldBe "2025-primary"
        }

        "V3.1.14: legacy JSON with signaturePublicKey wraps as single legacy key" {
            val legacyJson =
                """
                {
                  "id": "dev.kuml.plugin.pdv-theme",
                  "category": "theme",
                  "name": "PdV Branding Theme",
                  "version": "1.0.0",
                  "manifest": "m",
                  "downloads": "d",
                  "signaturePublicKey": "MCowBQYDK2VwAyEA..."
                }
                """.trimIndent()
            val entry = json.decodeFromString<PluginRegistryEntry>(legacyJson)
            entry.signingKeys shouldHaveSize 1
            entry.signingKeys[0].keyId shouldBe "legacy"
            entry.signingKeys[0].status shouldBe KeyStatus.ACTIVE
            entry.signingKeys[0].validUntil shouldBe null
        }

        "V3.1.14: legacy JSON with null signaturePublicKey results in empty signingKeys" {
            val legacyNullJson =
                """
                {
                  "id": "dev.kuml.plugin.elk-layout",
                  "category": "layout",
                  "name": "ELK Layout Engine",
                  "version": "2.0.0",
                  "manifest": "m",
                  "downloads": "d",
                  "signaturePublicKey": null
                }
                """.trimIndent()
            val entry = json.decodeFromString<PluginRegistryEntry>(legacyNullJson)
            entry.signingKeys.shouldBeEmpty()
        }

        "V3.1.14: no signing key fields at all results in empty signingKeys" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.signingKeys.shouldBeEmpty()
        }

        "V3.1.14: keyStatusSummary returns 'no keys' when signingKeys is empty" {
            val entry = json.decodeFromString<PluginRegistryEntry>(oldFormatJson)
            entry.keyStatusSummary(today) shouldBe "no keys"
        }

        "V3.1.14: serialization roundtrip preserves signingKeys" {
            val original = json.decodeFromString<PluginRegistryEntry>(signingKeysJson)
            val encoded = Json.encodeToString(PluginRegistryEntry.serializer(), original)
            val decoded = json.decodeFromString<PluginRegistryEntry>(encoded)
            decoded.signingKeys shouldHaveSize 3
            decoded.signingKeys[0].keyId shouldBe "2024-primary"
            decoded.signingKeys[1].keyId shouldBe "2025-primary"
            decoded.signingKeys[2].keyId shouldBe "2026-primary"
        }

        "V3.1.14: encoded JSON does not contain legacy signaturePublicKey field" {
            val original = json.decodeFromString<PluginRegistryEntry>(signingKeysJson)
            val encoded = Json.encodeToString(PluginRegistryEntry.serializer(), original)
            encoded shouldNotContain "signaturePublicKey"
        }

        "V3.1.14: roundtrip preserves all pre-existing fields alongside signingKeys" {
            val original = json.decodeFromString<PluginRegistryEntry>(fullFormatJson)
            val encoded = Json.encodeToString(PluginRegistryEntry.serializer(), original)
            val decoded = json.decodeFromString<PluginRegistryEntry>(encoded)
            decoded.id shouldBe original.id
            decoded.downloadCount shouldBe original.downloadCount
            decoded.rating shouldBe original.rating
            decoded.ratingCount shouldBe original.ratingCount
            decoded.reviews shouldHaveSize original.reviews.size
            decoded.screenshotUrls shouldBe original.screenshotUrls
        }
    })
