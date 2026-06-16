package dev.kuml.ai.provider

import ai.koog.prompt.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ProviderRegistryTest :
    FunSpec({

        test("builtIns contains exactly OpenAI Anthropic Google and Ollama") {
            val registry = ProviderRegistry.builtIns()
            val ids = registry.all().map { it.id }
            ids shouldContainExactlyInAnyOrder listOf("openai", "anthropic", "google", "ollama")
        }

        test("discover merges service loader entries with built ins") {
            // With no custom ProviderDescriptor implementations on the classpath,
            // discover() returns the same set as builtIns()
            val discovered = ProviderRegistry.discover()
            val builtIn = ProviderRegistry.builtIns()
            discovered.all().map { it.id }.toSet() shouldBe builtIn.all().map { it.id }.toSet()
        }

        test("byKoogProvider returns null for unknown koog provider type") {
            val registry = ProviderRegistry.builtIns()
            // LLMProvider.MistralAI is not in the built-in registry
            registry.byKoogProvider(LLMProvider.MistralAI).shouldBeNull()
        }
    })
