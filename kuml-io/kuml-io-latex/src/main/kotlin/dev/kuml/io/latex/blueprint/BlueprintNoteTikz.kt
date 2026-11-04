package dev.kuml.io.latex.blueprint

import dev.kuml.core.model.note.KumlNote
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout

/**
 * Placement of Service Blueprint notes for the LaTeX renderer: one column right of the grid, stacked downwards in
 * declaration order from the top of the grid. This is a simple LaTeX-side rule, not the SVG note router.
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

    /** Places the notes; the result is shorter than [notes] when the height limit is reached. */
    fun place(notes: List<KumlNote>): List<Placed> {
        val out = mutableListOf<Placed>()
        var y = 0f
        for ((k, note) in notes.withIndex()) {
            val size = NoteLayout.sizeOf(body = note.body, frame = NoteFrameStyle.DOG_EAR, kind = note.kind)
            if (y + size.height > MAX_STACK_PT) break
            out += Placed(k = k, note = note, yPt = y, size = size)
            y += size.height + NOTE_GAP_PT
        }
        return out
    }
}
