package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Explicit declarative table mapping (providerId, modelId) string pairs to
 * Koog LLModel instances.
 *
 * Replaces the reflection lookup that V3.0.22 used in ProviderRegistry.resolveModel
 * (V3.0.23 carry-over). Adding a new model = adding one line here, no reflection.
 *
 * Note on Google model availability: Koog 0.7.3 ships Gemini2_5Pro, Gemini2_5Flash,
 * Gemini2_0Flash, Gemini2_0FlashLite — not "gemini-2.0-pro" as originally planned.
 * The table uses the actual available models. Pass-through via LLModel constructor
 * handles any non-listed model for providers that accept dynamic model ids.
 */
public object ModelCatalog {
    @Suppress("DEPRECATION")
    private val table: Map<Pair<String, String>, LLModel> =
        mapOf(
            // ── OpenAI ───────────────────────────────────────────────────────
            ("openai" to "gpt-4o") to OpenAIModels.Chat.GPT4o,
            ("openai" to "gpt-4o-mini") to OpenAIModels.Chat.GPT4oMini,
            ("openai" to "gpt-4.1") to OpenAIModels.Chat.GPT4_1,
            ("openai" to "gpt-4.1-mini") to OpenAIModels.Chat.GPT4_1Mini,
            ("openai" to "o1") to OpenAIModels.Chat.O1,
            ("openai" to "o3") to OpenAIModels.Chat.O3,
            ("openai" to "o3-mini") to OpenAIModels.Chat.O3Mini,
            // ── Anthropic ────────────────────────────────────────────────────
            ("anthropic" to "claude-haiku-3") to AnthropicModels.Haiku_3,
            ("anthropic" to "claude-haiku-4-5") to AnthropicModels.Haiku_4_5,
            ("anthropic" to "claude-sonnet-4") to AnthropicModels.Sonnet_4,
            ("anthropic" to "claude-sonnet-4-5") to AnthropicModels.Sonnet_4_5,
            ("anthropic" to "claude-opus-4") to AnthropicModels.Opus_4,
            ("anthropic" to "claude-opus-4-5") to AnthropicModels.Opus_4_5,
            ("anthropic" to "claude-opus-4-6") to AnthropicModels.Opus_4_6,
            // ── Google ───────────────────────────────────────────────────────
            // Note: Gemini 2.0 Pro is not available in Koog 0.7.3 — use Flash variants.
            // The plan specified gemini-2.0-pro; actual catalog uses 2.0 Flash and 2.5 Pro.
            ("google" to "gemini-2.0-flash") to GoogleModels.Gemini2_0Flash,
            ("google" to "gemini-2.0-flash-lite") to GoogleModels.Gemini2_0FlashLite,
            ("google" to "gemini-2.5-pro") to GoogleModels.Gemini2_5Pro,
            ("google" to "gemini-2.5-flash") to GoogleModels.Gemini2_5Flash,
            // Legacy aliases for compatibility with settings that used old model ids
            ("google" to "gemini-2.0-pro") to GoogleModels.Gemini2_5Pro, // upgrade path
            ("google" to "gemini-1.5-pro") to GoogleModels.Gemini2_5Pro, // upgrade path
            ("google" to "gemini-1.5-flash") to GoogleModels.Gemini2_0Flash, // upgrade path
        )

    /**
     * Resolves (providerId, modelId) to a Koog LLModel.
     *
     * Returns null if not in the table — callers should fall back to LLModel constructor
     * for Ollama (accepts any model name) or return a provider-default.
     */
    public fun resolve(
        providerId: String,
        modelId: String,
    ): LLModel? = table[providerId to modelId]

    /**
     * For Ollama (and other local providers that accept arbitrary model names),
     * wraps the model id directly in a LLModel — no table lookup needed.
     */
    public fun resolveOllama(modelId: String): LLModel = LLModel(LLMProvider.Ollama, modelId)

    /** Returns all known model ids for a given provider. */
    public fun modelsFor(providerId: String): List<String> = table.keys.filter { it.first == providerId }.map { it.second }
}
