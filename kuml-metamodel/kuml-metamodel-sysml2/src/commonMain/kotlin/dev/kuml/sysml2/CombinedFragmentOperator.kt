package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Interaction operator for a Combined Fragment in a Sequence Diagram. The
 * operator determines the semantics of the frame: alternative branches,
 * optional execution, loops, parallel composition, etc.
 *
 * V2.0.15 supports the eight commonly-used operators. Subtle operators
 * (assert, neg, consider, ignore) are V2.x.
 *
 * The operator name is rendered uppercase in the operator-tag pentagon at
 * the top-left of the fragment frame (`ALT`, `OPT`, `LOOP`, etc.). The
 * frame itself is a dashed rectangle enclosing all lifelines from the
 * first to the last operand's seqNo range.
 *
 * V2.x — out of scope for V2.0.15 MVP:
 *  - `assert` — operand is the only valid continuation.
 *  - `neg` — operand denotes invalid traces.
 *  - `consider` / `ignore` — message-set filters.
 */
@Serializable
enum class CombinedFragmentOperator {
    /** Mutually exclusive branches — one operand selected per guard. */
    Alt,

    /** Single optional branch — operand executes if guard holds. */
    Opt,

    /** Repeated execution while guard holds. */
    Loop,

    /** Operands execute in parallel. */
    Par,

    /** Strict total order between operands. */
    Strict,

    /** Weak sequencing — default Interaction semantics, rarely written explicitly. */
    Seq,

    /** Operand executes; if it does, the rest of the enclosing interaction is skipped. */
    Break,

    /** Operand is treated as a critical section — no interleaving. */
    Critical,
}
