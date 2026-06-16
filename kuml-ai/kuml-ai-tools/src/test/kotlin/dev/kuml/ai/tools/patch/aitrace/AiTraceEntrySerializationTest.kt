package dev.kuml.ai.tools.patch.aitrace

import dev.kuml.runtime.AiTraceEntry
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString

/**
 * Roundtrip via KumlRuntimeJson using the TraceEntry polymorphic codec
 * (classDiscriminator = "type"). AiTraceEntry sub-types are registered
 * via @SerialName annotations.
 */
private inline fun <reified T : AiTraceEntry> roundtrip(entry: T): T {
    val json = KumlRuntimeJson.encodeToString<TraceEntry>(entry)
    val decoded = KumlRuntimeJson.decodeFromString<TraceEntry>(json)
    decoded.shouldBeInstanceOf<T>()
    return decoded as T
}

class AiTraceEntrySerializationTest :
    FunSpec({

        test("SessionStarted JSON roundtrip via kotlinx serialization") {
            val original =
                AiTraceEntry.SessionStarted(
                    seqNo = 0L,
                    timestamp = "2026-06-12T10:00:00Z",
                    sessionId = "01HZABCDEF",
                    baseModelFingerprint = "deadbeef01234567",
                )
            val decoded = roundtrip(original)
            decoded.seqNo shouldBe 0L
            decoded.sessionId shouldBe "01HZABCDEF"
            decoded.baseModelFingerprint shouldBe "deadbeef01234567"
        }

        test("Validated with phase=SANDBOX and errorCount=3 roundtrips") {
            val original =
                AiTraceEntry.Validated(
                    seqNo = 1L,
                    timestamp = "2026-06-12T10:00:01Z",
                    sessionId = "01HZABCDEF",
                    patchId = "01HZPATCH1",
                    patchKind = "uml.class",
                    phase = "SANDBOX",
                    errorCount = 3,
                )
            val decoded = roundtrip(original)
            decoded.phase shouldBe "SANDBOX"
            decoded.errorCount shouldBe 3
            decoded.patchId shouldBe "01HZPATCH1"
        }

        test("Applied roundtrip preserves all fields") {
            val original =
                AiTraceEntry.Applied(
                    seqNo = 2L,
                    timestamp = "2026-06-12T10:00:02Z",
                    sessionId = "01HZABCDEF",
                    patchId = "01HZPATCH2",
                    patchKind = "uml.generalization",
                    elementId = "gen-001",
                )
            val decoded = roundtrip(original)
            decoded.elementId shouldBe "gen-001"
            decoded.patchKind shouldBe "uml.generalization"
        }

        test("Rejected with null reason roundtrips as JSON null") {
            val original =
                AiTraceEntry.Rejected(
                    seqNo = 3L,
                    timestamp = "2026-06-12T10:00:03Z",
                    sessionId = "01HZABCDEF",
                    patchId = "01HZPATCH3",
                    reason = null,
                )
            val decoded = roundtrip(original)
            decoded.reason shouldBe null
            decoded.patchId shouldBe "01HZPATCH3"
        }

        test("SessionAborted with empty rejectedPatchIds roundtrips") {
            val original =
                AiTraceEntry.SessionAborted(
                    seqNo = 4L,
                    timestamp = "2026-06-12T10:00:04Z",
                    sessionId = "01HZABCDEF",
                    rejectedPatchIds = emptyList(),
                    reason = "user cancelled",
                )
            val decoded = roundtrip(original)
            decoded.rejectedPatchIds.isEmpty() shouldBe true
            decoded.reason shouldBe "user cancelled"
        }
    })
