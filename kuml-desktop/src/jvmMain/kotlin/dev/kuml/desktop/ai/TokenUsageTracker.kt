package dev.kuml.desktop.ai

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
        _costUsd += pricing.costUsd(providerId, modelId, inTok, outTok)
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
