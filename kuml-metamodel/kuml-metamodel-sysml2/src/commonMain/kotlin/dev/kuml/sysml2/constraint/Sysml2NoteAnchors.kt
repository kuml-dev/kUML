package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionUsage
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActivityPartitionUsage
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.ActorUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.LifelineUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateUsage
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.UseCaseUsage

/** What a note can be anchored to, independent of the diagram that shows it. */
enum class NoteAnchorKind {
    /** A definition that is drawn as a box (block, attribute definition, ...). */
    DEFINITION,

    /** A part usage (an IBD part box). */
    PART,

    /** A port usage (a small square on a part or on the owner frame). */
    PORT,

    /** A connection or flow between two parts / ports (an IBD wire). */
    CONNECTION,

    /** The owner definition of an IBD, drawn as the diagram frame. */
    OWNER_FRAME,

    /** A state (including composite states and regions). */
    STATE,

    /** A transition between states (an STM edge). */
    TRANSITION,

    /** An action or activity node. */
    ACTION,

    /** An activity partition (swimlane). */
    PARTITION,

    /** A constraint definition or usage (PAR). */
    CONSTRAINT,

    /** A requirement. */
    REQUIREMENT,

    /** A lifeline of a sequence diagram. */
    LIFELINE,

    /** An actor or use case. */
    USE_CASE_NODE,

    /** Anything else (allocations, messages, ...): not drawable as an anchor yet. */
    OTHER,
}

/**
 * Single source of truth for "can this diagram kind draw an anchor of that kind"
 * — used by [Sysml2NoteChecker] (warning `KUML-NOTE-W002`) and verified against the
 * placement engine by a parity test in the layout bridge.
 */
object Sysml2NoteAnchors {
    /** Classifies the anchor [id] of [model]; `null` if the id is unknown. */
    fun kindOf(
        model: Sysml2Model,
        id: String,
        ownerId: String? = null,
    ): NoteAnchorKind? {
        model.definitions.firstOrNull { it.id == id }?.let { def ->
            if (id == ownerId) return NoteAnchorKind.OWNER_FRAME
            return when (def) {
                is StateDefinition -> NoteAnchorKind.STATE
                is ActionDefinition -> NoteAnchorKind.ACTION
                is ActivityPartitionDefinition -> NoteAnchorKind.PARTITION
                is ConstraintDefinition -> NoteAnchorKind.CONSTRAINT
                is RequirementDefinition -> NoteAnchorKind.REQUIREMENT
                is LifelineDefinition -> NoteAnchorKind.LIFELINE
                is ActorDefinition, is UseCaseDefinition -> NoteAnchorKind.USE_CASE_NODE
                else -> NoteAnchorKind.DEFINITION
            }
        }
        val usage: Any? =
            model.definitions
                .asSequence()
                .flatMap { it.features.asSequence() }
                .firstOrNull { it.id == id }
                ?: model.usages.firstOrNull { it.id == id }
        return when (usage) {
            null -> null
            is PartUsage -> NoteAnchorKind.PART
            is PortUsage -> NoteAnchorKind.PORT
            is ConnectionUsage -> NoteAnchorKind.CONNECTION
            is TransitionUsage -> NoteAnchorKind.TRANSITION
            is StateUsage -> NoteAnchorKind.STATE
            is ActionUsage -> NoteAnchorKind.ACTION
            is ActivityPartitionUsage -> NoteAnchorKind.PARTITION
            is ConstraintUsage -> NoteAnchorKind.CONSTRAINT
            is RequirementUsage -> NoteAnchorKind.REQUIREMENT
            is LifelineUsage -> NoteAnchorKind.LIFELINE
            is ActorUsage, is UseCaseUsage -> NoteAnchorKind.USE_CASE_NODE
            else -> NoteAnchorKind.OTHER
        }
    }

    /** Anchor kinds [diagram] can draw. */
    fun supported(diagram: Sysml2Diagram): Set<NoteAnchorKind> =
        when (diagram) {
            is BdDiagram -> setOf(NoteAnchorKind.DEFINITION)
            is IbdDiagram ->
                setOf(NoteAnchorKind.PART, NoteAnchorKind.PORT, NoteAnchorKind.CONNECTION, NoteAnchorKind.OWNER_FRAME)
            is UcDiagram -> setOf(NoteAnchorKind.USE_CASE_NODE)
            is ReqDiagram -> setOf(NoteAnchorKind.REQUIREMENT)
            is StmDiagram -> setOf(NoteAnchorKind.STATE, NoteAnchorKind.TRANSITION)
            is ActDiagram -> setOf(NoteAnchorKind.ACTION, NoteAnchorKind.PARTITION)
            is SeqDiagram -> setOf(NoteAnchorKind.LIFELINE)
            is ParDiagram -> setOf(NoteAnchorKind.CONSTRAINT)
        }

    /** The owner id of [diagram] if it has one (IBD), for [kindOf]. */
    fun ownerOf(diagram: Sysml2Diagram): String? = (diagram as? IbdDiagram)?.ownerId
}
