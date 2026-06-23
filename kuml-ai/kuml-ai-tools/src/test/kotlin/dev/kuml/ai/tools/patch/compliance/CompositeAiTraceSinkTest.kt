package dev.kuml.ai.tools.patch.compliance

import dev.kuml.ai.tools.patch.aitrace.CompositeAiTraceSink
import dev.kuml.ai.tools.patch.aitrace.InMemoryAiTraceSink
import dev.kuml.runtime.AiTraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CompositeAiTraceSinkTest :
    FunSpec({

        fun makeComposite(compliance: InMemoryComplianceSink): CompositeAiTraceSink =
            CompositeAiTraceSink(
                delegate = InMemoryAiTraceSink(),
                compliance = compliance,
                ownerId = "test-user",
                providerId = "anthropic",
                modelId = "claude-sonnet-4-6",
            )

        // ── SessionStarted → SessionOpened ────────────────────────────────────

        test("SessionStarted entry maps to SessionOpened compliance event") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                sink.emit(
                    AiTraceEntry.SessionStarted(
                        seqNo = 0,
                        timestamp = "2026-06-23T10:00:00Z",
                        sessionId = "SES001",
                        baseModelFingerprint = "deadbeef",
                    ),
                )

                val events = compliance.snapshot()
                events shouldHaveSize 1
                val opened = events[0].shouldBeInstanceOf<ComplianceEvent.SessionOpened>()
                opened.sessionId shouldBe "SES001"
                opened.ownerId shouldBe "test-user"
                opened.providerId shouldBe "anthropic"
                opened.modelId shouldBe "claude-sonnet-4-6"
                opened.baseFingerprint shouldBe "deadbeef"
            }
        }

        // ── Applied → PatchApplied ────────────────────────────────────────────

        test("Applied entry maps to PatchApplied compliance event") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                sink.emit(
                    AiTraceEntry.Applied(
                        seqNo = 1,
                        timestamp = "2026-06-23T10:00:01Z",
                        sessionId = "SES001",
                        patchId = "PAT001",
                        patchKind = "uml.class",
                        elementId = "elem-001",
                    ),
                )

                val events = compliance.snapshot()
                events shouldHaveSize 1
                val applied = events[0].shouldBeInstanceOf<ComplianceEvent.PatchApplied>()
                applied.patchId shouldBe "PAT001"
                applied.patchKind shouldBe "uml.class"
                applied.elementId shouldBe "elem-001"
            }
        }

        // ── Rejected → PatchRejected (privacy: reason NOT forwarded) ─────────

        test("Rejected entry maps to PatchRejected with controlled reasonCode, NOT free-text reason") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                val llmReason = "I updated the Customer class as requested with new attributes"
                sink.emit(
                    AiTraceEntry.Rejected(
                        seqNo = 2,
                        timestamp = "2026-06-23T10:00:02Z",
                        sessionId = "SES001",
                        patchId = "PAT002",
                        reason = llmReason,
                    ),
                )

                val events = compliance.snapshot()
                events shouldHaveSize 1
                val rejected = events[0].shouldBeInstanceOf<ComplianceEvent.PatchRejected>()
                rejected.patchId shouldBe "PAT002"
                // reasonCode must be a controlled value, not the LLM text
                rejected.reasonCode shouldBe ReasonCode.USER_REJECTED

                // JSON must not carry the LLM text
                val json =
                    ComplianceJson.instance.encodeToString(
                        ComplianceEvent.serializer(),
                        rejected,
                    )
                json shouldNotContain llmReason
            }
        }

        test("Rejected with ownership-mismatch prefix maps to OWNERSHIP_MISMATCH code") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                sink.emit(
                    AiTraceEntry.Rejected(
                        seqNo = 2,
                        timestamp = "2026-06-23T10:00:02Z",
                        sessionId = "SES001",
                        patchId = "PAT002",
                        reason = "ownership-mismatch: Patch PAT002 owned by 'bob'",
                    ),
                )

                val events = compliance.snapshot()
                val rejected = events[0].shouldBeInstanceOf<ComplianceEvent.PatchRejected>()
                rejected.reasonCode shouldBe ReasonCode.OWNERSHIP_MISMATCH
            }
        }

        // ── Validated → NO compliance event ──────────────────────────────────

        test("Validated entry produces NO compliance event") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                sink.emit(
                    AiTraceEntry.Validated(
                        seqNo = 1,
                        timestamp = "2026-06-23T10:00:01Z",
                        sessionId = "SES001",
                        patchId = "PAT001",
                        patchKind = "uml.class",
                        phase = "OK",
                        errorCount = 0,
                    ),
                )

                compliance.snapshot().shouldBeEmpty()
            }
        }

        // ── SessionAborted → PatchRejected(s) + SessionClosed ────────────────

        test("SessionAborted maps to PatchRejected per id plus one SessionClosed") {
            runTest {
                val compliance = InMemoryComplianceSink()
                val sink = makeComposite(compliance)

                // First emit an Applied so appliedCount = 1
                sink.emit(
                    AiTraceEntry.Applied(
                        seqNo = 0,
                        timestamp = "2026-06-23T10:00:00Z",
                        sessionId = "SES001",
                        patchId = "PAT-APPLIED",
                        patchKind = "uml.class",
                        elementId = "elem-001",
                    ),
                )

                // Then abort with 2 patches
                sink.emit(
                    AiTraceEntry.SessionAborted(
                        seqNo = 1,
                        timestamp = "2026-06-23T10:00:01Z",
                        sessionId = "SES001",
                        rejectedPatchIds = listOf("PAT-A", "PAT-B"),
                        reason = "user cancelled session",
                    ),
                )

                val events = compliance.snapshot()
                // 1 PatchApplied + 2 PatchRejected + 1 SessionClosed = 4
                events shouldHaveSize 4

                val rejections = events.filterIsInstance<ComplianceEvent.PatchRejected>()
                rejections shouldHaveSize 2
                rejections.all { it.reasonCode == ReasonCode.SESSION_ABORTED } shouldBe true
                rejections.map { it.patchId }.toSet() shouldBe setOf("PAT-A", "PAT-B")

                val closed = events.last().shouldBeInstanceOf<ComplianceEvent.SessionClosed>()
                closed.appliedCount shouldBe 1
                closed.rejectedCount shouldBe 2

                // The LLM "reason" must never appear in any compliance JSON
                val abortReason = "user cancelled session"
                for (event in events) {
                    val json =
                        ComplianceJson.instance.encodeToString(
                            ComplianceEvent.serializer(),
                            event,
                        )
                    json shouldNotContain abortReason
                }
            }
        }

        // ── Delegate also receives all entries ────────────────────────────────

        test("delegate receives all AiTraceEntry instances including Validated") {
            runTest {
                val delegate = InMemoryAiTraceSink()
                val compliance = InMemoryComplianceSink()
                val sink =
                    CompositeAiTraceSink(
                        delegate = delegate,
                        compliance = compliance,
                        ownerId = "test-user",
                    )

                sink.emit(
                    AiTraceEntry.SessionStarted(0, "2026-06-23T10:00:00Z", "SES001", "fp"),
                )
                sink.emit(
                    AiTraceEntry.Validated(1, "2026-06-23T10:00:01Z", "SES001", "PAT001", "uml.class", "OK", 0),
                )

                delegate.snapshot() shouldHaveSize 2
                // Validated goes to delegate but NOT compliance
                compliance.snapshot() shouldHaveSize 1
            }
        }
    })
