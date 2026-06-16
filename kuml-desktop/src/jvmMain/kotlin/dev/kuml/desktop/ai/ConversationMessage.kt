package dev.kuml.desktop.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ConversationMessage {
    abstract val id: String
    abstract val timestamp: Long

    @Serializable
    @SerialName("user")
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String,
    ) : ConversationMessage()

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val id: String,
        override val timestamp: Long,
        val text: String,
        val isStreaming: Boolean = false,
        val providerId: String = "",
        val modelId: String = "",
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
    ) : ConversationMessage()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        override val id: String,
        override val timestamp: Long,
        val toolName: String,
        val argsJson: String,
        val state: ToolCallState = ToolCallState.RUNNING,
    ) : ConversationMessage()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        override val id: String,
        override val timestamp: Long,
        val toolCallId: String,
        val resultJson: String,
        val isError: Boolean = false,
    ) : ConversationMessage()

    @Serializable
    @SerialName("error")
    data class ErrorMessage(
        override val id: String,
        override val timestamp: Long,
        val message: String,
        val cause: String? = null,
    ) : ConversationMessage()
}

@Serializable
enum class ToolCallState { RUNNING, SUCCESS, FAILED }

@Serializable
data class Conversation(
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String = "",
    val modelId: String = "",
    val messages: List<ConversationMessage> = emptyList(),
    val totalTokensIn: Int = 0,
    val totalTokensOut: Int = 0,
    val totalCostUsd: Double = 0.0,
)
