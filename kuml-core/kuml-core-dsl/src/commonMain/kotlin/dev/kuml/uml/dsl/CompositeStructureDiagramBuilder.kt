package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.CompositeStructureDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlCollaboration
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
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
    private val appliedProfilesList = mutableListOf<KumlProfile>()

    public var showPortLabels: Boolean = true
    public var showRoleNames: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        when (element) {
            is UmlClass, is UmlInterface, is UmlComponent, is UmlPort, is UmlCollaboration -> { /* ✓ */ }
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
            is UmlConnector, is UmlDependency, is UmlInterfaceRealization -> { /* ✓ */ }
            else ->
                require(false) {
                    "[$name] ${relationship::class.simpleName} is not a valid relationship for a composite-structure diagram."
                }
        }
        elements += relationship
        takenIds += relationship.id
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram {
        val finalElements = synthesizeInterfaceRelationships(elements)
        return KumlDiagram(
            name = name,
            type = DiagramType.COMPOSITE_STRUCTURE,
            elements = finalElements,
            config =
                CompositeStructureDiagramConfig(
                    showPortLabels = showPortLabels,
                    showRoleNames = showRoleNames,
                ),
        )
    }

    /**
     * Synthetisiert fehlende Realization-/Dependency-Kanten aus den
     * `provides` / `requires`-Deklarationen der Komponenten — analog zu
     * [ComponentDiagramBuilder.synthesizeInterfaceRelationships]:
     *
     * - `provides(iface)` → [UmlInterfaceRealization] (gestrichelte Linie
     *   mit hohlem Dreieck, Komponente → Interface).
     * - `requires(iface)` → [UmlDependency] mit «use»-Stereotyp (gestrichelter
     *   offener Pfeil, Komponente → Interface).
     *
     * Kanten werden nur erzeugt, wenn das referenzierte Interface als Knoten
     * im Diagramm existiert und die Beziehung nicht bereits explizit deklariert
     * wurde. Nested Parts werden rekursiv besucht.
     */
    private fun synthesizeInterfaceRelationships(source: List<UmlElement>): List<UmlElement> {
        val knownInterfaceIds: Set<String> =
            source
                .filterIsInstance<UmlInterface>()
                .map { it.id }
                .toSet()

        val existingRealizations: Set<Pair<String, String>> =
            source
                .filterIsInstance<UmlInterfaceRealization>()
                .map { it.implementingId to it.interfaceId }
                .toSet()
        val existingDependencies: Set<Pair<String, String>> =
            source
                .filterIsInstance<UmlDependency>()
                .map { it.clientId to it.supplierId }
                .toSet()

        val synthesized = mutableListOf<UmlElement>()

        fun visit(component: UmlComponent) {
            for (ifaceId in component.providedInterfaceIds) {
                if (ifaceId !in knownInterfaceIds) continue
                if (component.id to ifaceId in existingRealizations) continue
                synthesized.add(
                    UmlInterfaceRealization(
                        id = "${component.id}-provides-$ifaceId",
                        implementingId = component.id,
                        interfaceId = ifaceId,
                    ),
                )
            }
            for (ifaceId in component.requiredInterfaceIds) {
                if (ifaceId !in knownInterfaceIds) continue
                if (component.id to ifaceId in existingDependencies) continue
                synthesized.add(
                    UmlDependency(
                        id = "${component.id}-requires-$ifaceId",
                        clientId = component.id,
                        supplierId = ifaceId,
                        name = "use",
                    ),
                )
            }
            // Nested Parts rekursiv besuchen — ihre provides/requires
            // sollen ebenfalls als sichtbare Kanten erscheinen.
            for (nested in component.nestedComponents) {
                visit(nested)
            }
        }

        for (element in source) {
            if (element is UmlComponent) visit(element)
        }
        return source + synthesized
    }
}
