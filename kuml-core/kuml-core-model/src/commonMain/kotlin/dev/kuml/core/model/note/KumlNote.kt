package dev.kuml.core.model.note

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /** Characters from this position on are never interpreted as markup (see [NoteMarkup]). */
    const val MAX_PARSE_CHARS: Int = 2_000

    /** Maximum number of formatted (non-plain) spans per note; the rest of the note stays plain text. */
    const val MAX_SPANS: Int = 256

    /** Maximum nesting depth of link, bold and italic (bold-italic counts as two levels). */
    const val MAX_NESTING: Int = 3

    /** Maximum length of a link target. */
    const val MAX_URL_LEN: Int = 2_048
}

/**
 * Semantic kind of a note. [NOTE] is the plain note (no symbol, no colour); every other
 * kind carries a symbol and a colour tone in the SVG output. The kind is serialised only
 * when it differs from [NOTE], so existing JSON stays byte-identical.
 */
@Serializable
enum class NoteKind {
    NOTE,
    INFO,
    HINT,
    WARNING,
    ERROR,
    IMPORTANT,
    SUCCESS,
    QUESTION,
    TODO,
    DECISION,
    ASSUMPTION,
    RISK,
    EXAMPLE,
    REFERENCE,
    DEPRECATED,
    ;

    /** True for every kind except [NOTE]. */
    val hasIcon: Boolean get() = this != NOTE
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
@OptIn(ExperimentalSerializationApi::class)
data class KumlNote(
    val id: String,
    val body: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: NoteKind = NoteKind.NOTE,
    /**
     * Line of the script the note was declared on (`comment(...)` / `note(...)`), 1-based, `null` when unknown.
     * Serialised only when set, so existing JSON stays byte-identical. Used by findings (`KUML-NOTE-W001/W002`).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceLine: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "Note id must not be blank" }
        require(body.length <= KumlNoteLimits.MAX_BODY_CHARS) {
            "Note '$id' body has ${body.length} chars, limit is ${KumlNoteLimits.MAX_BODY_CHARS}"
        }
    }

    /** Equality ignores [sourceLine]: where a note was written is not part of what the note is (round-trips through the printer differ in it). */
    override fun equals(other: Any?): Boolean = other is KumlNote && id == other.id && body == other.body && kind == other.kind

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + body.hashCode()) + kind.hashCode()
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
    /** Script line of the note the finding is about (1-based), `null` when unknown. */
    val sourceLine: Int? = null,
) {
    enum class Severity { ERROR, WARNING }
}
