package dev.kuml.runtime

import kotlinx.serialization.Serializable

/**
 * Wrapper für eine persistierte Trace mit Schema-Versions-Slot.
 */
@Serializable
public data class TraceFile(
    public val schema: String = SCHEMA,
    public val modelId: String? = null,
    public val entries: List<TraceEntry>,
) {
    public companion object {
        public const val SCHEMA: String = "kuml.trace.v1"
    }
}

/**
 * Wrapper für eine Event-Liste (Eingabe für `kuml simulate`).
 */
@Serializable
public data class EventFile(
    public val schema: String = SCHEMA,
    public val events: List<Event>,
) {
    public companion object {
        public const val SCHEMA: String = "kuml.events.v1"
    }
}
