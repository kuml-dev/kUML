package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement

/**
 * Placement-hint rule for a note that lives in a fixed column beside a grid and is stacked from the top (Service
 * Blueprint, SVG and LaTeX). Unit-neutral: all lengths share one unit (px for SVG, pt for LaTeX), y grows downwards.
 *
 * ABOVE/BELOW move the note next to the first anchor card (but never above the stack bottom [resolve]`.minY`);
 * RIGHT always holds because the column lies right of the grid; LEFT never can and is reported with `placed = RIGHT`.
 */
public object ColumnNoteHint {
    /** The first anchor card: [y] its top edge, [h] its height. */
    public data class Anchor(
        val y: Double,
        val h: Double,
    )

    /** The resolved top [y] of the note and the [miss] if the hint could not be honoured. */
    public data class Result(
        val y: Double,
        val miss: PlacementHintMiss?,
    )

    /**
     * @param fallbackY top of the note when no side hint applies to the anchor (the caller's default rule)
     * @param minY lowest free y of the column (bottom of the notes already stacked)
     */
    public fun resolve(
        noteId: String,
        sourceLine: Int?,
        hint: NotePlacement,
        anchor: Anchor,
        noteHeight: Double,
        fallbackY: Double,
        minY: Double,
        gap: Double,
        eps: Double,
    ): Result {
        val desired =
            when (hint) {
                NotePlacement.ABOVE -> anchor.y - gap - noteHeight
                NotePlacement.BELOW -> anchor.y + anchor.h + gap
                else -> fallbackY
            }
        val y = maxOf(desired, minY)
        val placed =
            when {
                y + noteHeight <= anchor.y + eps -> NotePlacement.ABOVE
                y >= anchor.y + anchor.h - eps -> NotePlacement.BELOW
                else -> NotePlacement.NEAR
            }
        val honoured =
            when (hint) {
                NotePlacement.RIGHT -> true
                NotePlacement.LEFT -> false
                else -> placed == hint
            }
        if (honoured) return Result(y = y, miss = null)
        val actual = if (hint == NotePlacement.LEFT) NotePlacement.RIGHT else placed
        return Result(y = y, miss = PlacementHintMiss(noteId = noteId, wanted = hint, placed = actual, sourceLine = sourceLine))
    }
}
