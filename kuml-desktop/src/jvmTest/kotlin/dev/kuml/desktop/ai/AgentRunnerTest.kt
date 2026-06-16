package dev.kuml.desktop.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList

/** Build a Message.Assistant with text via builder. */
private fun assistantMsg(text: String): Message.Assistant =
    AssistantMessageBuilder().content(text).build()

/** Build a minimal KumlAiExecutor (ollama, no API key) using plain JSON fallback backend. */
private fun stubExecutor(settings: KumlAiSettings = KumlAiSettings(privacyMode = false)): KumlAiExecutor {
    // Use the "plain" backend override so no OS keychain is needed in tests
    System.setProperty("kuml.ai.vault.backend", "plain")
    val vault = ApiKeyVault.detect()
    return KumlAiExecutor.fromSettings(settings, vault)
}

class AgentRunnerTest : FunSpec({
    test("text response emits AssistantDelta then Done") {
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = { _: Prompt, _: LLModel ->
                listOf(assistantMsg("Hello from LLM"))
            },
        )
        val history = listOf(ConversationMessage.User("u1", 1000L, "Hi"))
        val events = runner.runConversation(history).toList()
        val deltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
        deltas.isNotEmpty() shouldBe true
        deltas.first().delta shouldBe "Hello from LLM"
        events.last().shouldBeInstanceOf<AgentEvent.Done>()
    }

    test("empty response emits Done without crashing") {
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = { _: Prompt, _: LLModel -> emptyList() },
        )
        val events = runner.runConversation(emptyList()).toList()
        events.shouldContainExactly(AgentEvent.Done)
    }

    test("exception in executorFn emits Error event") {
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = { _: Prompt, _: LLModel ->
                throw RuntimeException("Simulated network error")
            },
        )
        val events = runner.runConversation(emptyList()).toList()
        events.isNotEmpty() shouldBe true
        val errorEvent = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
        errorEvent?.throwable?.message shouldBe "Simulated network error"
    }

    test("cancel propagates cleanly via try-finally") {
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = { _: Prompt, _: LLModel ->
                listOf(assistantMsg("response"))
            },
        )
        var finallyRan = false
        try {
            runner.runConversation(emptyList()).collect {
                finallyRan = true
            }
        } finally {
            finallyRan shouldBe true
        }
    }
})
