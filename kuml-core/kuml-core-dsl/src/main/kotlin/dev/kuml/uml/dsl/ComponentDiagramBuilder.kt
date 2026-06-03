package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.ComponentDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlRelationship

/**
 * Builder for a UML component diagram.
 *
 * Accepts:
 * - Named: [UmlComponent], [UmlInterface], [UmlPackage]
 * - Relationships: [UmlConnector], [UmlInterfaceRealization], [UmlDependency]
 *
 * Rejects everything else (classes, enums, actors, use cases, state machines,
 * interactions, associations, generalizations, includes, extends).
 *
 * Do not instantiate directly — use the [componentDiagram] entry-point function.
 */
@KumlDsl
class ComponentDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    var showPortLabels: Boolean = true
    var showInterfaceContracts: Boolean = true
    var showNestedComponents: Boolean = true
    var showStereotype: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        requireComponentDiagramElement(element)
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        requireComponentDiagramRelationship(relationship)
        elements += relationship
        takenIds += relationship.id
    }

    private fun requireComponentDiagramElement(element: UmlNamedElement) {
        val ok = element is UmlComponent || element is UmlInterface || element is UmlPackage
        require(ok) {
            "[$name] ${element::class.simpleName} is not a valid element for a component diagram. " +
                "Allowed: UmlComponent, UmlInterface, UmlPackage."
        }
    }

    private fun requireComponentDiagramRelationship(rel: UmlRelationship) {
        val ok = rel is UmlConnector || rel is UmlInterfaceRealization || rel is UmlDependency
        require(ok) {
            "[$name] ${rel::class.simpleName} is not a valid relationship for a component diagram. " +
                "Allowed: UmlConnector, UmlInterfaceRealization, UmlDependency."
        }
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.COMPONENT,
            elements = elements.toList(),
            config =
                ComponentDiagramConfig(
                    showPortLabels = showPortLabels,
                    showInterfaceContracts = showInterfaceContracts,
                    showNestedComponents = showNestedComponents,
                    showStereotype = showStereotype,
                ),
        )
}
