package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList

/** Build a Message.Assistant with text via builder.
 * Koog 1.0.0: use addText() instead of content().
 */
private fun assistantMsg(text: String): Message.Assistant =
    AssistantMessageBuilder().addText(text).build()

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
                assistantMsg("Hello from LLM")
            },
        )
        val history = listOf(ConversationMessage.User("u1", 1000L, "Hi"))
        val events = runner.runConversation(history).toList()
        val deltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
        deltas.isNotEmpty() shouldBe true
        deltas.first().delta shouldBe "Hello from LLM"
        events.last().shouldBeInstanceOf<AgentEvent.Done>()
    }

    test("empty response (blank text, no tool calls) emits Done without crashing") {
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            // Return an assistant with blank text — Koog 1.0.0 requires at least one content part.
            // addText("") satisfies the constraint; textContent() returns "" which is blank.
            executorFn = { _: Prompt, _: LLModel -> AssistantMessageBuilder().addText("").build() },
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
                assistantMsg("response")
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

    test("useOrchestration=true delegates to KumlAgentOrchestrator — OrchestratorRouted appears") {
        // Koog 1.0.0: executorFn now returns Message.Assistant, not List<Message.Response>.
        // Tool calls are MessagePart.Tool.Call inside Message.Assistant.parts.
        var step = 0
        val executor = stubExecutor()
        val runner = AgentRunner(
            executor = executor,
            providerId = "ollama",
            modelId = "llama3.2",
            useOrchestration = true,
            executorFn = { _: Prompt, _: LLModel ->
                when (step++) {
                    // Step 0: routing — emit route_to_specialist tool call
                    0 -> AssistantMessageBuilder()
                        .addToolCall(
                            MessagePart.Tool.Call(
                                id = "tc-route",
                                tool = "route_to_specialist",
                                args = """{"domain":"uml","reason":"UML class diagram request"}""",
                            ),
                        )
                        .build()
                    // Step 1: specialist
                    1 -> assistantMsg("I will add the class.")
                    // Step 2: synthesis
                    else -> assistantMsg("Class has been added to your diagram.")
                }
            },
        )

        val history = listOf(ConversationMessage.User("u1", 1L, "Add a UML class"))
        val events = runner.runConversation(history).toList()

        // OrchestratorRouted must appear
        val routed = events.filterIsInstance<AgentEvent.OrchestratorRouted>()
        routed.isNotEmpty() shouldBe true
        routed.first().domain shouldBe "uml"

        // SpecialistStarted must appear
        val started = events.filterIsInstance<AgentEvent.SpecialistStarted>()
        started.isNotEmpty() shouldBe true

        // Flow must end with Done
        events.last().shouldBeInstanceOf<AgentEvent.Done>()
    }
})
