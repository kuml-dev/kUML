package dev.kuml.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Action-Phasen in der State-Machine-Semantik.
 */
@Serializable
public enum class ActionPhase {
    ENTRY,
    EXIT,
    EFFECT,
    DO_ACTIVITY,
}

/**
 * Strukturierter Trace-Eintrag.
 *
 * Geordnet via [seqNo] (monoton ab 0 pro `ModelInstance`-Lebenszyklus).
 * `timestamp` ist ISO 8601 (UTC); siehe [Event.timestamp].
 */
@Serializable
public sealed class TraceEntry {
    public abstract val seqNo: Long
    public abstract val timestamp: String

    @Serializable
    public data class EventReceived(
        override val seqNo: Long,
        override val timestamp: String,
        public val eventName: String,
        public val payload: JsonObject,
    ) : TraceEntry()

    @Serializable
    public data class StateEntered(
        override val seqNo: Long,
        override val timestamp: String,
        public val vertexId: String,
    ) : TraceEntry()

    @Serializable
    public data class StateExited(
        override val seqNo: Long,
        override val timestamp: String,
        public val vertexId: String,
    ) : TraceEntry()

    @Serializable
    public data class ActionInvoked(
        override val seqNo: Long,
        override val timestamp: String,
        public val phase: ActionPhase,
        public val action: String,
        public val vertexId: String?,
        public val transitionId: String?,
    ) : TraceEntry()

    @Serializable
    public data class TransitionFired(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val fromVertexId: String,
        public val toVertexId: String,
    ) : TraceEntry()

    @Serializable
    public data class GuardEvaluated(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val guard: String,
        public val result: Boolean,
    ) : TraceEntry()

    @Serializable
    public data class GuardWarning(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val guard: String,
        public val message: String,
    ) : TraceEntry()

    @Serializable
    public data class ActionError(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String?,
        public val message: String,
    ) : TraceEntry()

    @Serializable
    public data class Stayed(
        override val seqNo: Long,
        override val timestamp: String,
        public val reason: String,
    ) : TraceEntry()

    @Serializable
    public data class Terminated(
        override val seqNo: Long,
        override val timestamp: String,
        public val finalVertexId: String,
    ) : TraceEntry()
}

/**
 * Geordneter Trace = serialisierbare Liste von [TraceEntry]s.
 */
public typealias Trace = List<TraceEntry>
