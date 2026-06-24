package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A phase — one horizontal column of the blueprint grid.
 *
 * [order] is the 0-based column position. It must be gap-free and unique across
 * the model; this is NOT enforced in the constructor (so partial models stay
 * buildable) but is checked by `BlueprintConstraintChecker` (V3.1.25).
 *
 * V3.1.21
 */
@Serializable
data class Phase(
    override val id: String,
    override val name: String?,
    val order: Int,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
