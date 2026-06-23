package dev.kuml.ai.integration

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Live end-to-end test against a local Ollama instance.
 *
 * Prerequisites:
 *  - Ollama running at localhost:11434
 *  - llama3.2 model pulled: `ollama pull llama3.2`
 *
 * Run with:
 *   ./gradlew :kuml-ai:kuml-ai-core:test -DexcludeTags= -Dkuml.ai.test.live=true
 */
@Tags("live")
class EndToEndOllamaLiveTest :
    FunSpec({

        test("end to end prompt against local ollama instance returns non-empty response") {
            val liveEnabled = System.getProperty("kuml.ai.test.live") == "true"
            if (!liveEnabled) {
                println("Skipping live Ollama test — set -Dkuml.ai.test.live=true to enable")
                return@test
            }

            val settings =
                KumlAiSettings(
                    defaultProvider = "ollama",
                    privacyMode = true,
                )
            val vault = ApiKeyVault.detect()
            val executor = KumlAiExecutor.fromSettings(settings, vault)

            val testPrompt =
                prompt("e2e-test") {
                    user("Say exactly: 'kUML AI test OK'")
                }

            val ollamaModel = LLModel(LLMProvider.Ollama, "llama3.2")
            val response = executor.execute(testPrompt, ollamaModel)
            response.textContent().shouldNotBeBlank()
        }
    })
