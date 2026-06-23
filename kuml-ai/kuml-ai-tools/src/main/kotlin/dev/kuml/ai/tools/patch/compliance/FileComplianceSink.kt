package dev.kuml.ai.tools.patch.compliance

import dev.kuml.ai.tools.io.KumlHome
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Compliance sink that appends one JSON line per event to a daily rotating log file.
 *
 * ## File naming
 * `<dir>/compliance-YYYY-MM-DD.log` — the date is derived from [Clock] on every [emit]
 * call. Day rollover is automatic: a new day produces a new filename, and the old
 * file is never renamed or closed explicitly.
 *
 * ## Atomicity
 * Each [emit] call serializes the event to a UTF-8 JSON line and writes it in a
 * single `Files.write(path, bytes, CREATE, APPEND)` call. On POSIX, a single
 * `write()` syscall for payloads < `PIPE_BUF` (~4 KB) is atomic. Compliance events
 * contain IDs and timestamps only, so they are always well within this limit.
 *
 * An instance-level `lock` additionally serializes concurrent [emit] calls from
 * different JVM threads, making this safe for multi-threaded coroutine dispatchers.
 *
 * ## Privacy
 * Events written here must never contain prompt text, model responses, or free-form
 * user content. This is enforced by the [ComplianceEvent] type hierarchy itself —
 * all fields are IDs, timestamps, or controlled vocabulary codes.
 *
 * @param dir    Directory for audit log files. Defaults to [KumlHome.auditDir].
 * @param clock  Clock for date derivation. Defaults to UTC system clock.
 *               Inject a fixed clock in tests to control rotation behavior.
 */
public class FileComplianceSink(
    private val dir: Path = KumlHome.auditDir(),
    private val clock: Clock = Clock.systemUTC(),
) : ComplianceSink {
    private val lock = Any()

    override fun emit(event: ComplianceEvent) {
        val line = ComplianceJson.instance.encodeToString(ComplianceEvent.serializer(), event) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        val date = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val file = dir.resolve("compliance-$date.log")
        synchronized(lock) {
            java.nio.file.Files.write(
                file,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
