package dev.kuml.core.script

import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [ScriptEvaluator] that evaluates each script in a **fresh, short-lived child
 * JVM** ([ScriptWorkerMain]) with a wall-clock timeout and a heap cap.
 *
 * This is the sandbox layer introduced in Welle 2 of the MCP-Sandbox
 * architecture. It does **not** yet apply OS-native isolation
 * (`sandbox-exec` / `bwrap` / Job Objects — those are later, separate waves).
 * What it *does* provide today, over the pure in-process path:
 *
 *  - **DoS containment via timeout**: an infinite loop (`while(true){}`) that
 *    the regex guard cannot catch is killed after [timeoutSeconds] by
 *    [Process.destroyForcibly]. The parent stays responsive.
 *  - **OOM containment via `-Xmx`**: a memory-bomb script blows the *child's*
 *    capped heap ([maxHeapMb]) and dies there; the server's heap is untouched.
 *  - **Blast-radius reduction**: even a full RCE runs in a separate process
 *    with a **minimal environment** (secrets in the parent's env are not
 *    inherited), a stepping stone toward the OS-cage waves.
 *
 * ## No Warm-Pool yet (by design, Welle 2 scope)
 *
 * Each call starts a brand-new JVM and discards it — so there is a real
 * cold-start cost (JVM boot + Kotlin-compiler warm-up). Measuring that cost is
 * an explicit deliverable of this wave; a warm-worker pool is the *next* wave.
 *
 * ## Fail-closed
 *
 * If the child cannot be started or its response cannot be parsed, this
 * evaluator returns an [EvaluatedScript.Failure] of kind [FailureKind.SANDBOX]
 * — it does **not** silently fall back to in-process evaluation. Falling back
 * would mean "when the sandbox breaks, run untrusted code unsandboxed", which
 * defeats the purpose. The choice of evaluator (and thus whether an
 * in-process fallback is acceptable) is made once, up front, by
 * [ScriptEvaluators.forCurrentConfig] based on host capability — not silently
 * per request.
 *
 * V0.23.3 — Welle 2.
 */
internal class ChildProcessScriptEvaluator(
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val maxHeapMb: Int = DEFAULT_MAX_HEAP_MB,
    /** Overridable for tests; defaults to the running JVM's `java` binary. */
    private val javaBinary: String = defaultJavaBinary(),
    /** Overridable for tests; defaults to the running JVM's classpath. */
    private val classpath: String = System.getProperty("java.class.path") ?: "",
) : ScriptEvaluator {
    private val json = Json { ignoreUnknownKeys = true }

    override fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript {
        // Layer 1 also runs in the parent, before we spend a JVM launch on an
        // obviously-hostile script. The child re-runs it too (defence in depth),
        // but this short-circuits the cheap cases without a process start.
        try {
            KumlScriptGuard.validate(source)
        } catch (e: ScriptSecurityException) {
            return EvaluatedScript.Failure(
                FailureKind.GUARD,
                e.message ?: "kUML script rejected by security guard.",
            )
        }

        val launched =
            try {
                // Shared launch path: fixed argv, minimal env, heap cap, AND the
                // Welle-4 OS-native cage (sandbox-exec on macOS). If OS isolation
                // is required but unavailable this throws SandboxUnavailableException,
                // which — like any launch failure — is a fail-closed SANDBOX error,
                // never a fall-through to an un-caged child.
                WorkerProcessSupport.launch(javaBinary, classpath, maxHeapMb, warm = false)
            } catch (e: SandboxUnavailableException) {
                return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Script sandbox OS isolation unavailable: ${e.message}",
                )
            } catch (e: Exception) {
                return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Could not start script sandbox worker: ${e::class.simpleName}",
                )
            }
        val process = launched.process

        // Drain stderr on a daemon thread so a chatty child can never fill the
        // stderr pipe buffer and deadlock (classic Process pitfall). We bound
        // what we keep for diagnostics.
        val stderrBuf = StringBuilder()
        val stderrDrainer =
            thread(isDaemon = true, name = "kuml-worker-stderr") {
                try {
                    process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (stderrBuf.length < MAX_STDERR_CAPTURE) {
                            stderrBuf.append(line).append('\n')
                        }
                    }
                } catch (_: Exception) {
                    // Child closed the stream / was killed — nothing to drain.
                }
            }

        return try {
            // Write the request, then close stdin so the child's readLine() returns.
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(json.encodeToString(WorkerRequest.serializer(), WorkerRequest(source, fileName)))
                    w.write("\n")
                }
            } catch (_: Exception) {
                // Child died before consuming input — handled by the wait below.
            }

            // Read the single response line on a daemon thread so a child that
            // writes nothing (or blocks) cannot hang us past the timeout. The
            // read is BOUNDED to MAX_RESPONSE_LENGTH chars: a hostile child that
            // writes a huge blob with no newline must never OOM the *parent*
            // (which is the whole thing we are protecting).
            var responseLine: String? = null
            val readerThread =
                thread(isDaemon = true, name = "kuml-worker-reader") {
                    responseLine = readBoundedLine(process.inputStream.bufferedReader(Charsets.UTF_8))
                }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(FORCE_KILL_GRACE_SECONDS, TimeUnit.SECONDS)
                return EvaluatedScript.Failure(
                    FailureKind.TIMEOUT,
                    "Script evaluation timed out after ${timeoutSeconds}s and was terminated.",
                )
            }

            // Process exited; give the reader a brief moment to hand over the line.
            readerThread.join(READER_JOIN_MILLIS)

            val line =
                responseLine
                    ?: return EvaluatedScript.Failure(
                        FailureKind.SANDBOX,
                        "Script sandbox worker produced no response (exit=${process.exitValue()}).",
                    )

            parseResponse(line, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stderrDrainer.join(READER_JOIN_MILLIS)
            launched.cleanup()
        }
    }

    /**
     * Reads up to [MAX_RESPONSE_LENGTH] characters, stopping at the first
     * newline. Returns null on EOF-before-any-data, `""` for an empty first
     * line, or the (bounded) content otherwise. Never buffers unboundedly.
     */
    private fun readBoundedLine(reader: java.io.BufferedReader): String? =
        try {
            val sb = StringBuilder()
            var sawAny = false
            while (sb.length < MAX_RESPONSE_LENGTH) {
                val c = reader.read()
                if (c < 0) break // EOF
                sawAny = true
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            if (!sawAny) null else sb.toString()
        } catch (_: Exception) {
            null
        }

    private fun parseResponse(
        line: String,
        exitValue: Int,
    ): EvaluatedScript {
        // Guard against a hostile/garbled response that is not JSON or decodes
        // to nonsense. Any of these is a SANDBOX failure — never crash the
        // parent, never treat garbage as a valid diagram. (Size is already
        // bounded by readBoundedLine; this is a belt-and-braces check.)
        if (line.length >= MAX_RESPONSE_LENGTH) {
            return EvaluatedScript.Failure(
                FailureKind.SANDBOX,
                "Script sandbox worker response exceeded the $MAX_RESPONSE_LENGTH-char limit.",
            )
        }
        val response =
            try {
                json.decodeFromString(WorkerResponse.serializer(), line)
            } catch (e: Exception) {
                return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Script sandbox worker returned an unparseable response (${e::class.simpleName}, exit=$exitValue).",
                )
            }

        if (!response.ok) {
            val kind =
                response.failureKind
                    ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
                    ?: FailureKind.EVALUATION
            return EvaluatedScript.Failure(kind, response.message ?: "Script evaluation failed.")
        }

        val envelope =
            response.envelope
                ?: return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Script sandbox worker reported success but returned no diagram.",
                )
        return try {
            EvaluatedScript.Success(ExtractedDiagramCodec.decode(envelope))
        } catch (e: Exception) {
            EvaluatedScript.Failure(
                FailureKind.SANDBOX,
                "Script sandbox worker returned an undecodable diagram (${e::class.simpleName}).",
            )
        }
    }

    internal companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 15
        const val DEFAULT_MAX_HEAP_MB: Int = 256
        private const val FORCE_KILL_GRACE_SECONDS: Long = 5
        private const val READER_JOIN_MILLIS: Long = 2_000
        private const val MAX_STDERR_CAPTURE: Int = 16 * 1024
        private const val MAX_RESPONSE_LENGTH: Int = 32 * 1024 * 1024 // 32 MiB — large models allowed, gibberish not

        /** Absolute path to the `java` binary of the *currently running* JVM. */
        internal fun defaultJavaBinary(): String = WorkerProcessSupport.defaultJavaBinary()
    }
}
