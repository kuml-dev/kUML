package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
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

    /** Records that a profile is applied to this container scope. */
    fun addAppliedProfile(profile: KumlProfile)

    /** Returns the list of profiles currently applied to this container scope. */
    fun appliedProfiles(): List<KumlProfile>
}

/**
 * Marker for element builders that can accept stereotype applications.
 *
 * Implemented by [ClassBuilder], [InterfaceBuilder], [EnumerationBuilder],
 * [ComponentBuilder], [PackageBuilder], [ActorBuilder], [UseCaseBuilder],
 * and related builders.
 */
@KumlDsl
interface UmlElementScope {
    /** The UML metaclass of the element being built. */
    val metaclass: UmlMetaclass

    /** Records a stereotype application on the element under construction. */
    fun addStereotype(app: KumlStereotypeApplication)

    /** The enclosing container — used to look up applied profiles. */
    val container: UmlContainerScope
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
 * Receiver scope for [dev.kuml.uml.UmlStateMachine] bodies.
 *
 * Implemented by [StateDiagramBuilder]. Sub-scopes for composite states
 * implement [UmlCompositeStateScope].
 */
@KumlDsl
interface UmlStateMachineScope {
    /** Qualified ID of the enclosing state machine (= state diagram name). */
    val stateMachineId: String

    /** Shared mutable ID registry across the whole state diagram. */
    val takenIds: MutableSet<String>

    /** Internal: called by [state], [initialState], etc. to register a vertex. */
    fun addVertex(vertex: dev.kuml.uml.UmlVertex)

    /** Internal: called by [transition] to register a transition. */
    fun addTransition(transition: dev.kuml.uml.UmlTransition)
}

/**
 * Receiver scope for [dev.kuml.uml.UmlState.substates] inside a composite state.
 *
 * Substate-IDs are derived from the enclosing state's ID, not from the
 * state machine's ID.
 *
 * Transitions are NOT declared in this scope — declare them at the
 * [UmlStateMachineScope] level using vertex handles returned here.
 */
@KumlDsl
interface UmlCompositeStateScope {
    /** Qualified ID of the enclosing composite state. */
    val parentStateId: String

    /** Shared mutable ID registry across the whole state diagram. */
    val takenIds: MutableSet<String>

    /** Internal: called by [state], [initialState], etc. to register a substate. */
    fun addSubstate(vertex: dev.kuml.uml.UmlVertex)
}

/**
 * Receiver scope for [dev.kuml.uml.UmlInteraction] bodies — the top-level
 * scope of a sequence diagram.
 *
 * Implemented by [SequenceDiagramBuilder]. Fragment branches expose a
 * narrower scope [UmlInteractionOperandScope] (no [lifeline] there).
 */
@KumlDsl
interface UmlInteractionScope {
    /** Qualified ID of the enclosing interaction (= diagram name). */
    val interactionId: String

    /** Shared mutable ID registry across the whole sequence diagram. */
    val takenIds: MutableSet<String>

    /** Returns the next 1-based sequence number for a message. */
    fun nextSequenceNumber(): Int

    /** Returns the next 1-based index for a combined fragment. */
    fun nextFragmentIndex(): Int

    /** Internal: called by [lifeline] to register a participant. */
    fun addLifeline(lifeline: dev.kuml.uml.UmlLifeline)

    /** Internal: called by [message] (and convenience overloads) to register a message. */
    fun addMessage(message: dev.kuml.uml.UmlMessage)

    /** Internal: called by [fragment] to register a combined fragment. */
    fun addFragment(fragment: dev.kuml.uml.UmlCombinedFragment)
}

/**
 * Receiver scope for [dev.kuml.uml.UmlInteractionOperand] bodies — one
 * branch inside a combined fragment.
 *
 * Messages and nested fragments added here are physically stored on the
 * enclosing [UmlInteraction] but their IDs are additionally recorded in
 * this operand for the structural reference.
 *
 * Lifelines cannot be declared here — declare them at the diagram scope.
 */
@KumlDsl
interface UmlInteractionOperandScope {
    /** Qualified ID of the enclosing interaction. */
    val interactionId: String

    /** Shared mutable ID registry across the whole sequence diagram. */
    val takenIds: MutableSet<String>

    /** Returns the next 1-based sequence number for a message. */
    fun nextSequenceNumber(): Int

    /** Returns the next 1-based index for a combined fragment. */
    fun nextFragmentIndex(): Int

    /** Internal: called by [message] etc. — registers on interaction + records ID here. */
    fun addMessage(message: dev.kuml.uml.UmlMessage)

    /** Internal: called by [fragment] — registers on interaction + records ID here. */
    fun addFragment(fragment: dev.kuml.uml.UmlCombinedFragment)
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

    /**
     * The enclosing container scope — used by feature builders ([AttributeBuilder],
     * [OperationBuilder]) to look up applied profiles for stereotype resolution.
     *
     * Implemented by [ClassBuilder] and [InterfaceBuilder], both of which already
     * carry a [UmlContainerScope] reference passed down from [classOf]/[interfaceOf].
     */
    val container: UmlContainerScope

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

    /** Internal: called by [constraint] to register an OCL constraint. */
    fun addConstraint(constraint: dev.kuml.uml.UmlConstraint)
}
