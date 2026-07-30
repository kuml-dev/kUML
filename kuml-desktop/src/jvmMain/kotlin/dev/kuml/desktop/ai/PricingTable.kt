package dev.kuml.desktop.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ModelPrice(
    val id: String,
    val inputPer1kTokens: Double,
    val outputPer1kTokens: Double,
)

@Serializable
private data class ProviderPricing(
    val id: String,
    val models: List<ModelPrice>,
)

@Serializable
private data class PricingSchema(
    val schemaVersion: Int,
    val providers: List<ProviderPricing>,
)

class PricingTable private constructor(
    private val schema: PricingSchema,
) {
    fun modelsForProvider(providerId: String): List<String> =
        schema.providers
            .firstOrNull { it.id == providerId }
            ?.models
            ?.map { it.id } ?: emptyList()

    fun costUsd(
        providerId: String,
        modelId: String,
        tokensIn: Int,
        tokensOut: Int,
    ): Double {
        val m =
            schema.providers
                .firstOrNull { it.id == providerId }
                ?.models
                ?.firstOrNull { it.id == modelId } ?: return 0.0
        return (tokensIn * m.inputPer1kTokens + tokensOut * m.outputPer1kTokens) / 1000.0
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadFromResources(): PricingTable {
            val text =
                PricingTable::class.java
                    .getResourceAsStream("/dev/kuml/desktop/ai/pricing.json")
                    ?.bufferedReader()
                    ?.readText()
                    ?: return PricingTable(PricingSchema(schemaVersion = 1, providers = emptyList()))
            return PricingTable(json.decodeFromString(text))
        }

        fun forTest(vararg entries: Pair<String, List<String>>): PricingTable =
            PricingTable(
                PricingSchema(
                    schemaVersion = 1,
                    providers =
                        entries.map { (p, ms) ->
                            ProviderPricing(
                                id = p,
                                models = ms.map { ModelPrice(id = it, inputPer1kTokens = 0.001, outputPer1kTokens = 0.002) },
                            )
                        },
                ),
            )
    }
}
