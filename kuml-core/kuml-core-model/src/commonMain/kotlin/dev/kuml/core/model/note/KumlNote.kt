package dev.kuml.core.model.note

import kotlinx.serialization.Serializable

/**
 * Limits shared by every note implementation (SysML 2, BPMN, ERM).
 *
 * [MAX_BODY_CHARS] is a hard limit enforced in [KumlNote]'s `init` block, so it
 * applies uniformly to the DSL, JSON decoding and XML import. The display
 * limits are soft: layout and SVG rendering truncate the visible text and the
 * CLI reports a `NOTE_TRUNCATED` diagnostic.
 */
object KumlNoteLimits {
    /** Hard limit for the body length (DSL, JSON decode, XML import). */
    const val MAX_BODY_CHARS: Int = 65_536

    /** Display limit in characters (applied before any line splitting). */
    const val MAX_DISPLAY_CHARS: Int = 2_000

    /** Display limit in lines (applied after wrapping). */
    const val MAX_DISPLAY_LINES: Int = 30

    /** Marker appended to the last visible line of a truncated note. */
    const val ELLIPSIS: String = "…"
}

/**
 * Free-text note shared by the notations that have no UML comment: SysML 2,
 * BPMN and ERM. A plain value type used via composition; it deliberately does
 * not implement [dev.kuml.core.model.KumlElement].
 *
 * The `init` block is the single DoS boundary. Error messages name the note
 * id but never echo the body.
 */
@Serializable
data class KumlNote(
    val id: String,
    val body: String,
) {
    init {
        require(id.isNotBlank()) { "Note id must not be blank" }
        require(body.length <= KumlNoteLimits.MAX_BODY_CHARS) {
            "Note '$id' body has ${body.length} chars, limit is ${KumlNoteLimits.MAX_BODY_CHARS}"
        }
    }
}

/** Anchor link from a note ([noteId]) to the annotated element ([targetId]). */
@Serializable
data class KumlNoteLink(
    val id: String,
    val noteId: String,
    val targetId: String,
) {
    init {
        require(id.isNotBlank()) { "Note link id must not be blank" }
        require(noteId.isNotBlank()) { "Note link '$id' has a blank noteId" }
        require(targetId.isNotBlank()) { "Note link '$id' has a blank targetId" }
    }
}

/**
 * Structural finding about notes in a model (unknown anchor, duplicate id,
 * unsupported anchor type, note shown nowhere). Produced by the per-notation
 * note checkers; the CLI prints it next to the other diagnostics.
 */
data class KumlNoteIssue(
    val code: String,
    val severity: Severity,
    val subjectId: String,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }
}
