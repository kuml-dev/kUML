package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4DeploymentNode
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutHintsScope

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
 *
 * Extends [LayoutHintsScope] so that `layout { … }` is available:
 * ```kotlin
 * person("Customer") {
 *     layout { col = 1; row = 1 }
 * }
 * ```
 */
@KumlDsl
interface PersonScope : LayoutHintsScope {
    var description: String?
    var external: Boolean
    var location: String?
}

/**
 * Scope for building software systems in a C4 model.
 *
 * Extends [LayoutHintsScope] so that `layout { … }` is available:
 * ```kotlin
 * softwareSystem("Banking System") {
 *     layout { col = 2; row = 1 }
 * }
 * ```
 */
@KumlDsl
interface SoftwareSystemScope :
    C4ModelScope,
    LayoutHintsScope {
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
 *
 * Extends [LayoutHintsScope] so that `layout { … }` is available:
 * ```kotlin
 * container("Web App") {
 *     technology = "Spring Boot"
 *     layout { col = 2; row = 1 }
 * }
 * ```
 */
@KumlDsl
interface ContainerScope :
    C4ModelScope,
    LayoutHintsScope {
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
 *
 * Extends [LayoutHintsScope] so that `layout { … }` is available:
 * ```kotlin
 * component("Auth Service") {
 *     technology = "Spring Security"
 *     layout { col = 1; row = 2 }
 * }
 * ```
 */
@KumlDsl
interface ComponentScope : LayoutHintsScope {
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
    var description: String?
    var technology: String?
    var bidirectional: Boolean
}
