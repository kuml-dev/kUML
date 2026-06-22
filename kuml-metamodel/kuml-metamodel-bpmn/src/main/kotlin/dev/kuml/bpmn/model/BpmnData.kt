package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A BPMN data object — represents a piece of information that flows through a process.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property collection When `true`, this data object represents a collection.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnDataObject(
    override val id: String,
    override val name: String? = null,
    val collection: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnFlowElement

/**
 * A BPMN data store — represents a persistent repository of data.
 *
 * @property id Stable element identifier.
 * @property name Optional human-readable label.
 * @property unlimited When `true`, the data store has unlimited capacity.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class BpmnDataStore(
    override val id: String,
    override val name: String? = null,
    val unlimited: Boolean = false,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * A directed association between a data object (or store) and an activity.
 *
 * @property id Stable element identifier.
 * @property sourceRef ID of the source element.
 * @property targetRef ID of the target element.
 * @property name Optional human-readable label.
 * @property metadata Arbitrary additional metadata.
 */
@Serializable
data class DataAssociation(
    override val id: String,
    val sourceRef: String,
    val targetRef: String,
    override val name: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement
