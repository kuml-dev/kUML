package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPort
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
    val builder = ComponentBuilder(
        name = name,
        parentId = containerId,
        takenIds = takenIds,
        explicitId = id,
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
    val builder = ComponentBuilder(
        name = name,
        parentId = ownerId,
        takenIds = takenIds,
        explicitId = id,
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
    val resolvedId = id ?: UmlIds.disambiguate(
        candidate = UmlIds.child(ownerId, name),
        taken = takenIds,
    )
    takenIds += resolvedId
    val builder = PortBuilder().apply(block)
    val port = UmlPort(
        id = resolvedId,
        name = name,
        visibility = visibility,
        type = type,
        isConjugated = isConjugated,
        stereotypes = builder.stereotypes.toList(),
    )
    addPort(port)
    return port
}

@KumlDsl
class PortBuilder internal constructor() {
    val stereotypes: MutableList<String> = mutableListOf()
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
    val relId = id ?: UmlIds.disambiguate(
        candidate = UmlIds.connector(end1Id, end2Id),
        taken = takenIds,
    )
    takenIds += relId
    val connector = UmlConnector(id = relId, end1Id = end1Id, end2Id = end2Id, name = name)
    addRelationship(connector)
    return connector
}
