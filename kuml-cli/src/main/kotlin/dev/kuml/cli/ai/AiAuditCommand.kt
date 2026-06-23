package dev.kuml.cli.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.kuml.ai.tools.io.KumlHome
import dev.kuml.ai.tools.patch.compliance.ComplianceEvent
import dev.kuml.ai.tools.patch.compliance.ComplianceJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * `kuml ai audit` — read and display the compliance audit log.
 *
 * The audit log is written by [dev.kuml.ai.tools.patch.compliance.FileComplianceSink]
 * to `~/.kuml/audit/compliance-YYYY-MM-DD.log`. Each line is a JSON-encoded
 * [ComplianceEvent] containing only IDs, timestamps, and controlled-vocabulary codes.
 *
 * **Privacy note**: the audit log contains ONLY session/patch IDs, owner IDs,
 * timestamps, and machine-readable reason codes. It never contains prompt text,
 * model responses, or free-form user content.
 *
 * Options:
 *   --date   YYYY-MM-DD  Date of the log file to read. Defaults to today.
 *   --session <id>       Filter events to a specific session id.
 *   -o text|json         Output format (default: text).
 */
internal class AiAuditCommand : CliktCommand(name = "audit") {
    private val date by option(
        "--date",
        help = "Date of the audit log to read (YYYY-MM-DD). Defaults to today.",
    ).default("")

    private val sessionFilter by option(
        "--session",
        help = "Filter events to a specific session id.",
    )

    private val outputFormat by option("-o", "--output", help = "Output format: text or json")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String =
        "Read the compliance audit log (~/.kuml/audit/). " +
            "The log contains only IDs and timestamps — never prompts or model responses."

    override fun run() {
        val effectiveDate =
            if (date.isBlank()) {
                LocalDate.now().toString()
            } else {
                try {
                    // Parse with LocalDate to validate format and reject path-separator sequences
                    // such as "../../.ssh/known_hosts". DateTimeParseException surfaces as a user error.
                    LocalDate.parse(date).toString()
                } catch (_: java.time.format.DateTimeParseException) {
                    echo("Error: --date must be in YYYY-MM-DD format (got: $date)", err = true)
                    return
                }
            }
        val logFile = KumlHome.auditDir().resolve("compliance-$effectiveDate.log")

        if (!java.nio.file.Files
                .exists(logFile)
        ) {
            echo("No audit log for $effectiveDate (expected: $logFile)")
            return
        }

        val lines =
            java.nio.file.Files
                .readAllLines(logFile, Charsets.UTF_8)
        val events = mutableListOf<ComplianceEvent>()
        var malformed = 0

        for ((lineNum, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val event = ComplianceJson.instance.decodeFromString(ComplianceEvent.serializer(), trimmed)
                events += event
            } catch (_: Exception) {
                malformed++
                System.err.println("audit: skipping malformed line ${lineNum + 1}: ${trimmed.take(80)}")
            }
        }

        val filtered =
            if (sessionFilter != null) {
                events.filter { it.sessionId == sessionFilter }
            } else {
                events
            }

        if (malformed > 0) {
            System.err.println("audit: $malformed malformed line(s) skipped.")
        }

        when (outputFormat) {
            "json" -> renderJson(filtered)
            else -> renderText(filtered, effectiveDate, logFile.toString())
        }
    }

    private fun renderJson(events: List<ComplianceEvent>) {
        val prettyJson = Json { prettyPrint = true }
        // Re-encode each event via the compliance codec to preserve @SerialName types,
        // then collect into a JSON array string.
        val items =
            events.map { event ->
                ComplianceJson.instance.encodeToString(ComplianceEvent.serializer(), event)
            }
        echo("[")
        items.forEachIndexed { i, item ->
            val suffix = if (i < items.size - 1) "," else ""
            // Pretty-print by re-parsing through prettyJson
            val jsonElement = prettyJson.parseToJsonElement(item)
            echo("  " + prettyJson.encodeToString(jsonElement) + suffix)
        }
        echo("]")
    }

    private fun renderText(
        events: List<ComplianceEvent>,
        date: String,
        logPath: String,
    ) {
        if (events.isEmpty()) {
            echo("No compliance events for $date${if (sessionFilter != null) " (session: $sessionFilter)" else ""}.")
            return
        }

        echo("Compliance Audit Log — $date  ($logPath)\n")
        echo(
            "%-30s  %-26s  %-18s  %-18s  DETAIL".format(
                "TIMESTAMP",
                "SESSION",
                "OWNER",
                "EVENT",
            ),
        )
        echo("-".repeat(120))

        for (event in events) {
            val timestamp = event.timestamp.take(26)
            val sessionShort = event.sessionId.take(18)
            val ownerShort = event.ownerId.take(18)
            val (eventName, detail) =
                when (event) {
                    is ComplianceEvent.SessionOpened ->
                        "session.opened" to "provider=${event.providerId} model=${event.modelId}"
                    is ComplianceEvent.PatchApplied ->
                        "patch.applied" to "patch=${event.patchId.take(12)} elem=${event.elementId.take(20)}"
                    is ComplianceEvent.PatchRejected ->
                        "patch.rejected" to "patch=${event.patchId.take(12)} reason=${event.reasonCode}"
                    is ComplianceEvent.SessionClosed ->
                        "session.closed" to "applied=${event.appliedCount} rejected=${event.rejectedCount}"
                }
            echo(
                "%-30s  %-26s  %-18s  %-18s  %s".format(
                    timestamp,
                    sessionShort,
                    ownerShort,
                    eventName,
                    detail,
                ),
            )
        }

        echo("\n${events.size} event(s).")
        echo("Note: this log contains only IDs and timestamps — never prompts or model responses.")
    }
}
