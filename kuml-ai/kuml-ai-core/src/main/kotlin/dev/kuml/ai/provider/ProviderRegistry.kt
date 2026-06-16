package dev.kuml.ai.provider

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import org.slf4j.LoggerFactory
import java.util.ServiceLoader

/**
 * Lookup registry of kUML-known providers.
 *
 * Koog's [LLMProvider] is sealed, so we wrap it in [KumlLlmProvider]
 * with a stable string id for settings serialization.
 */
public class ProviderRegistry private constructor(
    private val byId: Map<String, KumlLlmProvider>,
) {
    /** All registered providers. */
    public fun all(): Collection<KumlLlmProvider> = byId.values

    /** Lookup by string id (e.g. "openai", "anthropic", "google", "ollama"). */
    public fun get(id: String): KumlLlmProvider? = byId[id]

    /** Lookup by Koog [LLMProvider] instance. */
    public fun byKoogProvider(koog: LLMProvider): KumlLlmProvider? = byId.values.firstOrNull { it.koogProvider == koog }

    /**
     * Resolve a model string to a Koog LLModel for the given provider id.
     *
     * Currently returns null for unknown models — callers should fall back
     * to the provider's default model. Actual model-string → LLModel mapping
     * will be enriched in V3.0.23.
     */
    public fun resolveModel(
        providerId: String,
        modelId: String,
    ): LLModel? {
        val provider = get(providerId) ?: return null
        return resolveModelForProvider(provider.koogProvider, modelId)
    }

    public companion object {
        private val log = LoggerFactory.getLogger(ProviderRegistry::class.java)

        /** Built-in providers only — OpenAI, Anthropic, Google, Ollama. */
        public fun builtIns(): ProviderRegistry =
            ProviderRegistry(
                BuiltInProviders.all().associateBy { it.id },
            )

        /**
         * Built-ins plus any [ProviderDescriptor]s registered via ServiceLoader.
         * Custom providers override built-ins when ids conflict.
         * Prepared for V3.1+.
         */
        public fun discover(): ProviderRegistry {
            val base = BuiltInProviders.all().associateBy { it.id }.toMutableMap()
            ServiceLoader.load(ProviderDescriptor::class.java).forEach { descriptor ->
                val provider = descriptor.toKumlProvider()
                if (provider.id in base) {
                    log.info(
                        "Custom provider '{}' overrides built-in provider with the same id.",
                        provider.id,
                    )
                }
                base[provider.id] = provider
            }
            return ProviderRegistry(base)
        }

        private fun resolveModelForProvider(
            koogProvider: LLMProvider,
            modelId: String,
        ): LLModel? {
            // V3.0.23 carry-over: replaced reflection lookup with explicit ModelCatalog table.
            // Ollama accepts any model id the user has pulled locally — use pass-through.
            if (koogProvider == LLMProvider.Ollama) return ModelCatalog.resolveOllama(modelId)

            val providerId =
                when (koogProvider) {
                    LLMProvider.OpenAI -> "openai"
                    LLMProvider.Anthropic -> "anthropic"
                    LLMProvider.Google -> "google"
                    else -> return null
                }
            // Table lookup first; fall back to generic LLModel constructor for unknown ids.
            return ModelCatalog.resolve(providerId, modelId) ?: LLModel(koogProvider, modelId)
        }
    }
}
