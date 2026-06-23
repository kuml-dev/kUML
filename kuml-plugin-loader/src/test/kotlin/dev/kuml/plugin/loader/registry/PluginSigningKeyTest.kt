package dev.kuml.plugin.loader.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * V3.1.14 — Direct unit tests for [PluginSigningKey.isUsable].
 *
 * The method contains non-trivial boundary logic that was previously only exercised
 * indirectly. These tests drive [isUsable] with an injectable [today] date to cover
 * all boundary conditions and enum paths.
 */
class PluginSigningKeyTest :
    StringSpec({

        val today = LocalDate.of(2026, 6, 23)

        // ── ACTIVE key within date window ──────────────────────────────────────

        "ACTIVE key with today inside [validFrom, validUntil] window returns true" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAactiveKey==",
                    keyId = "active-key",
                    validFrom = "2025-01-01",
                    validUntil = "2027-12-31",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe true
        }

        // ── inclusive lower boundary: validFrom == today ───────────────────────

        "ACTIVE key with validFrom == today (inclusive lower bound) returns true" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAactiveBoundaryFrom==",
                    keyId = "from-boundary-key",
                    validFrom = "2026-06-23",
                    validUntil = "2027-12-31",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe true
        }

        // ── inclusive upper boundary: validUntil == today ─────────────────────

        "ACTIVE key with validUntil == today (inclusive upper bound) returns true" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAactiveBoundaryUntil==",
                    keyId = "until-boundary-key",
                    validFrom = "2025-01-01",
                    validUntil = "2026-06-23",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe true
        }

        // ── validUntil in the past ─────────────────────────────────────────────

        "ACTIVE key with validUntil in the past (implicit expiry) returns false" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAexpiredKey==",
                    keyId = "past-until-key",
                    validFrom = "2024-01-01",
                    validUntil = "2025-12-31",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe false
        }

        // ── validFrom in the future ────────────────────────────────────────────

        "ACTIVE key with validFrom in the future (not yet valid) returns false" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAfutureKey==",
                    keyId = "future-from-key",
                    validFrom = "2026-07-01",
                    validUntil = "2027-12-31",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe false
        }

        // ── REVOKED key ────────────────────────────────────────────────────────

        "REVOKED key always returns false regardless of date window" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEArevokedKey==",
                    keyId = "revoked-key",
                    validFrom = "2025-01-01",
                    validUntil = "2027-12-31",
                    status = KeyStatus.REVOKED,
                )
            key.isUsable(today) shouldBe false
        }

        // ── EXPIRED enum status ────────────────────────────────────────────────

        "EXPIRED status key returns false even if dates would otherwise be valid" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAexpiredStatusKey==",
                    keyId = "expired-status-key",
                    validFrom = "2025-01-01",
                    validUntil = "2027-12-31",
                    status = KeyStatus.EXPIRED,
                )
            key.isUsable(today) shouldBe false
        }

        // ── malformed validFrom ────────────────────────────────────────────────

        "malformed validFrom string causes runCatching to return false" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAmalformedKey==",
                    keyId = "malformed-key",
                    validFrom = "not-a-date",
                    validUntil = "2027-12-31",
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe false
        }

        // ── null validUntil means no expiry ────────────────────────────────────

        "ACTIVE key with null validUntil (no expiry) returns true indefinitely" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAnoExpiryKey==",
                    keyId = "no-expiry-key",
                    validFrom = "2025-01-01",
                    validUntil = null,
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe true
        }

        "ACTIVE key with null validUntil still respects validFrom (future validFrom returns false)" {
            val key =
                PluginSigningKey(
                    publicKey = "MCowBQYDK2VwAyEAfutureNoExpiryKey==",
                    keyId = "future-no-expiry-key",
                    validFrom = "2026-12-01",
                    validUntil = null,
                    status = KeyStatus.ACTIVE,
                )
            key.isUsable(today) shouldBe false
        }
    })
