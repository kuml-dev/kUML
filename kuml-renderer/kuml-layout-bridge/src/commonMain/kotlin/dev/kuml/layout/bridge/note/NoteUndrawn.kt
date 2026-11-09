package dev.kuml.layout.bridge.note

/**
 * A note a diagram declares (it lists it in `noteIds`, or one of its anchors belongs to the level the diagram draws)
 * but did not draw. Reported as `KUML-NOTE-W002` by every surface that renders (CLI, workspace, Desktop, MCP).
 *
 * [reason] is one of [dev.kuml.core.model.note.KumlNoteCodes.NoteNotDrawnReason]; [sourceLine] is the script line of the
 * `comment(...)` / `note(...)` call (1-based) or `null`. The message names ids and the reason, never the note body.
 */
public data class UndrawnNote(
    val noteId: String,
    val anchorId: String,
    val reason: String,
    val sourceLine: Int? = null,
) {
    /** The text of the finding: `note not drawn: <id>, anchor <x> (<reason>)`. */
    public fun message(): String = "note not drawn: $noteId, anchor $anchorId ($reason)"
}

/**
 * Collects the [UndrawnNote]s of one render run. The placers ([Sysml2NotePlacement], [C4NotePlacement] and the
 * sequence paths) call [report] after each placement; a surface wraps its render call in [collecting] and turns the
 * result into findings. Outside [collecting] [report] does nothing, so library callers pay nothing.
 *
 * The scope is per thread on the JVM (concurrent renders do not see each other's notes) and global on the
 * single-threaded JS and wasm targets.
 */
public object NoteUndrawnCollector {
    /** Runs [block] and returns its value with the notes that were reported while it ran (each note and anchor once). */
    public fun <T> collecting(block: () -> T): Pair<T, List<UndrawnNote>> {
        val previous = currentUndrawnSink()
        val sink = mutableListOf<UndrawnNote>()
        setUndrawnSink(sink)
        try {
            val value = block()
            return value to sink.toList()
        } finally {
            setUndrawnSink(previous)
        }
    }

    /** Hands [undrawn] to the surrounding [collecting] scope, if any. */
    public fun report(undrawn: List<UndrawnNote>) {
        if (undrawn.isEmpty()) return
        val sink = currentUndrawnSink() ?: return
        for (u in undrawn) {
            if (sink.none { it.noteId == u.noteId && it.anchorId == u.anchorId }) sink += u
        }
    }
}

internal expect fun currentUndrawnSink(): MutableList<UndrawnNote>?

internal expect fun setUndrawnSink(sink: MutableList<UndrawnNote>?)
