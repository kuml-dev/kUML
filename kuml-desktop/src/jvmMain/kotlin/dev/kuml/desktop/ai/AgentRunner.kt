package dev.kuml.desktop.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.ProviderRegistry
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.PatchApplyEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * @param executorFn Injectable execution function for testing — defaults to using [executor].
 */
class AgentRunner(
    private val executor: KumlAiExecutor,
    private val providerId: String,
    private val modelId: String,
    private val editingContext: AgentEditingContext? = null,        // V3.0.25
    private val patchEngine: PatchApplyEngine? = null,              // V3.0.25
    /** Test-only: override the execution function. Default uses [executor]. */
    internal val executorFn: (suspend (Prompt, LLModel) -> List<Message.Response>)? = null,
) {
    private val registry = ProviderRegistry.builtIns()
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun runConversation(history: List<ConversationMessage>): Flow<AgentEvent> = flow {
        try {
            val model = resolveModel() ?: run {
                emit(AgentEvent.Error(IllegalArgumentException("Cannot resolve model '$modelId' for provider '$providerId'")))
                return@flow
            }

            val koogPrompt = buildPrompt(history, executor.currentSettings().systemPrompt)

            val responses = if (executorFn != null) {
                executorFn.invoke(koogPrompt, model)
            } else {
                executor.execute(koogPrompt, model)
            }

            val textParts = responses.filterIsInstance<Message.Assistant>()
            if (textParts.isNotEmpty()) {
                // Message.Assistant.content is a Koog extension property that collapses
                // all ContentPart.Text parts into a single String.
                val fullText = textParts.joinToString("") { it.content }
                if (fullText.isNotBlank()) {
                    emit(AgentEvent.AssistantDelta(fullText, providerId, modelId))
                }
            }

            // V3.0.25: Decode tool calls into ModelPatch and buffer.
            // Tool execution loop (full round-trip) deferred to V3.0.26+.
            val toolCalls = responses.filterIsInstance<Message.Tool.Call>()
            for (tc in toolCalls) {
                val callId = UUID.randomUUID().toString()
                // tc.tool = tool name, tc.contentJson = args as JsonObject
                emit(AgentEvent.ToolCallStart(callId, tc.tool, tc.contentJson.toString()))

                // V3.0.25: Attempt to decode and buffer the patch
                if (patchEngine != null) {
                    val patch = runCatching { decodePatch(tc.tool, tc.contentJson.toString()) }.getOrNull()
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
     * Mapping is based on the `@Tool(customName = ...)` registrations in
     * [dev.kuml.ai.tools.uml.UmlEditingTools].
     */
    internal fun decodePatch(toolName: String, argsJson: String): ModelPatch? {
        val diagramId = editingContext?.currentDiagramId
        val args: JsonObject = runCatching {
            lenientJson.parseToJsonElement(argsJson) as? JsonObject
        }.getOrNull() ?: return null

        fun str(key: String): String? = runCatching { args[key]?.jsonPrimitive?.content }.getOrNull()

        return when (toolName) {
            "add_class" -> {
                val name = str("name") ?: return null
                ModelPatch.AddElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementKind = "uml.class",
                    elementId = name.toCandidateId(),
                    name = name,
                    payload = buildMap {
                        str("stereotype")?.let { put("stereotype", it) }
                        str("isAbstract")?.let { put("isAbstract", it) }
                    },
                )
            }
            "add_interface" -> {
                val name = str("name") ?: return null
                ModelPatch.AddElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementKind = "uml.interface",
                    elementId = name.toCandidateId(),
                    name = name,
                )
            }
            "add_attribute" -> {
                val ownerId = str("classifierIdOrName") ?: return null
                val attrName = str("name") ?: return null
                val attrType = str("type") ?: "Any"
                ModelPatch.UpdateAttribute(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    ownerId = ownerId,
                    attributeId = "${ownerId}_${attrName}",
                    field = "add_attribute",
                    newValue = "$attrName: $attrType",
                )
            }
            "add_operation" -> {
                val ownerId = str("classifierIdOrName") ?: return null
                val opName = str("name") ?: return null
                ModelPatch.UpdateAttribute(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    ownerId = ownerId,
                    attributeId = "${ownerId}_${opName}_op",
                    field = "add_operation",
                    newValue = opName,
                )
            }
            "add_association" -> {
                val sourceId = str("sourceIdOrName") ?: return null
                val targetId = str("targetIdOrName") ?: return null
                ModelPatch.AddRelationship(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    relationshipKind = "uml.association",
                    relationshipId = "${sourceId}_assoc_${targetId}",
                    sourceId = sourceId,
                    targetId = targetId,
                )
            }
            "add_generalization" -> {
                val specificId = str("specificIdOrName") ?: return null
                val generalId = str("generalIdOrName") ?: return null
                ModelPatch.AddRelationship(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    relationshipKind = "uml.generalization",
                    relationshipId = "${specificId}_gen_${generalId}",
                    sourceId = specificId,
                    targetId = generalId,
                )
            }
            "remove_element" -> {
                val elementId = str("elementId") ?: return null
                ModelPatch.RemoveElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementId = elementId,
                )
            }
            "rename_element" -> {
                val elementId = str("elementId") ?: return null
                val newName = str("newName") ?: return null
                val oldName = str("currentName") ?: elementId
                ModelPatch.RenameElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementId = elementId,
                    oldName = oldName,
                    newName = newName,
                )
            }
            else -> null   // Unknown tool — no patch buffered
        }
    }

    private fun resolveModel(): LLModel? = registry.resolveModel(providerId, modelId)

    private fun buildPrompt(history: List<ConversationMessage>, systemPrompt: String) =
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
        private fun String.toCandidateId(): String =
            this.lowercase().replace(Regex("[^a-z0-9]"), "_").trimEnd('_')
    }
}
