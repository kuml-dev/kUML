package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.CompositeStructureDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPort
import dev.kuml.uml.UmlRelationship

/**
 * Builder for a UML 2.x composite-structure diagram (V1.1).
 *
 * Shows the internal structure of a classifier — its parts (typed properties),
 * ports, and the connectors that wire them together. Reuses
 * [classOf] / [interfaceOf] / [component] from [UmlModelScope] so existing
 * structural builders apply.
 */
@KumlDsl
public class CompositeStructureDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val elements = mutableListOf<UmlElement>()

    public var showPortLabels: Boolean = true
    public var showRoleNames: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        when (element) {
            is UmlClass, is UmlInterface, is UmlComponent, is UmlPort -> { /* ✓ */ }
            else ->
                require(false) {
                    "[$name] ${element::class.simpleName} is not a valid element for a composite-structure diagram."
                }
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        when (relationship) {
            is UmlConnector, is UmlDependency -> { /* ✓ */ }
            else ->
                require(false) {
                    "[$name] ${relationship::class.simpleName} is not a valid relationship for a composite-structure diagram."
                }
        }
        elements += relationship
        takenIds += relationship.id
    }

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.COMPOSITE_STRUCTURE,
            elements = elements.toList(),
            config =
                CompositeStructureDiagramConfig(
                    showPortLabels = showPortLabels,
                    showRoleNames = showRoleNames,
                ),
        )
}
