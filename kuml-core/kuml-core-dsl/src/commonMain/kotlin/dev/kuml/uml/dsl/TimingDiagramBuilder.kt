package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.TimingDiagramConfig
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlTimingLifeline
import dev.kuml.uml.UmlTimingTick
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x timing diagram (V1.1).
 *
 * Available builders:
 *  - [lifeline] — declares a participant with its possible states
 *  - inside `lifeline { … }`: [TimingLifelineScope.tick] — records the
 *    lifeline's state at a discrete time point
 */
@KumlDsl
public class TimingDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showTickLabels: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        require(element is UmlTimingLifeline) {
            "[$name] ${element::class.simpleName} is not a valid element for a timing diagram."
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        require(relationship is UmlCommentLink) {
            "[$name] ${relationship::class.simpleName} is not a valid relationship for a timing diagram. " +
                "Allowed: UmlCommentLink."
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

    public fun lifeline(
        name: String,
        states: List<String>,
        block: TimingLifelineScope.() -> Unit = {},
    ): UmlTimingLifeline {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val scope = TimingLifelineScope()
        scope.apply(block)
        val l =
            UmlTimingLifeline(
                id = id,
                name = name,
                states = states,
                timeline = scope.ticks.toList(),
            )
        addNamedElement(l)
        return l
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.TIMING,
            elements = elements.toList(),
            config = TimingDiagramConfig(showTickLabels = showTickLabels),
        )
}

@KumlDsl
public class TimingLifelineScope internal constructor() {
    internal val ticks: MutableList<UmlTimingTick> = mutableListOf()

    public fun tick(
        t: Int,
        state: String,
    ) {
        ticks += UmlTimingTick(t = t, state = state)
    }
}
