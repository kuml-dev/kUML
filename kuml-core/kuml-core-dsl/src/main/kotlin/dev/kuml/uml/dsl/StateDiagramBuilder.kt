package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.StateDiagramConfig
import dev.kuml.core.model.StateDiagramOrientation
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.UmlVertex
import dev.kuml.uml.Visibility

/**
 * Builder for a UML state-machine diagram.
 *
 * A state diagram contains exactly one [UmlStateMachine]. All [state],
 * [initialState], [finalState], [choice], [fork], [join], [compositeState],
 * and [transition] declarations populate that single state machine.
 *
 * Also implements [UmlContainerScope] so that [StateBodyBuilder] and
 * [TransitionBuilder] can resolve stereotypes from profiles applied here.
 *
 * Do not instantiate directly — use the [dev.kuml.core.dsl.stateDiagram] entry-point function.
 */
@KumlDsl
class StateDiagramBuilder(
    private val name: String,
) : UmlStateMachineScope,
    UmlContainerScope {
    override val stateMachineId: String = name
    override val takenIds: MutableSet<String> = mutableSetOf(name)

    // UmlContainerScope
    override val containerId: String? = null

    private val vertices = mutableListOf<UmlVertex>()
    private val transitions = mutableListOf<UmlTransition>()
    private val appliedProfilesList = mutableListOf<KumlProfile>()

    // State machine properties
    var stateMachineVisibility: Visibility = Visibility.PUBLIC
    val stateMachineStereotypes: MutableList<String> = mutableListOf()

    // Display options
    var showGuards: Boolean = true
    var showEffects: Boolean = true
    var showEntryExitActions: Boolean = true
    var orientation: StateDiagramOrientation = StateDiagramOrientation.TOP_DOWN

    override fun addVertex(vertex: UmlVertex) {
        vertices += vertex
    }

    override fun addTransition(transition: UmlTransition) {
        transitions += transition
    }

    // UmlContainerScope — profiles for stereotype resolution inside state/transition bodies
    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    /** State diagrams don't own named elements — this is a no-op (states are UmlVertex, not UmlNamedElement). */
    override fun addNamedElement(element: UmlNamedElement) = Unit

    fun build(): KumlDiagram {
        val sm =
            UmlStateMachine(
                id = stateMachineId,
                name = name,
                visibility = stateMachineVisibility,
                vertices = vertices.toList(),
                transitions = transitions.toList(),
                stereotypes = stateMachineStereotypes.toList(),
            )
        return KumlDiagram(
            name = name,
            type = DiagramType.STATE,
            elements = listOf(sm),
            config =
                StateDiagramConfig(
                    showGuards = showGuards,
                    showEffects = showEffects,
                    showEntryExitActions = showEntryExitActions,
                    orientation = orientation,
                ),
        )
    }
}
