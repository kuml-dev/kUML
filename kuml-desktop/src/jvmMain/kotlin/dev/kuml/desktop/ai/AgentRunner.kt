package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.ProviderRegistry
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.desktop.ai.PatchDecoder.Companion.toCandidateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Drives one conversation turn: builds a Koog Prompt from the conversation history,
 * dispatches it via [KumlAiExecutor], and emits [AgentEvent] instances.
 *
 * V3.0.24: Tool-call traces are displayed in the UI but not yet executed against
 * the KumlToolRegistry. Full tool-loop integration is V3.0.25.
 *
 * V3.0.25: When [editingContext] and [patchEngine] are provided, known tool calls
 * are decoded into [ModelPatch] instances and buffered via [patchEngine].
 * [AgentEvent.PatchBuffered] is emitted for each buffered patch.
 *
 * V3.1.18: When [useOrchestration] is true, delegates to [KumlAgentOrchestrator]
 * which routes to domain-specialist agents before running synthesis. Default is false
 * — zero behaviour change for existing tests/users.
 *
 * V3.1.20: Updated for Koog 1.0.0 — execute() now returns Message.Assistant directly.
 * Tool calls moved from top-level Message.Tool.Call to MessagePart.Tool.Call inside
 * Message.Assistant.parts. executorFn type updated accordingly.
 *
 * @param executorFn Injectable execution function for testing — defaults to using [executor].
 */
class AgentRunner(
    private val executor: KumlAiExecutor,
    private val providerId: String,
    private val modelId: String,
    private val editingContext: AgentEditingContext? = null,        // V3.0.25
    private val patchEngine: PatchApplyEngine? = null,              // V3.0.25
    /** V3.1.18: when true, routes through [KumlAgentOrchestrator] instead of single-turn. */
    private val useOrchestration: Boolean = false,
    /** Test-only: override the execution function. Default uses [executor]. */
    internal val executorFn: (suspend (Prompt, LLModel) -> Message.Assistant)? = null,
) {
    private val registry = ProviderRegistry.builtIns()

    // V3.1.18: patch decoding extracted to shared PatchDecoder
    private val decoder = PatchDecoder(editingContext)

    fun runConversation(history: List<ConversationMessage>): Flow<AgentEvent> = flow {
        // V3.1.18: orchestration toggle — delegate to KumlAgentOrchestrator
        if (useOrchestration) {
            KumlAgentOrchestrator(
                executor = executor,
                providerId = providerId,
                modelId = modelId,
                editingContext = editingContext,
                patchEngine = patchEngine,
                executorFn = executorFn,
            ).runConversation(history).collect { emit(it) }
            return@flow
        }

        try {
            val model = resolveModel() ?: run {
                emit(AgentEvent.Error(IllegalArgumentException("Cannot resolve model '$modelId' for provider '$providerId'")))
                return@flow
            }

            val koogPrompt = buildPrompt(history, executor.currentSettings().systemPrompt)

            // Koog 1.0.0: execute() returns Message.Assistant directly.
            val response = if (executorFn != null) {
                executorFn.invoke(koogPrompt, model)
            } else {
                executor.execute(koogPrompt, model)
            }

            // textContent() collapses all text MessageParts into a single String.
            val fullText = response.textContent()
            if (fullText.isNotBlank()) {
                emit(AgentEvent.AssistantDelta(fullText, providerId, modelId))
            }

            // V3.0.25: Decode tool calls into ModelPatch and buffer.
            // Koog 1.0.0: tool calls are MessagePart.Tool.Call inside response.parts.
            val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            for (tc in toolCalls) {
                val callId = UUID.randomUUID().toString()
                // tc.tool = tool name, tc.argsJson = args as JsonObject
                emit(AgentEvent.ToolCallStart(callId, tc.tool, tc.argsJson.toString()))

                // V3.0.25: Attempt to decode and buffer the patch
                if (patchEngine != null) {
                    val patch = runCatching { decodePatch(tc.tool, tc.argsJson.toString()) }.getOrNull()
                    if (patch != null) {
                        runCatching { patchEngine.buffer(patch) }
                        emit(AgentEvent.PatchBuffered(patch.patchId, patch::class.simpleName ?: "unknown"))
                    }
                }

                emit(
                    AgentEvent.ToolCallEnd(
                        callId = callId,
                        resultJson = """{"note":"Tool execution in V3.0.26 — patch buffered in V3.0.25"}""",
                        isError = false,
                    ),
                )
            }

            emit(AgentEvent.Done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Error(e))
        }
    }

    /**
     * Decode the LLM tool name and JSON args into a [ModelPatch], or null if unknown.
     *
     * Delegates to [PatchDecoder] which was extracted in V3.1.18. Kept here for
     * test back-compat (AgentRunnerToolExecutionTest calls runner.decodePatch directly).
     */
    internal fun decodePatch(toolName: String, argsJson: String): ModelPatch? =
        decoder.decode(toolName, argsJson)

    private fun resolveModel(): LLModel? = registry.resolveModel(providerId, modelId)

    internal fun buildPrompt(history: List<ConversationMessage>, systemPrompt: String) =
        prompt("kuml-ai-chat") {
            system(systemPrompt)
            for (msg in history) {
                when (msg) {
                    is ConversationMessage.User -> user(msg.text)
                    is ConversationMessage.Assistant -> assistant(msg.text)
                    else -> {
                        // ToolCall / ToolResult / ErrorMessage are not included in Koog history
                    }
                }
            }
        }

    companion object {
        /** Converts a name to a plausible element ID candidate for patch buffering. */
        @Suppress("unused") // kept for callers that imported via AgentRunner.Companion
        fun String.toCandidateId(): String =
            this.lowercase().replace(Regex("[^a-z0-9]"), "_").trimEnd('_')
    }
}
