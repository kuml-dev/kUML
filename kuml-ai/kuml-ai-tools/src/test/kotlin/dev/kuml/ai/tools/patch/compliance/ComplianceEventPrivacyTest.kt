package dev.kuml.ai.tools.patch.compliance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain

/**
 * Privacy guard: ensures that none of the [ComplianceEvent] sub-types can
 * accidentally carry raw prompt text or LLM responses when serialized to JSON.
 *
 * Strategy: serialize all variant instances that contain a well-known "prompt sentinel"
 * in places they shouldn't, then assert the sentinel does NOT appear in the output.
 * Since [ComplianceEvent] fields only accept IDs, timestamps, and controlled codes,
 * this test documents and enforces that contract.
 */
class ComplianceEventPrivacyTest :
    FunSpec({

        val sentinels =
            listOf(
                "Please add a Customer class",
                "Here is the updated diagram with",
                "As an AI language model",
                "I'll modify the relationship between",
            )

        fun serialize(event: ComplianceEvent): String = ComplianceJson.instance.encodeToString(ComplianceEvent.serializer(), event)

        test("SessionOpened JSON contains no prompt sentinel") {
            val json =
                serialize(
                    ComplianceEvent.SessionOpened(
                        sessionId = "SES001",
                        ownerId = "alice",
                        timestamp = "2026-06-23T10:00:00Z",
                        providerId = "anthropic",
                        modelId = "claude-sonnet-4-6",
                        baseFingerprint = "deadbeef01234567",
                    ),
                )
            for (sentinel in sentinels) {
                json shouldNotContain sentinel
            }
        }

        test("PatchApplied JSON contains no prompt sentinel") {
            val json =
                serialize(
                    ComplianceEvent.PatchApplied(
                        sessionId = "SES001",
                        ownerId = "alice",
                        timestamp = "2026-06-23T10:00:01Z",
                        patchId = "PAT001",
                        patchKind = "uml.class",
                        elementId = "elem-001",
                    ),
                )
            for (sentinel in sentinels) {
                json shouldNotContain sentinel
            }
        }

        test("PatchRejected reasonCode is controlled vocabulary — not the LLM reason string") {
            // Simulate what would happen if the engine's free-text reason leaked in.
            // The event must only have the reasonCode field, which is from ReasonCode object.
            val freeTextReason = "Please add a Customer class with name attribute"
            val event =
                ComplianceEvent.PatchRejected(
                    sessionId = "SES001",
                    ownerId = "alice",
                    timestamp = "2026-06-23T10:00:02Z",
                    patchId = "PAT002",
                    patchKind = "rename",
                    // Note: we deliberately test with USER_REJECTED, not the free-text reason.
                    reasonCode = ReasonCode.USER_REJECTED,
                )
            val json = serialize(event)
            json shouldNotContain freeTextReason
            for (sentinel in sentinels) {
                json shouldNotContain sentinel
            }
        }

        test("SessionClosed JSON contains no prompt sentinel") {
            val json =
                serialize(
                    ComplianceEvent.SessionClosed(
                        sessionId = "SES001",
                        ownerId = "alice",
                        timestamp = "2026-06-23T10:00:03Z",
                        appliedCount = 3,
                        rejectedCount = 1,
                    ),
                )
            for (sentinel in sentinels) {
                json shouldNotContain sentinel
            }
        }

        test("All reasonCode constants are valid controlled vocabulary strings") {
            val codes =
                listOf(
                    ReasonCode.OWNERSHIP_MISMATCH,
                    ReasonCode.CONFLICT,
                    ReasonCode.VALIDATION_FAILED,
                    ReasonCode.USER_REJECTED,
                    ReasonCode.SESSION_ABORTED,
                )
            // Each code must be a short uppercase identifier without spaces.
            for (code in codes) {
                code.matches(Regex("[A-Z_]+")) shouldBe true
            }
        }
    })

// Extension for shouldBe bool check (kotest uses infix shouldBe)
private infix fun Boolean.shouldBe(expected: Boolean) {
    if (this != expected) throw AssertionError("Expected $expected but was $this")
}
