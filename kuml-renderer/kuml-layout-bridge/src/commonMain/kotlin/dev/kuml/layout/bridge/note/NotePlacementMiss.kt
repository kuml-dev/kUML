package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement

/**
 * A note whose placement hint ([wanted], one of ABOVE/BELOW/LEFT/RIGHT) could not be honoured: the hard layout rules
 * left no free position on that side, so the default rules decided and the note ended up on [placed]
 * ([NotePlacement.NEAR] when it sits on no single side of its anchors). Reported as `KUML-NOTE-W004`.
 *
 * The message names the note id and the two sides, never the note body.
 */
public data class PlacementHintMiss(
    val noteId: String,
    val wanted: NotePlacement,
    val placed: NotePlacement,
    val sourceLine: Int? = null,
) {
    /** The text of the finding: `placement hint not honoured: <id>, wanted <SIDE>, placed <SIDE|NEAR>`. */
    public fun message(): String = "placement hint not honoured: $noteId, wanted ${wanted.name}, placed ${placed.name}"
}

/**
 * Collects the [PlacementHintMiss]es of one render run, exactly like [NoteUndrawnCollector] does for undrawn notes:
 * a surface wraps its render call in [collecting]; outside it [report] does nothing. Per thread on the JVM, global on
 * the single-threaded JS and wasm targets.
 */
public object NotePlacementMissCollector {
    /** Runs [block] and returns its value with the misses reported while it ran (each note once). */
    public fun <T> collecting(block: () -> T): Pair<T, List<PlacementHintMiss>> {
        val previous = currentPlacementMissSink()
        val sink = mutableListOf<PlacementHintMiss>()
        setPlacementMissSink(sink)
        try {
            val value = block()
            return value to sink.toList()
        } finally {
            setPlacementMissSink(previous)
        }
    }

    /** Hands [misses] to the surrounding [collecting] scope, if any. */
    public fun report(misses: List<PlacementHintMiss>) {
        if (misses.isEmpty()) return
        val sink = currentPlacementMissSink() ?: return
        for (m in misses) {
            if (sink.none { it.noteId == m.noteId }) sink += m
        }
    }
}

internal expect fun currentPlacementMissSink(): MutableList<PlacementHintMiss>?

internal expect fun setPlacementMissSink(sink: MutableList<PlacementHintMiss>?)
