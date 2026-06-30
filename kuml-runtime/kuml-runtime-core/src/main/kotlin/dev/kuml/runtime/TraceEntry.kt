package dev.kuml.runtime

import kotlinx.serialization.SerialName
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
    @SerialName("EventReceived")
    public data class EventReceived(
        override val seqNo: Long,
        override val timestamp: String,
        public val eventName: String,
        public val payload: JsonObject,
    ) : TraceEntry()

    @Serializable
    @SerialName("StateEntered")
    public data class StateEntered(
        override val seqNo: Long,
        override val timestamp: String,
        public val vertexId: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("StateExited")
    public data class StateExited(
        override val seqNo: Long,
        override val timestamp: String,
        public val vertexId: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("ActionInvoked")
    public data class ActionInvoked(
        override val seqNo: Long,
        override val timestamp: String,
        public val phase: ActionPhase,
        public val action: String,
        public val vertexId: String?,
        public val transitionId: String?,
    ) : TraceEntry()

    @Serializable
    @SerialName("TransitionFired")
    public data class TransitionFired(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val fromVertexId: String,
        public val toVertexId: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("GuardEvaluated")
    public data class GuardEvaluated(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val guard: String,
        public val result: Boolean,
    ) : TraceEntry()

    @Serializable
    @SerialName("GuardWarning")
    public data class GuardWarning(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String,
        public val guard: String,
        public val message: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("ActionError")
    public data class ActionError(
        override val seqNo: Long,
        override val timestamp: String,
        public val transitionId: String?,
        public val message: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("Stayed")
    public data class Stayed(
        override val seqNo: Long,
        override val timestamp: String,
        public val reason: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("Terminated")
    public data class Terminated(
        override val seqNo: Long,
        override val timestamp: String,
        public val finalVertexId: String,
    ) : TraceEntry()

    // ── Activity-Runtime trace entries (V2.0.18) ──────────────────────────────

    @Serializable
    @SerialName("TokenPlaced")
    public data class TokenPlaced(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("TokenConsumed")
    public data class TokenConsumed(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("DecisionTaken")
    public data class DecisionTaken(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val chosenEdgeId: String,
        public val guard: String?,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("ForkSplit")
    public data class ForkSplit(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val targetNodeIds: List<String>,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("JoinReached")
    public data class JoinReached(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val awaitingEdgeIds: List<String>,
        public val isReady: Boolean,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("ActivityActionInvoked")
    public data class ActivityActionInvoked(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val body: String?,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("FlowFinalConsumed")
    public data class FlowFinalConsumed(
        override val seqNo: Long,
        override val timestamp: String,
        public val nodeId: String,
        public val clock: Long,
    ) : TraceEntry()

    @Serializable
    @SerialName("ActivityTerminated")
    public data class ActivityTerminated(
        override val seqNo: Long,
        override val timestamp: String,
        public val clock: Long,
    ) : TraceEntry()

    // ── Interaction (Sequence Diagram) trace entries (V3.2) ───────────────────

    @Serializable
    @SerialName("MessageSent")
    public data class MessageSent(
        override val seqNo: Long,
        override val timestamp: String,
        public val messageId: String,
        public val fromLifelineId: String,
        public val toLifelineId: String,
    ) : TraceEntry()

    @Serializable
    @SerialName("MessageReceived")
    public data class MessageReceived(
        override val seqNo: Long,
        override val timestamp: String,
        public val messageId: String,
    ) : TraceEntry()
}

/**
 * Geordneter Trace = serialisierbare Liste von [TraceEntry]s.
 */
public typealias Trace = List<TraceEntry>
