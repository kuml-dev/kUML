package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── Interaction (Sequence Diagram) ────────────────────────────────────────────

/**
 * A UML interaction — the root container for a sequence diagram.
 *
 * @property lifelines Participants (instances) in this interaction.
 * @property messages Messages exchanged between lifelines, in sequence order.
 * @property fragments Combined fragments (alt, loop, opt, …) that group messages.
 */
@Serializable
data class UmlInteraction(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val lifelines: List<UmlLifeline> = emptyList(),
    val messages: List<UmlMessage> = emptyList(),
    val fragments: List<UmlCombinedFragment> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Lifeline ──────────────────────────────────────────────────────────────────

/**
 * A lifeline in a [UmlInteraction] — represents one participant instance.
 *
 * @property represents Optional type of the represented instance ([UmlTypeRef]).
 * @property isActor `true` if this lifeline represents an actor rather than a component.
 */
@Serializable
data class UmlLifeline(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val represents: UmlTypeRef? = null,
    val isActor: Boolean = false,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Message ───────────────────────────────────────────────────────────────────

/** How a message is sent between lifelines. */
enum class MessageSort {
    /** Synchronous call (solid arrowhead). */
    SYNC_CALL,

    /** Asynchronous call (open arrowhead). */
    ASYNC_CALL,

    /** Reply to a synchronous call (dashed line). */
    REPLY,

    /** Object creation message. */
    CREATE,

    /** Object destruction message. */
    DELETE,
}

/**
 * A message sent from one lifeline to another.
 *
 * The [sequence] field encodes the 1-based position of this message
 * within its [UmlInteraction]. Order is semantically meaningful in
 * sequence diagrams, so the index is used when deriving the stable [id].
 *
 * @property label Displayed message name / signature (e.g. `"createOrder(items)"`).
 * @property fromLifelineId [UmlElement.id] of the sending lifeline.
 * @property toLifelineId [UmlElement.id] of the receiving lifeline.
 * @property sort How this message is sent.
 * @property sequence 1-based sequence number within the enclosing interaction.
 */
@Serializable
data class UmlMessage(
    override val id: String,
    val label: String,
    val fromLifelineId: String,
    val toLifelineId: String,
    val sort: MessageSort = MessageSort.SYNC_CALL,
    val sequence: Int,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlElement

// ── Combined Fragment ─────────────────────────────────────────────────────────

/** Interaction operator for a [UmlCombinedFragment]. */
enum class InteractionOperator {
    /** Alternatives (if/else). */
    ALT,

    /** Optional behaviour (if with no else). */
    OPT,

    /** Loop. */
    LOOP,

    /** Parallel execution. */
    PAR,

    /** Break out of enclosing loop. */
    BREAK,
}

/**
 * A combined fragment groups messages under an [InteractionOperator].
 *
 * @property operator The interaction operator (ALT, OPT, LOOP, …).
 * @property operands One operand per branch (at least one; ALT has ≥ 2).
 */
@Serializable
data class UmlCombinedFragment(
    override val id: String,
    val operator: InteractionOperator,
    val operands: List<UmlInteractionOperand>,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlElement

/**
 * One branch (operand) of a [UmlCombinedFragment].
 *
 * @property guard Guard condition expression (e.g. `"[payment.success]"`).
 *   `null` for unconditional operands (e.g. the sole OPT branch).
 * @property messageIds [UmlMessage.id]s belonging to this operand, in sequence order.
 * @property fragmentIds Nested [UmlCombinedFragment.id]s within this operand.
 */
@Serializable
data class UmlInteractionOperand(
    val guard: String? = null,
    val messageIds: List<String> = emptyList(),
    val fragmentIds: List<String> = emptyList(),
)
