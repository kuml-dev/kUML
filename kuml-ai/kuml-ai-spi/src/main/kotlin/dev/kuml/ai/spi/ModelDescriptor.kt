package dev.kuml.ai.spi

/**
 * Provider-agnostic description of one model a provider offers.
 *
 * Used in [KumlLlmProviderSpi.supportedModels] to advertise the models
 * available from a provider without referencing Koog types.
 */
public data class ModelDescriptor(
    /** Stable model id used in kUML settings + CLI (e.g. "gpt-4o", "llama3.2"). */
    val modelId: String,
    /** Human-readable label for tables and UI. */
    val displayName: String,
    /** Context window size in tokens; null when unknown or dynamic (e.g. Ollama). */
    val contextWindowTokens: Int? = null,
)
