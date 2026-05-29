package dev.kuml.uml.dsl

import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.ids.UmlIds

// ── Generalization ────────────────────────────────────────────────────────────

/**
 * Adds a [UmlGeneralization] (inheritance arrow) to this diagram or model root.
 *
 * ```kotlin
 * val animal = classOf("Animal") { isAbstract = true }
 * val dog = classOf("Dog") {}
 * generalization(specific = dog, general = animal)
 * ```
 *
 * @param specificId Qualified ID of the more specific (child) classifier.
 * @param generalId Qualified ID of the more general (parent) classifier.
 */
fun UmlModelScope.generalization(
    specificId: String,
    generalId: String,
    id: String? = null,
): UmlGeneralization {
    val relId =
        id
            ?: UmlIds.disambiguate(
                candidate = UmlIds.generalization(specificId, generalId),
                taken = takenIds,
            )
    takenIds += relId
    val gen = UmlGeneralization(id = relId, specificId = specificId, generalId = generalId)
    addRelationship(gen)
    return gen
}

/** Overload — classifiers via builder handles. */
fun UmlModelScope.generalization(
    specific: UmlClassifier,
    general: UmlClassifier,
    id: String? = null,
): UmlGeneralization = generalization(specific.id, general.id, id)

// ── Interface Realization ─────────────────────────────────────────────────────

/**
 * Adds a [UmlInterfaceRealization] to this diagram or model root.
 *
 * ```kotlin
 * val iOrderSvc = interfaceOf("IOrderSvc") { … }
 * val orderSvc = classOf("OrderSvc") { … }
 * realization(implementing = orderSvc, iface = iOrderSvc)
 * ```
 *
 * @param implementingId Qualified ID of the implementing class.
 * @param interfaceId Qualified ID of the realized interface.
 */
fun UmlModelScope.realization(
    implementingId: String,
    interfaceId: String,
    id: String? = null,
): UmlInterfaceRealization {
    val relId =
        id
            ?: UmlIds.disambiguate(
                candidate = UmlIds.realization(implementingId, interfaceId),
                taken = takenIds,
            )
    takenIds += relId
    val real = UmlInterfaceRealization(id = relId, implementingId = implementingId, interfaceId = interfaceId)
    addRelationship(real)
    return real
}

/** Overload — classifiers via builder handles. */
fun UmlModelScope.realization(
    implementing: UmlClassifier,
    iface: UmlInterface,
    id: String? = null,
): UmlInterfaceRealization = realization(implementing.id, iface.id, id)

// ── Dependency ────────────────────────────────────────────────────────────────

/**
 * Adds a [UmlDependency] to this diagram or model root.
 *
 * ```kotlin
 * dependency(clientId = "Order", supplierId = "OrderStatus")
 * ```
 *
 * @param clientId Qualified ID of the dependent (client) element.
 * @param supplierId Qualified ID of the supplier element.
 * @param name Optional stereotype label (e.g. `"<<use>>"`).
 */
fun UmlModelScope.dependency(
    clientId: String,
    supplierId: String,
    name: String? = null,
    id: String? = null,
): UmlDependency {
    val depId =
        id
            ?: UmlIds.disambiguate(
                candidate = UmlIds.dependency(clientId, supplierId),
                taken = takenIds,
            )
    takenIds += depId
    val dep = UmlDependency(id = depId, clientId = clientId, supplierId = supplierId, name = name)
    addRelationship(dep)
    return dep
}

/** Overload — classifiers via builder handles. */
fun UmlModelScope.dependency(
    client: UmlClassifier,
    supplier: UmlClassifier,
    name: String? = null,
    id: String? = null,
): UmlDependency = dependency(client.id, supplier.id, name, id)
