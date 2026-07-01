package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * One operand of a Combined Fragment. Spans a range of sequence numbers and
 * may carry a guard expression. For `Alt`, multiple operands have different
 * guards; for `Opt` / `Loop`, a single operand with one guard is typical.
 *
 * The renderer uses [startSeqNo] / [endSeqNo] to size the operand's
 * vertical slice inside the fragment frame, and the [guard] (if non-null)
 * is rendered as `[guard]` in the top-left corner of that slice. Multiple
 * operands are separated by horizontal dashed lines inside the frame.
 *
 * V2.x — out of scope for V2.0.15 MVP:
 *  - Nested combined fragments inside an operand (operands can only
 *    contain plain messages in V2.0.15).
 *  - Typed guard expressions (raw string only).
 */
@Serializable
data class CombinedFragmentOperand(
    /** Optional guard expression. `null` for the default/else operand. */
    val guard: String? = null,
    /** First sequence number covered by this operand (inclusive). */
    val startSeqNo: Int,
    /** Last sequence number covered by this operand (inclusive). */
    val endSeqNo: Int,
)
