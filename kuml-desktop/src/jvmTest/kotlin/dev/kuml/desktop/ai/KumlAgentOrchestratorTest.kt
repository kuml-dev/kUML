package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AssistantMessageBuilder
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.patch.aitrace.NoopAiTraceSink
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun stubExecutor(): KumlAiExecutor {
    System.setProperty("kuml.ai.vault.backend", "plain")
    val vault = ApiKeyVault.detect()
    return KumlAiExecutor.fromSettings(KumlAiSettings(privacyMode = false), vault)
}

// Koog 1.0.0: AssistantMessageBuilder uses addText() (not .content())
private fun assistantMsg(text: String): Message.Assistant =
    AssistantMessageBuilder().addText(text).build()

// Koog 1.0.0: tool calls are MessagePart.Tool.Call inside Message.Assistant.parts
private fun assistantWithToolCall(tool: String, argsJson: String): Message.Assistant =
    AssistantMessageBuilder()
        .addToolCall(MessagePart.Tool.Call(id = "tc-${tool.hashCode().toUInt()}", tool = tool, args = argsJson))
        .build()

/**
 * Build a three-step stub executorFn: routing / specialist / synthesis responses.
 * Each call increments a step counter. Extra calls beyond step 3 repeat synthesis.
 *
 * Koog 1.0.0: executorFn returns Message.Assistant (not List<Message.Response>).
 */
private fun threeStepExecutor(
    routingResponse: Message.Assistant,
    specialistResponse: Message.Assistant,
    synthesisResponse: Message.Assistant,
): suspend (Prompt, LLModel) -> Message.Assistant {
    var step = 0
    return { _: Prompt, _: LLModel ->
        when (step++) {
            0 -> routingResponse
            1 -> specialistResponse
            else -> synthesisResponse
        }
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class KumlAgentOrchestratorTest : FunSpec({

    test("routing emits OrchestratorRouted then SpecialistStarted") {
        val history = listOf(ConversationMessage.User("u1", 1L, "Add a container to my C4 diagram"))

        val executorFn = threeStepExecutor(
            routingResponse = assistantWithToolCall(
                "route_to_specialist",
                """{"domain":"c4","reason":"C4 container request"}""",
            ),
            specialistResponse = assistantMsg("Added container."),
            synthesisResponse = assistantMsg("Done! A container was added to your C4 diagram."),
        )

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = executorFn,
        ).runConversation(history).toList()

        val routed = events.filterIsInstance<AgentEvent.OrchestratorRouted>()
        routed shouldHaveSize 1
        routed.first().domain shouldBe "c4"
        routed.first().reason shouldBe "C4 container request"

        val started = events.filterIsInstance<AgentEvent.SpecialistStarted>()
        started shouldHaveSize 1
        started.first().domain shouldBe "c4"

        // OrchestratorRouted must come before SpecialistStarted
        val routedIdx = events.indexOf(routed.first())
        val startedIdx = events.indexOf(started.first())
        (routedIdx < startedIdx) shouldBe true
    }

    test("specialist buffers a UML patch — add_class emits PatchBuffered") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val history = listOf(ConversationMessage.User("u1", 1L, "Add class Order to the UML diagram"))

        val executorFn = threeStepExecutor(
            routingResponse = assistantWithToolCall(
                "route_to_specialist",
                """{"domain":"uml","reason":"UML class request"}""",
            ),
            specialistResponse = AssistantMessageBuilder()
                .addToolCall(MessagePart.Tool.Call(id = "tc-add", tool = "add_class", args = """{"name":"Order"}"""))
                .addText("I added class Order.")
                .build(),
            synthesisResponse = assistantMsg("Class Order has been added to your diagram."),
        )

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = ctx,
            patchEngine = engine,
            executorFn = executorFn,
        ).runConversation(history).toList()

        val patched = events.filterIsInstance<AgentEvent.PatchBuffered>()
        patched shouldHaveSize 1
        patched.first().kind shouldBe "AddElement"

        val ids = engine.pendingPatchIds()
        ids shouldHaveSize 1
    }

    test("domain allow-list filters foreign tools — add_class ignored when routed to c4") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val history = listOf(ConversationMessage.User("u1", 1L, "Add something"))

        val executorFn = threeStepExecutor(
            routingResponse = assistantWithToolCall(
                "route_to_specialist",
                """{"domain":"c4","reason":"architecture"}""",
            ),
            // specialist returns a UML tool call — should be filtered out
            specialistResponse = assistantWithToolCall("add_class", """{"name":"ShouldBeFiltered"}"""),
            synthesisResponse = assistantMsg("Done."),
        )

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = ctx,
            patchEngine = engine,
            executorFn = executorFn,
        ).runConversation(history).toList()

        // ToolCallStart/End still emitted, but no PatchBuffered
        events.filterIsInstance<AgentEvent.ToolCallStart>() shouldHaveSize 1
        events.filterIsInstance<AgentEvent.ToolCallEnd>() shouldHaveSize 1
        events.filterIsInstance<AgentEvent.PatchBuffered>() shouldHaveSize 0

        engine.pendingPatchIds() shouldHaveSize 0
    }

    test("synthesis text appears as AssistantDelta and sequence ends with Done") {
        val history = listOf(ConversationMessage.User("u1", 1L, "Summarise my diagram"))

        val executorFn = threeStepExecutor(
            routingResponse = assistantWithToolCall(
                "route_to_specialist",
                """{"domain":"uml","reason":"uml question"}""",
            ),
            specialistResponse = assistantMsg("The diagram has 3 classes."),
            synthesisResponse = assistantMsg("Your UML diagram contains 3 classes."),
        )

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = executorFn,
        ).runConversation(history).toList()

        val deltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
        deltas.isNotEmpty() shouldBe true
        deltas.last().delta shouldBe "Your UML diagram contains 3 classes."

        events.last().shouldBeInstanceOf<AgentEvent.Done>()
    }

    test("routing fallback — no tool call, no parseable domain, routes to MIXED") {
        val history = listOf(ConversationMessage.User("u1", 1L, "Help me with my diagram"))

        val executorFn = threeStepExecutor(
            // No tool call, ambiguous text
            routingResponse = assistantMsg("I will help you."),
            specialistResponse = assistantMsg("Sure, let me assist."),
            synthesisResponse = assistantMsg("I can help with your diagram!"),
        )

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = executorFn,
        ).runConversation(history).toList()

        val routed = events.filterIsInstance<AgentEvent.OrchestratorRouted>()
        routed shouldHaveSize 1
        routed.first().domain shouldBe "mixed"

        events.last().shouldBeInstanceOf<AgentEvent.Done>()
    }

    test("error in routing step emits single AgentEvent.Error with message preserved") {
        val history = listOf(ConversationMessage.User("u1", 1L, "Add something"))

        var step = 0
        val executorFn: suspend (Prompt, LLModel) -> Message.Assistant = { _, _ ->
            if (step++ == 0) throw RuntimeException("Simulated routing failure")
            assistantMsg("ok")
        }

        val events = KumlAgentOrchestrator(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            executorFn = executorFn,
        ).runConversation(history).toList()

        val errors = events.filterIsInstance<AgentEvent.Error>()
        errors shouldHaveSize 1
        errors.first().throwable.message shouldBe "Simulated routing failure"
    }

    test("cancellation rethrows CancellationException cleanly") {
        val history = listOf(ConversationMessage.User("u1", 1L, "Add something"))

        val executorFn: suspend (Prompt, LLModel) -> Message.Assistant = { _, _ ->
            throw kotlinx.coroutines.CancellationException("test cancel")
        }

        var caughtCancel = false
        try {
            KumlAgentOrchestrator(
                executor = stubExecutor(),
                providerId = "ollama",
                modelId = "llama3.2",
                executorFn = executorFn,
            ).runConversation(history).collect {}
        } catch (e: kotlinx.coroutines.CancellationException) {
            caughtCancel = true
        }
        caughtCancel shouldBe true
    }
})
