package dev.kuml.desktop.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeZero
import io.kotest.matchers.shouldBe

class TokenUsageTrackerTest : FunSpec({
    val pricing = PricingTable.forTest(
        "test-provider" to listOf("test-model"),
    )
    // forTest uses inputPer1kTokens=0.001, outputPer1kTokens=0.002

    test("accumulate sums tokens correctly over multiple calls") {
        val tracker = TokenUsageTracker(pricing)
        tracker.accumulate("test-provider", "test-model", 100, 50)
        tracker.accumulate("test-provider", "test-model", 200, 100)
        tracker.tokensIn shouldBe 300
        tracker.tokensOut shouldBe 150
    }

    test("reset() resets all counters to zero") {
        val tracker = TokenUsageTracker(pricing)
        tracker.accumulate("test-provider", "test-model", 100, 50)
        tracker.reset()
        tracker.tokensIn shouldBe 0
        tracker.tokensOut shouldBe 0
        tracker.costUsd.shouldBeZero()
    }

    test("costUsd calculation is correct") {
        val tracker = TokenUsageTracker(pricing)
        // cost = (1000 * 0.001 + 500 * 0.002) / 1000 = (1.0 + 1.0) / 1000 = 0.002
        tracker.accumulate("test-provider", "test-model", 1000, 500)
        tracker.costUsd shouldBe 0.002
    }

    test("isBudgetExceeded returns false for null budget and true when exceeded") {
        val tracker = TokenUsageTracker(pricing)
        tracker.isBudgetExceeded(null) shouldBe false
        tracker.accumulate("test-provider", "test-model", 10000, 5000)
        tracker.isBudgetExceeded(0.0001) shouldBe true
        tracker.isBudgetExceeded(999.99) shouldBe false
    }
})
