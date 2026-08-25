package dev.kuml.desktop.ai

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.PatchApplyEngine
import dev.kuml.ai.tools.registry.KumlToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
 * — zero behaviour change for existing tests/users. Note (V3.7.5): the orchestrator path
 * does not emit [AgentEvent.TokenUsage] either — if [useOrchestration] is ever activated,
 * this same token-usage-counter bug would silently resurface there. Wire
 * [emitTokenUsageIfKnown]-equivalent handling into [KumlAgentOrchestrator]/
 * [KumlSpecialistAgent] before flipping that flag on for real users.
 *
 * V3.1.20: Updated for Koog 1.0.0 — execute() now returns Message.Assistant directly.
 * Tool calls moved from top-level Message.Tool.Call to MessagePart.Tool.Call inside
 * Message.Assistant.parts. executorFn type updated accordingly.
 *
 * V3.2.x — real tool-calling (Fund 4, design review): the direct (non-orchestrated) path
 * below now dispatches with [KumlToolRegistry.forUml] and REALLY executes every tool call
 * the model makes — each `@Tool` method (see [dev.kuml.ai.tools.uml.UmlEditingTools]) already
 * calls `ctx.applyPatch(...)` itself, so [editingContext] is mutated for real inside the tool
 * call, not just decoded into a buffered [ModelPatch] guess. The old
 * [PatchDecoder]/[patchEngine] buffering path is therefore no longer invoked from here — it
 * stays wired only for the (currently never-activated) `useOrchestration = true` branch below,
 * which still calls [KumlAgentOrchestrator] unchanged. Runs a bounded tool-call loop
 * ([MAX_TOOL_ROUNDS]) so the model can inspect a tool's result and issue a follow-up call.
 *
 * @param executorFn Injectable execution function for testing — defaults to using [executor].
 *   Deliberately still 2-arg (no `tools` parameter): tests inject a fixed stubbed response per
 *   round regardless of which tools were offered.
 */
class AgentRunner(
    private val executor: KumlAiExecutor,
    private val providerId: String,
    private val modelId: String,
    /** V3.0.25: enables tool-call patch decoding when provided together with [patchEngine]. */
    private val editingContext: AgentEditingContext? = null,
    /** V3.0.25: buffers decoded [ModelPatch] instances when provided together with [editingContext]. */
    private val patchEngine: PatchApplyEngine? = null,
    /** V3.1.18: when true, routes through [KumlAgentOrchestrator] instead of single-turn. */
    private val useOrchestration: Boolean = false,
    /** Test-only: override the execution function. Default uses [executor]. */
    internal val executorFn: (suspend (Prompt, LLModel) -> Message.Assistant)? = null,
) {
    private val registry = ProviderRegistry.builtIns()

    // V3.1.18: patch decoding extracted to shared PatchDecoder — kept for the orchestrator
    // path and for existing decodePatch unit tests. NOT called from the direct tool-loop below.
    private val decoder = PatchDecoder(editingContext)

    private val jsonSerializer: JSONSerializer = KotlinxSerializer()

    fun runConversation(history: List<ConversationMessage>): Flow<AgentEvent> =
        flow {
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
                val model =
                    resolveModel() ?: run {
                        emit(AgentEvent.Error(IllegalArgumentException("Cannot resolve model '$modelId' for provider '$providerId'")))
                        return@flow
                    }

                // V3.2.x — real tool-calling: only UML editing/inspection tools are offered.
                // KumlToolRegistry.full() also pulls in C4/SysML2 tools whose ScriptSerializer
                // paths are still TODO stubs (see ScriptSerializer.kt) — offering tools whose
                // result can never be written back to the script would be a second lie in the
                // UI (design review). withMcpBridge() is also a documented no-op — not worth
                // the surface either.
                val toolRegistry: ToolRegistry? = editingContext?.let { KumlToolRegistry.forUml(it) }
                val toolDescriptors = toolRegistry?.tools?.map { it.descriptor } ?: emptyList()

                var koogPrompt = buildPrompt(history = history, systemPrompt = executor.currentSettings().systemPrompt)
                var toolRound = 0

                while (true) {
                    val response =
                        if (executorFn != null) {
                            executorFn.invoke(koogPrompt, model)
                        } else {
                            executor.execute(prompt = koogPrompt, model = model, tools = toolDescriptors)
                        }

                    // textContent() collapses all text MessageParts into a single String.
                    val fullText = response.textContent()
                    if (fullText.isNotBlank()) {
                        emit(AgentEvent.AssistantDelta(delta = fullText, providerId = providerId, modelId = modelId))
                    }
                    emitTokenUsageIfKnown(response.metaInfo)

                    // Koog 1.0.0: tool calls are MessagePart.Tool.Call inside response.parts.
                    val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
                    if (toolCalls.isEmpty()) break

                    toolRound++
                    if (toolRound > MAX_TOOL_ROUNDS) {
                        emit(
                            AgentEvent.Error(
                                IllegalStateException(
                                    "Tool-call loop exceeded $MAX_TOOL_ROUNDS rounds — aborting to avoid " +
                                        "an infinite loop. The model may be stuck retrying a failing call.",
                                ),
                            ),
                        )
                        // Abort the whole turn here — falling through to the unconditional
                        // emit(Done) below would tell callers the turn finished normally right
                        // after telling them it didn't.
                        return@flow
                    }

                    var nextPrompt = prompt(koogPrompt) { message(response) }
                    for (tc in toolCalls) {
                        // FIX (Absturz "Key was already used"): der Schlüssel einer Anzeigeliste
                        // gehört der Anzeige, nicht dem Provider. Ollamas Tool-Call-IDs sind ein
                        // Hash über toolName:content:index — `index` läuft nur innerhalb EINER
                        // Antwort (siehe OllamaConverters.kt generateToolCallId), nicht über den
                        // gesamten Tool-Loop. Ruft das Modell in einer späteren Runde dasselbe
                        // Tool mit strukturell ähnlichen Argumenten auf, kollidiert der Hash exakt
                        // — dieselbe ID würde zweimal als LazyColumn-Key landen und Compose
                        // crashen. `tc.id` fließt NICHT in `callId` ein; Koogs eigenes
                        // Tool-Result-Matching liest `tc.id` unten ohnehin direkt aus `tc`, nie
                        // aus dieser Variablen.
                        val callId = UUID.randomUUID().toString()
                        emit(
                            AgentEvent.ToolCallStart(
                                callId = callId,
                                tool = tc.tool,
                                argsJson = tc.args,
                                providerCallId = tc.id,
                            ),
                        )

                        val (resultJson, isError) = executeRegisteredTool(registry = toolRegistry, tc = tc)

                        nextPrompt = prompt(nextPrompt) { toolResult(tool = tc.tool, id = tc.id, output = resultJson, isError = isError) }
                        emit(AgentEvent.ToolCallEnd(callId = callId, resultJson = resultJson, isError = isError))
                    }
                    koogPrompt = nextPrompt
                }

                emit(AgentEvent.Done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AgentEvent.Error(e))
            }
        }

    /**
     * Emits [AgentEvent.TokenUsage] when [metaInfo] carries real, known counts for THIS turn.
     *
     * Koog populates `ResponseMetaInfo.inputTokensCount`/`outputTokensCount` from each
     * provider's native usage field (OpenAI `usage.promptTokens`/`completionTokens`, Anthropic
     * `usage.inputTokens`/`outputTokens`, Google `usageMetadata.promptTokenCount`/
     * `candidatesTokenCount`, Ollama `promptEvalCount`/`evalCount`, Gonka via the shared
     * OpenAI-compatible base) — but EVERY provider can in principle omit either field on a given
     * response (both fields are declared nullable on [ResponseMetaInfo] itself). Treating a
     * missing count as "unknown, do not accumulate" is deliberate: silently accumulating 0 for a
     * real, non-zero turn would look pixel-identical to the original bug (a counter stuck at a
     * wrong number instead of a visibly-live one) and would be strictly worse than just not
     * updating this turn.
     *
     * Uses [AgentRunner]'s own [providerId]/[modelId] fields for the emitted event — NOT
     * [ResponseMetaInfo.modelId], which is provider-reported, may be `null`, and may not match
     * kUML's own model-id namespace. This mirrors the existing [AgentEvent.AssistantDelta]
     * emission two lines above.
     */
    private suspend fun FlowCollector<AgentEvent>.emitTokenUsageIfKnown(metaInfo: ResponseMetaInfo) {
        val inTok = metaInfo.inputTokensCount
        val outTok = metaInfo.outputTokensCount
        if (inTok == null || outTok == null) return
        emit(AgentEvent.TokenUsage(inTok = inTok, outTok = outTok, providerId = providerId, modelId = modelId))
    }

    /**
     * Really executes [tc] against a tool from [registry] (V3.2.x). Returns the tool's
     * JSON-encoded result string plus whether it represents an error — both feed directly
     * into [ai.koog.prompt.dsl.PromptBuilder.toolResult] for the next round.
     *
     * Never throws: an unknown tool name, malformed JSON args, or a tool-internal exception
     * are all turned into an `isError = true` JSON payload instead of crashing the turn —
     * the model gets a chance to see the failure and retry or give up gracefully.
     */
    @OptIn(InternalAgentToolsApi::class)
    private suspend fun executeRegisteredTool(
        registry: ToolRegistry?,
        tc: MessagePart.Tool.Call,
    ): Pair<String, Boolean> {
        val tool: ToolBase<*, *> =
            registry?.getToolOrNull(tc.tool)
                ?: return jsonError("Unknown tool '${tc.tool}' — it is not registered for this session.") to true
        return try {
            val rawArgs =
                jsonSerializer.decodeJSONElementFromString(tc.args) as? JSONObject
                    ?: return jsonError("Tool arguments for '${tc.tool}' are not a JSON object.") to true
            val args = tool.decodeArgs(rawArgs, jsonSerializer)
            val result = tool.executeUnsafe(args)
            tool.encodeResultToStringUnsafe(result, jsonSerializer) to false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation: on JVM, CancellationException IS an Exception, so a
            // blanket `catch (e: Exception)` below would otherwise turn a user-initiated stop
            // into a normal isError=true tool result and let the outer conversation loop issue
            // another (paid, external) LLM round-trip after cancellation was requested.
            throw e
        } catch (e: Exception) {
            jsonError(e.message ?: e.javaClass.simpleName) to true
        }
    }

    private fun jsonError(message: String): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message))),
        )

    /**
     * Decode the LLM tool name and JSON args into a [ModelPatch], or null if unknown.
     *
     * Delegates to [PatchDecoder] which was extracted in V3.1.18. Kept here for test
     * back-compat (AgentRunnerToolExecutionTest calls runner.decodePatch directly) and for
     * the [useOrchestration] path (which never calls [executeRegisteredTool] and still relies
     * on decoded-patch buffering — see the class KDoc's Fund 4 note).
     */
    internal fun decodePatch(
        toolName: String,
        argsJson: String,
    ): ModelPatch? = decoder.decode(toolName = toolName, argsJson = argsJson)

    private fun resolveModel(): LLModel? = registry.resolveModel(providerId = providerId, modelId = modelId)

    internal fun buildPrompt(
        history: List<ConversationMessage>,
        systemPrompt: String,
    ) = prompt("kuml-ai-chat") {
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
        /** Bounds the tool-call loop so a model stuck retrying a failing call cannot hang the turn forever. */
        private const val MAX_TOOL_ROUNDS = 6

        /** Converts a name to a plausible element ID candidate for patch buffering. */
        @Suppress("unused") // kept for callers that imported via AgentRunner.Companion
        fun String.toCandidateId(): String = this.lowercase().replace(Regex("[^a-z0-9]"), "_").trimEnd('_')
    }
}
