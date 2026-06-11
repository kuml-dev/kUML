package dev.kuml.runtime.trace

import dev.kuml.runtime.Event
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile

/**
 * Extracts the external event sequence from a recorded [TraceFile] or entry list.
 *
 * Only [TraceEntry.EventReceived] entries are considered.
 * Entries are sorted by [TraceEntry.seqNo] before extraction.
 * Synthetic events with an empty name (produced by INITIAL/CHOICE auto-transitions)
 * are filtered out.
 */
public object EventsFromTrace {
    /** Extract events from a [TraceFile]. */
    public fun extract(traceFile: TraceFile): List<Event> = extract(traceFile.entries)

    /** Extract events from a raw list of [TraceEntry]s. */
    public fun extract(entries: List<TraceEntry>): List<Event> =
        entries
            .sortedBy { it.seqNo }
            .filterIsInstance<TraceEntry.EventReceived>()
            .filter { it.eventName.isNotEmpty() }
            .map { Event(name = it.eventName, payload = it.payload) }
}
