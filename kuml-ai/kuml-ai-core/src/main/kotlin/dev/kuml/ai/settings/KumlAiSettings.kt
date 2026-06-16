package dev.kuml.ai.settings

import kotlinx.serialization.Serializable

/** Persistence schema for kUML AI settings. Stored at the XDG-conformant ai-settings.json path. */
@Serializable
public data class KumlAiSettings(
    /** Schema version — bump on breaking changes; migrations live in KumlAiSettingsStore. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Providers the user has enabled (subset of registered providers). */
    val enabledProviders: Set<String> = setOf("ollama"),
    /** Default provider id used when the caller does not specify one. */
    val defaultProvider: String = "ollama",
    /**
     * Default model id per provider. String keys (provider id) → Koog LLModel id.
     * Example: "openai" → "gpt-4o", "anthropic" → "claude-sonnet-4-5", "ollama" → "llama3.2".
     */
    val defaultModels: Map<String, String> =
        mapOf(
            "openai" to "gpt-4o",
            "anthropic" to "claude-sonnet-4-5",
            "google" to "gemini-1.5-pro",
            "ollama" to "llama3.2",
        ),
    /** When true: only local providers (Ollama) may be invoked. Default true (privacy-by-default). */
    val privacyMode: Boolean = true,
    /** Optional soft cap on cumulative spend (USD). When exceeded, executor throws a budget exception (V3.0.26). */
    val costBudgetUsd: Double? = null,
    /** Default system prompt prepended to all prompts (caller may override). */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Temperature default applied when caller does not specify. */
    val temperature: Double = 0.2,
) {
    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1

        public const val DEFAULT_SYSTEM_PROMPT: String =
            "You are a kUML modelling assistant. " +
                "Prefer typed DSL operations over freeform text. " +
                "When patching models, use the smallest possible diff."
    }
}
