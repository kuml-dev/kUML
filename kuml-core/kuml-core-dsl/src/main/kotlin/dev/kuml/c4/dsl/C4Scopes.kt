package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.dsl.KumlDsl

/**
 * Base scope for C4 model builders.
 *
 * Provides common element and relationship tracking.
 */
@KumlDsl
interface C4ModelScope {
    val takenIds: MutableSet<String>
    val elements: MutableList<C4Element>
    val relationships: MutableList<C4Relationship>

    fun addElement(element: C4Element) {
        elements += element
        takenIds += element.id
    }

    fun addRelationship(relationship: C4Relationship) {
        relationships += relationship
        takenIds += relationship.id
    }
}

/**
 * Scope for building persons in a C4 model.
 */
@KumlDsl
interface PersonScope {
    var description: String?
    var external: Boolean
    var location: String?
}

/**
 * Scope for building software systems in a C4 model.
 */
@KumlDsl
interface SoftwareSystemScope : C4ModelScope {
    var description: String?
    var external: Boolean
    var location: String?
    val systemId: String

    fun container(
        name: String,
        block: ContainerScope.() -> Unit = {},
    ): C4Container
}

/**
 * Scope for building containers in a C4 software system.
 */
@KumlDsl
interface ContainerScope : C4ModelScope {
    var description: String?
    var technology: String?
    val containerId: String

    fun component(
        name: String,
        block: ComponentScope.() -> Unit = {},
    ): C4Component
}

/**
 * Scope for building components in a C4 container.
 */
@KumlDsl
interface ComponentScope {
    var description: String?
    var technology: String?
}

/**
 * Scope for building deployment nodes in a C4 model.
 */
@KumlDsl
interface DeploymentNodeScope : C4ModelScope {
    var description: String?
    var technology: String?
    var instances: Int
    val nodeId: String

    fun node(
        name: String,
        block: DeploymentNodeScope.() -> Unit = {},
    ): C4DeploymentNode

    fun containerInstance(
        name: String,
        containerId: String,
    )
}

/**
 * Scope for defining relationships between C4 elements.
 */
@KumlDsl
interface RelationshipScope {
    var technology: String?
    var bidirectional: Boolean
}
