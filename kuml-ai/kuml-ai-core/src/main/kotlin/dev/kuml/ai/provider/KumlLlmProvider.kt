package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.spi.ModelDescriptor

/**
 * kUML-side wrapper around a Koog [LLMProvider] with a stable string id,
 * a human-readable display name, and capability flags.
 *
 * For built-in providers (OpenAI, Anthropic, Google, Ollama), [koogProvider] is
 * always non-null. For custom providers registered via [dev.kuml.ai.spi.KumlLlmProviderSpi],
 * [koogProvider] is null because Koog's [LLMProvider] is a sealed class that cannot be
 * extended by third parties. Custom providers are therefore available for discovery
 * and listing (`kuml ai provider list/info`) but cannot yet be used as the active
 * execution provider — [dev.kuml.ai.KumlAiExecutor.fromSettings] will throw
 * [dev.kuml.ai.KumlAiException.UnknownProvider] if a custom provider is selected
 * as the default (deferred to V3.2+).
 */
public data class KumlLlmProvider(
    /** Stable lowercase string id (e.g. "openai", "anthropic", "google", "ollama"). */
    val id: String,
    /** Human-readable name for display in UI and error messages. */
    val displayName: String,
    /**
     * The underlying Koog LLMProvider sealed-class instance.
     * Null for custom SPI providers — Koog's sealed class cannot be extended.
     */
    val koogProvider: LLMProvider?,
    /** True if this provider does NOT call out to a third party (e.g. Ollama). */
    val isLocal: Boolean,
    /**
     * Builder that turns an API key (or null for local providers) into a Koog LLMClient.
     * Called once when KumlAiExecutor is constructed from settings.
     * Clients are loaded reflectively (V3.1.15 tree-shaking) so missing client JARs
     * produce a [ClassNotFoundException] at call time, not at startup.
     */
    val clientFactory: (apiKey: String?) -> LLMClient,
    /**
     * Models this provider advertises.
     * Empty for providers with dynamic model ids (e.g. Ollama).
     * Populated from [dev.kuml.ai.provider.ModelCatalog] for built-ins,
     * and from [dev.kuml.ai.spi.KumlLlmProviderSpi.supportedModels] for custom providers.
     */
    val supportedModels: List<ModelDescriptor> = emptyList(),
) {
    /**
     * True for custom SPI providers (those registered via [dev.kuml.ai.spi.KumlLlmProviderSpi]).
     * Custom providers cannot currently be used as the active execution provider.
     */
    val isCustomProvider: Boolean get() = koogProvider == null
}
