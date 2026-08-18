package dev.kuml.core.script.style

import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.SandboxClasspath
import dev.kuml.core.script.WorkerProcessSupport
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Public entry point of the source-style validation feature
 * (`RequireNamedArguments`, ported to run against real `*.kuml.kts` source
 * text): checks whether [source] passes any argument positionally to a
 * `dev.kuml.*` function/constructor that declares more than one value
 * parameter.
 *
 * ## Why a child process, not in-process analysis
 *
 * The Kotlin Analysis API standalone session
 * (`dev.detekt:detekt-kotlin-analysis-api-standalone`) needs the plain
 * `org.jetbrains.kotlin:kotlin-compiler` artifact on its classpath (unshaded
 * `com.intellij.**`). This JVM's own runtime classpath already carries
 * `kotlin-compiler-embeddable` (shaded `com.intellij.**`, pulled in via
 * `kotlin-scripting-jvm-host` for [dev.kuml.core.script.KumlScriptHost]).
 * Both jars declare overlapping `org.jetbrains.kotlin` packages; mixing
 * them on one classpath throws `NoSuchMethodError` at Analysis-API
 * session-build time regardless of classpath ordering (verified empirically).
 * An in-process isolated `URLClassLoader` avoids that collision but leaks a
 * non-daemon `ApplicationImpl pooled thread` that survives
 * `Disposer.dispose()` and hangs the host JVM forever — unacceptable for a
 * long-lived `kuml-mcp` server. A disposable **child process**
 * (`:kuml-style-worker`, `dev.kuml.style.worker.StyleWorkerMain`) sidesteps
 * both problems: its own classpath never touches this JVM's, and it
 * `exitProcess(0)`s unconditionally, taking the leaked thread down with it.
 * The added wall-clock cost (~1.2 s including JVM boot) is paid concurrently
 * with the DSL script's own evaluation by callers that launch it on a
 * background thread (see `ValidateCommand`) — net overhead is close to zero
 * against the ~2.3 s `kuml validate` baseline.
 *
 * ## Trust boundary
 *
 * No OS-level sandbox (`sandbox-exec` / `bwrap` / Job Object — see
 * [dev.kuml.core.script.OsSandbox]) is applied to this worker, unlike
 * [dev.kuml.core.script.ChildProcessScriptEvaluator]'s DSL-execution worker.
 * That is a deliberate difference, not an oversight: this worker never
 * *executes* the analyzed script — the Analysis API only performs static
 * symbol resolution over the wrapped source — so there is no code-execution
 * trust boundary to cage. The classpath the analysis session resolves
 * `dev.kuml.*` symbols against ([SandboxClasspath.curatedEntries]) is the
 * exact same curated set the DSL scripting host itself already trusts.
 *
 * ## DoS guards (size cap + concurrency ceiling)
 *
 * Unlike the DSL-evaluation path ([dev.kuml.core.script.ChildProcessScriptEvaluator],
 * [dev.kuml.core.script.WorkerPool]), which validates every submitted script
 * against [KumlScriptGuard.MAX_SCRIPT_LENGTH] *before*
 * the Kotlin compiler is ever invoked, this worker previously had no size cap
 * of its own: an MCP client could submit an arbitrarily large `script` string
 * to `kuml.validate` and unconditionally trigger a full child-JVM
 * Kotlin-compiler-frontend PSI parse of the whole payload, up to
 * [DEFAULT_TIMEOUT_SECONDS]. [check] now reuses
 * [KumlScriptGuard.MAX_SCRIPT_LENGTH] as the same
 * upfront cap, applied before the temp file is written or the worker process
 * is started.
 *
 * A second, independent guard bounds *concurrent* worker launches: without
 * it, a burst of parallel `kuml.validate` calls could spawn an unbounded
 * number of ~[DEFAULT_MAX_HEAP_MB]MB-heap style-worker JVMs on top of
 * whatever [dev.kuml.core.script.WorkerPool] is already running for DSL
 * evaluation — the same fork-bomb concern [dev.kuml.core.script.WorkerPool]
 * already guards against on the evaluation path, just unaddressed on this
 * one. [MAX_CONCURRENT_WORKERS] mirrors that pool's `maxConcurrentWorkers`
 * ceiling: [check] fails fast with [StyleCheckResult.Unavailable] (never
 * blocks indefinitely) once the ceiling is reached.
 */
public object NamedArgumentStyleCheck {
    private val json = Json { ignoreUnknownKeys = true }

    private const val STYLE_WORKER_MAIN_CLASS = "dev.kuml.style.worker.StyleWorkerMain"
    private const val DEFAULT_TIMEOUT_SECONDS = 10L
    private const val DEFAULT_MAX_HEAP_MB = 1024
    private const val FORCE_KILL_GRACE_SECONDS = 5L
    private const val READER_JOIN_MILLIS = 2_000L
    private const val MAX_STDERR_CAPTURE = 16 * 1024

    /**
     * Environment variable that overrides [MAX_CONCURRENT_WORKERS] (mirrors
     * `dev.kuml.core.script.WorkerPool.ENV_MAX_WORKERS`'s naming convention).
     */
    public const val ENV_MAX_CONCURRENT_WORKERS: String = "KUML_STYLE_WORKER_MAX_CONCURRENT"
    private const val DEFAULT_MAX_CONCURRENT_WORKERS = 4

    /** Hard ceiling on concurrently-live style-worker child JVMs — see class KDoc. */
    private val MAX_CONCURRENT_WORKERS: Int =
        (System.getenv(ENV_MAX_CONCURRENT_WORKERS)?.trim()?.toIntOrNull() ?: DEFAULT_MAX_CONCURRENT_WORKERS)
            .coerceIn(1, 16)

    /** How long [check] waits for a concurrency-gate permit before failing fast. */
    private const val CONCURRENCY_ACQUIRE_TIMEOUT_SECONDS = 5L

    /** Fair so callers queue in arrival order rather than starving under sustained load. */
    private val concurrencyGate = Semaphore(MAX_CONCURRENT_WORKERS, true)

    /**
     * Runs the check against [source] (the full text of a `*.kuml.kts`
     * script; [fileName] is used only to name the temp file handed to the
     * worker). Blocking — callers that want it to overlap script evaluation
     * should launch it on their own background thread (see `ValidateCommand`).
     *
     * Rejected upfront, before any temp file is written or worker process
     * started, in two cases (see class KDoc "DoS guards"):
     *  - [source] exceeds [KumlScriptGuard.MAX_SCRIPT_LENGTH].
     *  - [MAX_CONCURRENT_WORKERS] style-worker child JVMs are already in
     *    flight and none frees up within [CONCURRENCY_ACQUIRE_TIMEOUT_SECONDS].
     *
     * Never throws: any failure to locate the worker library, launch the
     * child process, or parse its response degrades to
     * [StyleCheckResult.Unavailable].
     */
    public fun check(
        source: String,
        fileName: String,
    ): StyleCheckResult {
        if (source.length > KumlScriptGuard.MAX_SCRIPT_LENGTH) {
            return StyleCheckResult.Unavailable(
                "Style check skipped: source exceeds the maximum length of " +
                    "${KumlScriptGuard.MAX_SCRIPT_LENGTH} characters " +
                    "(${source.length} characters submitted).",
            )
        }

        if (!concurrencyGate.tryAcquire(CONCURRENCY_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return StyleCheckResult.Unavailable(
                "Style worker concurrency ceiling ($MAX_CONCURRENT_WORKERS) reached; " +
                    "too many concurrent style checks in flight. Retry shortly.",
            )
        }
        try {
            val libDir =
                StyleWorkerLibLocator.resolve()
                    ?: return StyleCheckResult.Unavailable(
                        "Style worker library not found (lib/style/ missing from this kUML installation).",
                    )

            val tempFile =
                try {
                    Files.createTempFile("kuml-style-", "-${sanitizeForFileName(fileName)}")
                } catch (e: Exception) {
                    return StyleCheckResult.Unavailable("Could not create temp file for style check: ${e::class.simpleName}")
                }
            return try {
                tempFile.toFile().writeText(source, Charsets.UTF_8)
                runWorker(libDir = libDir, sourcePath = tempFile.toFile().absolutePath)
            } finally {
                runCatching { Files.deleteIfExists(tempFile) }
            }
        } finally {
            concurrencyGate.release()
        }
    }

    private fun runWorker(
        libDir: File,
        sourcePath: String,
    ): StyleCheckResult {
        val classpath = "${libDir.absolutePath}${File.separator}*"
        val binaryRoots = SandboxClasspath.curatedEntries().map { it.absolutePath }

        val command =
            listOf(
                WorkerProcessSupport.defaultJavaBinary(),
                "-Xmx${DEFAULT_MAX_HEAP_MB}m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-Djava.awt.headless=true",
                "-cp",
                classpath,
                STYLE_WORKER_MAIN_CLASS,
            )

        val builder = ProcessBuilder(command)
        // Minimal environment — the worker never needs the parent's env
        // (secrets included), only PATH so the JVM itself can boot.
        builder.environment().clear()
        System.getenv("PATH")?.let { builder.environment()["PATH"] = it }
        builder.redirectErrorStream(false)

        val process =
            try {
                builder.start()
            } catch (e: Exception) {
                return StyleCheckResult.Unavailable("Could not start style worker process: ${e::class.simpleName}")
            }

        val stderrBuf = StringBuilder()
        val stderrDrainer =
            thread(isDaemon = true, name = "kuml-style-worker-stderr") {
                try {
                    process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (stderrBuf.length < MAX_STDERR_CAPTURE) stderrBuf.append(line).append('\n')
                    }
                } catch (_: Exception) {
                    // Child died / was killed — nothing to drain.
                }
            }

        return try {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val request = StyleWorkerRequest(sourcePath = sourcePath, binaryRoots = binaryRoots)
                    writer.write(json.encodeToString(StyleWorkerRequest.serializer(), request))
                    writer.write("\n")
                }
            } catch (_: Exception) {
                // Child died before consuming input — handled by the wait below.
            }

            var responseLine: String? = null
            val readerThread =
                thread(isDaemon = true, name = "kuml-style-worker-reader") {
                    responseLine = WorkerProcessSupport.readBoundedLine(process.inputStream.bufferedReader(Charsets.UTF_8))
                }

            val finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(FORCE_KILL_GRACE_SECONDS, TimeUnit.SECONDS)
                return StyleCheckResult.Unavailable("Style worker timed out after ${DEFAULT_TIMEOUT_SECONDS}s and was terminated.")
            }

            readerThread.join(READER_JOIN_MILLIS)

            val line =
                responseLine
                    ?: return StyleCheckResult.Unavailable("Style worker produced no response (exit=${process.exitValue()}).")

            parseResponse(line)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stderrDrainer.join(READER_JOIN_MILLIS)
        }
    }

    private fun parseResponse(line: String): StyleCheckResult {
        if (line.length >= WorkerProcessSupport.MAX_RESPONSE_LENGTH) {
            return StyleCheckResult.Unavailable("Style worker response exceeded the size limit.")
        }
        val response =
            try {
                json.decodeFromString(StyleWorkerResponse.serializer(), line)
            } catch (e: Exception) {
                return StyleCheckResult.Unavailable("Style worker returned an unparseable response: ${e::class.simpleName}")
            }
        if (!response.ok) {
            return StyleCheckResult.Unavailable(response.error ?: "Style worker reported an unspecified failure.")
        }
        val findings =
            response.findings.map { f ->
                StyleFinding(
                    id = "POSITIONAL_ARGUMENT",
                    severity = "error",
                    message =
                        "Argument for '${f.paramName}' of '${f.calleeFqName}' is passed positionally; " +
                            "use a named argument (CLAUDE.md: Named Parameters — PFLICHT).",
                    line = f.line,
                    column = f.column,
                )
            }
        return StyleCheckResult.Ok(findings)
    }

    private fun sanitizeForFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9.]"), "_")
}
