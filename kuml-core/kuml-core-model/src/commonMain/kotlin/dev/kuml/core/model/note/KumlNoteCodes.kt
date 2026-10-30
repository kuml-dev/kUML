package dev.kuml.core.model.note

/**
 * Stable diagnostic codes for notes, shared by every notation and every surface
 * (CLI, workspace validation, Desktop, MCP). All three are warnings: a note that
 * cannot be anchored is shown free-standing or not at all, it never aborts a render.
 *
 * Messages name note ids, anchor ids and diagram ids, never the note body.
 */
object KumlNoteCodes {
    /** The anchor id of a note refers to no element of the model. */
    const val ANCHOR_UNKNOWN: String = "KUML-NOTE-W001"

    /** The anchor exists but this diagram kind cannot draw an anchor of that kind. */
    const val ANCHOR_NOT_DRAWABLE: String = "KUML-NOTE-W002"

    /** A display limit was applied: more than [MAX_ANCHORS_PER_NOTE] anchors or more than [MAX_NOTES_PER_DIAGRAM] notes. */
    const val LIMIT_APPLIED: String = "KUML-NOTE-W003"

    /** Anchors beyond this count are not drawn (first ones in declaration order win). */
    const val MAX_ANCHORS_PER_NOTE: Int = 8

    /** Notes beyond this count per diagram are not drawn (first ones in declaration order win). */
    const val MAX_NOTES_PER_DIAGRAM: Int = 200
}
