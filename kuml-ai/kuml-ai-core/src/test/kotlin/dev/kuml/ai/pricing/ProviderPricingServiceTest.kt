package dev.kuml.ai.pricing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for [ProviderPricingService] — live-fetch path, fallback logic, and bundled resource.
 * All tests use stub fetchers; no network calls are made.
 */
class ProviderPricingServiceTest :
    FunSpec({

        // ── Stub helpers ──────────────────────────────────────────────────────

        fun validJson() =
            """
            {
              "schemaVersion": 1,
              "source": "test",
              "generatedAt": "2026-01-01T00:00:00Z",
              "entries": [
                { "providerId": "openai", "modelId": "gpt-4o", "inputPricePerMToken": 5.0, "outputPricePerMToken": 15.0, "updatedAt": "2026-01-01" }
              ]
            }
            """.trimIndent()

        // ── Test 1: valid live JSON → live=true, entries parsed ───────────────

        test("valid live JSON produces live=true result with parsed entries") {
            val service = ProviderPricingService.forTest(PricingFetcher { validJson() })
            val result = service.load()

            result.live.shouldBeTrue()
            result.document.entries.size shouldBe 1
            result.document.entries[0].providerId shouldBe "openai"
            result.document.entries[0].modelId shouldBe "gpt-4o"
            result.document.entries[0].inputPricePerMToken shouldBe 5.0
        }

        // ── Test 2: null fetch → fallback, live=false ────────────────────────

        test("null fetch result falls back to bundled snapshot with live=false") {
            val service = ProviderPricingService.forTest(PricingFetcher { null })
            val result = service.load()

            result.live.shouldBeFalse()
            result.document.entries.shouldNotBeEmpty()
            // Bundled fallback must include at least openai/gpt-4o
            val gpt4o =
                result.document.entries.firstOrNull {
                    it.providerId == "openai" && it.modelId == "gpt-4o"
                }
            val expected =
                PricingEntry(
                    providerId = "openai",
                    modelId = "gpt-4o",
                    inputPricePerMToken = 5.0,
                    outputPricePerMToken = 15.0,
                    updatedAt = "2026-06-23",
                )
            gpt4o shouldBe expected
        }

        // ── Test 3: malformed JSON → fallback ────────────────────────────────

        test("malformed JSON from fetcher falls back to bundled snapshot") {
            val service = ProviderPricingService.forTest(PricingFetcher { "not valid json!!!" })
            val result = service.load()

            result.live.shouldBeFalse()
            result.document.entries.shouldNotBeEmpty()
        }

        // ── Test 4: empty entries list → fallback ────────────────────────────

        test("valid JSON with empty entries list falls back to bundled snapshot") {
            val emptyEntriesJson =
                """{"schemaVersion":1,"source":"test","generatedAt":"2026-01-01T00:00:00Z","entries":[]}"""
            val service = ProviderPricingService.forTest(PricingFetcher { emptyEntriesJson })
            val result = service.load()

            result.live.shouldBeFalse()
            result.document.entries.shouldNotBeEmpty()
        }

        // ── Test 5: bundled fallback loads correctly from classpath ───────────

        test("bundled fallback resource loads from classpath with known entries") {
            val doc = ProviderPricingService.loadFallback()

            doc.source shouldBe "bundled-fallback"
            doc.entries.shouldNotBeEmpty()
            doc.entries.any { it.providerId == "anthropic" }.shouldBeTrue()
            doc.entries.any { it.providerId == "ollama" }.shouldBeTrue()
        }

        // ── Test 6: bundledEstimator builds a non-empty estimator ─────────────

        test("bundledEstimator returns an estimator with gpt-4o pricing") {
            val est = ProviderPricingService.bundledEstimator()
            val cost = est.estimate("openai", "gpt-4o", 1_000_000L, 1_000_000L)
            // 1M input at $5 + 1M output at $15 = $20
            cost shouldBe 20.0
        }

        // ── Test 7: HttpsPricingFetcher rejects http:// URL (pure unit) ───────

        test("HttpsPricingFetcher returns null for a non-https URL") {
            // We test the guard indirectly by invoking the internal class.
            // The class is internal, so we use reflection-free approach: instantiate via constructor.
            val fetcher = HttpsPricingFetcher(url = "http://example.com/pricing.json")
            fetcher.fetch() shouldBe null
        }

        // ── Test 8: generatedAt is exposed in fallback source label ───────────

        test("fallback document generatedAt is non-empty") {
            val doc = ProviderPricingService.loadFallback()
            doc.generatedAt shouldContain "2026"
        }
    })
