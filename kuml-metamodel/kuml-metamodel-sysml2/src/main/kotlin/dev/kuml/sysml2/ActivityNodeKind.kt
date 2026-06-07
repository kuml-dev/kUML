package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Discriminator for the seven activity-node shapes in a SysML 2 activity
 * diagram (V2.0.10). Used by [ActionDefinition.kind] so a single definition
 * class can carry every node kind — keeps the sealed [Sysml2Definition]
 * surface compact and lets the renderer / layout-bridge dispatch on a single
 * enum instead of seven sealed sub-types.
 *
 * **Design rationale**: the V2.0.9 STM wave used two boolean flags
 * ([dev.kuml.sysml2.StateDefinition.isInitial] / `isFinal`) to disambiguate
 * pseudo-states from regular states — a defensible choice for two flavours.
 * ACT has seven distinct flavours (Action, Initial, Final, FlowFinal,
 * Decision, Merge, Fork, Join); a Boolean-flag explosion (`isInitial`,
 * `isFinal`, `isFlowFinal`, `isDecision`, `isMerge`, `isFork`, `isJoin`)
 * would be ugly, error-prone (mutually-exclusive flags are easy to
 * mis-configure), and ill-suited to the renderer's dispatch shape.
 *
 * Using a single enum + a single definition class is cleaner: callers pick
 * exactly one kind, the renderer's `when (kind)` is exhaustive at compile
 * time, and the metamodel stays compact. The same shape will recur in
 * follow-up waves (SEQ-Diagram fragment kinds, PAR-Diagram constraint kinds).
 */
@Serializable
enum class ActivityNodeKind {
    /** Regular action — rounded box with body text. */
    Action,

    /** Start of the activity — small filled circle (initial node). */
    Initial,

    /** End of the entire activity — outer ring with filled inner disc (donut). */
    Final,

    /**
     * End of a single token (other concurrent tokens continue) — outer ring
     * with an X drawn inside via two diagonal lines.
     */
    FlowFinal,

    /** Branches on guards — diamond shape, 1 incoming → N outgoing. */
    Decision,

    /** Merges alternative branches — diamond shape, N incoming → 1 outgoing. */
    Merge,

    /**
     * Splits into parallel branches — synchronisation bar, 1 incoming → N
     * outgoing. The bridge picks a horizontal bar by default
     * (`ACT_BAR_WIDTH × ACT_BAR_HEIGHT`); the layout-engine may flip the
     * orientation later.
     */
    Fork,

    /** Synchronises parallel branches — synchronisation bar, N incoming → 1 outgoing. */
    Join,
}
