package dev.kuml.cli.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.kuml.ai.pricing.CostEstimator
import dev.kuml.ai.pricing.PricingDocument
import dev.kuml.ai.pricing.PricingEntry
import dev.kuml.ai.pricing.PricingFetcher
import dev.kuml.ai.pricing.ProviderPricingService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `kuml ai pricing` — display LLM provider pricing per million tokens.
 *
 * Fetches live pricing from `https://kuml.dev/api/pricing.json` (5 s timeout, 64 KB cap);
 * falls back silently to the bundled snapshot when offline or on any fetch error.
 *
 * Options:
 *   --format text|json   Output format (default: text).
 *   --no-fetch           Skip the live fetch and use the bundled fallback immediately.
 *
 * Exit code: always 0 (fallback guarantees a result).
 */
internal class AiPricingCommand : CliktCommand(name = "pricing") {
    private val format by option("--format", help = "Output format: text or json")
        .choice("text", "json")
        .default("text")

    private val noFetch by option(
        "--no-fetch",
        help = "Skip live fetch — use bundled fallback pricing immediately (useful offline or in tests)",
    ).flag()

    override fun help(context: Context): String = "Show LLM provider pricing per million tokens (live from kuml.dev or bundled fallback)."

    override fun run() {
        val service =
            if (noFetch) {
                ProviderPricingService.forTest(PricingFetcher { null })
            } else {
                ProviderPricingService.create()
            }

        val loaded = service.load()
        val estimator = CostEstimator.fromDocument(loaded.document)
        val rows = estimator.rows()

        when (format) {
            "json" -> renderJson(loaded.document)
            else -> renderText(rows, loaded)
        }
    }

    private fun renderText(
        rows: List<PricingEntry>,
        loaded: ProviderPricingService.LoadedPricing,
    ) {
        val sourceLabel =
            if (loaded.live) {
                "(live from kuml.dev)"
            } else {
                "(bundled fallback, as of ${loaded.document.generatedAt.take(10).ifBlank { "unknown" }})"
            }
        echo("LLM Provider Pricing — USD per million tokens $sourceLabel\n")

        if (rows.isEmpty()) {
            echo("No pricing data available.")
            return
        }

        val provWidth = rows.maxOf { it.providerId.length }.coerceAtLeast(8)
        val modelWidth = rows.maxOf { it.modelId.length }.coerceAtLeast(12)

        val header =
            "%-${provWidth}s  %-${modelWidth}s  %12s  %13s  UPDATED".format(
                "PROVIDER",
                "MODEL",
                "\$/MTok IN",
                "\$/MTok OUT",
            )
        echo(header)
        echo("-".repeat(header.length))

        for (r in rows) {
            echo(
                "%-${provWidth}s  %-${modelWidth}s  %12s  %13s  %s".format(
                    r.providerId,
                    r.modelId,
                    if (r.inputPricePerMToken == 0.0) "free" else "$%.2f".format(r.inputPricePerMToken),
                    if (r.outputPricePerMToken == 0.0) "free" else "$%.2f".format(r.outputPricePerMToken),
                    r.updatedAt.ifBlank { "—" },
                ),
            )
        }
    }

    private fun renderJson(doc: PricingDocument) {
        val pricingJson =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
        echo(pricingJson.encodeToString(doc))
    }
}
