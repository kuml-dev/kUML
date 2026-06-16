package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider

/**
 * kUML-side wrapper around a Koog [LLMProvider] with a stable string id,
 * a human-readable display name, and capability flags.
 */
public data class KumlLlmProvider(
    /** Stable lowercase string id (e.g. "openai", "anthropic", "google", "ollama"). */
    val id: String,
    /** Human-readable name for display in UI and error messages. */
    val displayName: String,
    /** The underlying Koog LLMProvider sealed-class instance. */
    val koogProvider: LLMProvider,
    /** True if this provider does NOT call out to a third party (e.g. Ollama). */
    val isLocal: Boolean,
    /**
     * Builder that turns an API key (or null for local providers) into a Koog LLMClient.
     * Called once when KumlAiExecutor is constructed from settings.
     */
    val clientFactory: (apiKey: String?) -> LLMClient,
)
