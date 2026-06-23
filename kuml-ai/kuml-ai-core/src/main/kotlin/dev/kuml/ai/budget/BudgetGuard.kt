package dev.kuml.ai.budget

import dev.kuml.ai.KumlAiException
import dev.kuml.ai.pricing.CostEstimator

/**
 * Session-scoped, thread-safe cumulative spend accumulator with a hard USD cap.
 *
 * Usage pattern:
 * 1. Call [checkBeforeCall] before initiating each LLM call — throws [KumlAiException.BudgetExceeded]
 *    if the budget is already exhausted.
 * 2. Call [recordUsage] after the call returns with the actual token counts — accumulates spend
 *    and throws [KumlAiException.BudgetExceeded] if this call pushed the session over the cap.
 *
 * A null [budgetUsd] means unlimited — both methods become no-ops in that case.
 *
 * Unknown (provider, model) pairs produce a `null` cost estimate from [CostEstimator.estimate];
 * these are treated as zero cost (never block on an unpriced model).
 */
public class BudgetGuard(
    private val budgetUsd: Double?,
    private val estimator: CostEstimator,
) {
    private val lock = Any()
    private var spentUsd: Double = 0.0

    /** Running total of estimated spend in this session. */
    public val currentSpendUsd: Double
        get() = synchronized(lock) { spentUsd }

    /** The configured limit, or `null` if unlimited. */
    public val limitUsd: Double?
        get() = budgetUsd

    /**
     * Pre-call guard — throws if the budget is already at or above the limit.
     * No-op when [budgetUsd] is null.
     *
     * @throws KumlAiException.BudgetExceeded if spent >= budget.
     */
    public fun checkBeforeCall() {
        val b = budgetUsd ?: return
        synchronized(lock) {
            if (spentUsd >= b) throw KumlAiException.BudgetExceeded(spentUsd, b)
        }
    }

    /**
     * Post-call accounting — adds the cost of one call and throws if the cap is exceeded.
     * Unknown models are treated as zero cost (no exception from unpriced models).
     *
     * @param providerId Provider id of the call (e.g. "openai").
     * @param modelId    Model id of the call (e.g. "gpt-4o").
     * @param inTok      Input tokens consumed.
     * @param outTok     Output tokens produced.
     * @throws KumlAiException.BudgetExceeded if the accumulated spend now exceeds the budget.
     */
    public fun recordUsage(
        providerId: String,
        modelId: String,
        inTok: Long,
        outTok: Long,
    ) {
        val cost = estimator.estimate(providerId, modelId, inTok, outTok) ?: 0.0
        synchronized(lock) {
            spentUsd += cost
            val b = budgetUsd ?: return
            if (spentUsd > b) throw KumlAiException.BudgetExceeded(spentUsd, b)
        }
    }

    /** Reset the accumulated spend to zero (e.g. when starting a new session). */
    public fun reset() {
        synchronized(lock) { spentUsd = 0.0 }
    }
}
