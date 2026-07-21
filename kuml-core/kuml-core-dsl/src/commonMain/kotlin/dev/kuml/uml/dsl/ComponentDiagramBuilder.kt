package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.ComponentDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
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

    /**
     * Override der Layout-Engine für dieses Diagramm.
     *
     * Standard: `null` → Pipeline wählt `"kuml.grid"` als Default für
     * Komponenten-Diagramme. Setze `"elk"` oder `"elk.layered"` um zur
     * ELK-Engine zurückzukehren.
     */
    var layoutEngine: String? = null

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

    /**
     * Adds a [UmlComment] (UML note) to this diagram.
     *
     * Comment/Note support (V0.23.1+) is available for all UML diagram types
     * — see `UmlModelScope.addComment` KDoc.
     */
    override fun addComment(comment: UmlComment) {
        elements += comment
        takenIds += comment.id
    }

    private fun requireComponentDiagramElement(element: UmlNamedElement) {
        val ok = element is UmlComponent || element is UmlInterface || element is UmlPackage
        require(ok) {
            "[$name] ${element::class.simpleName} is not a valid element for a component diagram. " +
                "Allowed: UmlComponent, UmlInterface, UmlPackage."
        }
    }

    private fun requireComponentDiagramRelationship(rel: UmlRelationship) {
        val ok = rel is UmlConnector || rel is UmlInterfaceRealization || rel is UmlDependency || rel is UmlCommentLink
        require(ok) {
            "[$name] ${rel::class.simpleName} is not a valid relationship for a component diagram. " +
                "Allowed: UmlConnector, UmlInterfaceRealization, UmlDependency, UmlCommentLink."
        }
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    fun build(): KumlDiagram {
        val meta: Map<String, KumlMetaValue> =
            if (layoutEngine != null) {
                mapOf(LayoutMetadataKeys.ENGINE to KumlMetaValue.Text(layoutEngine!!))
            } else {
                emptyMap()
            }
        // V2.0.47 — synthetisiert UmlInterfaceRealization für jedes
        // `provides(iface)` und UmlDependency mit «use»-Stereotyp für jedes
        // `requires(iface)`, sofern die Beziehung nicht bereits explizit
        // deklariert wurde und sowohl Komponente als auch Interface als
        // sichtbarer Knoten im Diagramm existieren. Ohne diesen Schritt
        // hingen die Felder `providedInterfaceIds` / `requiredInterfaceIds`
        // ohne sichtbare Wirkung im Modell, obwohl die DSL-Doku eine
        // "lollipop/ball"-Rendering verspricht (siehe
        // [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]]).
        val finalElements = synthesizeInterfaceRelationships(elements)
        return KumlDiagram(
            name = name,
            type = DiagramType.COMPONENT,
            elements = finalElements,
            metadata = meta,
            config =
                ComponentDiagramConfig(
                    showPortLabels = showPortLabels,
                    showInterfaceContracts = showInterfaceContracts,
                    showNestedComponents = showNestedComponents,
                    showStereotype = showStereotype,
                ),
        )
    }

    /**
     * Geht die [source]-Liste durch und ergänzt fehlende Realization-/
     * Dependency-Kanten, die aus den `provides` / `requires` Deklarationen
     * der Komponenten folgen:
     *
     *  - `provides(iface)` → [UmlInterfaceRealization] (component → interface,
     *    gestrichelte Linie mit hohlem Dreieck).
     *  - `requires(iface)` → [UmlDependency] mit «use»-Stereotyp (component
     *    → interface, gestrichelter offener Pfeil).
     *
     * Bereits explizit deklarierte Beziehungen für dasselbe Paar werden
     * nicht doppelt synthetisiert. Beziehungen werden nur erzeugt, wenn das
     * referenzierte Interface tatsächlich als Knoten im Diagramm existiert
     * (sonst hätten die Endpunkte kein sichtbares Ziel).
     */
    private fun synthesizeInterfaceRelationships(source: List<UmlElement>): List<UmlElement> {
        // Index aller bekannten Interfaces (top-level + in Paketen).
        val knownInterfaceIds: Set<String> = collectInterfaceIds(source)

        // Bereits vorhandene Realization-/Dependency-Paare, damit wir nicht
        // doppelt erzeugen.
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
            // Nested components: rekursiv. Sie sind keine eigenen Knoten im
            // Diagramm, aber ihre provides/requires sollen ebenfalls wirken.
            for (nested in component.nestedComponents) {
                visit(nested)
            }
        }

        fun walk(elements: List<UmlElement>) {
            for (element in elements) {
                when (element) {
                    is UmlComponent -> visit(element)
                    is UmlPackage -> walk(element.members)
                    else -> {}
                }
            }
        }
        walk(source)
        return source + synthesized
    }

    /** Sammelt alle Interface-IDs aus [source] (top-level + in Paketen rekursiv). */
    private fun collectInterfaceIds(source: List<UmlElement>): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(elements: List<UmlElement>) {
            for (element in elements) {
                when (element) {
                    is UmlInterface -> out += element.id
                    is UmlPackage -> walk(element.members)
                    else -> {}
                }
            }
        }
        walk(source)
        return out
    }
}
