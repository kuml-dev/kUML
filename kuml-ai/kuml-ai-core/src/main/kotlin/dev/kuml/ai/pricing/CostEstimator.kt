package dev.kuml.ai.pricing

/**
 * Computes USD cost for LLM calls given per-MTok pricing entries.
 *
 * Per-MTok pricing means the cost in USD for one million tokens.
 * Example: `inputPricePerMToken = 5.0` means $5.00 per 1,000,000 input tokens.
 */
public class CostEstimator(
    private val entries: List<PricingEntry>,
) {
    private val byKey: Map<Pair<String, String>, PricingEntry> =
        entries.associateBy { it.providerId to it.modelId }

    /**
     * Estimate USD cost for a single call.
     *
     * @param providerId Provider id (e.g. "openai", "anthropic", "ollama").
     * @param modelId    Model id (e.g. "gpt-4o", "claude-sonnet-4-5").
     * @param inputTokens  Number of prompt/input tokens consumed.
     * @param outputTokens Number of completion/output tokens produced.
     * @return USD cost, or `null` if the (provider, model) pair is not in this estimator's entries.
     *         Callers should treat `null` as "unknown cost" — not as zero — and log or surface it.
     */
    public fun estimate(
        providerId: String,
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
    ): Double? {
        val e = byKey[providerId to modelId] ?: return null
        return inputTokens * e.inputPricePerMToken / 1_000_000.0 +
            outputTokens * e.outputPricePerMToken / 1_000_000.0
    }

    /**
     * Sorted list of all pricing entries — used by `kuml ai pricing` table output.
     * Sorted by providerId then modelId for deterministic display.
     */
    public fun rows(): List<PricingEntry> = entries.sortedWith(compareBy({ it.providerId }, { it.modelId }))

    public companion object {
        /** Build a [CostEstimator] from a [PricingDocument]. */
        public fun fromDocument(doc: PricingDocument): CostEstimator = CostEstimator(doc.entries)

        /** Empty estimator — all lookups return `null`. Useful as a safe default. */
        public fun empty(): CostEstimator = CostEstimator(emptyList())
    }
}
