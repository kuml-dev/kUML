package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Discriminator for the three message flavours in a SysML 2 sequence diagram
 * (V2.0.11). Used by [MessageUsage.kind] so a single usage class can carry
 * every message kind — keeps the sealed [Sysml2Usage] surface compact and
 * lets the renderer dispatch on a single enum.
 *
 * **Design rationale**: same trade-off as [ActivityNodeKind] in V2.0.10 — one
 * usage class plus an enum is cleaner than three near-empty sealed sub-types,
 * and the renderer's `when (kind)` stays exhaustive at compile time.
 *
 * **V2.x — out of scope for V2.0.11 MVP**: `Create` (arrow with `«create»`
 * stereotype on the target lifeline) and `Destroy` (arrow ending the target
 * lifeline with an X marker) are deliberately omitted. They require lifecycle
 * semantics on the target lifeline that the V2.0.11 layout model does not yet
 * model — a follow-up V2.x wave will add them.
 */
@Serializable
enum class MessageKind {
    /** Synchronous call — solid line, filled arrowhead. */
    Sync,

    /** Asynchronous send — solid line, open arrowhead. */
    Async,

    /** Reply / return — dashed line, open arrowhead. */
    Reply,
}
