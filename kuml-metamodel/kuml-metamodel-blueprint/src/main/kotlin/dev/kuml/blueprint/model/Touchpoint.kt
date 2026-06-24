package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Geometric symbol drawn for a [Touchpoint]. */
@Serializable
enum class TouchpointSymbol { CIRCLE, DIAMOND, SQUARE, HEXAGON }

/**
 * A point of contact between customer and service in a concrete step.
 * References a [Channel] by id (Pattern: reference, not ownership).
 *
 * V3.1.21
 */
@Serializable
data class Touchpoint(
    override val id: String,
    override val name: String?,
    val channelRef: String? = null,
    val symbol: TouchpointSymbol = TouchpointSymbol.CIRCLE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
