package dev.kuml.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import dev.kuml.ai.privacy.PrivacyEnforcer
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.ai.vault.PlainJsonFallbackBackend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files

/** Create a simple Message.Assistant with text content via builder. */
private fun assistantMessage(text: String): Message.Assistant = AssistantMessageBuilder().addText(text).build()

/**
 * Fake PromptExecutor for unit tests — does not call any real LLM.
 *
 * Koog 1.0.0: execute() returns Message.Assistant (not List<Message.Response>).
 */
private class FakePromptExecutor(
    private val responsesByModel: Map<LLModel, Message.Assistant> = emptyMap(),
    private val streamsByModel: Map<LLModel, Flow<StreamFrame>> = emptyMap(),
) : PromptExecutor() {
    override suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = responsesByModel[model] ?: assistantMessage("fake response")

    override fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = streamsByModel[model] ?: flowOf(StreamFrame.End("stop"))

    override suspend fun moderate(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
    ): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close(): Unit = Unit
}

private fun testRegistry(): ProviderRegistry = ProviderRegistry.builtIns()

class KumlAiExecutorTest :
    FunSpec({

        val ollamaModel = LLModel(LLMProvider.Ollama, "llama3.2")
        val openAiModel = LLModel(LLMProvider.OpenAI, "gpt-4o")

        test("execute dispatches to the configured default model") {
            val fakeExecutor =
                FakePromptExecutor(
                    responsesByModel =
                        mapOf(
                            ollamaModel to assistantMessage("hello from ollama"),
                        ),
                )
            val executor =
                KumlAiExecutor.forTest(
                    delegate = fakeExecutor,
                    settings = KumlAiSettings(defaultProvider = "ollama", privacyMode = true),
                    privacy = PrivacyEnforcer(privacyMode = true),
                    registry = testRegistry(),
                )
            val testPrompt = prompt("test") { user("hello") }
            val result = executor.execute(testPrompt, ollamaModel)
            result.textContent() shouldBe "hello from ollama"
        }

        test("execute throws PrivacyModeViolation when privacy mode is on and provider is Anthropic") {
            val executor =
                KumlAiExecutor.forTest(
                    delegate = FakePromptExecutor(),
                    settings = KumlAiSettings(privacyMode = true),
                    privacy = PrivacyEnforcer(privacyMode = true),
                    registry = testRegistry(),
                )
            val testPrompt = prompt("test") { user("hello") }
            val anthropicModel = LLModel(LLMProvider.Anthropic, "claude-sonnet-4-5")
            shouldThrow<KumlAiException.PrivacyModeViolation> {
                executor.execute(testPrompt, anthropicModel)
            }
        }

        test("executeStreaming emits stream frames in order from Ollama") {
            val fakeExecutor =
                FakePromptExecutor(
                    streamsByModel = mapOf(ollamaModel to flowOf(StreamFrame.End("stop"))),
                )
            val executor =
                KumlAiExecutor.forTest(
                    delegate = fakeExecutor,
                    settings = KumlAiSettings(privacyMode = true),
                    privacy = PrivacyEnforcer(privacyMode = true),
                    registry = testRegistry(),
                )
            val testPrompt = prompt("test") { user("hello") }
            val flow = executor.executeStreaming(testPrompt, ollamaModel)
            flow.shouldBeInstanceOf<Flow<StreamFrame>>()
        }

        test("executeStreaming throws PrivacyModeViolation eagerly when privacy mode is on") {
            val executor =
                KumlAiExecutor.forTest(
                    delegate = FakePromptExecutor(),
                    settings = KumlAiSettings(privacyMode = true),
                    privacy = PrivacyEnforcer(privacyMode = true),
                    registry = testRegistry(),
                )
            val testPrompt = prompt("test") { user("hello") }
            shouldThrow<KumlAiException.PrivacyModeViolation> {
                executor.executeStreaming(testPrompt, openAiModel)
            }
        }

        test("fromSettings throws when defaultProvider is unknown") {
            val settings = KumlAiSettings(defaultProvider = "nonexistent-provider", privacyMode = false)
            val tempDir = Files.createTempDirectory("kuml-vault-test")
            try {
                shouldThrow<KumlAiException.UnknownProvider> {
                    KumlAiExecutor.fromSettings(
                        settings = settings,
                        vault = ApiKeyVault(PlainJsonFallbackBackend(tempDir.resolve("secrets.json"))),
                        registry = testRegistry(),
                    )
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    })
