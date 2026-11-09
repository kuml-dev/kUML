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

    /**
     * The note is declared for a diagram but not drawn in it: the anchor exists, but this diagram kind cannot draw
     * an anchor of that kind, or the diagram does not show the anchor at its level (see [NoteNotDrawnReason]).
     */
    const val ANCHOR_NOT_DRAWABLE: String = "KUML-NOTE-W002"

    /** A display limit was applied: more than [MAX_ANCHORS_PER_NOTE] anchors or more than [MAX_NOTES_PER_DIAGRAM] notes. */
    const val LIMIT_APPLIED: String = "KUML-NOTE-W003"

    /** Anchors beyond this count are not drawn (first ones in declaration order win). */
    const val MAX_ANCHORS_PER_NOTE: Int = 8

    /** Notes beyond this count per diagram are not drawn (first ones in declaration order win). */
    const val MAX_NOTES_PER_DIAGRAM: Int = 200

    /** Reasons of a `KUML-NOTE-W002` "note not drawn", the text in parentheses of the message. */
    object NoteNotDrawnReason {
        /** The anchor is a part (or other element) nested inside a composite whose level this diagram does not draw. */
        const val NESTED: String = "nested"

        /** The anchor is only a row in a compartment (an action or attribute of a definition), not a drawn box. */
        const val ROW_ONLY: String = "row-only"

        /** The anchor belongs to another level or owner than the one this diagram shows. */
        const val OTHER_LEVEL: String = "other-level"

        /** This diagram kind cannot draw an anchor of that kind at all. */
        const val KIND_UNSUPPORTED: String = "kind-unsupported"
    }
}
