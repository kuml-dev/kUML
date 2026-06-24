package dev.kuml.blueprint.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A named diagram view (projection) over a [BlueprintModel].
 *
 * Two concrete views share one metamodel:
 * - [JourneyDiagram] — slim Journey Map (Customer layer + emotion curve).
 * - [BlueprintDiagramFull] — full Service Blueprint (4 layers + 3 lines).
 *
 * V3.1.21
 */
@Serializable
sealed interface BlueprintDiagram {
    val name: String
    val elementIds: List<String>
}

/**
 * Journey-Map view: by default only the Customer-Actions layer plus the
 * emotion curve. Empty [elementIds] means the whole model is projected.
 *
 * V3.1.21
 */
@Serializable
@SerialName("JourneyDiagram")
data class JourneyDiagram(
    override val name: String,
    val visibleLayers: Set<BlueprintLayer> = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
    val showEmotionCurve: Boolean = true,
    override val elementIds: List<String> = emptyList(),
) : BlueprintDiagram

/**
 * Service-Blueprint view: all four layers and all three separator lines by
 * default. Empty [elementIds] means the whole model is projected.
 *
 * V3.1.21
 */
@Serializable
@SerialName("BlueprintDiagramFull")
data class BlueprintDiagramFull(
    override val name: String,
    val visibleLayers: Set<BlueprintLayer> = BlueprintLayer.entries.toSet(),
    val showLines: Set<BlueprintLine> = BlueprintLine.entries.toSet(),
    val showEmotionCurve: Boolean = false,
    override val elementIds: List<String> = emptyList(),
) : BlueprintDiagram
