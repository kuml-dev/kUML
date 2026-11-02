package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.dsl.layout.DiagramLayoutScope
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.ClassDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.VisibilityFilter
import dev.kuml.core.model.layout.LayoutSpecCollector
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
) : UmlModelScope,
    DiagramLayoutScope {
    override val layoutCollector: LayoutSpecCollector = LayoutSpecCollector()
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

    /**
     * Opt-in für Edge-Merging der Layout-Engine.
     *
     * `null` (Default) → Pipeline nimmt den globalen [LayoutHints]-Default
     * (`false`); kein Trunk-Routing. Auf `true` setzen, wenn das Diagramm
     * viele Kanten auf denselben Endpunkt-Port hat (z.B. N Generalisierungen
     * zu einer gemeinsamen Basisklasse) — die ELK-Engine konsolidiert sie
     * dann zu einem gemeinsamen Stamm-Segment.
     *
     * Beispiel:
     * ```kotlin
     * classDiagram("Schema") {
     *     mergeEdges = true   // Fan-In zu Tree-Trunk konsolidieren
     *     ...
     * }
     * ```
     */
    var mergeEdges: Boolean? = null

    /**
     * Opt-in (PAL wave 2G-a): draws one dashed dependency with an open arrowhead from a classifier to every
     * other classifier of this diagram that one of its attributes is typed with. Several attributes of the same
     * owner pointing to the same classifier give one edge whose label joins the attribute names with `", "`. No edge is
     * drawn where the diagram already has an association between the two classifiers or a dependency from the owner to the
     * target, for primitive or unknown types, or for an attribute typed with its own owner.
     */
    var attributeTypeEdges: Boolean = false

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

    /** Adds a [dev.kuml.uml.UmlComment] (UML note) to this class diagram. */
    override fun addComment(comment: dev.kuml.uml.UmlComment) {
        elements += comment
        takenIds += comment.id
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
        val allElements = if (attributeTypeEdges) elements + attributeTypeDependencies() else elements.toList()
        val meta: Map<String, KumlMetaValue> =
            buildMap {
                layoutEngine?.let { put(LayoutMetadataKeys.ENGINE, KumlMetaValue.Text(it)) }
                mergeEdges?.let { put(LayoutMetadataKeys.MERGE_EDGES, KumlMetaValue.Flag(it)) }
            }
        return KumlDiagram(
            layout = layoutCollector.result(),
            name = name,
            type = DiagramType.CLASS,
            elements = allElements,
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

    /** The dependencies [attributeTypeEdges] adds, in declaration order of the owners and their attributes. */
    private fun attributeTypeDependencies(): List<dev.kuml.uml.UmlElement> {
        val classifiers = elements.filterIsInstance<dev.kuml.uml.UmlNamedElement>().filter { it.isClassifierBox() }
        val byId = classifiers.associateBy { it.id }
        val byName = classifiers.associateBy { it.name }
        val associated =
            elements
                .filterIsInstance<dev.kuml.uml.UmlAssociation>()
                .filter { it.ends.size >= 2 }
                .map { setOf(it.ends[0].typeId, it.ends[1].typeId) }
                .toSet()
        val existing =
            elements
                .filterIsInstance<dev.kuml.uml.UmlDependency>()
                .map { it.clientId to it.supplierId }
                .toSet()
        val out = mutableListOf<dev.kuml.uml.UmlElement>()
        val taken = takenIds.toMutableSet()
        for (owner in classifiers) {
            val attributes =
                when (owner) {
                    is dev.kuml.uml.UmlClass -> owner.attributes
                    is dev.kuml.uml.UmlInterface -> owner.attributes
                    else -> emptyList()
                }
            val labelsByTarget = linkedMapOf<String, MutableList<String>>()
            for (attr in attributes) {
                val target = attr.type.referencedId?.let(byId::get) ?: byName[attr.type.name] ?: continue
                if (target.id == owner.id) continue
                labelsByTarget.getOrPut(target.id) { mutableListOf() } += attr.name
            }
            for ((targetId, labels) in labelsByTarget) {
                if (setOf(owner.id, targetId) in associated || (owner.id to targetId) in existing) continue
                val id =
                    dev.kuml.uml.ids.UmlIds
                        .disambiguate(candidate = "attrtype::${owner.id}-->$targetId", taken = taken)
                taken += id
                out += dev.kuml.uml.UmlDependency(id = id, clientId = owner.id, supplierId = targetId, name = labels.joinToString(", "))
            }
        }
        return out
    }

    private fun dev.kuml.uml.UmlNamedElement.isClassifierBox(): Boolean =
        this is dev.kuml.uml.UmlClass || this is dev.kuml.uml.UmlInterface || this is dev.kuml.uml.UmlEnumeration
}
