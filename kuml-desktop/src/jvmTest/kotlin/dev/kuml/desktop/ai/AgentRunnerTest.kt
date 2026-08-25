package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/** Build a Message.Assistant with text via builder.
 * Koog 1.0.0: use addText() instead of content().
 */
private fun assistantMsg(text: String): Message.Assistant = AssistantMessageBuilder().addText(text).build()

/**
 * Build a Message.Assistant with text AND a [ResponseMetaInfo] carrying token counts —
 * used by the V3.7.5 token-usage-wiring tests below. `totalTokensCount` defaults to
 * `inputTokensCount + outputTokensCount` when both are non-null, matching typical provider
 * behaviour, but is not itself read by the code under test (only inputTokensCount/
 * outputTokensCount feed AgentEvent.TokenUsage).
 */
private fun assistantMsgWithUsage(
    text: String,
    inputTokensCount: Int?,
    outputTokensCount: Int?,
    modelId: String? = null,
): Message.Assistant =
    AssistantMessageBuilder()
        .addText(text)
        .metaInfo(
            ResponseMetaInfo(
                timestamp = Clock.System.now(),
                totalTokensCount =
                    if (inputTokensCount != null && outputTokensCount != null) {
                        inputTokensCount + outputTokensCount
                    } else {
                        null
                    },
                inputTokensCount = inputTokensCount,
                outputTokensCount = outputTokensCount,
                modelId = modelId,
            ),
        ).build()

/**
 * Build a Message.Assistant containing a single MessagePart.Tool.Call AND a [ResponseMetaInfo]
 * carrying token counts — used by the multi-round token-usage test below.
 */
private fun assistantToolCallWithUsage(
    tool: String,
    argsJson: String,
    inputTokensCount: Int,
    outputTokensCount: Int,
    id: String = "tc-${tool.hashCode().toUInt()}",
): Message.Assistant =
    AssistantMessageBuilder()
        .addToolCall(MessagePart.Tool.Call(id = id, tool = tool, args = argsJson))
        .metaInfo(
            ResponseMetaInfo(
                timestamp = Clock.System.now(),
                totalTokensCount = inputTokensCount + outputTokensCount,
                inputTokensCount = inputTokensCount,
                outputTokensCount = outputTokensCount,
                modelId = null,
            ),
        ).build()

/**
 * Executor stub that returns a DIFFERENT response on each successive call (mirrors
 * AgentRunnerToolExecutionTest's sequencedExecutorFn — duplicated here to keep this file
 * self-contained rather than introducing a shared test-fixtures module for two call sites).
 */
private fun sequencedExecutorFn(vararg responses: Message.Assistant): suspend (Prompt, LLModel) -> Message.Assistant {
    val index = AtomicInteger(0)
    return { _, _ ->
        val i = index.getAndIncrement()
        if (i < responses.size) responses[i] else responses.last()
    }
}

/** Build a minimal KumlAiExecutor (ollama, no API key) using plain JSON fallback backend. */
private fun stubExecutor(settings: KumlAiSettings = KumlAiSettings(privacyMode = false)): KumlAiExecutor {
    // Use the "plain" backend override so no OS keychain is needed in tests
    System.setProperty("kuml.ai.vault.backend", "plain")
    val vault = ApiKeyVault.detect()
    return KumlAiExecutor.fromSettings(settings = settings, vault = vault)
}

class AgentRunnerTest :
    FunSpec({
        test("text response emits AssistantDelta then Done") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn = { _: Prompt, _: LLModel ->
                        assistantMsg("Hello from LLM")
                    },
                )
            val history = listOf(ConversationMessage.User(id = "u1", timestamp = 1000L, text = "Hi"))
            val events = runner.runConversation(history).toList()
            val deltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
            deltas.isNotEmpty() shouldBe true
            deltas.first().delta shouldBe "Hello from LLM"
            events.last().shouldBeInstanceOf<AgentEvent.Done>()
        }

        test("empty response (blank text, no tool calls) emits Done without crashing") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
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
            val runner =
                AgentRunner(
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
            val runner =
                AgentRunner(
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
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    useOrchestration = true,
                    executorFn = { _: Prompt, _: LLModel ->
                        when (step++) {
                            // Step 0: routing — emit route_to_specialist tool call
                            0 ->
                                AssistantMessageBuilder()
                                    .addToolCall(
                                        MessagePart.Tool.Call(
                                            id = "tc-route",
                                            tool = "route_to_specialist",
                                            args = """{"domain":"uml","reason":"UML class diagram request"}""",
                                        ),
                                    ).build()
                            // Step 1: specialist
                            1 -> assistantMsg("I will add the class.")
                            // Step 2: synthesis
                            else -> assistantMsg("Class has been added to your diagram.")
                        }
                    },
                )

            val history = listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "Add a UML class"))
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

        // ── V3.7.5 — token-usage counter dead-wiring fix ──────────────────────

        test("realistic metaInfo emits TokenUsage with correct counts and providerId/modelId") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn = { _: Prompt, _: LLModel ->
                        // metaInfo.modelId deliberately differs from the runner's own modelId —
                        // the emitted event must use the runner's providerId/modelId, not this one.
                        assistantMsgWithUsage(
                            text = "Hello from LLM",
                            inputTokensCount = 10,
                            outputTokensCount = 20,
                            modelId = "llama3.2-provider-reported-string",
                        )
                    },
                )
            val history = listOf(ConversationMessage.User(id = "u1", timestamp = 1000L, text = "Hi"))
            val events = runner.runConversation(history).toList()

            val usageEvents = events.filterIsInstance<AgentEvent.TokenUsage>()
            usageEvents shouldHaveSize 1
            usageEvents.first().apply {
                inTok shouldBe 10
                outTok shouldBe 20
                providerId shouldBe "ollama"
                modelId shouldBe "llama3.2"
            }

            // Ordering: AssistantDelta -> TokenUsage -> Done
            events.map { it::class } shouldBe
                listOf(AgentEvent.AssistantDelta::class, AgentEvent.TokenUsage::class, AgentEvent.Done::class)
        }

        test("null inputTokensCount does not emit TokenUsage — no crash, no false-zero accumulate") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn = { _: Prompt, _: LLModel ->
                        assistantMsgWithUsage(text = "partial usage", inputTokensCount = null, outputTokensCount = 5)
                    },
                )
            val events = runner.runConversation(emptyList()).toList()
            events.filterIsInstance<AgentEvent.TokenUsage>() shouldHaveSize 0
            events.last().shouldBeInstanceOf<AgentEvent.Done>()
        }

        test("null outputTokensCount does not emit TokenUsage — no crash, no false-zero accumulate") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn = { _: Prompt, _: LLModel ->
                        assistantMsgWithUsage(text = "partial usage", inputTokensCount = 5, outputTokensCount = null)
                    },
                )
            val events = runner.runConversation(emptyList()).toList()
            events.filterIsInstance<AgentEvent.TokenUsage>() shouldHaveSize 0
            events.last().shouldBeInstanceOf<AgentEvent.Done>()
        }

        test("both counts null does not emit TokenUsage — no crash, no false-zero accumulate") {
            val executor = stubExecutor()
            val runner =
                AgentRunner(
                    executor = executor,
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn = { _: Prompt, _: LLModel ->
                        assistantMsgWithUsage(text = "no usage info at all", inputTokensCount = null, outputTokensCount = null)
                    },
                )
            val events = runner.runConversation(emptyList()).toList()
            events.filterIsInstance<AgentEvent.TokenUsage>() shouldHaveSize 0
            events.last().shouldBeInstanceOf<AgentEvent.Done>()
        }

        test("multi-round tool-call loop emits one TokenUsage per round") {
            val runner =
                AgentRunner(
                    executor = stubExecutor(),
                    providerId = "ollama",
                    modelId = "llama3.2",
                    executorFn =
                        sequencedExecutorFn(
                            assistantToolCallWithUsage(
                                tool = "totally_unknown_tool",
                                argsJson = """{}""",
                                inputTokensCount = 100,
                                outputTokensCount = 15,
                                id = "tc-1",
                            ),
                            assistantToolCallWithUsage(
                                tool = "totally_unknown_tool",
                                argsJson = """{}""",
                                inputTokensCount = 130,
                                outputTokensCount = 22,
                                id = "tc-2",
                            ),
                            assistantMsgWithUsage(text = "Done.", inputTokensCount = 150, outputTokensCount = 8),
                        ),
                )
            val events =
                runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            val usageEvents = events.filterIsInstance<AgentEvent.TokenUsage>()
            usageEvents shouldHaveSize 3
            usageEvents.map { it.inTok to it.outTok } shouldBe listOf(100 to 15, 130 to 22, 150 to 8)
            events.last().shouldBeInstanceOf<AgentEvent.Done>()
        }
    })
