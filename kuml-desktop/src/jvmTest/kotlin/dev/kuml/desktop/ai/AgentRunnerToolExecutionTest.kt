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
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.patch.aitrace.NoopAiTraceSink
import dev.kuml.ai.vault.ApiKeyVault
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun stubExecutor(): KumlAiExecutor {
    System.setProperty("kuml.ai.vault.backend", "plain")
    val vault = ApiKeyVault.detect()
    return KumlAiExecutor.fromSettings(settings = KumlAiSettings(privacyMode = false), vault = vault)
}

/**
 * Build a Message.Assistant containing a single MessagePart.Tool.Call.
 * Koog 1.0.0: tool calls are MessagePart.Tool.Call inside Message.Assistant.parts.
 */
private fun assistantWithToolCall(
    tool: String,
    argsJson: String,
    id: String = "tc-${tool.hashCode().toUInt()}",
): Message.Assistant =
    AssistantMessageBuilder()
        .addToolCall(MessagePart.Tool.Call(id = id, tool = tool, args = argsJson))
        .build()

private fun assistantWithText(text: String): Message.Assistant = AssistantMessageBuilder().addText(text).build()

/**
 * Executor stub that returns a DIFFERENT response on each successive call — needed since
 * V3.2.x's runConversation() loops calling executorFn once per tool-call round (a fixed,
 * always-the-same response would either never terminate the loop, when it always returns a
 * tool call, or never test the loop at all, when it never does).
 */
private fun sequencedExecutorFn(vararg responses: Message.Assistant): suspend (Prompt, LLModel) -> Message.Assistant {
    val index = AtomicInteger(0)
    return { _, _ ->
        val i = index.getAndIncrement()
        if (i < responses.size) responses[i] else responses.last()
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class AgentRunnerToolExecutionTest :
    FunSpec({

        fun makeRunner(
            editingContext: AgentEditingContext?,
            engine: PatchApplyEngine?,
            executorFn: suspend (Prompt, LLModel) -> Message.Assistant,
        ): AgentRunner =
            AgentRunner(
                executor = stubExecutor(),
                providerId = "ollama",
                modelId = "llama3.2",
                editingContext = editingContext,
                patchEngine = engine,
                executorFn = executorFn,
            )

        // ── V3.2.x — real tool-calling: tool calls really mutate editingContext ─────

        test("add_class tool call really creates a class in the editing context") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    sequencedExecutorFn(
                        assistantWithToolCall(tool = "add_class", argsJson = """{"name":"Order"}"""),
                        assistantWithText("Done — added the Order class."),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            events.filterIsInstance<AgentEvent.ToolCallEnd>().apply {
                shouldHaveSize(1)
                first().isError shouldBe false
            }
            events.filterIsInstance<AgentEvent.Error>() shouldHaveSize 0

            val model = ctx.resolveModel() as AnyKumlModel.Uml
            val added = model.elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "Order" }
            added.shouldNotBeNull()

            // The legacy PatchDecoder/patchEngine buffer path is no longer used on the direct
            // (non-orchestrated) run path — nothing should have landed in the engine's buffer.
            engine.pendingPatchIds() shouldHaveSize 0
        }

        test("multiple tool calls across rounds: round 1 tool calls, round 2 plain text ends the loop") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val roundOneResponse =
                AssistantMessageBuilder()
                    .addToolCall(MessagePart.Tool.Call(id = "tc1", tool = "add_class", args = """{"name":"Order"}"""))
                    .build()
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    sequencedExecutorFn(
                        roundOneResponse,
                        assistantWithText("All done."),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            events.filterIsInstance<AgentEvent.ToolCallEnd>() shouldHaveSize 1
            events.filterIsInstance<AgentEvent.Done>() shouldHaveSize 1
            events.filterIsInstance<AgentEvent.Error>() shouldHaveSize 0

            val model = ctx.resolveModel() as AnyKumlModel.Uml
            model.elements.filterIsInstance<UmlClass>().map { it.name } shouldBe listOf("Order")
        }

        test("tool-call loop aborts with an Error after MAX_TOOL_ROUNDS repeated rounds") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            // Every round returns a fresh add_class call (different name so each one succeeds) —
            // the model never stops calling tools, so the runner's own round cap must kick in.
            var counter = 0
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    { _: Prompt, _: LLModel ->
                        counter++
                        assistantWithToolCall(tool = "add_class", argsJson = """{"name":"Class$counter"}""", id = "tc-$counter")
                    },
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            val errors = events.filterIsInstance<AgentEvent.Error>()
            errors shouldHaveSize 1
            val errorMessage = errors.first().throwable.message
            errorMessage.shouldNotBeNull()
            errorMessage shouldContain "Tool-call loop exceeded"
            events.filterIsInstance<AgentEvent.Done>() shouldHaveSize 0
        }

        test("unknown tool name does not crash — ToolCallEnd reports an error") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    sequencedExecutorFn(
                        assistantWithToolCall(tool = "totally_unknown_tool", argsJson = """{}"""),
                        assistantWithText("giving up"),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            val toolEnds = events.filterIsInstance<AgentEvent.ToolCallEnd>()
            toolEnds shouldHaveSize 1
            toolEnds.first().isError shouldBe true
            toolEnds.first().resultJson shouldContain "Unknown tool"
            events.filterIsInstance<AgentEvent.Error>() shouldHaveSize 0
        }

        test("malformed JSON tool args do not crash — ToolCallEnd reports an error") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    sequencedExecutorFn(
                        assistantWithToolCall(tool = "add_class", argsJson = """{NOT VALID JSON"""),
                        assistantWithText("giving up"),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            val toolEnds = events.filterIsInstance<AgentEvent.ToolCallEnd>()
            toolEnds shouldHaveSize 1
            toolEnds.first().isError shouldBe true
            events.filterIsInstance<AgentEvent.Error>() shouldHaveSize 0
        }

        test("without editingContext (null) tool calls are traced but reported as errors — no crash") {
            val runner =
                makeRunner(
                    null,
                    null,
                    sequencedExecutorFn(
                        assistantWithToolCall(tool = "add_class", argsJson = """{"name":"Order"}"""),
                        assistantWithText("no editing context available"),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            events.filterIsInstance<AgentEvent.PatchBuffered>() shouldHaveSize 0
            events.filterIsInstance<AgentEvent.ToolCallStart>() shouldHaveSize 1
            events.filterIsInstance<AgentEvent.ToolCallEnd>().apply {
                shouldHaveSize(1)
                first().isError shouldBe true
            }
        }

        // Regression test for the "Key was already used" crash (P1): Ollama's tool-call id is a
        // hash over toolName:content:index where `index` resets every response — so the SAME
        // provider id can legitimately recur across tool-loop rounds. callId (the UI/LazyColumn
        // key) must never collide even when the provider's raw id does.
        test("same provider tc.id across two rounds still yields distinct callId (collision-proof UI key)") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val collidingId = "tc-collision"
            val runner =
                makeRunner(
                    ctx,
                    engine,
                    sequencedExecutorFn(
                        assistantWithToolCall(tool = "add_class", argsJson = """{"name":"Order"}""", id = collidingId),
                        assistantWithToolCall(tool = "add_class", argsJson = """{"name":"Invoice"}""", id = collidingId),
                        assistantWithText("Done."),
                    ),
                )
            val events = runner.runConversation(listOf(ConversationMessage.User(id = "u1", timestamp = 1L, text = "test"))).toList()

            val starts = events.filterIsInstance<AgentEvent.ToolCallStart>()
            starts shouldHaveSize 2
            starts.map { it.callId }.distinct() shouldHaveSize 2
            starts.map { it.providerCallId } shouldBe listOf(collidingId, collidingId)
        }

        // ── decodePatch unit tests (PatchDecoder — unaffected by V3.2.x, still used by the
        // orchestrator path, see AgentRunner's class KDoc) ──────────────────────────────

        test("decodePatch: add_class returns AddElement with correct elementKind") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                AgentRunner(
                    executor = stubExecutor(),
                    providerId = "ollama",
                    modelId = "llama3.2",
                    editingContext = ctx,
                    patchEngine = engine,
                )
            val patch = runner.decodePatch(toolName = "add_class", argsJson = """{"name":"Customer"}""")
            patch.shouldNotBeNull()
            patch.shouldBeInstanceOf<ModelPatch.AddElement>()
            patch.elementKind shouldBe "uml.class"
            patch.name shouldBe "Customer"
        }

        test("decodePatch: unknown tool returns null") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                AgentRunner(
                    executor = stubExecutor(),
                    providerId = "ollama",
                    modelId = "llama3.2",
                    editingContext = ctx,
                    patchEngine = engine,
                )
            val patch = runner.decodePatch(toolName = "render_diagram", argsJson = """{}""")
            patch shouldBe null
        }

        test("decodePatch with broken JSON does not crash — returns null gracefully") {
            val ctx = AgentEditingContext(initialModel = AnyKumlModel.emptyUml())
            val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
            val runner =
                AgentRunner(
                    executor = stubExecutor(),
                    providerId = "ollama",
                    modelId = "llama3.2",
                    editingContext = ctx,
                    patchEngine = engine,
                )
            val patch = runner.decodePatch(toolName = "add_class", argsJson = """{INVALID""")
            patch shouldBe null
        }
    })
