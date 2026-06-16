package dev.kuml.runtime.trace.otlp

import dev.kuml.runtime.AiTraceEntry
import dev.kuml.runtime.TraceFile

// ── OTLP attribute key constants (mirrors AiTraceAttributes in kuml-ai-tools) ─

private const val SESSION_ID = "kuml.ai.session.id"
private const val PATCH_ID = "kuml.ai.patch.id"
private const val PATCH_KIND = "kuml.ai.patch.kind"
private const val PATCH_PHASE = "kuml.ai.patch.phase"
private const val PATCH_ERR_COUNT = "kuml.ai.patch.error.count"
private const val BASE_FINGERPRINT = "kuml.ai.base.fingerprint"
private const val REJECTED_COUNT = "kuml.ai.rejected.count"
private const val PATCH_REJECTED = "kuml.ai.patch.rejected"

/**
 * Converts AI-patch lifecycle trace entries into a separate OTLP resource span
 * group with `service.name = "kuml.ai"`.
 *
 * AI spans are structurally independent of the STM/Activity span stack produced
 * by [OtlpExporter]: they form their own session-root / patch-lifecycle hierarchy.
 *
 * Span structure produced:
 * ```
 * ai.session [root span]
 * ├── ai.patch.lifecycle [patchId=ULID-A]  (Validated OK → Applied or Rejected)
 * ├── ai.patch.lifecycle [patchId=ULID-B]  …
 * └── ai.patch.lifecycle [patchId=ULID-C]  …
 * ```
 *
 * Closed by [AiTraceEntry.SessionAborted] (status OK) or left open if the
 * session ended without an explicit abort (defensive: endTime = last entry).
 */
public fun buildAiOtlpResourceSpans(
    traceFile: TraceFile,
    aiEntries: List<AiTraceEntry>,
    serviceName: String = "kuml.ai",
    scopeName: String = "dev.kuml.runtime.ai",
    scopeVersion: String = "v1",
): OtlpResourceSpans? {
    if (aiEntries.isEmpty()) return null

    val sorted = aiEntries.sortedBy { it.seqNo }
    val sessionEntry = sorted.filterIsInstance<AiTraceEntry.SessionStarted>().firstOrNull()
    val sessionId = sessionEntry?.sessionId ?: "(unknown)"
    val traceId = OtlpIds.traceId("ai:$sessionId")
    val rootSpanId = OtlpIds.spanId("ai.session:$sessionId")

    var clockFallback = 0L

    fun entryNanos(e: AiTraceEntry): Long {
        val ts = e.timestamp
        return try {
            if (ts.isNotEmpty()) {
                val instant = java.time.Instant.parse(ts)
                instant.epochSecond * 1_000_000_000L + instant.nano
            } else {
                clockFallback++ * 1_000_000L
            }
        } catch (_: Exception) {
            clockFallback++ * 1_000_000L
        }
    }

    val firstNanos = entryNanos(sorted.first())
    val lastNanos = entryNanos(sorted.last()).coerceAtLeast(firstNanos + 1L)

    // Track open patch spans keyed by patchId
    data class OpenPatchSpan(
        val spanId: String,
        val patchId: String,
        val startNanos: Long,
        val events: MutableList<OtlpEvent> = mutableListOf(),
    )

    val openPatchSpans = mutableMapOf<String, OpenPatchSpan>()
    val finishedSpans = mutableListOf<OtlpSpan>()
    val rootEvents = mutableListOf<OtlpEvent>()
    var rootStatus = OtlpStatus()

    for (entry in sorted) {
        val ts = entryNanos(entry)

        when (entry) {
            is AiTraceEntry.SessionStarted -> {
                rootEvents.add(
                    OtlpEvent(
                        timeUnixNano = ts.toString(),
                        name = "ai.session.started",
                        attributes =
                            listOf(
                                kv(SESSION_ID, entry.sessionId),
                                kv(BASE_FINGERPRINT, entry.baseModelFingerprint),
                            ),
                    ),
                )
            }

            is AiTraceEntry.Validated -> {
                val spanId = OtlpIds.spanId("ai.patch:${entry.patchId}:${entry.seqNo}")
                if (entry.phase == "OK") {
                    // Open a patch span — will be closed by Applied or Rejected
                    openPatchSpans[entry.patchId] =
                        OpenPatchSpan(
                            spanId = spanId,
                            patchId = entry.patchId,
                            startNanos = ts,
                        )
                } else {
                    // Validation failed — open and immediately close with ERROR
                    finishedSpans.add(
                        OtlpSpan(
                            traceId = traceId,
                            spanId = spanId,
                            parentSpanId = rootSpanId,
                            name = "ai.patch.lifecycle",
                            startTimeUnixNano = ts.toString(),
                            endTimeUnixNano = ts.toString(),
                            attributes =
                                listOf(
                                    kv(PATCH_ID, entry.patchId),
                                    kv(PATCH_KIND, entry.patchKind),
                                    kv(PATCH_PHASE, entry.phase),
                                    kv(PATCH_ERR_COUNT, entry.errorCount.toLong()),
                                ),
                            status = OtlpStatus(code = 2, message = "Validation failed: phase=${entry.phase}"),
                        ),
                    )
                }
            }

            is AiTraceEntry.Applied -> {
                val open = openPatchSpans.remove(entry.patchId)
                val spanId = open?.spanId ?: OtlpIds.spanId("ai.patch:${entry.patchId}:applied")
                val startNanos = open?.startNanos ?: ts
                finishedSpans.add(
                    OtlpSpan(
                        traceId = traceId,
                        spanId = spanId,
                        parentSpanId = rootSpanId,
                        name = "ai.patch.lifecycle",
                        startTimeUnixNano = startNanos.toString(),
                        endTimeUnixNano = ts.toString(),
                        attributes =
                            listOf(
                                kv(PATCH_ID, entry.patchId),
                                kv(PATCH_KIND, entry.patchKind),
                                kv(PATCH_PHASE, "OK"),
                            ),
                        events = (open?.events ?: mutableListOf()).toList(),
                        status = OtlpStatus(code = 1), // OK
                    ),
                )
            }

            is AiTraceEntry.Rejected -> {
                // rejectOne: create a short-lived patch span
                val open = openPatchSpans.remove(entry.patchId)
                val spanId = open?.spanId ?: OtlpIds.spanId("ai.patch:${entry.patchId}:rejected")
                val startNanos = open?.startNanos ?: ts
                val attrs =
                    buildList {
                        add(kv(PATCH_ID, entry.patchId))
                        add(kv(PATCH_REJECTED, true))
                        entry.reason?.let { add(kv("kuml.ai.patch.reject.reason", it)) }
                    }
                finishedSpans.add(
                    OtlpSpan(
                        traceId = traceId,
                        spanId = spanId,
                        parentSpanId = rootSpanId,
                        name = "ai.patch.lifecycle",
                        startTimeUnixNano = startNanos.toString(),
                        endTimeUnixNano = ts.toString(),
                        attributes = attrs,
                        events = (open?.events ?: mutableListOf()).toList(),
                        status = OtlpStatus(code = 1), // OK — rejection is expected
                    ),
                )
            }

            is AiTraceEntry.SessionAborted -> {
                rootEvents.add(
                    OtlpEvent(
                        timeUnixNano = ts.toString(),
                        name = "ai.session.aborted",
                        attributes =
                            buildList {
                                add(kv(SESSION_ID, entry.sessionId))
                                add(kv(REJECTED_COUNT, entry.rejectedPatchIds.size.toLong()))
                                entry.reason?.let { add(kv("kuml.ai.session.abort.reason", it)) }
                            },
                    ),
                )
            }
        }
    }

    // Close any still-open patch spans (e.g. pending patches when session ends)
    for (open in openPatchSpans.values) {
        finishedSpans.add(
            OtlpSpan(
                traceId = traceId,
                spanId = open.spanId,
                parentSpanId = rootSpanId,
                name = "ai.patch.lifecycle",
                startTimeUnixNano = open.startNanos.toString(),
                endTimeUnixNano = lastNanos.toString(),
                events = open.events.toList(),
            ),
        )
    }

    // Root session span
    val rootSpan =
        OtlpSpan(
            traceId = traceId,
            spanId = rootSpanId,
            parentSpanId = "",
            name = "ai.session",
            startTimeUnixNano = firstNanos.toString(),
            endTimeUnixNano = lastNanos.toString(),
            attributes =
                buildList {
                    add(kv(SESSION_ID, sessionId))
                    traceFile.modelId?.let { add(kv("kuml.model.id", it)) }
                },
            events = rootEvents,
            status = rootStatus,
        )

    val allSpans = listOf(rootSpan) + finishedSpans

    return OtlpResourceSpans(
        resource = OtlpResource(attributes = listOf(kv("service.name", serviceName))),
        scopeSpans =
            listOf(
                OtlpScopeSpans(
                    scope = OtlpScope(name = scopeName, version = scopeVersion),
                    spans = allSpans,
                ),
            ),
    )
}
