package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.ActorStyle
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.UseCaseDiagramConfig
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlUseCase
import dev.kuml.uml.UmlUseCaseSubject

/**
 * Builder for a UML use-case diagram.
 *
 * Accepts only use-case-relevant elements:
 * - Named: [UmlActor], [UmlUseCase], [UmlUseCaseSubject]
 * - Relationships: [UmlAssociation] (actor ↔ use-case),
 *   [UmlInclude], [UmlExtend], [UmlGeneralization] (actor specialisation,
 *   use-case generalisation).
 *
 * Anything else throws [IllegalArgumentException] at insertion time.
 *
 * Do not instantiate directly — use the [useCaseDiagram] entry-point function.
 */
@KumlDsl
class UseCaseDiagramBuilder(
    private val name: String,
) : UmlModelScope {

    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val elements = mutableListOf<UmlElement>()

    // ── Display options ───────────────────────────────────────────────────────

    var showSubjectBox: Boolean = true
    var actorStyle: ActorStyle = ActorStyle.STICK_FIGURE

    // ── UmlModelScope ─────────────────────────────────────────────────────────

    override fun addNamedElement(element: UmlNamedElement) {
        requireUseCaseDiagramElement(element)
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        requireUseCaseDiagramRelationship(relationship)
        elements += relationship
        takenIds += relationship.id
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Rejects element types that do not belong in a use-case diagram.
     *
     * Accepted: [UmlActor], [UmlUseCase], [UmlUseCaseSubject].
     * Rejected: everything else.
     */
    private fun requireUseCaseDiagramElement(element: UmlNamedElement) {
        val ok = element is UmlActor || element is UmlUseCase || element is UmlUseCaseSubject
        require(ok) {
            "[$name] ${element::class.simpleName} is not a valid element for a use-case diagram. " +
                "Allowed: UmlActor, UmlUseCase, UmlUseCaseSubject."
        }
    }

    /**
     * Rejects relationship types that do not belong in a use-case diagram.
     *
     * Accepted: [UmlAssociation], [UmlInclude], [UmlExtend], [UmlGeneralization].
     * Rejected: everything else.
     */
    private fun requireUseCaseDiagramRelationship(rel: UmlRelationship) {
        val ok = rel is UmlAssociation || rel is UmlInclude || rel is UmlExtend || rel is UmlGeneralization
        require(ok) {
            "[$name] ${rel::class.simpleName} is not a valid relationship for a use-case diagram. " +
                "Allowed: UmlAssociation, UmlInclude, UmlExtend, UmlGeneralization."
        }
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /** Builds the immutable [KumlDiagram] with [UseCaseDiagramConfig] attached. */
    fun build(): KumlDiagram = KumlDiagram(
        name = name,
        type = DiagramType.USE_CASE,
        elements = elements.toList(),
        config = UseCaseDiagramConfig(
            showSubjectBox = showSubjectBox,
            actorStyle = actorStyle,
        ),
    )
}
