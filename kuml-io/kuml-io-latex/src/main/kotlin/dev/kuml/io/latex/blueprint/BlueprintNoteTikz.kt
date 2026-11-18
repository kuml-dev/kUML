package dev.kuml.io.latex.blueprint

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.isHint
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.note.ColumnNoteHint
import dev.kuml.layout.bridge.note.PlacementHintMiss

/**
 * Placement of Service Blueprint notes for the LaTeX renderer: one column right of the grid, stacked downwards in
 * declaration order from the top of the grid; an ABOVE/BELOW placement hint moves a note next to its first anchor card
 * (shared rule [ColumnNoteHint], Nacharbeit 104). This is a simple LaTeX-side rule, not the SVG note router.
 */
internal object BlueprintNoteTikz {
    const val PT_PER_CM = 28.452756
    const val COLUMN_GAP_CM = 0.4
    const val NOTE_GAP_PT = 8.5f

    /** TeX stops at 16383.99pt; stay well below it so a model with 200 long notes cannot break the compile. */
    const val MAX_STACK_PT = 12000f

    internal const val OMITTED_ANCHOR = "% note anchor omitted: target not drawn in this view"
    internal const val OMITTED_NOTE = "% note omitted: picture height limit reached"

    /** [k] is the declaration index, [yPt] the top of the note below the grid top (pt, downwards). */
    data class Placed(
        val k: Int,
        val note: KumlNote,
        val yPt: Float,
        val size: Size,
    )

    /** Result of [place]: the placed notes and the placement hints that could not be honoured. */
    data class Placement(
        val placed: List<Placed>,
        val misses: List<PlacementHintMiss>,
    )

    /**
     * Places the notes; the result is shorter than [notes] when the height limit is reached.
     *
     * Without a placement hint (or with NEAR) a note simply takes the next free slot of the stack, exactly as before.
     * An ABOVE/BELOW hint moves it next to its first anchor card ([anchorOf], pt below the grid top), never above the
     * stack bottom; RIGHT always holds, LEFT never can (the column lies right of the grid). Misses are returned.
     */
    fun place(
        notes: List<KumlNote>,
        anchorOf: (KumlNote) -> ColumnNoteHint.Anchor? = { null },
    ): Placement {
        val out = mutableListOf<Placed>()
        val misses = mutableListOf<PlacementHintMiss>()
        var y = 0f
        for ((k, note) in notes.withIndex()) {
            val size = NoteLayout.sizeOf(body = note.body, frame = NoteFrameStyle.DOG_EAR, kind = note.kind)
            var top = y
            val anchor = if (note.placement.isHint()) anchorOf(note) else null
            if (anchor != null) {
                val r =
                    ColumnNoteHint.resolve(
                        noteId = note.id,
                        sourceLine = note.sourceLine,
                        hint = note.placement!!,
                        anchor = anchor,
                        noteHeight = size.height.toDouble(),
                        fallbackY = y.toDouble(),
                        minY = y.toDouble(),
                        gap = NOTE_GAP_PT.toDouble(),
                        eps = 1e-3,
                    )
                top = r.y.toFloat()
                r.miss?.let { misses += it }
            }
            if (top + size.height > MAX_STACK_PT) break
            out += Placed(k = k, note = note, yPt = top, size = size)
            y = top + size.height + NOTE_GAP_PT
        }
        return Placement(placed = out, misses = misses)
    }
}
