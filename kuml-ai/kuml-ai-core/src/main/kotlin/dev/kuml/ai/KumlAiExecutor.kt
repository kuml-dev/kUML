package dev.kuml.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import dev.kuml.ai.budget.BudgetGuard
import dev.kuml.ai.pricing.ProviderPricingService
import dev.kuml.ai.privacy.PrivacyEnforcer
import dev.kuml.ai.provider.ProviderRegistry
import dev.kuml.ai.settings.KumlAiSettings
import dev.kuml.ai.vault.ApiKeyVault
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

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
 *
 * Implements [java.io.Closeable]: some provider clients (e.g. Gonka's `GonkaLLMClient`)
 * own dedicated resources (an HTTP client + connection pool) that must be released.
 * [close] delegates to the underlying [PromptExecutor]'s own `close()` — Koog's
 * `MultiLLMPromptExecutor.close()` already iterates every constructed `LLMClient` and
 * closes it. Callers should wrap executor usage in `.use { ... }` or call [close] explicitly
 * once done, especially for providers whose client is not a no-op to close.
 */
public class KumlAiExecutor private constructor(
    private val delegate: PromptExecutor,
    private val settings: KumlAiSettings,
    private val privacy: PrivacyEnforcer,
    private val registry: ProviderRegistry,
    private val budgetGuard: BudgetGuard? = null,
) : java.io.Closeable {
    /**
     * Execute a prompt with the configured default model.
     * Resolves the default provider + model from [settings].
     * Throws [KumlAiException.PrivacyModeViolation] when privacy mode blocks the provider.
     * Throws [KumlAiException.BudgetExceeded] when the session cost cap is reached.
     *
     * Koog 1.0.0: returns [Message.Assistant] directly (was List<Message.Response> in 0.7.3).
     */
    public suspend fun execute(prompt: Prompt): Message.Assistant {
        val model = resolveDefaultModel()
        budgetGuard?.checkBeforeCall()
        privacy.guard(model.provider)
        return delegate.execute(prompt, model)
    }

    /**
     * Execute a prompt with an explicit model override.
     * Throws [KumlAiException.PrivacyModeViolation] when privacy mode blocks the provider.
     * Throws [KumlAiException.BudgetExceeded] when the session cost cap is reached.
     *
     * Koog 1.0.0: returns [Message.Assistant] directly (was List<Message.Response> in 0.7.3).
     */
    public suspend fun execute(
        prompt: Prompt,
        model: LLModel,
    ): Message.Assistant {
        budgetGuard?.checkBeforeCall()
        privacy.guard(model.provider)
        return delegate.execute(prompt, model)
    }

    /**
     * Execute a prompt with an explicit model override and a set of [tools] the model may
     * call. Koog 1.0.0's `PromptExecutorAPI.execute` already supports a `tools` parameter —
     * this overload just threads it through the same privacy/budget guards as the two-arg
     * [execute] above.
     *
     * Deliberately has NO default value for [tools] (unlike Koog's own `tools: List<ToolDescriptor> = emptyList()`)
     * so callers explicitly opt into the tool-calling code path instead of silently landing here
     * via an ambiguous overload resolution against the two-arg [execute].
     *
     * Throws [KumlAiException.PrivacyModeViolation] when privacy mode blocks the provider.
     * Throws [KumlAiException.BudgetExceeded] when the session cost cap is reached.
     */
    public suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        budgetGuard?.checkBeforeCall()
        privacy.guard(model.provider)
        return delegate.execute(prompt, model, tools)
    }

    /**
     * Streaming variant — returns a [Flow] over Koog's [StreamFrame].
     *
     * The privacy guard is applied **eagerly** before the Flow is built,
     * so callers see [KumlAiException.PrivacyModeViolation] immediately
     * and not on the first collect. Wire-level integration target: V3.0.24.
     * Budget guard is also applied eagerly (pre-check only; token accounting
     * is done by the desktop's AgentRunner via [budgetGuard]).
     */
    public fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
    ): Flow<StreamFrame> {
        // Eager guard — throw before building the Flow
        budgetGuard?.checkBeforeCall()
        privacy.guard(model.provider)
        return delegate.executeStreaming(prompt, model)
    }

    /**
     * Exposes the [BudgetGuard] for external token accounting (e.g. desktop's AgentRunner
     * which reads token counts from Koog streaming events). Returns `null` when no budget is set.
     */
    public fun budgetGuard(): BudgetGuard? = budgetGuard

    /** Active settings snapshot — read-only copy. */
    public fun currentSettings(): KumlAiSettings = settings

    /** Exposes the underlying PromptExecutor for AIAgent integration (V3.0.24). */
    public fun promptExecutor(): PromptExecutor = delegate

    /**
     * Releases resources held by the underlying [PromptExecutor] (e.g. Gonka's owned
     * HTTP client). Delegates to Koog's own `PromptExecutorAPI.close()`, which already
     * closes every constructed `LLMClient` in this executor's provider map. Never throws —
     * failures are logged and swallowed, matching typical `Closeable.close()` contracts.
     */
    override fun close() {
        runCatching { delegate.close() }
            .onFailure { log.warn("KumlAiExecutor.close() failed to close delegate PromptExecutor", it) }
    }

    public companion object {
        private val log = LoggerFactory.getLogger(KumlAiExecutor::class.java)

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
                        "choose a built-in provider: openai, anthropic, google, ollama, gonka)",
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

            // Build BudgetGuard from bundled pricing (no network call at construction time).
            val guard =
                settings.costBudgetUsd?.let { budget ->
                    BudgetGuard(budgetUsd = budget, estimator = ProviderPricingService.bundledEstimator())
                }

            return KumlAiExecutor(
                delegate = delegate,
                settings = settings,
                privacy = privacy,
                registry = registry,
                budgetGuard = guard,
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
            budgetGuard: BudgetGuard? = null,
        ): KumlAiExecutor =
            KumlAiExecutor(delegate = delegate, settings = settings, privacy = privacy, registry = registry, budgetGuard = budgetGuard)
    }

    private fun resolveDefaultModel(): LLModel {
        val providerId = settings.defaultProvider
        val modelId =
            settings.defaultModels[providerId]
                ?: error("No default model configured for provider '$providerId'")

        return registry.resolveModel(providerId = providerId, modelId = modelId)
            ?: error("Cannot resolve model '$modelId' for provider '$providerId'")
    }
}
