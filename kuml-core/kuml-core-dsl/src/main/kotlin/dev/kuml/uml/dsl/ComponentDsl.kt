package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPort
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

// ── component() on container scopes ──────────────────────────────────────────

/**
 * Adds a top-level [UmlComponent] to the enclosing diagram or model.
 *
 * ```kotlin
 * componentDiagram("Architecture") {
 *     val order = component("OrderService") {
 *         port("api")
 *         provides(orderApi)
 *     }
 * }
 * ```
 */
fun UmlContainerScope.component(
    name: String,
    id: String? = null,
    block: ComponentBuilder.() -> Unit = {},
): UmlComponent {
    val builder =
        ComponentBuilder(
            name = name,
            parentId = containerId,
            takenIds = takenIds,
            explicitId = id,
            container = this,
        )
    builder.block()
    val component = builder.buildComponent()
    addNamedElement(component)
    return component
}

/**
 * Adds a **nested** [UmlComponent] inside an enclosing component.
 *
 * ```kotlin
 * component("OrderService") {
 *     component("OrderRepository") { … }   // nested
 * }
 * ```
 *
 * The nested component's qualified ID is built from the parent component's ID.
 */
fun UmlComponentScope.component(
    name: String,
    id: String? = null,
    block: ComponentBuilder.() -> Unit = {},
): UmlComponent {
    // Resolve the enclosing container for profile lookup (ComponentBuilder implements UmlElementScope).
    val parentContainer =
        (this as? UmlElementScope)?.container
            ?: error("Nested component() must be called inside a ComponentBuilder that has a container reference.")
    val builder =
        ComponentBuilder(
            name = name,
            parentId = ownerId,
            takenIds = takenIds,
            explicitId = id,
            container = parentContainer,
        )
    builder.block()
    val component = builder.buildComponent()
    addNestedComponent(component)
    return component
}

// ── port() inside a component body ───────────────────────────────────────────

/**
 * Declares a [UmlPort] on the enclosing component.
 *
 * Ports cannot be declared at diagram level — they always belong to a component.
 *
 * @param name Port name (unique within the component).
 * @param type Optional type reference (typically an interface this port exposes).
 * @param isConjugated `true` if the component is a consumer at this port.
 */
fun UmlComponentScope.port(
    name: String,
    type: UmlTypeRef? = null,
    isConjugated: Boolean = false,
    visibility: Visibility = Visibility.PUBLIC,
    id: String? = null,
    block: PortBuilder.() -> Unit = {},
): UmlPort {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(ownerId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val parentContainer = (this as? UmlElementScope)?.container
    val builder = PortBuilder(parentContainer = parentContainer).apply(block)
    val port =
        UmlPort(
            id = resolvedId,
            name = name,
            visibility = visibility,
            type = type,
            isConjugated = isConjugated,
            stereotypes = builder.stereotypes.toList(),
            appliedStereotypes = builder.stereotypeApplications.toList<AppliedStereotype>(),
        )
    addPort(port)
    return port
}

@KumlDsl
class PortBuilder internal constructor(
    /** The enclosing container scope — used to look up applied profiles. */
    private val parentContainer: UmlContainerScope? = null,
) : UmlElementScope {
    override val metaclass: UmlMetaclass = UmlMetaclass.Port

    override val container: UmlContainerScope
        get() =
            parentContainer
                ?: error("PortBuilder has no container reference — stereotype() cannot resolve applied profiles.")

    val stereotypes: MutableList<String> = mutableListOf()
    internal val stereotypeApplications = mutableListOf<KumlStereotypeApplication>()

    override fun addStereotype(app: KumlStereotypeApplication) {
        stereotypeApplications += app
    }
}

// ── provides / requires inside a component body ──────────────────────────────

/**
 * Declares that the enclosing component provides (realises) the given interface.
 *
 * The interface ID is stored in [UmlComponent.providedInterfaceIds] and rendered
 * as a "lollipop" / ball symbol at diagram time.
 */
fun UmlComponentScope.provides(iface: UmlInterface): Unit = addProvidedInterface(iface.id)

fun UmlComponentScope.providesById(interfaceId: String): Unit = addProvidedInterface(interfaceId)

/**
 * Declares that the enclosing component requires (uses) the given interface.
 *
 * Rendered as a "socket" / half-circle symbol at diagram time.
 */
fun UmlComponentScope.requires(iface: UmlInterface): Unit = addRequiredInterface(iface.id)

fun UmlComponentScope.requiresById(interfaceId: String): Unit = addRequiredInterface(interfaceId)

// ── connect() at diagram / model level ───────────────────────────────────────

/**
 * Creates a [UmlConnector] between two ports (or parts) at the diagram level.
 *
 * ```kotlin
 * componentDiagram("Architecture") {
 *     val order   = component("OrderService") { port("api") }
 *     val invoice = component("InvoiceService") { port("orderEvents") }
 *     connect(end1 = order, port1 = "api", end2 = invoice, port2 = "orderEvents")
 * }
 * ```
 */
fun UmlModelScope.connect(
    end1: UmlComponent,
    port1: String,
    end2: UmlComponent,
    port2: String,
    name: String? = null,
    id: String? = null,
): UmlConnector =
    connectByIds(
        end1Id = "${end1.id}${UmlIds.SEP}$port1",
        end2Id = "${end2.id}${UmlIds.SEP}$port2",
        name = name,
        id = id,
    )

fun UmlModelScope.connect(
    end1: UmlPort,
    end2: UmlPort,
    name: String? = null,
    id: String? = null,
): UmlConnector = connectByIds(end1Id = end1.id, end2Id = end2.id, name = name, id = id)

fun UmlModelScope.connectByIds(
    end1Id: String,
    end2Id: String,
    name: String? = null,
    id: String? = null,
): UmlConnector {
    val relId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.connector(end1Id, end2Id),
            taken = takenIds,
        )
    takenIds += relId
    val connector = UmlConnector(id = relId, end1Id = end1Id, end2Id = end2Id, name = name)
    addRelationship(connector)
    return connector
}

// ── V1.1.3 — attribute() and operation() on UmlComponentScope ─────────────────
//
// Components in V1.1.3 may carry attributes and operations — needed e.g. for
// AUTOSAR SoftwareComponents that own Runnables. We deliberately do NOT make
// ComponentBuilder implement the full `UmlClassifierScope` interface; instead
// we mirror the feature-owner subset (addAttribute / addOperation) directly.
// Generalization/Realization between components and OCL constraints on
// components are V1.1.4+ topics — see ticket T4-A.

/** V1.1.3 — attribute() inside a component body, type as [UmlTypeRef]. */
fun UmlComponentScope.attribute(
    name: String,
    type: UmlTypeRef,
    visibility: Visibility = Visibility.PRIVATE,
    multiplicity: Multiplicity = Multiplicity(),
    defaultValue: String? = null,
    isStatic: Boolean = false,
    isReadOnly: Boolean = false,
    id: String? = null,
): UmlProperty {
    val builder =
        this as? ComponentBuilder
            ?: error("attribute() inside a component requires a ComponentBuilder scope.")
    val propId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(ownerId, name),
            taken = takenIds,
        )
    takenIds += propId
    val prop =
        UmlProperty(
            id = propId,
            name = name,
            visibility = visibility,
            type = type,
            multiplicity = multiplicity,
            defaultValue = defaultValue,
            isStatic = isStatic,
            isReadOnly = isReadOnly,
        )
    builder.addAttribute(prop)
    return prop
}

/** V1.1.3 — attribute() inside a component body, type by string name. */
fun UmlComponentScope.attribute(
    name: String,
    type: String,
    visibility: Visibility = Visibility.PRIVATE,
    multiplicity: Multiplicity = Multiplicity(),
    defaultValue: String? = null,
    isStatic: Boolean = false,
    isReadOnly: Boolean = false,
    id: String? = null,
): UmlProperty = attribute(name, typeRef(type), visibility, multiplicity, defaultValue, isStatic, isReadOnly, id)

/**
 * V1.1.3 — attribute() with builder block on a component, enabling stereotype calls.
 *
 * ```kotlin
 * component("SteeringControlSWC") {
 *     attribute("connection", "DataSource") {
 *         stereotype("InPort")
 *     }
 * }
 * ```
 */
fun UmlComponentScope.attribute(
    name: String,
    type: UmlTypeRef,
    id: String? = null,
    block: AttributeBuilder.() -> Unit,
): UmlProperty {
    val builder =
        this as? ComponentBuilder
            ?: error("attribute() inside a component requires a ComponentBuilder scope.")
    val attrBuilder = AttributeBuilder(name, type, ownerId, takenIds, builder.container, id)
    attrBuilder.block()
    val prop = attrBuilder.build()
    builder.addAttribute(prop)
    return prop
}

/** V1.1.3 — attribute() block overload with type-by-name. */
fun UmlComponentScope.attribute(
    name: String,
    type: String,
    id: String? = null,
    block: AttributeBuilder.() -> Unit,
): UmlProperty = attribute(name, typeRef(type), id, block)

/**
 * V1.1.3 — operation() inside a component body.
 *
 * ```kotlin
 * component("SteeringControlSWC") {
 *     operation("computeSteeringAngle") {
 *         stereotype("Runnable") { "period_ms" to 10 }
 *         parameter("rawInput", "Double") { stereotype("InPort") }
 *     }
 * }
 * ```
 */
fun UmlComponentScope.operation(
    name: String,
    id: String? = null,
    block: OperationBuilder.() -> Unit = {},
): UmlOperation {
    val builder =
        this as? ComponentBuilder
            ?: error("operation() inside a component requires a ComponentBuilder scope.")
    val opBuilder =
        OperationBuilder(
            name = name,
            ownerId = ownerId,
            takenIds = takenIds,
            explicitId = id,
            container = builder.container,
        )
    opBuilder.block()
    val op = opBuilder.build()
    builder.addOperation(op)
    return op
}
