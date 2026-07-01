package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/** Routing logic of a BPMN gateway. */
@Serializable
enum class GatewayType {
    EXCLUSIVE,
    INCLUSIVE,
    PARALLEL,
    EVENT_BASED,
    COMPLEX,
}

/** Whether the gateway splits, merges, or does both. */
@Serializable
enum class GatewayDirection {
    UNSPECIFIED,
    DIVERGING,
    CONVERGING,
    MIXED,
}

/**
 * A BPMN gateway — routes tokens through a process flow.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property gatewayType Routing logic.
 * @property direction Split / merge / mixed classification.
 * @property defaultFlow ID of the default (else) sequence flow, if any.
 * @property incoming IDs of incoming sequence flows.
 * @property outgoing IDs of outgoing sequence flows.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnGateway(
    override val id: String,
    override val name: String? = null,
    val gatewayType: GatewayType,
    val direction: GatewayDirection = GatewayDirection.UNSPECIFIED,
    val defaultFlow: String? = null,
    override val incoming: List<String> = emptyList(),
    override val outgoing: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnFlowNode
