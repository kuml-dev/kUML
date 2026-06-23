package dev.kuml.ai.spi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Stub implementation used in tests — no Koog on the classpath. */
private class StubClient

private class StubProvider : KumlLlmProviderSpi {
    override val id: String = "stub-provider"
    override val displayName: String = "Stub Provider"
    override val isLocalProvider: Boolean = false

    override fun supportedModels(): List<ModelDescriptor> =
        listOf(
            ModelDescriptor("stub-model-fast", "Stub Fast", contextWindowTokens = 8_192),
            ModelDescriptor("stub-model-large", "Stub Large", contextWindowTokens = 128_000),
        )

    override fun buildClient(
        apiKey: String,
        options: Map<String, String>,
    ): Any = StubClient()
}

class KumlLlmProviderSpiTest :
    FunSpec({

        test("stub provider returns expected id and displayName") {
            val spi: KumlLlmProviderSpi = StubProvider()
            spi.id shouldBe "stub-provider"
            spi.displayName shouldBe "Stub Provider"
            spi.isLocalProvider shouldBe false
        }

        test("stub provider supportedModels returns two descriptors") {
            val models = StubProvider().supportedModels()
            models shouldHaveSize 2
            models[0].modelId shouldBe "stub-model-fast"
            models[0].contextWindowTokens shouldBe 8_192
            models[1].modelId shouldBe "stub-model-large"
            models[1].contextWindowTokens shouldBe 128_000
        }

        test("buildClient returns a non-null Any without Koog types") {
            val client = StubProvider().buildClient("test-key", emptyMap())
            (client is StubClient) shouldBe true
        }

        test("ModelDescriptor defaults contextWindowTokens to null") {
            val d = ModelDescriptor("my-model", "My Model")
            d.contextWindowTokens shouldBe null
        }

        test("ModelDescriptor displayName is present") {
            val d = ModelDescriptor("gpt-4o", "GPT-4o", contextWindowTokens = 128_000)
            d.displayName shouldContain "GPT"
        }
    })
