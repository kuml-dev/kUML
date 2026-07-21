package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.ActivityDiagramConfig
import dev.kuml.core.model.ActivityOrientation
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x activity diagram (V1.1).
 *
 * Available builders:
 *  - [initialNode] / [finalNode] / [flowFinal] — control endpoints
 *  - [action] — a unit of work (rounded rectangle)
 *  - [decision] / [merge] — diamonds
 *  - [fork] / [join] — bars
 *  - [objectNode] — data flowing between actions
 *  - [edge] — control flow (or `objectFlow = true` for object flow)
 */
@KumlDsl
public class ActivityDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showGuardLabels: Boolean = true
    public var orientation: ActivityOrientation = ActivityOrientation.TOP_DOWN

    override fun addNamedElement(element: UmlNamedElement) {
        require(element is UmlActivityNode) {
            "[$name] ${element::class.simpleName} is not a valid element for an activity diagram."
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        require(relationship is UmlActivityEdge || relationship is UmlCommentLink) {
            "[$name] ${relationship::class.simpleName} is not a valid relationship for an activity diagram."
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

    public fun initialNode(name: String = "initial"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.INITIAL)

    public fun finalNode(name: String = "final"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.ACTIVITY_FINAL)

    public fun flowFinal(name: String = "flowFinal"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.FLOW_FINAL)

    public fun action(name: String): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.ACTION)

    public fun decision(name: String = "decision"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.DECISION)

    public fun merge(name: String = "merge"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.MERGE)

    public fun fork(name: String = "fork"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.FORK)

    public fun join(name: String = "join"): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.JOIN)

    public fun objectNode(name: String): UmlActivityNode = addNode(name = name, kind = UmlActivityNodeKind.OBJECT)

    public fun edge(
        from: UmlActivityNode,
        to: UmlActivityNode,
        guard: String? = null,
        objectFlow: Boolean = false,
    ): UmlActivityEdge {
        val id = UmlIds.disambiguate(candidate = "edge::${from.id}-->${to.id}", taken = takenIds)
        val e =
            UmlActivityEdge(
                id = id,
                sourceId = from.id,
                targetId = to.id,
                guard = guard,
                isObjectFlow = objectFlow,
            )
        addRelationship(e)
        return e
    }

    private fun addNode(
        name: String,
        kind: UmlActivityNodeKind,
    ): UmlActivityNode {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val n = UmlActivityNode(id = id, name = name, kind = kind)
        addNamedElement(n)
        return n
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.ACTIVITY,
            elements = elements.toList(),
            config =
                ActivityDiagramConfig(
                    showGuardLabels = showGuardLabels,
                    orientation = orientation,
                ),
        )
}
