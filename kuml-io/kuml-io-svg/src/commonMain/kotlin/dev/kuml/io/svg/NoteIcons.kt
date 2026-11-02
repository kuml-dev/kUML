package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind

/**
 * Symbols of the note kinds on a 24 x 24 grid, one `d` string per kind.
 *
 * Every symbol is a set of closed polygons (`M`/`L`/`Z`, absolute) that is filled with `fill-rule="evenodd"`,
 * so glyph parts are punched out of the body of the symbol. The paths are constants: they contain no user data.
 * Drawn by hand on the 24 grid (see `icons/README.md`).
 */
internal object NoteIcons {
    private const val INFO_PATH: String =
        "M 4.5 1.5 L 19.5 1.5 L 22.5 4.5 L 22.5 19.5 L 19.5 22.5 L 4.5 22.5 L 1.5 19.5 L 1.5 4.5 Z M 13.7 6.8 L 13.4 7.8 L 12.5 8.4 L 11.5 8.4 L 10.6 7.8 L 10.3 6.8 L 10.6 5.8 L 11.5 5.2 L 12.5 5.2 L 13.4 5.8 Z M 10.6 10 L 13.4 10 L 13.4 18 L 10.6 18 Z"

    private const val HINT_PATH: String =
        "M 5.5 13.2 L 4.8 11.4 L 4.5 9.5 L 4.8 7.6 L 5.5 5.8 L 6.7 4.2 L 8.3 3 L 10.1 2.3 L 12 2 L 13.9 2.3 L 15.8 3 L 17.3 4.2 L 18.5 5.8 L 19.2 7.6 L 19.5 9.5 L 19.2 11.4 L 18.5 13.2 L 14.6 16.5 L 14.6 18 L 9.4 18 L 9.4 16.5 Z M 9.4 19.6 L 14.6 19.6 L 14.6 21.8 L 9.4 21.8 Z"

    private const val WARNING_PATH: String =
        "M 12 1.5 L 23.2 21.5 L 0.8 21.5 Z M 10.7 8.5 L 13.3 8.5 L 13.3 15.5 L 10.7 15.5 Z M 10.7 17 L 13.3 17 L 13.3 19.4 L 10.7 19.4 Z"

    private const val ERROR_PATH: String =
        "M 22.6 16.4 L 16.4 22.6 L 7.6 22.6 L 1.4 16.4 L 1.4 7.6 L 7.6 1.4 L 16.4 1.4 L 22.6 7.6 Z M 8.2 6.8 L 12 10.6 L 15.8 6.8 L 17.2 8.2 L 13.4 12 L 17.2 15.8 L 15.8 17.2 L 12 13.4 L 8.2 17.2 L 6.8 15.8 L 10.6 12 L 6.8 8.2 Z"

    private const val IMPORTANT_PATH: String =
        "M 12 0.8 L 23.2 12 L 12 23.2 L 0.8 12 Z M 10.8 6.8 L 13.2 6.8 L 13.2 13.8 L 10.8 13.8 Z M 10.8 15.4 L 13.2 15.4 L 13.2 17.8 L 10.8 17.8 Z"

    private const val SUCCESS_PATH: String =
        "M 23 12 L 22.7 14.4 L 21.9 16.8 L 20.6 18.9 L 18.9 20.6 L 16.8 21.9 L 14.4 22.7 L 12 23 L 9.6 22.7 L 7.2 21.9 L 5.1 20.6 L 3.4 18.9 L 2.1 16.8 L 1.3 14.4 L 1 12 L 1.3 9.6 L 2.1 7.2 L 3.4 5.1 L 5.1 3.4 L 7.2 2.1 L 9.6 1.3 L 12 1 L 14.4 1.3 L 16.8 2.1 L 18.9 3.4 L 20.6 5.1 L 21.9 7.2 L 22.7 9.6 Z M 20.6 12 L 20.4 13.9 L 19.7 15.7 L 18.7 17.4 L 17.4 18.7 L 15.7 19.7 L 13.9 20.4 L 12 20.6 L 10.1 20.4 L 8.3 19.7 L 6.6 18.7 L 5.3 17.4 L 4.3 15.7 L 3.6 13.9 L 3.4 12 L 3.6 10.1 L 4.3 8.3 L 5.3 6.6 L 6.6 5.3 L 8.3 4.3 L 10.1 3.6 L 12 3.4 L 13.9 3.6 L 15.7 4.3 L 17.4 5.3 L 18.7 6.6 L 19.7 8.3 L 20.4 10.1 Z M 6.6 13.2 L 10.7 17.4 L 17.5 9.6 L 15.7 8 L 10.5 13.8 L 8.2 11.6 Z"

    private const val QUESTION_PATH: String =
        "M 2 3 L 22 3 L 22 18 L 14 18 L 8 23 L 8 18 L 2 18 Z M 10 8.6 L 10.8 7.4 L 12 7.1 L 13.2 7.4 L 13.7 8.2 L 13.4 9 L 10.8 10.8 L 10.8 13.4 L 13.2 13.4 L 13.2 12 L 15.4 10.6 L 16.3 7.8 L 14.8 5.4 L 12 4.5 L 9.2 5.4 L 8 7.4 Z M 10.8 15 L 13.2 15 L 13.2 17.2 L 10.8 17.2 Z"

    private const val TODO_PATH: String =
        "M 2 2 L 22 2 L 22 22 L 2 22 Z M 5 5 L 19 5 L 19 19 L 5 19 Z M 8.5 8.5 L 15.5 8.5 L 15.5 15.5 L 8.5 15.5 Z"

    private const val DECISION_PATH: String =
        "M 1 7 L 13 7 L 13 2 L 23 12 L 13 22 L 13 17 L 1 17 Z M 4 10.4 L 9 10.4 L 9 13.6 L 4 13.6 Z"

    private const val ASSUMPTION_PATH: String =
        "M 1 3 L 23 3 L 12 22.5 Z M 5.6 5.8 L 18.4 5.8 L 12 16.9 Z M 13.5 9.6 L 13.2 10.5 L 12.5 11 L 11.5 11 L 10.8 10.5 L 10.5 9.6 L 10.8 8.7 L 11.5 8.2 L 12.5 8.2 L 13.2 8.7 Z"

    private const val RISK_PATH: String =
        "M 15 0.8 L 3.5 13.2 L 10.2 13.2 L 7.8 23.2 L 20.5 9.8 L 13.6 9.8 L 18 0.8 Z"

    private const val EXAMPLE_PATH: String =
        "M 12 0.5 L 16 8 L 23.5 12 L 16 16 L 12 23.5 L 8 16 L 0.5 12 L 8 8 Z"

    private const val REFERENCE_PATH: String =
        "M 4 1.5 L 20 1.5 L 20 22.5 L 12 16.5 L 4 22.5 Z M 8 5.5 L 16 5.5 L 16 7.7 L 8 7.7 Z"

    private const val DEPRECATED_PATH: String =
        "M 23 12 L 22.7 14.4 L 21.9 16.8 L 20.6 18.9 L 18.9 20.6 L 16.8 21.9 L 14.4 22.7 L 12 23 L 9.6 22.7 L 7.2 21.9 L 5.1 20.6 L 3.4 18.9 L 2.1 16.8 L 1.3 14.4 L 1 12 L 1.3 9.6 L 2.1 7.2 L 3.4 5.1 L 5.1 3.4 L 7.2 2.1 L 9.6 1.3 L 12 1 L 14.4 1.3 L 16.8 2.1 L 18.9 3.4 L 20.6 5.1 L 21.9 7.2 L 22.7 9.6 Z M 19.6 12 L 19.4 13.7 L 18.8 15.3 L 17.9 16.7 L 16.7 17.9 L 15.3 18.8 L 13.7 19.4 L 12 19.6 L 10.3 19.4 L 8.7 18.8 L 7.3 17.9 L 6.1 16.7 L 5.2 15.3 L 4.6 13.7 L 4.4 12 L 4.6 10.3 L 5.2 8.7 L 6.1 7.3 L 7.3 6.1 L 8.7 5.2 L 10.3 4.6 L 12 4.4 L 13.7 4.6 L 15.3 5.2 L 16.7 6.1 L 17.9 7.3 L 18.8 8.7 L 19.4 10.3 Z M 18.3 7.7 L 16.3 5.7 L 5.7 16.3 L 7.7 18.3 Z"

    /** Path data (24 x 24 grid) of the symbol of [kind]; empty for [NoteKind.NOTE]. */
    fun path(kind: NoteKind): String =
        when (kind) {
            NoteKind.NOTE -> ""
            NoteKind.INFO -> INFO_PATH
            NoteKind.HINT -> HINT_PATH
            NoteKind.WARNING -> WARNING_PATH
            NoteKind.ERROR -> ERROR_PATH
            NoteKind.IMPORTANT -> IMPORTANT_PATH
            NoteKind.SUCCESS -> SUCCESS_PATH
            NoteKind.QUESTION -> QUESTION_PATH
            NoteKind.TODO -> TODO_PATH
            NoteKind.DECISION -> DECISION_PATH
            NoteKind.ASSUMPTION -> ASSUMPTION_PATH
            NoteKind.RISK -> RISK_PATH
            NoteKind.EXAMPLE -> EXAMPLE_PATH
            NoteKind.REFERENCE -> REFERENCE_PATH
            NoteKind.DEPRECATED -> DEPRECATED_PATH
        }

    /** Colour tone of [kind]. */
    fun tone(kind: NoteKind): NoteTone =
        when (kind) {
            NoteKind.NOTE -> NoteTone.NEUTRAL
            NoteKind.INFO -> NoteTone.INFO
            NoteKind.HINT -> NoteTone.HINT
            NoteKind.WARNING -> NoteTone.WARNING
            NoteKind.ERROR -> NoteTone.DANGER
            NoteKind.IMPORTANT -> NoteTone.IMPORTANT
            NoteKind.SUCCESS -> NoteTone.SUCCESS
            NoteKind.QUESTION -> NoteTone.INFO
            NoteKind.TODO -> NoteTone.WARNING
            NoteKind.DECISION -> NoteTone.SUCCESS
            NoteKind.ASSUMPTION -> NoteTone.WARNING
            NoteKind.RISK -> NoteTone.DANGER
            NoteKind.EXAMPLE -> NoteTone.HINT
            NoteKind.REFERENCE -> NoteTone.INFO
            NoteKind.DEPRECATED -> NoteTone.NEUTRAL
        }

    /** Accessible name of the symbol (fixed text, never user input). */
    fun label(kind: NoteKind): String =
        when (kind) {
            NoteKind.NOTE -> "Note"
            NoteKind.INFO -> "Info"
            NoteKind.HINT -> "Hint"
            NoteKind.WARNING -> "Warning"
            NoteKind.ERROR -> "Error"
            NoteKind.IMPORTANT -> "Important"
            NoteKind.SUCCESS -> "Success"
            NoteKind.QUESTION -> "Question"
            NoteKind.TODO -> "Todo"
            NoteKind.DECISION -> "Decision"
            NoteKind.ASSUMPTION -> "Assumption"
            NoteKind.RISK -> "Risk"
            NoteKind.EXAMPLE -> "Example"
            NoteKind.REFERENCE -> "Reference"
            NoteKind.DEPRECATED -> "Deprecated"
        }
}
