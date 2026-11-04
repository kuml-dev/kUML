package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind
import dev.kuml.layout.bridge.note.NoteSymbols

/** Symbols of the note kinds; the data lives in [NoteSymbols] (shared with the LaTeX renderer). */
internal object NoteIcons {
    /** Path data (24 x 24 grid) of the symbol of [kind]; empty for [NoteKind.NOTE]. */
    fun path(kind: NoteKind): String = NoteSymbols.path(kind)

    /** Colour tone of [kind]. */
    fun tone(kind: NoteKind): NoteTone = NoteSymbols.tone(kind)

    /** Accessible name of the symbol (fixed text, never user input). */
    fun label(kind: NoteKind): String = NoteSymbols.label(kind)
}
