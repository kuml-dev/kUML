package dev.kuml.desktop.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.patch.aitrace.NoopAiTraceSink
import dev.kuml.ai.vault.ApiKeyVault
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun stubExecutor(): KumlAiExecutor {
    System.setProperty("kuml.ai.vault.backend", "plain")
    val vault = ApiKeyVault.detect()
    return KumlAiExecutor.fromSettings(KumlAiSettings(privacyMode = false), vault)
}

/** Build a Message.Tool.Call using the (id, tool, content String, metaInfo) overload. */
private fun toolCall(tool: String, argsJson: String): Message.Tool.Call =
    Message.Tool.Call(
        id = "tc-${tool.hashCode().toUInt()}",
        tool = tool,
        content = argsJson,
        metaInfo = ResponseMetaInfo.Companion.Empty,
    )

/** Collect a list of buffered (not yet applied/rejected) patch IDs from the engine. */
private suspend fun PatchApplyEngine.drainPatchIds(): List<String> = pendingPatchIds()

// ── Tests ─────────────────────────────────────────────────────────────────────

class AgentRunnerToolExecutionTest : FunSpec({

    fun makeRunner(
        editingContext: AgentEditingContext,
        engine: PatchApplyEngine,
        vararg responses: Message.Response,
    ): AgentRunner {
        return AgentRunner(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = editingContext,
            patchEngine = engine,
            executorFn = { _: Prompt, _: LLModel -> responses.toList() },
        )
    }

    test("add_class tool call emits PatchBuffered and buffers AddElement patch") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = makeRunner(
            ctx, engine,
            toolCall("add_class", """{"name":"Order"}"""),
        )
        val events = runner.runConversation(listOf(ConversationMessage.User("u1", 1L, "test"))).toList()

        val patched = events.filterIsInstance<AgentEvent.PatchBuffered>()
        patched shouldHaveSize 1
        patched.first().kind shouldBe "AddElement"

        val ids = engine.drainPatchIds()
        ids shouldHaveSize 1
    }

    test("add_attribute tool call buffers UpdateAttribute patch") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = makeRunner(
            ctx, engine,
            toolCall("add_attribute", """{"classifierIdOrName":"Order","name":"id","type":"Long"}"""),
        )
        val events = runner.runConversation(listOf(ConversationMessage.User("u1", 1L, "test"))).toList()

        val patched = events.filterIsInstance<AgentEvent.PatchBuffered>()
        patched shouldHaveSize 1
        patched.first().kind shouldBe "UpdateAttribute"

        val ids = engine.drainPatchIds()
        ids shouldHaveSize 1
    }

    test("unknown tool does not emit PatchBuffered but emits ToolCallStart and ToolCallEnd") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = makeRunner(
            ctx, engine,
            toolCall("list_elements", """{}"""),
        )
        val events = runner.runConversation(listOf(ConversationMessage.User("u1", 1L, "test"))).toList()

        val patched = events.filterIsInstance<AgentEvent.PatchBuffered>()
        patched shouldHaveSize 0

        events.filterIsInstance<AgentEvent.ToolCallStart>() shouldHaveSize 1
        events.filterIsInstance<AgentEvent.ToolCallEnd>() shouldHaveSize 1
    }

    test("decodePatch with broken JSON does not crash — returns null gracefully") {
        // Note: Message.Tool.Call itself parses JSON in its constructor, so we can't
        // pass invalid JSON via toolCall(). Instead test decodePatch directly.
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = AgentRunner(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = ctx,
            patchEngine = engine,
        )
        // broken JSON → decodePatch should return null without throwing
        val patch = runner.decodePatch("add_class", """{INVALID""")
        patch shouldBe null
    }

    test("multiple tool calls in one response emit one PatchBuffered per decodable call") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = makeRunner(
            ctx, engine,
            toolCall("add_class", """{"name":"Order"}"""),
            toolCall("add_class", """{"name":"Item"}"""),
            toolCall("list_elements", """{}"""),   // unknown → no PatchBuffered
        )
        val events = runner.runConversation(listOf(ConversationMessage.User("u1", 1L, "test"))).toList()

        val patched = events.filterIsInstance<AgentEvent.PatchBuffered>()
        patched shouldHaveSize 2

        val ids = engine.drainPatchIds()
        ids shouldHaveSize 2
    }

    test("without patchEngine (null) V3.0.24 behavior is preserved — no PatchBuffered events") {
        val runner = AgentRunner(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = null,
            patchEngine = null,
            executorFn = { _: Prompt, _: LLModel ->
                listOf(toolCall("add_class", """{"name":"Order"}"""))
            },
        )
        val events = runner.runConversation(listOf(ConversationMessage.User("u1", 1L, "test"))).toList()

        events.filterIsInstance<AgentEvent.PatchBuffered>() shouldHaveSize 0
        // ToolCallStart/End still emitted (V3.0.24 trace behavior preserved)
        events.filterIsInstance<AgentEvent.ToolCallStart>() shouldHaveSize 1
        events.filterIsInstance<AgentEvent.ToolCallEnd>() shouldHaveSize 1
    }

    // ── decodePatch unit tests ─────────────────────────────────────────────────

    test("decodePatch: add_class returns AddElement with correct elementKind") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = AgentRunner(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = ctx,
            patchEngine = engine,
        )
        val patch = runner.decodePatch("add_class", """{"name":"Customer"}""")
        patch.shouldNotBeNull()
        patch.shouldBeInstanceOf<ModelPatch.AddElement>()
        (patch as ModelPatch.AddElement).elementKind shouldBe "uml.class"
        patch.name shouldBe "Customer"
    }

    test("decodePatch: unknown tool returns null") {
        val ctx = AgentEditingContext(AnyKumlModel.emptyUml())
        val engine = PatchApplyEngine(context = ctx, traceSink = NoopAiTraceSink)
        val runner = AgentRunner(
            executor = stubExecutor(),
            providerId = "ollama",
            modelId = "llama3.2",
            editingContext = ctx,
            patchEngine = engine,
        )
        val patch = runner.decodePatch("render_diagram", """{}""")
        patch shouldBe null
    }
})
