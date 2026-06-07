package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Discriminator for the message flavours in a SysML 2 sequence diagram.
 * Used by [MessageUsage.kind] so a single usage class can carry every
 * message kind — keeps the sealed [Sysml2Usage] surface compact and lets
 * the renderer dispatch on a single enum.
 *
 * **Design rationale**: same trade-off as [ActivityNodeKind] in V2.0.10 — one
 * usage class plus an enum is cleaner than five near-empty sealed sub-types,
 * and the renderer's `when (kind)` stays exhaustive at compile time.
 *
 * **Versioning**:
 *  - V2.0.11 shipped [Sync] / [Async] / [Reply].
 *  - V2.0.15 adds [Create] and [Destroy] — the two lifecycle-message kinds
 *    that require a target-lifeline visualisation tweak (open arrow into
 *    the head-box for `Create`, X marker terminating the lifeline for
 *    `Destroy`). LaTeX rendering of these two new kinds is deferred —
 *    the SVG renderer covers them; LaTeX still uses the lifeline-box
 *    fallback.
 */
@Serializable
enum class MessageKind {
    /** Synchronous call — solid line, filled arrowhead. */
    Sync,

    /** Asynchronous send — solid line, open arrowhead. */
    Async,

    /** Reply / return — dashed line, open arrowhead. */
    Reply,

    /** Object-creation message — open arrow at target end, pointing at the new lifeline's head box. */
    Create,

    /** Object-destruction message — solid arrow at target end, target lifeline ends with an X marker. */
    Destroy,
}
