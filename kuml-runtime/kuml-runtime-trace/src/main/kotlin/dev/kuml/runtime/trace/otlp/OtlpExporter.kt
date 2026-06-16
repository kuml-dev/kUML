package dev.kuml.runtime.trace.otlp

import dev.kuml.runtime.AiTraceEntry
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Converts a [TraceFile] into an OpenTelemetry OTLP-JSON export.
 *
 * ## Span structure
 * - One **root span** per trace: `stateMachine:<modelId>` (no parent)
 * - One **child span** per `StateEntered` / `StateExited` pair, nested by the active
 *   span stack at the time of entry.
 * - All other entries (EventReceived, TransitionFired, GuardEvaluated, …) become
 *   OTLP events attached to the innermost open span (or the root span if none is open).
 * - Activity entries (TokenPlaced / TokenConsumed pairs) produce flat node spans.
 *
 * ## Timestamps
 * Timestamps are converted from ISO-8601 strings to Unix nanoseconds (as decimal strings,
 * the OTLP wire format for int64 values). Empty or unparseable timestamps fall back to a
 * monotonically increasing counter (1 ms per step).
 *
 * ## IDs
 * All span/trace IDs are generated deterministically via [OtlpIds] (FNV-1a 64-bit).
 * This makes the output stable for golden-file tests.
 *
 * @param serviceName Value for the `service.name` resource attribute.
 * @param scopeName Instrumentation scope name.
 * @param scopeVersion Instrumentation scope version string.
 * @param json [Json] instance to use for serialization (default: [OtlpJson]).
 */
public class OtlpExporter(
    private val serviceName: String = "kuml.runtime",
    private val scopeName: String = "dev.kuml.runtime.trace",
    private val scopeVersion: String = "v1",
    private val json: Json = OtlpJson,
) {
    /** Convert [traceFile] to an [OtlpExport] data structure. */
    public fun convert(traceFile: TraceFile): OtlpExport {
        val modelId = traceFile.modelId ?: "(anon)"
        val traceId = OtlpIds.traceId(modelId)

        // Separate AI-lifecycle entries — they go into a dedicated kuml.ai resource span.
        val aiEntries = traceFile.entries.filterIsInstance<AiTraceEntry>()
        val sortedEntries = traceFile.entries.filter { it !is AiTraceEntry }.sortedBy { it.seqNo }

        // Derive bounding timestamps
        val firstNanos = sortedEntries.firstOrNull()?.timestampNanos(0L) ?: 0L
        val lastNanos = sortedEntries.lastOrNull()?.timestampNanos(sortedEntries.size.toLong()) ?: (firstNanos + 1L)

        // Root span
        val rootSpanId = OtlpIds.spanId("root:$modelId")
        val rootSpanName = "stateMachine:$modelId"

        // Resource attributes
        val resourceAttrs =
            buildList {
                add(kv("service.name", serviceName))
                traceFile.modelId?.let { add(kv("kuml.model.id", it)) }
                add(kv("kuml.trace.schema", traceFile.schema))
            }

        // Mutable root span events / status
        val rootEvents = mutableListOf<OtlpEvent>()
        var rootStatus = OtlpStatus()

        // Span stack for state nesting
        data class OpenSpan(
            val spanId: String,
            val vertexId: String,
            val parentSpanId: String,
            val startNanos: Long,
            val events: MutableList<OtlpEvent> = mutableListOf(),
            var status: OtlpStatus = OtlpStatus(),
        )

        val spanStack = mutableListOf<OpenSpan>()
        val finishedSpans = mutableListOf<OtlpSpan>()

        // Activity node spans: nodeId → (spanId, startNanos, events)
        data class OpenActivitySpan(
            val spanId: String,
            val nodeId: String,
            val startNanos: Long,
            val events: MutableList<OtlpEvent> = mutableListOf(),
        )

        val activitySpans = mutableMapOf<String, OpenActivitySpan>()
        var clockFallback = 0L

        fun entryNanos(entry: TraceEntry): Long {
            val nanos = entry.timestampNanos(clockFallback)
            if (nanos == clockFallback * 1_000_000L) clockFallback++
            return nanos
        }

        for (entry in sortedEntries) {
            val ts = entryNanos(entry)

            when (entry) {
                is TraceEntry.StateEntered -> {
                    val parentId = spanStack.lastOrNull()?.spanId ?: rootSpanId
                    val spanId = OtlpIds.spanId(modelId, entry.vertexId, entry.seqNo)
                    spanStack.add(
                        OpenSpan(
                            spanId = spanId,
                            vertexId = entry.vertexId,
                            parentSpanId = parentId,
                            startNanos = ts,
                        ),
                    )
                }

                is TraceEntry.StateExited -> {
                    // Find topmost matching open span (from top of stack)
                    val idx = spanStack.indexOfLast { it.vertexId == entry.vertexId }
                    if (idx >= 0) {
                        val open = spanStack.removeAt(idx)
                        finishedSpans.add(
                            OtlpSpan(
                                traceId = traceId,
                                spanId = open.spanId,
                                parentSpanId = open.parentSpanId,
                                name = "state:${open.vertexId}",
                                startTimeUnixNano = open.startNanos.toString(),
                                endTimeUnixNano = ts.toString(),
                                events = open.events.toList(),
                                status = open.status,
                            ),
                        )
                    }
                    // If not found: silently ignore (defensive)
                }

                is TraceEntry.TokenPlaced -> {
                    val spanId = OtlpIds.spanId("activity:${entry.nodeId}:${entry.seqNo}")
                    activitySpans[entry.nodeId] =
                        OpenActivitySpan(
                            spanId = spanId,
                            nodeId = entry.nodeId,
                            startNanos = ts,
                        )
                }

                is TraceEntry.TokenConsumed -> {
                    val open = activitySpans.remove(entry.nodeId)
                    if (open != null) {
                        finishedSpans.add(
                            OtlpSpan(
                                traceId = traceId,
                                spanId = open.spanId,
                                parentSpanId = rootSpanId,
                                name = "activity:${open.nodeId}",
                                startTimeUnixNano = open.startNanos.toString(),
                                endTimeUnixNano = ts.toString(),
                                events = open.events.toList(),
                            ),
                        )
                    }
                }

                else -> {
                    // Map to an OtlpEvent and attach to the appropriate span
                    val otlpEvent = entry.toOtlpEvent(ts) ?: continue

                    // Activity-specific entries: attach to root (no state span open)
                    val isActivityEntry =
                        entry is TraceEntry.DecisionTaken ||
                            entry is TraceEntry.ForkSplit ||
                            entry is TraceEntry.JoinReached ||
                            entry is TraceEntry.ActivityActionInvoked ||
                            entry is TraceEntry.FlowFinalConsumed ||
                            entry is TraceEntry.ActivityTerminated

                    val topSpan = if (isActivityEntry) null else spanStack.lastOrNull()

                    if (topSpan != null) {
                        topSpan.events.add(otlpEvent)
                        // Mark span as ERROR on ActionError
                        if (entry is TraceEntry.ActionError) {
                            topSpan.status = OtlpStatus(code = 2, message = entry.message)
                            rootStatus = OtlpStatus(code = 2, message = entry.message)
                        }
                    } else {
                        rootEvents.add(otlpEvent)
                        if (entry is TraceEntry.ActionError) {
                            rootStatus = OtlpStatus(code = 2, message = entry.message)
                        }
                    }
                }
            }
        }

        // Close any spans still open (e.g. if trace ended without StateExited)
        for (open in spanStack.reversed()) {
            finishedSpans.add(
                OtlpSpan(
                    traceId = traceId,
                    spanId = open.spanId,
                    parentSpanId = open.parentSpanId,
                    name = "state:${open.vertexId}",
                    startTimeUnixNano = open.startNanos.toString(),
                    endTimeUnixNano = lastNanos.toString(),
                    events = open.events.toList(),
                    status = open.status,
                ),
            )
        }
        spanStack.clear()

        // Close any open activity spans
        for (open in activitySpans.values) {
            finishedSpans.add(
                OtlpSpan(
                    traceId = traceId,
                    spanId = open.spanId,
                    parentSpanId = rootSpanId,
                    name = "activity:${open.nodeId}",
                    startTimeUnixNano = open.startNanos.toString(),
                    endTimeUnixNano = lastNanos.toString(),
                    events = open.events.toList(),
                ),
            )
        }

        // Build root span
        val rootSpan =
            OtlpSpan(
                traceId = traceId,
                spanId = rootSpanId,
                parentSpanId = "",
                name = rootSpanName,
                startTimeUnixNano = firstNanos.toString(),
                endTimeUnixNano = lastNanos.toString(),
                events = rootEvents.toList(),
                status = rootStatus,
            )

        val allSpans = listOf(rootSpan) + finishedSpans

        val runtimeResourceSpans =
            OtlpResourceSpans(
                resource = OtlpResource(attributes = resourceAttrs),
                scopeSpans =
                    listOf(
                        OtlpScopeSpans(
                            scope = OtlpScope(name = scopeName, version = scopeVersion),
                            spans = allSpans,
                        ),
                    ),
            )

        // AI-lifecycle entries live in their own kuml.ai resource span (V3.0.25).
        val aiResourceSpans = buildAiOtlpResourceSpans(traceFile, aiEntries)

        return OtlpExport(
            resourceSpans =
                buildList {
                    add(runtimeResourceSpans)
                    aiResourceSpans?.let { add(it) }
                },
        )
    }

    /** Serialize a [TraceFile] to OTLP-JSON string. */
    public fun exportToJson(traceFile: TraceFile): String = json.encodeToString(OtlpExport.serializer(), convert(traceFile))
}

// ── Timestamp conversion ──────────────────────────────────────────────────────

private fun TraceEntry.timestampNanos(fallbackClock: Long): Long =
    try {
        val ts = this.timestamp
        if (ts.isNotEmpty()) {
            val instant = java.time.Instant.parse(ts)
            instant.epochSecond * 1_000_000_000L + instant.nano
        } else {
            fallbackClock * 1_000_000L
        }
    } catch (_: Exception) {
        fallbackClock * 1_000_000L
    }

// ── TraceEntry → OtlpEvent mapping ───────────────────────────────────────────

private fun TraceEntry.toOtlpEvent(timeUnixNano: Long): OtlpEvent? =
    when (this) {
        is TraceEntry.EventReceived ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.event.received",
                attributes =
                    listOf(
                        kv("event.name", eventName),
                        kv("event.payload", payload.toString()),
                    ),
            )

        is TraceEntry.TransitionFired ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.transition.fired",
                attributes =
                    listOf(
                        kv("transition.id", transitionId),
                        kv("from.vertex.id", fromVertexId),
                        kv("to.vertex.id", toVertexId),
                    ),
            )

        is TraceEntry.GuardEvaluated ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.guard.evaluated",
                attributes =
                    listOf(
                        kv("transition.id", transitionId),
                        kv("guard", guard),
                        kv("result", result),
                    ),
            )

        is TraceEntry.GuardWarning ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.guard.warning",
                attributes =
                    listOf(
                        kv("transition.id", transitionId),
                        kv("guard", guard),
                        kv("message", message),
                    ),
            )

        is TraceEntry.ActionInvoked -> {
            val attrs =
                mutableListOf(
                    kv("phase", phase.name),
                    kv("action", action),
                )
            vertexId?.let { attrs.add(kv("vertex.id", it)) }
            transitionId?.let { attrs.add(kv("transition.id", it)) }
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.action.invoked",
                attributes = attrs,
            )
        }

        is TraceEntry.ActionError ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.action.error",
                attributes =
                    listOf(
                        kv("message", message),
                    ),
            )

        is TraceEntry.Stayed ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.stayed",
                attributes =
                    listOf(
                        kv("reason", reason),
                    ),
            )

        is TraceEntry.Terminated ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.terminated",
                attributes =
                    listOf(
                        kv("final.vertex.id", finalVertexId),
                    ),
            )

        is TraceEntry.DecisionTaken ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.decision",
                attributes =
                    buildList {
                        add(kv("node.id", nodeId))
                        add(kv("chosen.edge.id", chosenEdgeId))
                        guard?.let { add(kv("guard", it)) }
                    },
            )

        is TraceEntry.ForkSplit ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.fork",
                attributes =
                    listOf(
                        kv("node.id", nodeId),
                        kv("target.node.ids", targetNodeIds.joinToString(",")),
                    ),
            )

        is TraceEntry.JoinReached ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.join",
                attributes =
                    listOf(
                        kv("node.id", nodeId),
                        kv("is.ready", isReady),
                    ),
            )

        is TraceEntry.ActivityActionInvoked ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.action",
                attributes =
                    buildList {
                        add(kv("node.id", nodeId))
                        body?.let { add(kv("body", it)) }
                    },
            )

        is TraceEntry.FlowFinalConsumed ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.flow.final",
                attributes = listOf(kv("node.id", nodeId)),
            )

        is TraceEntry.ActivityTerminated ->
            OtlpEvent(
                timeUnixNano = timeUnixNano.toString(),
                name = "kuml.activity.terminated",
                attributes = listOf(kv("clock", clock)),
            )

        // StateEntered and StateExited are handled as span boundaries — not events
        is TraceEntry.StateEntered -> null
        is TraceEntry.StateExited -> null
        is TraceEntry.TokenPlaced -> null
        is TraceEntry.TokenConsumed -> null
        // AI-lifecycle entries (V3.0.25) are separated out before the main loop
        // and processed by buildAiOtlpResourceSpans() — they never reach here.
        is AiTraceEntry -> null
    }
