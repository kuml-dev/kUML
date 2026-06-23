package dev.kuml.ai.budget

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.pricing.CostEstimator
import dev.kuml.ai.pricing.PricingDocument
import dev.kuml.ai.pricing.PricingEntry
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Tests for [BudgetGuard] — budget checking, accumulation, thread-safety, reset.
 */
class BudgetGuardTest :
    FunSpec({

        fun makeEstimator(vararg entries: PricingEntry) = CostEstimator.fromDocument(PricingDocument(entries = entries.toList()))

        fun gpt4oEntry() = PricingEntry("openai", "gpt-4o", 5.0, 15.0, "2026-01-01")

        // ── Test 1: null budget — never throws, accumulates ───────────────────

        test("null budget never throws on checkBeforeCall or recordUsage") {
            val guard = BudgetGuard(null, makeEstimator(gpt4oEntry()))
            shouldNotThrow<KumlAiException.BudgetExceeded> {
                guard.checkBeforeCall()
                guard.recordUsage("openai", "gpt-4o", 1_000_000L, 1_000_000L)
                guard.checkBeforeCall() // still no throw
            }
            guard.currentSpendUsd shouldBe (20.0 plusOrMinus 1e-9)
        }

        // ── Test 2: budget 0.01, call costs 0.02 → recordUsage throws ────────

        test("recordUsage throws BudgetExceeded when spend exceeds budget") {
            val guard = BudgetGuard(0.01, makeEstimator(gpt4oEntry()))
            val ex =
                shouldThrow<KumlAiException.BudgetExceeded> {
                    // gpt-4o: 5.0/MTok in, 15.0/MTok out
                    // 1000 input + 1000 output = 5*1000/1_000_000 + 15*1000/1_000_000
                    //   = 0.005 + 0.015 = 0.02 USD
                    guard.recordUsage("openai", "gpt-4o", 1_000L, 1_000L)
                }
            ex.spentUsd shouldBe (0.02 plusOrMinus 1e-9)
            ex.budgetUsd shouldBe 0.01
        }

        // ── Test 3: already over budget → next checkBeforeCall throws ─────────

        test("checkBeforeCall throws when session is already over budget") {
            val guard = BudgetGuard(0.005, makeEstimator(gpt4oEntry()))
            // First record: 1000 input = 0.005 USD — exactly at limit, does not throw
            guard.recordUsage("openai", "gpt-4o", 1_000L, 0L)
            // 0.005 >= 0.005 → next checkBeforeCall must throw
            shouldThrow<KumlAiException.BudgetExceeded> {
                guard.checkBeforeCall()
            }
        }

        // ── Test 4: unknown model → null cost → no throw ─────────────────────

        test("unknown model produces no cost and does not throw") {
            val guard = BudgetGuard(0.001, makeEstimator(gpt4oEntry()))
            shouldNotThrow<KumlAiException.BudgetExceeded> {
                guard.recordUsage("unknown", "unknown-model", 999_999_999L, 999_999_999L)
            }
            guard.currentSpendUsd shouldBe 0.0
        }

        // ── Test 5: thread-safety — concurrent recordUsage ────────────────────

        test("concurrent recordUsage accumulates spend correctly without data races") {
            val guard = BudgetGuard(null, makeEstimator(gpt4oEntry()))
            val threads = 10
            val callsPerThread = 100
            // Each call: 100 input tokens = 100 * 5.0 / 1_000_000 = 0.0005 USD input
            // + 0 output → total per call = 0.0005 USD
            // threads * callsPerThread * 0.0005 = 10 * 100 * 0.0005 = 0.5 USD

            runBlocking {
                (1..threads)
                    .map {
                        async(Dispatchers.Default) {
                            repeat(callsPerThread) {
                                guard.recordUsage("openai", "gpt-4o", 100L, 0L)
                            }
                        }
                    }.awaitAll()
            }

            val expected = threads * callsPerThread * (100.0 * 5.0 / 1_000_000.0)
            guard.currentSpendUsd shouldBe (expected plusOrMinus 1e-9)
        }

        // ── Test 6: reset() zeroes spend ──────────────────────────────────────

        test("reset zeroes accumulated spend") {
            val guard = BudgetGuard(null, makeEstimator(gpt4oEntry()))
            guard.recordUsage("openai", "gpt-4o", 1_000L, 0L)
            guard.currentSpendUsd shouldBe (0.005 plusOrMinus 1e-9)
            guard.reset()
            guard.currentSpendUsd shouldBe 0.0
        }

        // ── Test 7: limitUsd exposed correctly ────────────────────────────────

        test("limitUsd reflects the configured budget") {
            val guardWithBudget = BudgetGuard(1.50, makeEstimator())
            guardWithBudget.limitUsd shouldBe 1.50

            val guardUnlimited = BudgetGuard(null, makeEstimator())
            guardUnlimited.limitUsd shouldBe null
        }

        // ── Test 8: BudgetExceeded message contains spend and limit markers ───

        test("BudgetExceeded exception message contains KUML-AI-E-006 code and USD markers") {
            val guard = BudgetGuard(1.00, makeEstimator(gpt4oEntry()))
            val ex =
                shouldThrow<KumlAiException.BudgetExceeded> {
                    // 1M input at $5/MTok = $5.00 > $1.00 limit
                    guard.recordUsage("openai", "gpt-4o", 1_000_000L, 0L)
                }
            // Message should contain the error code
            ex.message!!.contains("KUML-AI-E-006") shouldBe true
            // And USD markers — spent formatted as %.4f (e.g. "$5.0000") and limit as %.2f ("$1.00")
            ex.message!!.contains("$") shouldBe true
            ex.spentUsd shouldBe (5.0 plusOrMinus 1e-9)
            ex.budgetUsd shouldBe 1.00
        }
    })
