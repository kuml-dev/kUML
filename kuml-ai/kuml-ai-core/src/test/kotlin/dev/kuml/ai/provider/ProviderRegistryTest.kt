package dev.kuml.ai.provider

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.spi.KumlLlmProviderSpi
import dev.kuml.ai.spi.ModelDescriptor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Minimal stub SPI provider used by discover tests. */
private class StubSpiProvider(
    override val id: String,
    override val displayName: String = "Stub $id",
    override val isLocalProvider: Boolean = false,
) : KumlLlmProviderSpi {
    override fun supportedModels(): List<ModelDescriptor> = emptyList()

    override fun buildClient(
        apiKey: String,
        options: Map<String, String>,
    ): Any = Any()
}

class ProviderRegistryTest :
    FunSpec({

        beforeEach { ProviderRegistry.resetCacheForTest() }
        afterEach { ProviderRegistry.resetCacheForTest() }

        // ── 1. builtIns() returns exactly the four built-ins ─────────────────

        test("builtIns contains exactly OpenAI Anthropic Google and Ollama") {
            val registry = ProviderRegistry.builtIns()
            val ids = registry.all().map { it.id }
            ids shouldContainExactlyInAnyOrder listOf("openai", "anthropic", "google", "ollama")
        }

        // ── 2. discover() with no custom providers equals builtIns() ─────────

        test("discover with empty iterable returns same ids as builtIns") {
            val discovered = ProviderRegistry.discoverFrom(emptyList())
            val builtIn = ProviderRegistry.builtIns()
            discovered.all().map { it.id }.toSet() shouldBe builtIn.all().map { it.id }.toSet()
        }

        // ── 3. discover() includes a custom provider with a new id ───────────

        test("discoverFrom includes custom provider with unique id") {
            val custom = StubSpiProvider(id = "my-custom-llm")
            val registry = ProviderRegistry.discoverFrom(listOf(custom))
            val ids = registry.all().map { it.id }
            ids shouldContainExactlyInAnyOrder listOf("openai", "anthropic", "google", "ollama", "my-custom-llm")

            val found = registry.get("my-custom-llm")
            found.shouldNotBeNull()
            found.displayName shouldBe "Stub my-custom-llm"
            found.koogProvider.shouldBeNull() // custom — no sealed LLMProvider instance
        }

        // ── 4. built-in WINS on id collision (V3.1.15 rule) ─────────────────

        test("built-in wins when custom SPI declares the same id as a built-in") {
            // Pre-V3.1.15 custom providers could override built-ins; now built-ins win.
            val impostor = StubSpiProvider(id = "openai", displayName = "Fake OpenAI")
            val registry = ProviderRegistry.discoverFrom(listOf(impostor))

            // Registry still has exactly 4 providers — the impostor was ignored
            val ids = registry.all().map { it.id }
            ids shouldContainExactlyInAnyOrder listOf("openai", "anthropic", "google", "ollama")

            // The "openai" entry is the real built-in, not the impostor
            val openAi = registry.get("openai")
            openAi.shouldNotBeNull()
            openAi.displayName shouldBe "OpenAI" // real built-in display name
            openAi.koogProvider shouldBe LLMProvider.OpenAI // built-in has non-null koogProvider
        }

        // ── 5. discover() caches — two calls return the same instance ────────

        test("discover caches the result across calls") {
            val first = ProviderRegistry.discover()
            val second = ProviderRegistry.discover()
            (first === second) shouldBe true
        }

        // ── 6. resetCacheForTest() produces a fresh instance ─────────────────

        test("resetCacheForTest produces a fresh registry on next discover call") {
            val first = ProviderRegistry.discover()
            ProviderRegistry.resetCacheForTest()
            val second = ProviderRegistry.discover()
            // Different instances after reset (identity check)
            (first === second) shouldBe false
        }

        // ── 7. byKoogProvider returns null for unknown sealed type ───────────

        test("byKoogProvider returns null for unknown koog provider type") {
            val registry = ProviderRegistry.builtIns()
            registry.byKoogProvider(LLMProvider.MistralAI).shouldBeNull()
        }

        // ── 8. resolveModel returns null for custom (null koogProvider) ──────

        test("resolveModel returns null for custom provider with null koogProvider") {
            val custom = StubSpiProvider(id = "custom-null-koog")
            val registry = ProviderRegistry.discoverFrom(listOf(custom))
            registry.resolveModel("custom-null-koog", "some-model").shouldBeNull()
        }

        // ── 9. multiple custom providers all registered when ids are unique ───

        test("discoverFrom registers multiple custom providers with unique ids") {
            val customA = StubSpiProvider(id = "acme-fast")
            val customB = StubSpiProvider(id = "acme-slow")
            val registry = ProviderRegistry.discoverFrom(listOf(customA, customB))
            val ids = registry.all().map { it.id }
            ids shouldContainExactlyInAnyOrder listOf("openai", "anthropic", "google", "ollama", "acme-fast", "acme-slow")
        }
    })
