package dev.kuml.desktop.ai

/**
 * Accumulates a LOCAL ESTIMATE of token usage/cost for the current session — NOT a live account
 * balance or billing query (V3.7.4, design-review P3: "show the real remaining credit/balance").
 *
 * [costUsd] is computed purely client-side from token counts × [PricingTable]'s per-model
 * $/1K-token rates; it is never fetched from any provider. There is no provider-agnostic way to
 * do better: Koog's `LLMClient`/`PromptExecutor` (the abstraction every provider in this app goes
 * through) is a pure chat-completion interface with no billing/account endpoint, and two of the
 * five built-in providers (Ollama, Gonka) have no billing concept at all — a real balance query
 * would be a bespoke REST call per cloud provider with its own auth scope, and would simply not
 * exist for the other two. The local estimate here is the one thing that covers all five
 * providers uniformly, so it is the broader solution, not a fallback for a missing feature.
 */
class TokenUsageTracker(
    private val pricing: PricingTable,
) {
    private var _tokensIn: Int = 0
    private var _tokensOut: Int = 0
    private var _costUsd: Double = 0.0

    // Plain private fields, not backing properties (no public providerId/modelId
    // getter is exposed) — no leading underscore, per ktlint's
    // backing-property-naming rule (fixed 2026-07-17 alongside the ktlint-
    // coverage gap; the underscore prefix is reserved for fields that back a
    // same-named public property, which these do not).
    private var lastProviderId: String = ""
    private var lastModelId: String = ""

    val tokensIn: Int get() = _tokensIn
    val tokensOut: Int get() = _tokensOut
    val costUsd: Double get() = _costUsd

    fun accumulate(
        providerId: String,
        modelId: String,
        inTok: Int,
        outTok: Int,
    ) {
        lastProviderId = providerId
        lastModelId = modelId
        _tokensIn += inTok
        _tokensOut += outTok
        _costUsd += pricing.costUsd(providerId = providerId, modelId = modelId, tokensIn = inTok, tokensOut = outTok)
    }

    fun reset() {
        _tokensIn = 0
        _tokensOut = 0
        _costUsd = 0.0
        lastProviderId = ""
        lastModelId = ""
    }

    fun isBudgetExceeded(budgetUsd: Double?): Boolean = budgetUsd != null && _costUsd > budgetUsd
}
