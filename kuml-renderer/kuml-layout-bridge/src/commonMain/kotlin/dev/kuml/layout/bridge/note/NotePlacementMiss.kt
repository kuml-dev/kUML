package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement

/**
 * A note whose placement hint ([wanted], one of ABOVE/BELOW/LEFT/RIGHT) could not be honoured: the hard layout rules
 * left no free position on that side, so the default rules decided and the note ended up on [placed]
 * ([NotePlacement.NEAR] when it sits on no single side of its anchors). Reported as `KUML-NOTE-W004`.
 *
 * [outsideHomeLane] (Nacharbeit 120, BPMN): the note also ended up outside the lane or pool of its anchor, because no
 * free position inside it existed; the message then says so.
 *
 * The message names the note id and the two sides, never the note body.
 */
public data class PlacementHintMiss(
    val noteId: String,
    val wanted: NotePlacement,
    val placed: NotePlacement,
    val sourceLine: Int? = null,
    val outsideHomeLane: Boolean = false,
    /** Finding code override: a `labelAt` port label hint that was not honoured is `KUML-IBD-W001` (PAL Welle B), not a note finding. */
    val code: String? = null,
    /** Message override for [code] findings; never a note or label body. */
    val customMessage: String? = null,
) {
    /**
     * The text of the finding: `placement hint not honoured: <id>, wanted <SIDE>, placed <SIDE|NEAR>`, followed by
     * `, outside the lane or pool of its anchor` when [outsideHomeLane] is set.
     */
    public fun message(): String =
        customMessage
            ?: "placement hint not honoured: $noteId, wanted ${wanted.name}, placed ${placed.name}" +
            if (outsideHomeLane) ", outside the lane or pool of its anchor" else ""

    public companion object {
        /** `labelAt` of a port could not be honoured (PAL Welle B). */
        public const val PORT_LABEL_HINT_NOT_HONOURED: String = "KUML-IBD-W001"
    }
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
