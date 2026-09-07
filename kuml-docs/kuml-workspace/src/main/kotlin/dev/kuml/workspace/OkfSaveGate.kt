package dev.kuml.workspace

/** The outcome of [OkfSaveGate.decide] over a list of [OkfFinding]s for one document. */
public sealed interface OkfSaveDecision {
    /** The document may be written; [warnings] are non-blocking findings to surface in the UI. */
    public data class Allow(
        public val warnings: List<OkfFinding>,
    ) : OkfSaveDecision

    /** The document must NOT be written; [blocking] are the findings that caused the refusal. */
    public data class Block(
        public val blocking: List<OkfFinding>,
        public val warnings: List<OkfFinding>,
    ) : OkfSaveDecision
}

/**
 * Decides whether a set of [OkfFinding]s for a single document permits a write
 * (kUML Desktop's editable-workspace document editor).
 *
 * A deliberately small, fixed set of codes blocks a save: both are dokumentlokal, and
 * both are fixable with a single click in the `type:` dropdown — so there is no reason
 * to ever let a workspace-local document accumulate one of these on disk via the
 * editor. `OKF-E-003` (missing kuml block for a diagram type), `OKF-W-004` (more than
 * one kuml block), and `OKF-E-005` (a broken link) do NOT block: a document may
 * legitimately point at a file that is about to be created, or be saved mid-edit while
 * the author is cutting and re-pasting the diagram block.
 */
public object OkfSaveGate {
    /** Finding codes that block a save. See the class KDoc for why exactly these two. */
    public val BLOCKING_CODES: Set<String> = setOf("OKF-E-001", "OKF-W-002")

    /** Partitions [findings] into blocking/non-blocking per [BLOCKING_CODES]. */
    public fun decide(findings: List<OkfFinding>): OkfSaveDecision {
        val blocking = findings.filter { it.code in BLOCKING_CODES }
        val warnings = findings.filter { it.code !in BLOCKING_CODES }
        return if (blocking.isEmpty()) {
            OkfSaveDecision.Allow(warnings = warnings)
        } else {
            OkfSaveDecision.Block(blocking = blocking, warnings = warnings)
        }
    }
}
