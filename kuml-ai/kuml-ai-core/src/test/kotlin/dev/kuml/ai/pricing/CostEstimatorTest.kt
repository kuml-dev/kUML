package dev.kuml.ai.pricing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [CostEstimator] — cost arithmetic, unknown model handling.
 */
class CostEstimatorTest :
    FunSpec({

        fun entry(
            provider: String,
            model: String,
            inPerM: Double,
            outPerM: Double,
        ) = PricingEntry(
            providerId = provider,
            modelId = model,
            inputPricePerMToken = inPerM,
            outputPricePerMToken = outPerM,
            updatedAt = "2026-01-01",
        )

        val entries =
            listOf(
                entry("openai", "gpt-4o", 5.0, 15.0),
                entry("openai", "gpt-4o-mini", 0.15, 0.60),
                entry("ollama", "llama3.2", 0.0, 0.0),
            )

        val estimator = CostEstimator(entries)

        // ── Test 1: gpt-4o 1M in / 1M out → $5 + $15 = $20 ─────────────────

        test("gpt-4o 1M input and 1M output costs exactly 20.00 USD") {
            val cost = estimator.estimate(providerId = "openai", modelId = "gpt-4o", inputTokens = 1_000_000L, outputTokens = 1_000_000L)!!
            cost shouldBe (20.0 plusOrMinus 1e-9)
        }

        // ── Test 2: ollama free model → 0.0 ──────────────────────────────────

        test("ollama llama3.2 always costs 0.0 USD") {
            val cost = estimator.estimate(providerId = "ollama", modelId = "llama3.2", inputTokens = 10_000L, outputTokens = 5_000L)!!
            cost shouldBe 0.0
        }

        // ── Test 3: unknown (provider, model) → null ──────────────────────────

        test("unknown provider model pair returns null") {
            estimator
                .estimate(
                    providerId = "unknown-provider",
                    modelId = "unknown-model",
                    inputTokens = 100L,
                    outputTokens = 100L,
                ).shouldBeNull()
        }

        test("known provider but unknown model returns null") {
            estimator.estimate(providerId = "openai", modelId = "gpt-99-ultra", inputTokens = 100L, outputTokens = 100L).shouldBeNull()
        }

        // ── Test 4: mixed token counts arithmetic ─────────────────────────────

        test("gpt-4o-mini 1500 input and 500 output computes correctly") {
            // 1500 * 0.15/1_000_000 + 500 * 0.60/1_000_000
            // = 0.000225 + 0.0003 = 0.000525
            val cost = estimator.estimate(providerId = "openai", modelId = "gpt-4o-mini", inputTokens = 1500L, outputTokens = 500L)!!
            cost shouldBe (0.000525 plusOrMinus 1e-12)
        }

        // ── Test 5: zero tokens → zero cost ──────────────────────────────────

        test("zero tokens for any priced model costs 0.0") {
            val cost = estimator.estimate(providerId = "openai", modelId = "gpt-4o", inputTokens = 0L, outputTokens = 0L)!!
            cost shouldBe 0.0
        }

        // ── Test 6: rows() returns sorted entries ────────────────────────────

        test("rows() returns entries sorted by provider then model") {
            val rows = estimator.rows()
            rows.size shouldBe 3
            // ollama before openai
            rows[0].providerId shouldBe "ollama"
            // openai/gpt-4o before openai/gpt-4o-mini (4o < 4o-mini lexicographically)
            rows[1].providerId shouldBe "openai"
            rows[1].modelId shouldBe "gpt-4o"
            rows[2].modelId shouldBe "gpt-4o-mini"
        }

        // ── Test 7: fromDocument builds correct estimator ────────────────────

        test("fromDocument produces a working estimator") {
            val doc =
                PricingDocument(
                    entries =
                        listOf(
                            PricingEntry(
                                providerId = "anthropic",
                                modelId = "claude-sonnet-4-5",
                                inputPricePerMToken = 3.0,
                                outputPricePerMToken = 15.0,
                            ),
                        ),
                )
            val est = CostEstimator.fromDocument(doc)
            val cost = est.estimate(providerId = "anthropic", modelId = "claude-sonnet-4-5", inputTokens = 1_000_000L, outputTokens = 0L)!!
            cost shouldBe (3.0 plusOrMinus 1e-9)
        }

        // ── Test 8: empty() estimator returns null for all queries ────────────

        test("empty estimator returns null for any query") {
            val est = CostEstimator.empty()
            est.estimate(providerId = "openai", modelId = "gpt-4o", inputTokens = 100L, outputTokens = 100L).shouldBeNull()
        }
    })
