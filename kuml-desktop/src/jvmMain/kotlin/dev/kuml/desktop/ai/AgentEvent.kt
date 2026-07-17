package dev.kuml.desktop.ai

sealed class AgentEvent {
    data class AssistantDelta(
        val delta: String,
        val providerId: String,
        val modelId: String,
    ) : AgentEvent()

    data class ToolCallStart(
        val callId: String,
        val tool: String,
        val argsJson: String,
    ) : AgentEvent()

    data class ToolCallEnd(
        val callId: String,
        val resultJson: String,
        val isError: Boolean,
    ) : AgentEvent()

    data class TokenUsage(
        val inTok: Int,
        val outTok: Int,
        val providerId: String,
        val modelId: String,
    ) : AgentEvent()

    data object Done : AgentEvent()

    data class Error(
        val throwable: Throwable,
    ) : AgentEvent()

    /** V3.0.25: emitted when a tool call was decoded into a [dev.kuml.ai.tools.context.ModelPatch] and buffered. */
    data class PatchBuffered(
        val patchId: String,
        val kind: String,
    ) : AgentEvent()

    /** V3.1.18: orchestrator routed the prompt to a specialist domain. */
    data class OrchestratorRouted(
        val domain: String,
        val reason: String,
    ) : AgentEvent()

    /** V3.1.18: specialist agent for [domain] began its editing loop. */
    data class SpecialistStarted(
        val domain: String,
    ) : AgentEvent()
}
