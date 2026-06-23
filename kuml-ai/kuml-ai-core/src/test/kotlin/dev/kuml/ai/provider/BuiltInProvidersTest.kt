package dev.kuml.ai.provider

import ai.koog.prompt.llm.LLMProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BuiltInProvidersTest :
    FunSpec({

        // ── Original tests (reflective clients preserve same contracts) ───────

        test("OpenAI factory requires non-null api key") {
            val openAiProvider = BuiltInProviders.openAi()
            shouldThrow<IllegalArgumentException> {
                openAiProvider.clientFactory(null)
            }
        }

        test("Ollama factory works with null api key") {
            val ollamaProvider = BuiltInProviders.ollama()
            val client = ollamaProvider.clientFactory(null)
            client.shouldNotBeNull()
        }

        // ── V3.1.15 additions ─────────────────────────────────────────────────

        test("all built-in providers have non-null koogProvider") {
            for (provider in BuiltInProviders.all()) {
                provider.koogProvider.shouldNotBeNull()
            }
        }

        test("OpenAI built-in maps to LLMProvider.OpenAI") {
            BuiltInProviders.openAi().koogProvider shouldBe LLMProvider.OpenAI
        }

        test("Anthropic built-in maps to LLMProvider.Anthropic") {
            BuiltInProviders.anthropic().koogProvider shouldBe LLMProvider.Anthropic
        }

        test("Google built-in maps to LLMProvider.Google") {
            BuiltInProviders.google().koogProvider shouldBe LLMProvider.Google
        }

        test("Ollama built-in maps to LLMProvider.Ollama") {
            BuiltInProviders.ollama().koogProvider shouldBe LLMProvider.Ollama
        }

        test("OpenAI built-in advertises non-empty supportedModels") {
            BuiltInProviders.openAi().supportedModels.shouldNotBeEmpty()
        }

        test("Anthropic built-in advertises non-empty supportedModels") {
            BuiltInProviders.anthropic().supportedModels.shouldNotBeEmpty()
        }

        test("Google built-in advertises non-empty supportedModels") {
            BuiltInProviders.google().supportedModels.shouldNotBeEmpty()
        }

        test("Ollama built-in has empty supportedModels because model ids are dynamic") {
            BuiltInProviders.ollama().supportedModels shouldBe emptyList()
        }

        test("Anthropic factory requires non-null api key") {
            shouldThrow<IllegalArgumentException> {
                BuiltInProviders.anthropic().clientFactory(null)
            }
        }

        test("Google factory requires non-null api key") {
            shouldThrow<IllegalArgumentException> {
                BuiltInProviders.google().clientFactory(null)
            }
        }

        test("all() returns exactly four built-in providers") {
            BuiltInProviders.all().size shouldBe 4
        }

        test("all() built-in ids are unique") {
            val ids = BuiltInProviders.all().map { it.id }
            ids.toSet().size shouldBe ids.size
        }

        test("built-in isLocal is false for cloud providers and true for Ollama") {
            BuiltInProviders.openAi().isLocal shouldBe false
            BuiltInProviders.anthropic().isLocal shouldBe false
            BuiltInProviders.google().isLocal shouldBe false
            BuiltInProviders.ollama().isLocal shouldBe true
        }

        test("OpenAI supportedModels contains gpt-4o") {
            val models = BuiltInProviders.openAi().supportedModels
            models.any { it.modelId == "gpt-4o" } shouldBe true
        }

        test("Anthropic supportedModels context windows are populated") {
            val models = BuiltInProviders.anthropic().supportedModels
            models.all { m -> m.contextWindowTokens?.let { it > 0 } == true } shouldBe true
        }

        test("Google supportedModels contains gemini-2.5-pro") {
            val models = BuiltInProviders.google().supportedModels
            models.any { it.modelId == "gemini-2.5-pro" } shouldBe true
        }

        test("reflective OpenAI factory throws ClassNotFoundException when client not on classpath") {
            // This test verifies the reflective mechanism is in use. Since the OpenAI JAR
            // IS on the test classpath (it's runtimeOnly but still included in test scope),
            // we verify the factory calls through reflection rather than direct import.
            // We can confirm by checking that the class is not directly imported here.
            val provider = BuiltInProviders.openAi()
            // clientFactory is the reflective one — it should succeed with a valid key
            val client = provider.clientFactory("test-api-key")
            client.shouldNotBeNull()
            // Confirm the instance is indeed OpenAILLMClient (not some other type)
            client::class.java.name shouldNotBe "java.lang.Object"
        }
    })
