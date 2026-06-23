package dev.kuml.ai.tools.patch.aitrace

import dev.kuml.ai.tools.patch.PatchReasonPrefix
import dev.kuml.ai.tools.patch.compliance.ComplianceEvent
import dev.kuml.ai.tools.patch.compliance.ComplianceSink
import dev.kuml.ai.tools.patch.compliance.NoopComplianceSink
import dev.kuml.ai.tools.patch.compliance.ReasonCode
import dev.kuml.runtime.AiTraceEntry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges the suspending [AiTraceSink] world to the non-suspending [ComplianceSink].
 *
 * Every [AiTraceEntry] forwarded to [delegate] is simultaneously translated to the
 * corresponding [ComplianceEvent] and emitted to [compliance].
 *
 * ## Privacy
 * The `reason: String?` field on [AiTraceEntry.Rejected] and [AiTraceEntry.SessionAborted]
 * is intentionally dropped — it may contain LLM-derived free text. Only a fixed
 * [reasonCode] from the controlled vocabulary in [ReasonCode] is forwarded.
 *
 * [AiTraceEntry.Validated] has no compliance counterpart and is silently forwarded
 * only to [delegate].
 *
 * @param delegate    The underlying [AiTraceSink] (e.g. OTLP exporter, InMemory).
 * @param compliance  The [ComplianceSink] receiving translated events.
 * @param ownerId     Owner identifier to embed in every [ComplianceEvent].
 * @param providerId  LLM provider id embedded in [ComplianceEvent.SessionOpened].
 * @param modelId     LLM model id embedded in [ComplianceEvent.SessionOpened].
 */
public class CompositeAiTraceSink(
    private val delegate: AiTraceSink = NoopAiTraceSink,
    private val compliance: ComplianceSink = NoopComplianceSink,
    private val ownerId: String,
    private val providerId: String = "unknown",
    private val modelId: String = "unknown",
) : AiTraceSink {
    // Track applied/rejected counts for SessionClosed — self-contained, no engine ref.
    private val appliedCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)

    override suspend fun emit(entry: AiTraceEntry) {
        // Always forward to the underlying sink first.
        delegate.emit(entry)

        // Translate to compliance event (privacy-safe subset).
        when (entry) {
            is AiTraceEntry.SessionStarted -> {
                compliance.emit(
                    ComplianceEvent.SessionOpened(
                        sessionId = entry.sessionId,
                        ownerId = ownerId,
                        timestamp = entry.timestamp,
                        providerId = providerId,
                        modelId = modelId,
                        baseFingerprint = entry.baseModelFingerprint,
                    ),
                )
            }

            is AiTraceEntry.Applied -> {
                appliedCount.incrementAndGet()
                compliance.emit(
                    ComplianceEvent.PatchApplied(
                        sessionId = entry.sessionId,
                        ownerId = ownerId,
                        timestamp = entry.timestamp,
                        patchId = entry.patchId,
                        patchKind = entry.patchKind,
                        elementId = entry.elementId,
                    ),
                )
            }

            is AiTraceEntry.Rejected -> {
                rejectedCount.incrementAndGet()
                // Determine reason code from context. When reason contains the
                // ownership-mismatch marker set by PatchApplyEngine, map accordingly.
                // All other rejections (including LLM-text reasons) become USER_REJECTED.
                val code =
                    when {
                        entry.reason?.startsWith(PatchReasonPrefix.OWNERSHIP_MISMATCH) == true -> ReasonCode.OWNERSHIP_MISMATCH
                        entry.reason?.startsWith(PatchReasonPrefix.CONFLICT) == true -> ReasonCode.CONFLICT
                        entry.reason?.startsWith(PatchReasonPrefix.VALIDATION) == true -> ReasonCode.VALIDATION_FAILED
                        else -> ReasonCode.USER_REJECTED
                    }
                compliance.emit(
                    ComplianceEvent.PatchRejected(
                        sessionId = entry.sessionId,
                        ownerId = ownerId,
                        timestamp = entry.timestamp,
                        patchId = entry.patchId,
                        patchKind = "", // AiTraceEntry.Rejected has no patchKind field
                        reasonCode = code,
                    ),
                )
            }

            is AiTraceEntry.SessionAborted -> {
                // Emit SESSION_ABORTED rejection for each aborted patch id.
                for (patchId in entry.rejectedPatchIds) {
                    rejectedCount.incrementAndGet()
                    compliance.emit(
                        ComplianceEvent.PatchRejected(
                            sessionId = entry.sessionId,
                            ownerId = ownerId,
                            timestamp = entry.timestamp,
                            patchId = patchId,
                            patchKind = "",
                            reasonCode = ReasonCode.SESSION_ABORTED,
                        ),
                    )
                }
                compliance.emit(
                    ComplianceEvent.SessionClosed(
                        sessionId = entry.sessionId,
                        ownerId = ownerId,
                        timestamp = entry.timestamp,
                        appliedCount = appliedCount.get(),
                        rejectedCount = rejectedCount.get(),
                    ),
                )
            }

            is AiTraceEntry.Validated -> {
                // Validated is a technical trace event, not a compliance event. No-op.
            }
        }
    }
}
