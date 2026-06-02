package dev.kuml.llm.core

/**
 * Deterministic mock backend for testing.
 * Returns a fixed [response] for every call.
 */
public class LlmMockBackend(
    private val response: String = "diagram(name = \"Mock\") { }",
    override val model: String = "mock",
) : LlmBackend {
    override val id: String = "mock"
    public var callCount: Int = 0
        private set

    override fun complete(
        messages: List<LlmMessage>,
        systemPrompt: String?,
    ): LlmResponse {
        callCount++
        return LlmResponse(
            content = response,
            model = model,
            inputTokens = 0,
            outputTokens = 0,
        )
    }
}
