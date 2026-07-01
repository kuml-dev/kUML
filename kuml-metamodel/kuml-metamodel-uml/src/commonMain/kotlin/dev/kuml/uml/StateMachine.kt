package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── State Machine ─────────────────────────────────────────────────────────────

/**
 * A UML state machine — models the lifecycle and reactions of a classifier.
 *
 * V1 uses a flat list of [vertices] plus [transitions].
 * Composite states (nested [UmlState.substates]) are supported as opt-in.
 *
 * @property vertices All states, pseudostates, and final states in this machine.
 * @property transitions Transitions connecting the vertices.
 */
@Serializable
data class UmlStateMachine(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val vertices: List<UmlVertex> = emptyList(),
    val transitions: List<UmlTransition> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlNamedElement,
    Stereotypable

// ── Vertex ────────────────────────────────────────────────────────────────────

/**
 * A vertex (node) in a [UmlStateMachine].
 *
 * Sealed subtypes: [UmlState], [UmlPseudostate], [UmlFinalState].
 */
@Serializable
sealed interface UmlVertex : UmlNamedElement

/**
 * A simple or composite UML state.
 *
 * @property entry Activity executed on entering this state (text expression).
 * @property exit Activity executed on exiting this state.
 * @property doActivity Activity executed while in this state (concurrent).
 * @property substates Sub-vertices for composite states (empty for simple states).
 */
@Serializable
data class UmlState(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val entry: String? = null,
    val exit: String? = null,
    val doActivity: String? = null,
    val substates: List<UmlVertex> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlVertex,
    Stereotypable

/** The kind of a [UmlPseudostate]. */
enum class PseudostateKind {
    INITIAL,
    CHOICE,
    FORK,
    JOIN,
    JUNCTION,
    SHALLOW_HISTORY,
    DEEP_HISTORY,
}

/**
 * A UML pseudostate — a control vertex in a [UmlStateMachine].
 *
 * @property kind Specifies the behaviour of this pseudostate
 *   (e.g. [PseudostateKind.INITIAL] for the mandatory start vertex).
 */
@Serializable
data class UmlPseudostate(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val kind: PseudostateKind,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlVertex

/**
 * A UML final state — a terminal vertex.
 *
 * When a state machine reaches a final state, execution of the
 * enclosing region is complete.
 */
@Serializable
data class UmlFinalState(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlVertex

// ── Transition ────────────────────────────────────────────────────────────────

/**
 * A directed edge in a [UmlStateMachine] from one [UmlVertex] to another.
 *
 * @property sourceId [UmlElement.id] of the source vertex.
 * @property targetId [UmlElement.id] of the target vertex.
 * @property trigger Event that fires the transition (text expression, e.g. `"confirm()"`).
 * @property guard Guard condition (e.g. `"[payment.success]"`).
 * @property effect Action executed when the transition fires (text expression).
 */
@Serializable
data class UmlTransition(
    override val id: String,
    val sourceId: String,
    val targetId: String,
    val trigger: String? = null,
    val guard: String? = null,
    val effect: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlElement,
    Stereotypable
