package dev.kuml.ai.provider

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.kuml.ai.spi.KumlLlmProviderSpi
import dev.kuml.ai.spi.validateBaseUrl
import org.slf4j.LoggerFactory
import java.util.ServiceLoader

/**
 * Lookup registry of kUML-known providers.
 *
 * Koog's [LLMProvider] is sealed, so we wrap it in [KumlLlmProvider]
 * with a stable string id for settings serialization.
 *
 * Built-in providers (OpenAI, Anthropic, Google, Ollama) always win on
 * id collision with custom SPI providers — see [discover].
 */
public class ProviderRegistry private constructor(
    private val byId: Map<String, KumlLlmProvider>,
) {
    /** All registered providers. */
    public fun all(): Collection<KumlLlmProvider> = byId.values

    /** Lookup by string id (e.g. "openai", "anthropic", "google", "ollama"). */
    public fun get(id: String): KumlLlmProvider? = byId[id]

    /**
     * Lookup by Koog [LLMProvider] instance.
     * Returns null for custom SPI providers (their [KumlLlmProvider.koogProvider] is null).
     */
    public fun byKoogProvider(koog: LLMProvider): KumlLlmProvider? = byId.values.firstOrNull { it.koogProvider == koog }

    /**
     * Resolve a model string to a Koog LLModel for the given provider id.
     *
     * Returns null for unknown models or for custom SPI providers (which have no
     * Koog LLMProvider mapping). Callers should fall back to the provider's default model.
     */
    public fun resolveModel(
        providerId: String,
        modelId: String,
    ): LLModel? {
        val provider = get(providerId) ?: return null
        val koog = provider.koogProvider ?: return null // custom providers not resolvable
        return resolveModelForProvider(koog, modelId)
    }

    public companion object {
        private val log = LoggerFactory.getLogger(ProviderRegistry::class.java)

        /** Built-in providers only — OpenAI, Anthropic, Google, Ollama. */
        public fun builtIns(): ProviderRegistry =
            ProviderRegistry(
                BuiltInProviders.all().associateBy { it.id },
            )

        /**
         * Built-ins plus any [KumlLlmProviderSpi] implementations registered via ServiceLoader.
         *
         * **Collision rule (V3.1.15):** built-ins ALWAYS win on id collision.
         * A custom provider declaring id="openai" is ignored with a warning log.
         * This is the inverse of the pre-V3.1.15 behaviour where custom providers
         * could override built-ins.
         *
         * The result is cached thread-safely on first call. Call [resetCacheForTest]
         * between test cases to get a fresh registry.
         */
        @Volatile private var cached: ProviderRegistry? = null

        public fun discover(): ProviderRegistry =
            cached ?: synchronized(this) {
                cached ?: discoverFromServiceLoader().also { cached = it }
            }

        /**
         * Testable seam: discover from an explicit iterable of SPI instances.
         * Used in tests to inject stub providers without a real ServiceLoader. Result is NOT cached.
         */
        internal fun discoverFrom(spis: Iterable<KumlLlmProviderSpi>): ProviderRegistry {
            // Built-ins first so they WIN on id collision (V3.1.15 rule).
            val merged = LinkedHashMap<String, KumlLlmProvider>()
            BuiltInProviders.all().forEach { merged[it.id] = it }

            for (spi in spis) {
                if (spi.id in merged) {
                    log.warn(
                        "Custom provider '{}' ignored — a built-in with the same id takes precedence. " +
                            "Choose a unique id for your custom provider.",
                        spi.id,
                    )
                    continue
                }
                merged[spi.id] = spi.toKumlProvider()
            }

            return ProviderRegistry(merged)
        }

        /** Real ServiceLoader-based discovery, used internally by [discover]. */
        private fun discoverFromServiceLoader(): ProviderRegistry =
            discoverFrom(
                ServiceLoader.load(KumlLlmProviderSpi::class.java),
            )

        /** Test-only: reset the discover() cache so each test gets a fresh registry. */
        internal fun resetCacheForTest() {
            synchronized(this) { cached = null }
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

/**
 * Hard upper bound on the number of [dev.kuml.ai.spi.ModelDescriptor] entries accepted
 * from a single custom SPI provider. Prevents a malicious or buggy provider JAR from
 * returning millions of entries and causing an OOM during [ProviderRegistry.discover].
 * The full list is also serialized to JSON (`kuml ai provider list --output json`) and
 * rendered into a console table (`kuml ai provider info`), both of which would become
 * unusably large without this cap.
 *
 * Legitimate providers should never come close to this limit — the largest public
 * model catalogues (OpenAI, Anthropic, Google) have O(10–100) entries. A warning is
 * logged when the cap is hit so maintainers of legitimate providers know to trim
 * their list.
 */
internal const val MAX_MODELS_PER_PROVIDER: Int = 500

/**
 * Adapter: converts a [KumlLlmProviderSpi] into a [KumlLlmProvider] for registry storage.
 *
 * The [KumlLlmProvider.koogProvider] is set to null because custom SPI providers cannot
 * map to Koog's sealed [LLMProvider] class. This means custom providers are available
 * for listing and inspection but cannot be used as the active execution provider in
 * [dev.kuml.ai.KumlAiExecutor] until V3.2+ (deferred).
 */
internal fun KumlLlmProviderSpi.toKumlProvider(): KumlLlmProvider {
    val spiRef = this
    val rawModels = spiRef.supportedModels()
    val models =
        if (rawModels.size > MAX_MODELS_PER_PROVIDER) {
            LoggerFactory.getLogger(ProviderRegistry::class.java).warn(
                "Custom provider '{}' returned {} models — capped at {}. " +
                    "Trim your supportedModels() list to avoid this warning.",
                spiRef.id,
                rawModels.size,
                MAX_MODELS_PER_PROVIDER,
            )
            rawModels.take(MAX_MODELS_PER_PROVIDER)
        } else {
            rawModels
        }
    return KumlLlmProvider(
        id = spiRef.id,
        displayName = spiRef.displayName,
        koogProvider = null, // sealed class — cannot be extended by third parties
        isLocal = spiRef.isLocalProvider,
        clientFactory = { key ->
            // Core-enforced SSRF guard: validate baseUrl before delegating to the SPI
            // implementation, regardless of whether the implementor called validateBaseUrl
            // themselves. This is defence-in-depth — the SPI contract says MUST, but the
            // core cannot rely on third-party compliance.
            val options = emptyMap<String, String>()
            val baseUrl = options["baseUrl"]
            if (baseUrl != null) {
                validateBaseUrl(baseUrl, spiRef.isLocalProvider)
            }
            @Suppress("UNCHECKED_CAST")
            spiRef.buildClient(key ?: "", options) as LLMClient
        },
        supportedModels = models,
    )
}
