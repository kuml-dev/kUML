package dev.kuml.ai.tools.patch.compliance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines

class FileComplianceSinkTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun tempDir() =
            Files.createTempDirectory("kuml-compliance-test-").also {
                it.toFile().deleteOnExit()
            }

        fun fixedClock(dateStr: String): Clock =
            Clock.fixed(
                Instant.parse("${dateStr}T10:00:00Z"),
                ZoneOffset.UTC,
            )

        fun sampleOpened(sessionId: String = "SES001") =
            ComplianceEvent.SessionOpened(
                sessionId = sessionId,
                ownerId = "test-user",
                timestamp = "2026-06-23T10:00:00Z",
                providerId = "anthropic",
                modelId = "claude-sonnet-4-6",
                baseFingerprint = "deadbeef01234567",
            )

        fun sampleApplied(
            sessionId: String = "SES001",
            patchId: String = "PAT001",
        ) = ComplianceEvent.PatchApplied(
            sessionId = sessionId,
            ownerId = "test-user",
            timestamp = "2026-06-23T10:00:01Z",
            patchId = patchId,
            patchKind = "uml.class",
            elementId = "elem-001",
        )

        fun sampleRejected(
            sessionId: String = "SES001",
            patchId: String = "PAT002",
        ) = ComplianceEvent.PatchRejected(
            sessionId = sessionId,
            ownerId = "test-user",
            timestamp = "2026-06-23T10:00:02Z",
            patchId = patchId,
            patchKind = "rename",
            reasonCode = ReasonCode.USER_REJECTED,
        )

        fun sampleClosed(sessionId: String = "SES001") =
            ComplianceEvent.SessionClosed(
                sessionId = sessionId,
                ownerId = "test-user",
                timestamp = "2026-06-23T10:00:03Z",
                appliedCount = 1,
                rejectedCount = 1,
            )

        // ── Tests ─────────────────────────────────────────────────────────────

        test("emit 3 events produces one log file with 3 non-empty JSON lines") {
            val dir = tempDir()
            val clock = fixedClock("2026-06-23")
            val sink = FileComplianceSink(dir = dir, clock = clock)

            sink.emit(sampleOpened())
            sink.emit(sampleApplied())
            sink.emit(sampleRejected())

            val logFile = dir.resolve("compliance-2026-06-23.log")
            Files.exists(logFile) shouldBe true

            val lines = logFile.readLines().filter { it.isNotBlank() }
            lines shouldHaveSize 3
        }

        test("each line round-trips to the original ComplianceEvent") {
            val dir = tempDir()
            val clock = fixedClock("2026-06-23")
            val sink = FileComplianceSink(dir = dir, clock = clock)

            val events = listOf(sampleOpened(), sampleApplied(), sampleRejected(), sampleClosed())
            events.forEach { sink.emit(it) }

            val logFile = dir.resolve("compliance-2026-06-23.log")
            val lines = logFile.readLines().filter { it.isNotBlank() }
            lines shouldHaveSize 4

            val decoded =
                lines.map {
                    ComplianceJson.instance.decodeFromString(ComplianceEvent.serializer(), it)
                }
            decoded[0] shouldBe events[0]
            decoded[1] shouldBe events[1]
            decoded[2] shouldBe events[2]
            decoded[3] shouldBe events[3]
        }

        test("daily rotation: two different dates produce two distinct files") {
            val dir = tempDir()

            val clockA = fixedClock("2026-06-23")
            val sinkA = FileComplianceSink(dir = dir, clock = clockA)
            sinkA.emit(sampleOpened("SES-DAY-A"))

            val clockB = fixedClock("2026-06-24")
            val sinkB = FileComplianceSink(dir = dir, clock = clockB)
            sinkB.emit(sampleOpened("SES-DAY-B"))

            val fileA = dir.resolve("compliance-2026-06-23.log")
            val fileB = dir.resolve("compliance-2026-06-24.log")

            Files.exists(fileA) shouldBe true
            Files.exists(fileB) shouldBe true
            fileA shouldNotBe fileB

            val linesA = fileA.readLines().filter { it.isNotBlank() }
            val linesB = fileB.readLines().filter { it.isNotBlank() }
            linesA shouldHaveSize 1
            linesB shouldHaveSize 1
            linesA[0] shouldContain "SES-DAY-A"
            linesB[0] shouldContain "SES-DAY-B"
        }

        test("privacy: PatchRejected log line contains reasonCode but never prompt sentinel text") {
            val dir = tempDir()
            val clock = fixedClock("2026-06-23")
            val sink = FileComplianceSink(dir = dir, clock = clock)

            // The rejection reason from the engine might be LLM text — it must NOT appear.
            val llmPromptSentinel = "Please add a Customer class with name attribute"
            // ComplianceEvent.PatchRejected holds only reasonCode, not the free-text reason.
            val event =
                ComplianceEvent.PatchRejected(
                    sessionId = "SES001",
                    ownerId = "test-user",
                    timestamp = "2026-06-23T10:00:02Z",
                    patchId = "PAT002",
                    patchKind = "rename",
                    reasonCode = ReasonCode.USER_REJECTED,
                )
            sink.emit(event)

            val logFile = dir.resolve("compliance-2026-06-23.log")
            val content = logFile.readLines().joinToString("\n")

            content shouldContain ReasonCode.USER_REJECTED
            content shouldNotContain llmPromptSentinel
        }

        test("concurrent emit from 8 threads produces exactly 100 well-formed lines") {
            val dir = tempDir()
            val clock = fixedClock("2026-06-23")
            val sink = FileComplianceSink(dir = dir, clock = clock)

            val threadCount = 8
            val eventsPerThread = 100 / threadCount // 12 each + a few extras below
            val totalEvents = 100
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { t ->
                executor.submit {
                    try {
                        repeat(eventsPerThread) { i ->
                            sink.emit(
                                ComplianceEvent.PatchApplied(
                                    sessionId = "SES-CONCURRENT",
                                    ownerId = "user-$t",
                                    timestamp = "2026-06-23T10:00:0${i % 10}Z",
                                    patchId = "PAT-$t-$i",
                                    patchKind = "uml.class",
                                    elementId = "elem-$t-$i",
                                ),
                            )
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            // Emit the remaining events on the calling thread to hit exactly 100
            val emittedByThreads = threadCount * eventsPerThread
            repeat(totalEvents - emittedByThreads) { i ->
                sink.emit(
                    ComplianceEvent.PatchApplied(
                        sessionId = "SES-CONCURRENT",
                        ownerId = "main",
                        timestamp = "2026-06-23T10:00:09Z",
                        patchId = "PAT-MAIN-$i",
                        patchKind = "rename",
                        elementId = "elem-main-$i",
                    ),
                )
            }

            latch.await(30, TimeUnit.SECONDS)
            executor.shutdown()

            val logFile = dir.resolve("compliance-2026-06-23.log")
            val lines = logFile.readLines().filter { it.isNotBlank() }
            lines shouldHaveSize 100

            // Each line must be valid JSON (round-trip check).
            var parseErrors = 0
            for (line in lines) {
                runCatching {
                    ComplianceJson.instance.decodeFromString(ComplianceEvent.serializer(), line)
                }.onFailure { parseErrors++ }
            }
            parseErrors shouldBe 0
        }
    })
