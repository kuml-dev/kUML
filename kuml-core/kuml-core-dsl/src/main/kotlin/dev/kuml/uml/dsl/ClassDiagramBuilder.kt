package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.ClassDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.VisibilityFilter
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Builder for a UML class diagram.
 *
 * All structural UML element builders available in [UmlModelScope] are available here:
 * [classOf], [interfaceOf], [enumOf], [packageOf], [association], [generalization],
 * [realization], [dependency].
 *
 * Behavioural elements ([stateMachine], [interaction]) are NOT accepted here
 * — use the dedicated diagram builders for those.
 *
 * Do not instantiate directly — use the [classDiagram] entry-point function.
 */
@KumlDsl
class ClassDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<dev.kuml.uml.UmlElement>()

    // ── Display options ───────────────────────────────────────────────────────

    var showAttributes: Boolean = true
    var showOperations: Boolean = true
    var showVisibility: Boolean = true
    var visibilityFilter: VisibilityFilter = VisibilityFilter.ALL
    var showPackageNames: Boolean = false

    // ── Layout ────────────────────────────────────────────────────────────────

    /**
     * Override der Layout-Engine für dieses Diagramm.
     *
     * Standard: `null` → Pipeline wählt `"kuml.grid"` als Default für
     * Klassen-Diagramme. Setze `"elk"` oder `"elk.layered"` um zur
     * ELK-Engine zurückzukehren.
     */
    var layoutEngine: String? = null

    // ── UmlModelScope ─────────────────────────────────────────────────────────

    override fun addNamedElement(element: UmlNamedElement) {
        requireClassDiagramElement(element)
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        requireClassDiagramRelationship(relationship)
        elements += relationship
        takenIds += relationship.id
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Rejects element types that do not belong in a class diagram.
     *
     * Accepted: [dev.kuml.uml.UmlClass], [dev.kuml.uml.UmlInterface],
     *   [dev.kuml.uml.UmlEnumeration], [dev.kuml.uml.UmlPackage],
     *   [dev.kuml.uml.UmlComponent] (permitted for SoaML «Participant» in mixed diagrams),
     *   [dev.kuml.uml.UmlCollaboration].
     * Rejected: [dev.kuml.uml.UmlStateMachine], [dev.kuml.uml.UmlInteraction],
     *   [dev.kuml.uml.UmlActor], [dev.kuml.uml.UmlUseCase].
     */
    private fun requireClassDiagramElement(element: UmlNamedElement) {
        val rejected =
            when (element) {
                is dev.kuml.uml.UmlStateMachine -> "UmlStateMachine"
                is dev.kuml.uml.UmlInteraction -> "UmlInteraction"
                is dev.kuml.uml.UmlActor -> "UmlActor (use useCaseDiagram { })"
                is dev.kuml.uml.UmlUseCase -> "UmlUseCase (use useCaseDiagram { })"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid element for a class diagram."
        }
    }

    /**
     * Rejects relationship types that belong to non-class diagram types.
     *
     * Accepted: [dev.kuml.uml.UmlAssociation], [dev.kuml.uml.UmlGeneralization],
     *   [dev.kuml.uml.UmlInterfaceRealization], [dev.kuml.uml.UmlDependency].
     * Rejected: [dev.kuml.uml.UmlInclude], [dev.kuml.uml.UmlExtend],
     *   [dev.kuml.uml.UmlConnector].
     */
    private fun requireClassDiagramRelationship(rel: UmlRelationship) {
        val rejected =
            when (rel) {
                is dev.kuml.uml.UmlInclude -> "UmlInclude (use useCaseDiagram { })"
                is dev.kuml.uml.UmlExtend -> "UmlExtend (use useCaseDiagram { })"
                is dev.kuml.uml.UmlConnector -> "UmlConnector (use componentDiagram { })"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid relationship for a class diagram."
        }
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /** Builds the immutable [KumlDiagram] with [ClassDiagramConfig] attached. */
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
        return KumlDiagram(
            name = name,
            type = DiagramType.CLASS,
            elements = elements.toList(),
            metadata = meta,
            config =
                ClassDiagramConfig(
                    showAttributes = showAttributes,
                    showOperations = showOperations,
                    showVisibility = showVisibility,
                    visibilityFilter = visibilityFilter,
                    showPackageNames = showPackageNames,
                ),
        )
    }
}
