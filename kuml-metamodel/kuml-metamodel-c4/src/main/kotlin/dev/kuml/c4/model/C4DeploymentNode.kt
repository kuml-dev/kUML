package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 deployment node element representing a hardware or application node in a deployment diagram.
 *
 * Deployment nodes can be hierarchically nested via [children].
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property technology Optional technology specification
 * @property instances Number of instances of this node (default 1)
 * @property containerInstances List of container instance element IDs deployed on this node
 * @property children List of child deployment node element IDs for hierarchical nesting
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4DeploymentNode(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val technology: String? = null,
    val instances: Int = 1,
    val containerInstances: List<ElementId> = emptyList(),
    val children: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
