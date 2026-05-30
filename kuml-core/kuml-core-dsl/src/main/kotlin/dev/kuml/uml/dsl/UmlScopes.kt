package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Receiver scope for UML containers that own named elements (classifiers and packages).
 *
 * Implemented by [UmlModelBuilder], [dev.kuml.core.dsl.DiagramBuilder], and [PackageBuilder].
 * The builders for [classOf], [interfaceOf], [enumOf], and [packageOf] are
 * defined as extension functions on this interface.
 */
@KumlDsl
interface UmlContainerScope {
    /** Qualified ID of this container, or `null` at the root level. */
    val containerId: String?

    /**
     * Shared mutable set of every ID already assigned within this model build.
     * Passed by reference to all child builders so that collision detection
     * works across the whole model, regardless of nesting depth.
     */
    val takenIds: MutableSet<String>

    /** Adds a named element (classifier, package) to this container. */
    fun addNamedElement(element: UmlNamedElement)
}

/**
 * Extended scope for diagram and model roots — additionally allows relationships.
 *
 * Implemented by [UmlModelBuilder] and [dev.kuml.core.dsl.DiagramBuilder].
 *
 * [PackageBuilder] implements only [UmlContainerScope] because the current
 * [dev.kuml.uml.UmlPackage] metamodel does not carry owned relationships.
 * Use top-level [association], [generalization], [realization], or [dependency]
 * in a diagram or model scope to add relationships between packaged elements.
 *
 * Note: [classOf] and [interfaceOf] are also overloaded on [UmlModelScope] so
 * that inline [ClassBuilder.extends] / [ClassBuilder.implements] calls are
 * propagated to the diagram as relationships.  When called inside a [PackageBuilder]
 * (which is only [UmlContainerScope]), those inline declarations are silently
 * ignored — declare the relationships explicitly at diagram level instead.
 */
@KumlDsl
interface UmlModelScope : UmlContainerScope {
    /** Adds a relationship (association, generalization, …) to this diagram or model root. */
    fun addRelationship(relationship: UmlRelationship)
}

/**
 * Receiver scope for [dev.kuml.uml.UmlComponent] bodies — owns ports,
 * nested components, and provided/required interface references.
 *
 * Implemented by [ComponentBuilder].
 */
@KumlDsl
interface UmlComponentScope {
    /** Qualified ID of the owning component. */
    val ownerId: String

    /** Shared mutable ID registry — same instance as the enclosing container. */
    val takenIds: MutableSet<String>

    /** Internal: called by [port] to register a port. */
    fun addPort(port: dev.kuml.uml.UmlPort)

    /** Internal: called by [component] (nested) to register a sub-component. */
    fun addNestedComponent(component: dev.kuml.uml.UmlComponent)

    /** Internal: called by [provides] to register a provided interface ID. */
    fun addProvidedInterface(interfaceId: String)

    /** Internal: called by [requires] to register a required interface ID. */
    fun addRequiredInterface(interfaceId: String)
}

/**
 * Receiver scope for classifier bodies — owns attributes, operations,
 * and inline relationship declarations.
 *
 * Implemented by [ClassBuilder] and [InterfaceBuilder].
 */
@KumlDsl
interface UmlClassifierScope {
    /**
     * Qualified ID of the owning classifier.
     *
     * Child features (attributes, operations) derive their IDs from this.
     */
    val ownerId: String

    /** Shared mutable ID registry — same instance as the enclosing container. */
    val takenIds: MutableSet<String>

    /** Internal: called by [attribute] to register a property. */
    fun addAttribute(property: dev.kuml.uml.UmlProperty)

    /** Internal: called by [operation] to register an operation. */
    fun addOperation(op: dev.kuml.uml.UmlOperation)

    /** Internal: called by [extends] to record a pending generalization. */
    fun addPendingGeneralization(
        specificId: String,
        generalId: String,
    )

    /** Internal: called by [implements] to record a pending realization. */
    fun addPendingRealization(
        implementingId: String,
        interfaceId: String,
    )
}
