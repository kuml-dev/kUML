package dev.kuml.c4.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A C4 container element representing an application or data store within a software system.
 *
 * @property id Unique stable identifier
 * @property name Human-readable name
 * @property description Optional description
 * @property technology Optional technology specification (e.g., "Spring Boot")
 * @property system Optional ID of the parent software system
 * @property instanceOf For a deployed container instance created via
 *   [dev.kuml.c4.dsl.DeploymentNodeScope.containerInstance], the ID of the [C4Container] this
 *   instance represents. `null` for a regular (non-instance) container declared via
 *   [dev.kuml.c4.dsl.SoftwareSystemScope.container].
 * @property components List of component element IDs that belong to this container
 * @property metadata Arbitrary additional metadata
 */
@Serializable
data class C4Container(
    override val id: ElementId,
    override val name: String,
    override val description: String? = null,
    val technology: String? = null,
    val system: ElementId? = null,
    val instanceOf: ElementId? = null,
    val components: List<ElementId> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : C4Element
