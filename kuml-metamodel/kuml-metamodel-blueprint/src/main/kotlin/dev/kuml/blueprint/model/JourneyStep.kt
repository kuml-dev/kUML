package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * The atomic cell content of a blueprint: one step living in exactly one
 * (phase × layer) cell.
 *
 * - [phaseRef] points to a [Phase].
 * - [touchpointRefs] / [actorRef] are references into [BlueprintModel], not ownership.
 * - [sentiment] is only meaningful in [BlueprintLayer.CUSTOMER_ACTIONS] (emotion curve).
 *
 * V3.1.21
 */
@Serializable
data class JourneyStep(
    override val id: String,
    override val name: String?,
    val phaseRef: String,
    val layer: BlueprintLayer = BlueprintLayer.CUSTOMER_ACTIONS,
    val touchpointRefs: List<String> = emptyList(),
    val actorRef: String? = null,
    val sentiment: Sentiment? = null,
    val painPoint: String? = null,
    val opportunity: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
