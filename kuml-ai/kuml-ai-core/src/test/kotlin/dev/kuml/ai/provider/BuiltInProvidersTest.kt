package dev.kuml.ai.provider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull

class BuiltInProvidersTest :
    FunSpec({

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
    })
