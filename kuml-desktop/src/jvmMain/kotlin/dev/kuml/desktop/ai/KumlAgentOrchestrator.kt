package dev.kuml.desktop.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import dev.kuml.ai.KumlAiExecutor
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.patch.PatchApplyEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Domain enum for the multi-agent orchestrator.
 *
 * [allowedToolNames] is derived from `@Tool(customName = ...)` registrations in
 * UmlEditingTools, C4EditingTools, and Sysml2EditingTools. Used by [KumlSpecialistAgent]
 * to filter which tool calls may produce patches.
 *
 * Note (V3.1.18): C4 and SysML2 entries are present in the allow-list so the
 * specialist correctly accepts those tool calls for tracing; however, [PatchDecoder]
 * currently only decodes UML tool names into [dev.kuml.ai.tools.context.ModelPatch]
 * instances. C4/SysML2 tool calls will be traced but not buffered as patches.
 * This gap is documented in the CHANGELOG.
 */
enum class AgentDomain(val id: String, val allowedToolNames: Set<String>) {
    UML(
        "uml",
        setOf(
            "add_class", "add_interface", "add_attribute", "add_operation",
            "add_association", "add_generalization", "remove_element", "rename_element",
            "set_current_diagram",
        ),
    ),
    C4(
        "c4",
        setOf(
            "add_person", "add_software_system", "add_container",
            "add_component", "add_relationship",
        ),
    ),
    SYSML2(
        "sysml2",
        setOf(
            "add_part_def", "add_attribute_def", "add_state", "add_transition",
            "add_use_case", "add_requirement", "add_action", "add_constraint",
        ),
    ),
    MIXED(
        "mixed",
        UML.allowedToolNames + C4.allowedToolNames + SYSML2.allowedToolNames,
    ),
    ;

    companion object {
        fun fromId(s: String): AgentDomain = entries.firstOrNull { it.id == s } ?: MIXED
    }
}

/**
 * Three-step multi-agent orchestrator for kUML AI editing.
 *
 * 1. **Routing** — a routing turn asks the LLM to emit a `route_to_specialist` tool
 *    call identifying the target domain (uml|c4|sysml2|mixed). Emits
 *    [AgentEvent.OrchestratorRouted].
 *
 * 2. **Specialist** — a [KumlSpecialistAgent] runs a domain-scoped turn, filtering
 *    tool calls against [AgentDomain.allowedToolNames]. Emits
 *    [AgentEvent.SpecialistStarted] before the specialist runs, then the specialist
 *    emits ToolCallStart / PatchBuffered / ToolCallEnd events as usual.
 *
 * 3. **Synthesis** — a synthesis turn turns the specialist output into a concise
 *    user-facing reply. Emits [AgentEvent.AssistantDelta] then [AgentEvent.Done].
 *
 * All three steps share the same try/catch/CancellationException structure as
 * [AgentRunner] so error propagation is identical.
 *
 * Note (V3.1.18): MIXED domain runs as a single specialist pass with the union
 * allow-list — it does NOT fan out to multiple specialists in parallel. Sequential
 * multi-domain fan-out is deferred to a future wave.
 *
 * @param executorFn Injectable execution function for testing — bypasses [executor] when set.
 *   Invocations are counted per-step (routing / specialist / synthesis), so the stub
 *   must return different responses per call.
 */
class KumlAgentOrchestrator(
    private val executor: KumlAiExecutor,
    private val providerId: String,
    private val modelId: String,
    private val editingContext: AgentEditingContext? = null,
    private val patchEngine: PatchApplyEngine? = null,
    internal val executorFn: (suspend (Prompt, LLModel) -> List<Message.Response>)? = null,
) {
    // PatchDecoder is internal — kept as a field so tests can reach it via the orchestrator,
    // but not exposed in the public constructor signature.
    internal val decoder: PatchDecoder = PatchDecoder(editingContext)
    private val registry = ProviderRegistry.builtIns()

    fun runConversation(history: List<ConversationMessage>): Flow<AgentEvent> = flow {
        try {
            val model = resolveModel() ?: run {
                emit(
                    AgentEvent.Error(
                        IllegalArgumentException("Cannot resolve model '$modelId' for provider '$providerId'"),
                    ),
                )
                return@flow
            }

            // ── Step 1: Routing ────────────────────────────────────────────────
            val routingPrompt = buildRoutingPrompt(history)
            val routingResponses = executeStep(routingPrompt, model)

            val (domain, routingReason) = extractDomain(routingResponses)
            emit(AgentEvent.OrchestratorRouted(domain.id, routingReason))

            // ── Step 2: Specialist ─────────────────────────────────────────────
            emit(AgentEvent.SpecialistStarted(domain.id))

            val specialist = KumlSpecialistAgent(
                domain = domain,
                executor = executor,
                model = model,
                providerId = providerId,
                modelId = modelId,
                editingContext = editingContext,
                patchEngine = patchEngine,
                decoder = decoder,
                executorFn = executorFn,
            )
            val specialistResult = specialist.run(history) { emit(it) }

            // ── Step 3: Synthesis ──────────────────────────────────────────────
            val synthesisPrompt = buildSynthesisPrompt(
                history = history,
                domain = domain,
                specialistText = specialistResult.assistantText,
                patchKinds = specialistResult.bufferedPatchKinds,
            )
            val synthesisResponses = executeStep(synthesisPrompt, model)

            val synthText = synthesisResponses
                .filterIsInstance<Message.Assistant>()
                .joinToString("") { it.content }
            if (synthText.isNotBlank()) {
                emit(AgentEvent.AssistantDelta(synthText, providerId, modelId))
            }

            emit(AgentEvent.Done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Error(e))
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun executeStep(koogPrompt: Prompt, model: LLModel): List<Message.Response> =
        if (executorFn != null) {
            executorFn.invoke(koogPrompt, model)
        } else {
            executor.execute(koogPrompt, model)
        }

    private fun resolveModel(): LLModel? = registry.resolveModel(providerId, modelId)

    /**
     * Builds the routing prompt.
     *
     * Instructs the LLM to classify the user's intent and emit a virtual
     * `route_to_specialist` tool call with `domain` (uml|c4|sysml2|mixed)
     * and optional `reason`. The tool call schema is described in natural language
     * because we do not register a live Koog ToolRegistry here — we just
     * opportunistically parse whatever the model emits (same contract as V3.0.25).
     */
    private fun buildRoutingPrompt(history: List<ConversationMessage>) =
        prompt("kuml-orchestrator-routing") {
            system(ROUTING_SYSTEM_PROMPT)
            for (msg in history) {
                when (msg) {
                    is ConversationMessage.User -> user(msg.text)
                    is ConversationMessage.Assistant -> assistant(msg.text)
                    else -> {}
                }
            }
        }

    /**
     * Extracts the routing decision from the LLM responses.
     *
     * Priority:
     * 1. First `route_to_specialist` tool call — read `domain` and `reason` from args.
     * 2. If no tool call, scan assistant text for a domain keyword.
     * 3. Final fallback: MIXED.
     */
    private fun extractDomain(responses: List<Message.Response>): Pair<AgentDomain, String> {
        // Try tool call first
        val routeCall = responses
            .filterIsInstance<Message.Tool.Call>()
            .firstOrNull { it.tool == "route_to_specialist" }
        if (routeCall != null) {
            val args = routeCall.contentJson
            val domainStr = runCatching { args["domain"]?.toString()?.trim('"') }.getOrNull() ?: ""
            val reason = runCatching { args["reason"]?.toString()?.trim('"') }.getOrNull() ?: ""
            return AgentDomain.fromId(domainStr) to reason
        }

        // Fallback: scan assistant text for a domain keyword
        val text = responses
            .filterIsInstance<Message.Assistant>()
            .joinToString(" ") { it.content }
            .lowercase()
        val domainFromText = when {
            "sysml" in text || "sysml2" in text -> AgentDomain.SYSML2
            " c4 " in text || "c4model" in text -> AgentDomain.C4
            "uml" in text -> AgentDomain.UML
            else -> AgentDomain.MIXED
        }
        return domainFromText to "inferred from assistant text"
    }

    private fun buildSynthesisPrompt(
        history: List<ConversationMessage>,
        domain: AgentDomain,
        specialistText: String,
        patchKinds: List<String>,
    ) = prompt("kuml-orchestrator-synthesis") {
        system(SYNTHESIS_SYSTEM_PROMPT)
        // Replay the original user request
        history.filterIsInstance<ConversationMessage.User>().lastOrNull()?.let {
            user(it.text)
        }
        // Provide specialist output as context
        val patchSummary = if (patchKinds.isEmpty()) {
            "No model patches were produced."
        } else {
            "Patches buffered: ${patchKinds.joinToString(", ")}."
        }
        assistant(
            "I routed your request to the ${domain.id.uppercase()} specialist. " +
                "Specialist output: $specialistText\n$patchSummary\n" +
                "Please provide a concise, user-facing summary.",
        )
        user("Summarise what was done.")
    }

    companion object {
        private val ROUTING_SYSTEM_PROMPT = """
            You are a routing agent for kUML, a multi-domain modelling tool.
            Your job is to classify the user's modelling request into one of four specialist domains:
            - uml: UML class diagrams, interfaces, attributes, operations, associations, generalizations
            - c4: C4 architecture models — persons, software systems, containers, components
            - sysml2: SysML 2 — part definitions, states, transitions, requirements, use cases, actions
            - mixed: requests spanning multiple domains or unclear domain

            Respond ONLY by calling the tool route_to_specialist with:
              { "domain": "<uml|c4|sysml2|mixed>", "reason": "<one sentence>" }

            If you cannot determine the domain, use "mixed".
        """.trimIndent()

        private val SYNTHESIS_SYSTEM_PROMPT = """
            You are a synthesis agent for kUML. A domain specialist has processed the user's request.
            Your job is to provide a concise, friendly summary of what was done:
            - What elements were added or modified (from the patch kinds list)
            - Any relevant explanation of the changes
            - Next steps the user might want to take
            Keep the response brief (2-4 sentences).
        """.trimIndent()
    }
}
