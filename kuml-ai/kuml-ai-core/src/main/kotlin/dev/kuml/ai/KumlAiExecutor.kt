package dev.kuml.ai

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import dev.kuml.ai.privacy.PrivacyEnforcer
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import kotlinx.coroutines.flow.Flow

/**
 * kUML-side thin wrapper around Koog's [MultiLLMPromptExecutor].
 *
 * Responsibilities:
 *  - Resolve API keys from [ApiKeyVault] at construction time.
 *  - Apply kUML defaults (default model selection per provider).
 *  - Enforce [PrivacyEnforcer] guards before every prompt dispatch.
 *  - Surface kUML-typed exceptions for caller-friendly handling.
 *
 * Construct via [fromSettings] — do not call the constructor directly.
 */
public class KumlAiExecutor private constructor(
    private val delegate: PromptExecutor,
    private val settings: KumlAiSettings,
    private val privacy: PrivacyEnforcer,
    private val registry: ProviderRegistry,
) {
    /**
     * Execute a prompt with the configured default model.
     * Resolves the default provider + model from [settings].
     * Throws [KumlAiException.PrivacyModeViolation] when privacy mode blocks the provider.
     */
    public suspend fun execute(prompt: Prompt): List<Message.Response> {
        val model = resolveDefaultModel()
        privacy.guard(model.provider)
        return delegate.execute(prompt, model)
    }

    /**
     * Execute a prompt with an explicit model override.
     * Throws [KumlAiException.PrivacyModeViolation] when privacy mode blocks the provider.
     */
    public suspend fun execute(
        prompt: Prompt,
        model: LLModel,
    ): List<Message.Response> {
        privacy.guard(model.provider)
        return delegate.execute(prompt, model)
    }

    /**
     * Streaming variant — returns a [Flow] over Koog's [StreamFrame].
     *
     * The privacy guard is applied **eagerly** before the Flow is built,
     * so callers see [KumlAiException.PrivacyModeViolation] immediately
     * and not on the first collect. Wire-level integration target: V3.0.24.
     */
    public fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
    ): Flow<StreamFrame> {
        // Eager guard — throw before building the Flow
        privacy.guard(model.provider)
        return delegate.executeStreaming(prompt, model)
    }

    /** Active settings snapshot — read-only copy. */
    public fun currentSettings(): KumlAiSettings = settings

    /** Exposes the underlying PromptExecutor for AIAgent integration (V3.0.24). */
    public fun promptExecutor(): PromptExecutor = delegate

    public companion object {
        /**
         * Build an executor from the persisted settings and vault.
         *
         * @throws KumlAiException.UnknownProvider if the default provider is not in the registry.
         * @throws KumlAiException.MissingApiKey if a cloud provider has no API key.
         * @throws KumlAiException.PrivacyModeViolation if privacyMode=true and defaultProvider is cloud.
         */
        public fun fromSettings(
            settings: KumlAiSettings,
            vault: ApiKeyVault,
            registry: ProviderRegistry = ProviderRegistry.builtIns(),
        ): KumlAiExecutor {
            val privacy = PrivacyEnforcer(settings.privacyMode)

            // Validate that the default provider is registered
            val defaultProvider =
                registry.get(settings.defaultProvider)
                    ?: throw KumlAiException.UnknownProvider(settings.defaultProvider)

            // Custom SPI providers (koogProvider == null) cannot be used as the active executor
            // provider in V3.1.15 — they are available for listing and inspection only.
            // V3.2+ will add execution support when Koog supports open provider extension.
            if (defaultProvider.koogProvider == null) {
                throw KumlAiException.UnknownProvider(
                    "${settings.defaultProvider} (custom SPI providers are not yet executable — " +
                        "choose a built-in provider: openai, anthropic, google, ollama)",
                )
            }

            // Eagerly check privacy mode against the default provider
            privacy.guard(defaultProvider.koogProvider)

            // Build (provider, client) pairs for all enabled providers.
            // Custom providers (koogProvider == null) are skipped silently — they cannot
            // be wired into MultiLLMPromptExecutor without a sealed LLMProvider instance.
            val providerClientPairs =
                settings.enabledProviders.mapNotNull { providerId ->
                    val kumlProvider = registry.get(providerId) ?: return@mapNotNull null
                    val koog = kumlProvider.koogProvider ?: return@mapNotNull null // skip custom
                    val apiKey =
                        if (!kumlProvider.isLocal) {
                            vault.get(koog)
                                ?: throw KumlAiException.MissingApiKey(koog)
                        } else {
                            null
                        }
                    koog to kumlProvider.clientFactory(apiKey)
                }

            val delegate = MultiLLMPromptExecutor(*providerClientPairs.toTypedArray())

            return KumlAiExecutor(
                delegate = delegate,
                settings = settings,
                privacy = privacy,
                registry = registry,
            )
        }

        /**
         * Package-internal constructor for tests — allows injecting a fake PromptExecutor.
         */
        internal fun forTest(
            delegate: PromptExecutor,
            settings: KumlAiSettings,
            privacy: PrivacyEnforcer,
            registry: ProviderRegistry,
        ): KumlAiExecutor = KumlAiExecutor(delegate, settings, privacy, registry)
    }

    private fun resolveDefaultModel(): LLModel {
        val providerId = settings.defaultProvider
        val modelId =
            settings.defaultModels[providerId]
                ?: error("No default model configured for provider '$providerId'")

        return registry.resolveModel(providerId, modelId)
            ?: error("Cannot resolve model '$modelId' for provider '$providerId'")
    }
}
