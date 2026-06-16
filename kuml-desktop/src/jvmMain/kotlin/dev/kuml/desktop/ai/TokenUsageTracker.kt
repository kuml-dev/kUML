package dev.kuml.desktop.ai

class TokenUsageTracker(private val pricing: PricingTable) {
    private var _tokensIn: Int = 0
    private var _tokensOut: Int = 0
    private var _costUsd: Double = 0.0
    private var _providerId: String = ""
    private var _modelId: String = ""

    val tokensIn: Int get() = _tokensIn
    val tokensOut: Int get() = _tokensOut
    val costUsd: Double get() = _costUsd

    fun accumulate(providerId: String, modelId: String, inTok: Int, outTok: Int) {
        _providerId = providerId
        _modelId = modelId
        _tokensIn += inTok
        _tokensOut += outTok
        _costUsd += pricing.costUsd(providerId, modelId, inTok, outTok)
    }

    fun reset() {
        _tokensIn = 0
        _tokensOut = 0
        _costUsd = 0.0
        _providerId = ""
        _modelId = ""
    }

    fun isBudgetExceeded(budgetUsd: Double?): Boolean =
        budgetUsd != null && _costUsd > budgetUsd
}
