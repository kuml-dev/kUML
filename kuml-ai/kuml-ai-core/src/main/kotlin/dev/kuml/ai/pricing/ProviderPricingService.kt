package dev.kuml.ai.pricing

import kotlinx.serialization.json.Json

/**
 * Loads provider pricing — live from remote first, bundled fallback on any failure.
 *
 * The live endpoint is `https://kuml.dev/api/pricing.json` (hardcoded in [HttpsPricingFetcher]).
 * The bundled snapshot lives at `FALLBACK_RESOURCE` on the classpath.
 *
 * This class is stateless — each [load] call fetches or reads fresh data.
 * For performance-sensitive callers (e.g. executor construction), use [bundledEstimator]
 * which always reads from the classpath without a network round-trip.
 *
 * Construct via [create] for production or [forTest] to inject a stub fetcher.
 */
public class ProviderPricingService internal constructor(
    private val fetcher: PricingFetcher,
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Load pricing.
     *
     * 1. Attempt live fetch via [fetcher].
     * 2. On any fetch or parse failure, fall back to the bundled classpath snapshot.
     * 3. If the bundled snapshot is also missing or corrupt, return an empty document (never throws).
     *
     * @return [LoadedPricing] with [LoadedPricing.live]=true when the remote fetch succeeded.
     */
    public fun load(): LoadedPricing {
        val liveJson = fetcher.fetch()
        if (liveJson != null) {
            val doc = runCatching { json.decodeFromString<PricingDocument>(liveJson) }.getOrNull()
            if (doc != null && doc.entries.isNotEmpty()) {
                return LoadedPricing(doc, live = true)
            }
        }
        return LoadedPricing(loadFallback(json), live = false)
    }

    /** Result of [load]. */
    public data class LoadedPricing(
        val document: PricingDocument,
        /** `true` if the data came from the remote endpoint; `false` if from the bundled fallback. */
        val live: Boolean,
    )

    public companion object {
        public const val FALLBACK_RESOURCE: String = "/dev/kuml/ai/pricing/pricing-fallback.json"

        private val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true }

        /** Production factory — uses HTTPS fetcher + bundled fallback. */
        public fun create(): ProviderPricingService = ProviderPricingService(HttpsPricingFetcher())

        /** Test factory — inject any [PricingFetcher] stub. */
        public fun forTest(fetcher: PricingFetcher): ProviderPricingService = ProviderPricingService(fetcher)

        /**
         * Load the bundled fallback document from the classpath.
         * Returns an empty [PricingDocument] if the resource is absent or unreadable.
         */
        public fun loadFallback(json: Json = DEFAULT_JSON): PricingDocument {
            val text =
                ProviderPricingService::class.java
                    .getResourceAsStream(FALLBACK_RESOURCE)
                    ?.bufferedReader()
                    ?.readText()
                    ?: return PricingDocument(source = "fallback-missing")
            return runCatching { json.decodeFromString<PricingDocument>(text) }
                .getOrDefault(PricingDocument(source = "fallback-corrupt"))
        }

        /**
         * Convenience: build a [CostEstimator] directly from the bundled fallback without
         * any network call. Use this in executor construction to avoid latency.
         */
        public fun bundledEstimator(): CostEstimator = CostEstimator.fromDocument(loadFallback())
    }
}
