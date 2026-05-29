package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * A UML component — a modular, deployable, and replaceable part of a system.
 *
 * Components expose their services through provided interfaces and require
 * services through required interfaces. Both are referenced by [UmlElement.id].
 *
 * @property ports Ports owned by this component.
 * @property providedInterfaceIds [UmlElement.id]s of interfaces this component realises.
 * @property requiredInterfaceIds [UmlElement.id]s of interfaces this component uses.
 * @property nestedComponents Components nested inside this component.
 */
@Serializable
data class UmlComponent(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val isAbstract: Boolean = false,
    val ports: List<UmlPort> = emptyList(),
    val providedInterfaceIds: List<String> = emptyList(),
    val requiredInterfaceIds: List<String> = emptyList(),
    val nestedComponents: List<UmlComponent> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

// ── Port ──────────────────────────────────────────────────────────────────────

/**
 * A UML port — an interaction point of a [UmlComponent].
 *
 * @property type Optional type of the port (the interface it exposes or requires).
 * @property isConjugated `true` if the port's provided and required interfaces are swapped
 *   (i.e. the component is a consumer at this port, not a provider).
 */
@Serializable
data class UmlPort(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val type: UmlTypeRef? = null,
    val isConjugated: Boolean = false,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Connector ─────────────────────────────────────────────────────────────────

/**
 * A UML connector — a link between two ports or parts.
 *
 * @property end1Id [UmlElement.id] of the first connected port or part.
 * @property end2Id [UmlElement.id] of the second connected port or part.
 * @property name Optional connector name or stereotype label.
 */
@Serializable
data class UmlConnector(
    override val id: String,
    val end1Id: String,
    val end2Id: String,
    val name: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship
