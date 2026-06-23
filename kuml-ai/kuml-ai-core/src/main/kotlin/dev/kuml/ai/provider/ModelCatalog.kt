package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.spi.ModelDescriptor

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
     * Context window sizes (in tokens) for known models.
     * Used to populate [ModelDescriptor.contextWindowTokens] in built-in provider listings.
     * Entries reflect publicly documented values; null means the context window is
     * unknown or dynamic.
     */
    private val contextWindows: Map<Pair<String, String>, Int> =
        mapOf(
            // ── OpenAI ───────────────────────────────────────────────────────
            ("openai" to "gpt-4o") to 128_000,
            ("openai" to "gpt-4o-mini") to 128_000,
            ("openai" to "gpt-4.1") to 1_047_576,
            ("openai" to "gpt-4.1-mini") to 1_047_576,
            ("openai" to "o1") to 200_000,
            ("openai" to "o3") to 200_000,
            ("openai" to "o3-mini") to 200_000,
            // ── Anthropic ────────────────────────────────────────────────────
            ("anthropic" to "claude-haiku-3") to 200_000,
            ("anthropic" to "claude-haiku-4-5") to 200_000,
            ("anthropic" to "claude-sonnet-4") to 200_000,
            ("anthropic" to "claude-sonnet-4-5") to 200_000,
            ("anthropic" to "claude-opus-4") to 200_000,
            ("anthropic" to "claude-opus-4-5") to 200_000,
            ("anthropic" to "claude-opus-4-6") to 200_000,
            // ── Google ───────────────────────────────────────────────────────
            ("google" to "gemini-2.0-flash") to 1_048_576,
            ("google" to "gemini-2.0-flash-lite") to 1_048_576,
            ("google" to "gemini-2.5-pro") to 1_048_576,
            ("google" to "gemini-2.5-flash") to 1_048_576,
        )

    /**
     * Human-readable display names for known models.
     */
    private val displayNames: Map<Pair<String, String>, String> =
        mapOf(
            // ── OpenAI ───────────────────────────────────────────────────────
            ("openai" to "gpt-4o") to "GPT-4o",
            ("openai" to "gpt-4o-mini") to "GPT-4o Mini",
            ("openai" to "gpt-4.1") to "GPT-4.1",
            ("openai" to "gpt-4.1-mini") to "GPT-4.1 Mini",
            ("openai" to "o1") to "o1",
            ("openai" to "o3") to "o3",
            ("openai" to "o3-mini") to "o3-mini",
            // ── Anthropic ────────────────────────────────────────────────────
            ("anthropic" to "claude-haiku-3") to "Claude Haiku 3",
            ("anthropic" to "claude-haiku-4-5") to "Claude Haiku 4.5",
            ("anthropic" to "claude-sonnet-4") to "Claude Sonnet 4",
            ("anthropic" to "claude-sonnet-4-5") to "Claude Sonnet 4.5",
            ("anthropic" to "claude-opus-4") to "Claude Opus 4",
            ("anthropic" to "claude-opus-4-5") to "Claude Opus 4.5",
            ("anthropic" to "claude-opus-4-6") to "Claude Opus 4.6",
            // ── Google ───────────────────────────────────────────────────────
            ("google" to "gemini-2.0-flash") to "Gemini 2.0 Flash",
            ("google" to "gemini-2.0-flash-lite") to "Gemini 2.0 Flash Lite",
            ("google" to "gemini-2.5-pro") to "Gemini 2.5 Pro",
            ("google" to "gemini-2.5-flash") to "Gemini 2.5 Flash",
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

    /**
     * Returns [ModelDescriptor] entries for all known models of a given provider.
     * Excludes legacy alias entries (the same model id may be listed only once).
     * Ollama returns an empty list — it accepts dynamic model ids.
     */
    public fun descriptorsFor(providerId: String): List<ModelDescriptor> {
        if (providerId == "ollama") return emptyList()
        // Deduplicate: some entries are legacy aliases pointing to the same LLModel.
        // Keep only primary entries (those whose modelId appears in displayNames).
        val primaryKeys = displayNames.keys.filter { it.first == providerId }
        return primaryKeys.map { key ->
            ModelDescriptor(
                modelId = key.second,
                displayName = displayNames[key] ?: key.second,
                contextWindowTokens = contextWindows[key],
            )
        }
    }
}
