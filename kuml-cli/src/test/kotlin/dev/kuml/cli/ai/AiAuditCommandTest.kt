package dev.kuml.cli.ai

import com.github.ajalt.clikt.testing.test
import dev.kuml.ai.tools.io.KumlHome
import dev.kuml.ai.tools.patch.compliance.ComplianceEvent
import dev.kuml.ai.tools.patch.compliance.ComplianceJson
import dev.kuml.ai.tools.patch.compliance.FileComplianceSink
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AiAuditCommandTest :
    FunSpec({

        // ── Setup/teardown: redirect KumlHome to a temp dir ──────────────────

        lateinit var tempHome: java.nio.file.Path

        beforeSpec {
            tempHome = Files.createTempDirectory("kuml-audit-cli-test-")
            tempHome.toFile().deleteOnExit()
            KumlHome.overrideBaseForTest(tempHome)
        }

        afterSpec {
            KumlHome.clearTestOverride()
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        fun writeLogLines(
            date: String,
            vararg events: ComplianceEvent,
        ) {
            val auditDir = tempHome.resolve("audit")
            Files.createDirectories(auditDir)
            val logFile = auditDir.resolve("compliance-$date.log")
            val lines =
                events.map { event ->
                    ComplianceJson.instance.encodeToString(ComplianceEvent.serializer(), event)
                }
            Files.write(
                logFile,
                lines,
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }

        fun fixedClock(dateStr: String): Clock = Clock.fixed(Instant.parse("${dateStr}T10:00:00Z"), ZoneOffset.UTC)

        val testDate = "2026-01-15"

        val sessionOpened =
            ComplianceEvent.SessionOpened(
                sessionId = "SES-AUDIT-001",
                ownerId = "irakli",
                timestamp = "2026-01-15T10:00:00Z",
                providerId = "anthropic",
                modelId = "claude-sonnet-4-6",
                baseFingerprint = "deadbeef01234567",
            )
        val patchApplied =
            ComplianceEvent.PatchApplied(
                sessionId = "SES-AUDIT-001",
                ownerId = "irakli",
                timestamp = "2026-01-15T10:00:01Z",
                patchId = "PAT-AUDIT-001",
                patchKind = "uml.class",
                elementId = "elem-audit-001",
            )
        val sessionClosed =
            ComplianceEvent.SessionClosed(
                sessionId = "SES-AUDIT-001",
                ownerId = "irakli",
                timestamp = "2026-01-15T10:00:02Z",
                appliedCount = 1,
                rejectedCount = 0,
            )

        // ── Tests ─────────────────────────────────────────────────────────────

        test("kuml ai audit --date exits 0 when log exists") {
            writeLogLines(testDate, sessionOpened, patchApplied, sessionClosed)
            val result = KumlCli().test("ai audit --date $testDate")
            result.statusCode shouldBe 0
        }

        test("text output contains session id, owner, and event names") {
            writeLogLines(testDate, sessionOpened, patchApplied, sessionClosed)
            val result = KumlCli().test("ai audit --date $testDate")
            result.output shouldContain "SES-AUDIT-001"
            result.output shouldContain "irakli"
            result.output shouldContain "session.opened"
            result.output shouldContain "patch.applied"
            result.output shouldContain "session.closed"
        }

        test("text output contains privacy note") {
            writeLogLines(testDate, sessionOpened)
            val result = KumlCli().test("ai audit --date $testDate")
            result.output shouldContain "never prompts"
        }

        test("json output contains all event type discriminators") {
            writeLogLines(testDate, sessionOpened, patchApplied, sessionClosed)
            val result = KumlCli().test("ai audit --date $testDate -o json")
            result.statusCode shouldBe 0
            result.output shouldContain "session.opened"
            result.output shouldContain "patch.applied"
            result.output shouldContain "session.closed"
            result.output shouldContain "SES-AUDIT-001"
        }

        test("--session filter returns only matching events") {
            val otherSession =
                ComplianceEvent.SessionOpened(
                    sessionId = "SES-OTHER-999",
                    ownerId = "bob",
                    timestamp = "2026-01-15T11:00:00Z",
                    providerId = "openai",
                    modelId = "gpt-4o",
                    baseFingerprint = "abcdef01",
                )
            writeLogLines(testDate, sessionOpened, patchApplied, otherSession)
            val result = KumlCli().test("ai audit --date $testDate --session SES-AUDIT-001")
            result.output shouldContain "SES-AUDIT-001"
            result.output shouldNotContain "SES-OTHER-999"
        }

        test("missing log file exits 0 with graceful message") {
            val result = KumlCli().test("ai audit --date 1999-01-01")
            result.statusCode shouldBe 0
            result.output shouldContain "No audit log"
            result.output shouldContain "1999-01-01"
        }

        test("FileComplianceSink written log can be read back by kuml ai audit") {
            val logDate = "2026-01-16"
            val auditDir = tempHome.resolve("audit")
            Files.createDirectories(auditDir)
            val sink = FileComplianceSink(dir = auditDir, clock = fixedClock(logDate))

            sink.emit(
                ComplianceEvent.SessionOpened(
                    sessionId = "SES-ROUNDTRIP",
                    ownerId = "roundtrip-user",
                    timestamp = "2026-01-16T10:00:00Z",
                    providerId = "anthropic",
                    modelId = "claude-opus-4-5",
                    baseFingerprint = "roundtripfp",
                ),
            )
            sink.emit(
                ComplianceEvent.PatchApplied(
                    sessionId = "SES-ROUNDTRIP",
                    ownerId = "roundtrip-user",
                    timestamp = "2026-01-16T10:00:01Z",
                    patchId = "PAT-ROUNDTRIP",
                    patchKind = "rename",
                    elementId = "elem-roundtrip",
                ),
            )

            val result = KumlCli().test("ai audit --date $logDate")
            result.statusCode shouldBe 0
            result.output shouldContain "SES-ROUNDTRIP"
            result.output shouldContain "roundtrip-user"
            result.output shouldContain "session.opened"
            result.output shouldContain "patch.applied"
        }
    })
