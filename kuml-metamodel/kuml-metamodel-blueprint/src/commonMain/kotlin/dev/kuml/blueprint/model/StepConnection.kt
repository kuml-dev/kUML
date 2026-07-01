package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Line style for a [StepConnection]. DASHED = cross-layer support connection. */
@Serializable
enum class ConnectionStyle { SOLID, DASHED }

/**
 * A directed flow between two steps (or touchpoints) — Pattern A, lives on the
 * model because it carries the journey's cross-layer causality identity.
 *
 * V3.1.21
 */
@Serializable
data class StepConnection(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    val style: ConnectionStyle = ConnectionStyle.SOLID,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BlueprintElement
