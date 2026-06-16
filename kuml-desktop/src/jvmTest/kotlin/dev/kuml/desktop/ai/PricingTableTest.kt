package dev.kuml.desktop.ai

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeZero
import io.kotest.matchers.shouldBe

class PricingTableTest : FunSpec({
    test("loadFromResources() parses without exception") {
        shouldNotThrowAny {
            val table = PricingTable.loadFromResources()
            // Should have at least one provider
            val openAiModels = table.modelsForProvider("openai")
            openAiModels.isNotEmpty() shouldBe true
        }
    }

    test("modelsForProvider(\"unknown\") returns emptyList") {
        val table = PricingTable.loadFromResources()
        table.modelsForProvider("unknown-provider-xyz").shouldBeEmpty()
    }

    test("costUsd for gpt-4o with 1000 input + 500 output tokens is correct") {
        val table = PricingTable.loadFromResources()
        // gpt-4o: inputPer1kTokens=0.005, outputPer1kTokens=0.015
        // cost = (1000 * 0.005 + 500 * 0.015) / 1000 = (5.0 + 7.5) / 1000 = 0.0125
        val cost = table.costUsd("openai", "gpt-4o", 1000, 500)
        cost shouldBe 0.0125
    }

    test("costUsd for unknown model returns 0.0") {
        val table = PricingTable.loadFromResources()
        val cost = table.costUsd("openai", "unknown-model-xyz-9999", 100, 100)
        cost.shouldBeZero()
    }
})
