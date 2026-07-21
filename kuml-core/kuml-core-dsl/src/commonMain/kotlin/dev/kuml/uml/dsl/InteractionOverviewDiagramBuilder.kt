package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.InteractionOverviewDiagramConfig
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInteractionFrameKind
import dev.kuml.uml.UmlInteractionOverviewFrame
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x interaction-overview diagram (V1.1).
 *
 * Available builders:
 *  - [initial] / [final] — control endpoints (activity-style)
 *  - [decision] / [merge] — diamonds for branching
 *  - [interactionRef] — a `ref InteractionName` frame that points at a
 *    sequence / communication diagram defined elsewhere
 *  - [edge] — control flow between frames (`guard` for branching edges)
 */
@KumlDsl
public class InteractionOverviewDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showFrameLabels: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        require(element is UmlInteractionOverviewFrame) {
            "[$name] ${element::class.simpleName} is not a valid element for an interaction-overview diagram."
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        require(relationship is UmlActivityEdge || relationship is UmlCommentLink) {
            "[$name] ${relationship::class.simpleName} is not a valid relationship for an interaction-overview diagram."
        }
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

    public fun initial(name: String = "initial"): UmlInteractionOverviewFrame =
        addFrame(name = name, kind = UmlInteractionFrameKind.INITIAL)

    public fun final(name: String = "final"): UmlInteractionOverviewFrame = addFrame(name = name, kind = UmlInteractionFrameKind.FINAL)

    public fun decision(name: String = "decision"): UmlInteractionOverviewFrame =
        addFrame(name = name, kind = UmlInteractionFrameKind.DECISION)

    public fun merge(name: String = "merge"): UmlInteractionOverviewFrame = addFrame(name = name, kind = UmlInteractionFrameKind.MERGE)

    public fun interactionRef(
        name: String,
        referencedInteractionId: String? = null,
    ): UmlInteractionOverviewFrame {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val f =
            UmlInteractionOverviewFrame(
                id = id,
                name = name,
                kind = UmlInteractionFrameKind.INTERACTION_REF,
                referencedInteractionId = referencedInteractionId,
            )
        addNamedElement(f)
        return f
    }

    public fun edge(
        from: UmlInteractionOverviewFrame,
        to: UmlInteractionOverviewFrame,
        guard: String? = null,
    ): UmlActivityEdge {
        val id = UmlIds.disambiguate(candidate = "ioe::${from.id}-->${to.id}", taken = takenIds)
        val e =
            UmlActivityEdge(
                id = id,
                sourceId = from.id,
                targetId = to.id,
                guard = guard,
                isObjectFlow = false,
            )
        addRelationship(e)
        return e
    }

    private fun addFrame(
        name: String,
        kind: UmlInteractionFrameKind,
    ): UmlInteractionOverviewFrame {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val f = UmlInteractionOverviewFrame(id = id, name = name, kind = kind)
        addNamedElement(f)
        return f
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.INTERACTION_OVERVIEW,
            elements = elements.toList(),
            config = InteractionOverviewDiagramConfig(showFrameLabels = showFrameLabels),
        )
}
