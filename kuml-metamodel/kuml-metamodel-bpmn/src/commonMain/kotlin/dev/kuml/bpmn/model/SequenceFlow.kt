package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A directed edge in a BPMN process flow.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property sourceRef ID of the source flow node.
 * @property targetRef ID of the target flow node.
 * @property conditionExpression Optional condition that must evaluate to `true` for the token to pass.
 * @property isDefault When `true`, this is the default flow of a gateway or activity.
 * @property immediate When `true`, the flow bypasses any attached intermediate events.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class SequenceFlow(
    override val id: String,
    override val name: String? = null,
    val sourceRef: String,
    val targetRef: String,
    val conditionExpression: String? = null,
    val isDefault: Boolean = false,
    val immediate: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnFlowElement
