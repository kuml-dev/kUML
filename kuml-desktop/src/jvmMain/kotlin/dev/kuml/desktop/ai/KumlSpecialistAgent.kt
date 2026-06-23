package dev.kuml.desktop.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.patch.PatchApplyEngine
import java.util.UUID

/**
 * Runs one domain-scoped LLM turn and emits [AgentEvent] instances for tool calls / patches.
 *
 * V3.1.18: This is the specialist layer inside [KumlAgentOrchestrator]. It reuses
 * [PatchDecoder] and mirrors the tool-call loop from [AgentRunner] exactly — same
 * ToolCallStart → (optional PatchBuffered) → ToolCallEnd cadence — so that the
 * existing [AiPanelState.handleEvent] and [PatchPreviewDialog] flow requires no changes.
 *
 * Domain scoping is enforced via [AgentDomain.allowedToolNames]: tool calls whose
 * name is not in the allow-list are still traced (ToolCallStart/End) but their
 * patches are silently dropped (no PatchBuffered). This prevents, for example, a
 * UML `add_class` call from being applied when the orchestrator routed to `c4`.
 *
 * V3.1.20: Updated for Koog 1.0.0 — execute() returns Message.Assistant directly.
 * Tool calls are now MessagePart.Tool.Call inside Message.Assistant.parts;
 * argsJson replaces contentJson; textContent() replaces .content extension.
 *
 * @param executorFn Injectable execution function for testing — bypasses [executor] when set.
 */
internal class KumlSpecialistAgent(
    val domain: AgentDomain,
    private val executor: KumlAiExecutor,
    private val model: LLModel,
    private val providerId: String,
    private val modelId: String,
    private val editingContext: AgentEditingContext?,
    private val patchEngine: PatchApplyEngine?,
    private val decoder: PatchDecoder,
    private val executorFn: (suspend (Prompt, LLModel) -> Message.Assistant)? = null,
) {
    /**
     * Runs a single specialist turn.
     *
     * @param history conversation history to replay in the Koog prompt
     * @param emit callback to emit [AgentEvent] instances upstream
     * @return [SpecialistResult] with the assistant text and a list of patch kind strings for
     *         the synthesis prompt.
     */
    suspend fun run(
        history: List<ConversationMessage>,
        emit: suspend (AgentEvent) -> Unit,
    ): SpecialistResult {
        val systemPrompt = domainSystemPrompt(domain, executor.currentSettings().systemPrompt)
        val koogPrompt = buildPrompt(history, systemPrompt)

        // Koog 1.0.0: execute() returns Message.Assistant directly.
        val response = if (executorFn != null) {
            executorFn.invoke(koogPrompt, model)
        } else {
            executor.execute(koogPrompt, model)
        }

        // textContent() aggregates all text MessageParts.
        val assistantText = response.textContent()

        // Process tool calls, filtered by domain allow-list.
        // Koog 1.0.0: tool calls are MessagePart.Tool.Call inside response.parts.
        val bufferedPatchKinds = mutableListOf<String>()
        val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
        for (tc in toolCalls) {
            val callId = UUID.randomUUID().toString()
            emit(AgentEvent.ToolCallStart(callId, tc.tool, tc.argsJson.toString()))

            if (patchEngine != null && tc.tool in domain.allowedToolNames) {
                val patch = runCatching { decoder.decode(tc.tool, tc.argsJson.toString()) }.getOrNull()
                if (patch != null) {
                    val engine = patchEngine   // local val enables smart cast inside runCatching lambda
                    runCatching { engine.buffer(patch) }
                    val kind = patch::class.simpleName ?: "unknown"
                    bufferedPatchKinds += kind
                    emit(AgentEvent.PatchBuffered(patch.patchId, kind))
                }
            }

            emit(
                AgentEvent.ToolCallEnd(
                    callId = callId,
                    resultJson = """{"note":"Specialist turn V3.1.18 — domain=${domain.id}"}""",
                    isError = false,
                ),
            )
        }

        return SpecialistResult(assistantText = assistantText, bufferedPatchKinds = bufferedPatchKinds)
    }

    private fun buildPrompt(history: List<ConversationMessage>, systemPrompt: String) =
        prompt("kuml-specialist-${domain.id}") {
            system(systemPrompt)
            for (msg in history) {
                when (msg) {
                    is ConversationMessage.User -> user(msg.text)
                    is ConversationMessage.Assistant -> assistant(msg.text)
                    else -> {
                        // ToolCall / ToolResult / ErrorMessage not replayed
                    }
                }
            }
        }

    companion object {
        /**
         * Returns a domain-specific system prompt that scopes the specialist's focus.
         * The [baseSystemPrompt] from settings is included as context so the specialist
         * inherits any user-configured persona / constraints.
         */
        fun domainSystemPrompt(domain: AgentDomain, baseSystemPrompt: String): String {
            val domainScope = when (domain) {
                AgentDomain.UML ->
                    "You are a UML specialist. Focus exclusively on UML class diagrams, " +
                        "interfaces, attributes, operations, associations, and generalizations. " +
                        "Use only UML editing tools: add_class, add_interface, add_attribute, " +
                        "add_operation, add_association, add_generalization, remove_element, rename_element."
                AgentDomain.C4 ->
                    "You are a C4 architecture specialist. Focus on C4 model elements: " +
                        "persons, software systems, containers, components, and relationships. " +
                        "Use only C4 editing tools: add_person, add_software_system, add_container, " +
                        "add_component, add_relationship."
                AgentDomain.SYSML2 ->
                    "You are a SysML 2 specialist. Focus on SysML 2 elements: " +
                        "part definitions, attribute definitions, states, transitions, use cases, " +
                        "requirements, actions, and constraints. " +
                        "Use only SysML 2 editing tools: add_part_def, add_attribute_def, add_state, " +
                        "add_transition, add_use_case, add_requirement, add_action, add_constraint."
                AgentDomain.MIXED ->
                    "You are a multi-domain modelling assistant. You may use tools from UML, C4, " +
                        "and SysML 2 domains as needed to fulfil the request."
            }
            return "$domainScope\n\n$baseSystemPrompt".trim()
        }
    }
}

/** Result returned by a completed specialist run. */
internal data class SpecialistResult(
    val assistantText: String,
    val bufferedPatchKinds: List<String>,
)
