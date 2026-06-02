package dev.kuml.llm.core

/** A single message in a conversation. */
public data class LlmMessage(
    val role: String,
    val content: String,
)

/** Result of one LLM completion call. */
public data class LlmResponse(
    val content: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Backend interface for LLM API calls.
 * Implementations: [AnthropicBackend] (Anthropic Claude), [LlmMockBackend] (testing).
 */
public interface LlmBackend {
    /** Unique backend identifier (e.g. "anthropic-claude-3-haiku"). */
    public val id: String

    /** Model name as reported in responses. */
    public val model: String

    /**
     * Perform a single completion and return the assistant's response.
     * @throws LlmException on API error or network failure.
     */
    public fun complete(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
    ): LlmResponse
}

/** Thrown on LLM API errors (HTTP 4xx/5xx, network failures). */
public class LlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
