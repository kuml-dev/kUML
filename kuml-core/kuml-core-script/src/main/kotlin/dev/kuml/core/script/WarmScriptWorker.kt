package dev.kuml.core.script

import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A single **pre-started, use-once** script-evaluation worker process for the
 * [WorkerPool] (Welle 3).
 *
 * Lifecycle (state in [state]):
 *
 * ```
 *  STARTING ──ready line──▶ IDLE ──assigned──▶ BUSY ──response──▶ CONSUMED
 *      │                      │                  │
 *      └────── start/warm ────┴──── crash ───────┴────────────▶ DEAD
 * ```
 *
 * On construction the process is launched in **warm mode** ([ScriptWorkerMain.ARG_WARM]):
 * it boots a JVM, fully initialises the Kotlin scripting host, then writes
 * [ScriptWorkerMain.READY_SENTINEL] and blocks on stdin. A background reader
 * thread waits for that ready line and flips the worker to [State.IDLE] — the
 * parent never *guesses* readiness from a fixed sleep. If the process dies before
 * the ready line, the worker becomes [State.DEAD] and the pool will not assign it.
 *
 * When the pool assigns the worker it calls [evaluate] exactly once: the request
 * is written to stdin, the single response line is read (bounded), the process is
 * awaited under a wall-clock timeout, and the worker ends up [State.CONSUMED] (or
 * [State.DEAD] on timeout/crash). A worker process serves **one** request and
 * exits — no state leaks between scripts ("use-once + recycle").
 *
 * All the process-launch hardening (fixed argv, minimal environment, heap cap,
 * bounded reads) is shared with the cold-start path via [WorkerProcessSupport].
 *
 * V0.23.3 — Welle 3.
 */
internal class WarmScriptWorker(
    private val timeoutSeconds: Long,
    private val maxHeapMb: Int,
    private val javaBinary: String,
    private val classpath: String,
) {
    internal enum class State { STARTING, IDLE, BUSY, CONSUMED, DEAD }

    private val json = Json { ignoreUnknownKeys = true }
    private val stateRef = AtomicReference(State.STARTING)

    /** Current lifecycle state. */
    val state: State get() = stateRef.get()

    val isIdle: Boolean get() = state == State.IDLE
    val isDead: Boolean get() = state == State.DEAD

    private val process: Process = WorkerProcessSupport.launch(javaBinary, classpath, maxHeapMb, warm = true)
    private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
    private val readyLatch = CountDownLatch(1)
    private val stderrBuf = StringBuilder()

    init {
        // Drain stderr so a chatty child can never fill the pipe buffer and
        // deadlock. Bounded for diagnostics.
        thread(isDaemon = true, name = "kuml-warm-worker-stderr") {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderrBuf) {
                        if (stderrBuf.length < MAX_STDERR_CAPTURE) stderrBuf.append(line).append('\n')
                    }
                }
            } catch (_: Exception) {
                // Child closed the stream / was killed — nothing to drain.
            }
        }

        // Wait for the ready line on a daemon thread. When it arrives, flip to
        // IDLE; on EOF / crash before it, flip to DEAD.
        thread(isDaemon = true, name = "kuml-warm-worker-ready") {
            val readyLine =
                try {
                    WorkerProcessSupport.readBoundedLine(reader)
                } catch (_: Exception) {
                    null
                }
            if (readyLine == ScriptWorkerMain.READY_SENTINEL) {
                // Only STARTING → IDLE; if we were already killed, stay dead.
                stateRef.compareAndSet(State.STARTING, State.IDLE)
            } else {
                markDead()
            }
            readyLatch.countDown()
        }
    }

    /**
     * Blocks up to [millis] for this worker to become [State.IDLE] (or die).
     * Returns true if it is idle and assignable.
     */
    fun awaitReady(millis: Long): Boolean =
        try {
            readyLatch.await(millis, TimeUnit.MILLISECONDS)
            state == State.IDLE
        } catch (_: InterruptedException) {
            // Pool is shutting down and interrupted the refiller thread. Treat as
            // "not ready" and restore the interrupt flag so the caller can stop.
            Thread.currentThread().interrupt()
            false
        }

    /**
     * Atomically claims this worker for a request. Returns true only if it was
     * IDLE and is now BUSY — so at most one caller can ever evaluate on a given
     * worker.
     */
    fun tryClaim(): Boolean = stateRef.compareAndSet(State.IDLE, State.BUSY)

    /**
     * Sends [source] to the claimed worker, reads its single response, and
     * returns the result. Must be called exactly once, after a successful
     * [tryClaim]. The worker is [State.CONSUMED] (or [State.DEAD]) afterwards.
     */
    fun evaluate(
        source: String,
        fileName: String,
    ): EvaluatedScript {
        check(state == State.BUSY) { "WarmScriptWorker.evaluate called without a successful tryClaim()" }
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

            var responseLine: String? = null
            val readerThread =
                thread(isDaemon = true, name = "kuml-warm-worker-reader") {
                    responseLine = WorkerProcessSupport.readBoundedLine(reader)
                }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                destroy()
                return EvaluatedScript.Failure(
                    FailureKind.TIMEOUT,
                    "Script evaluation timed out after ${timeoutSeconds}s and was terminated.",
                )
            }
            readerThread.join(READER_JOIN_MILLIS)

            val line =
                responseLine ?: return EvaluatedScript.Failure(
                    FailureKind.SANDBOX,
                    "Script sandbox worker produced no response (exit=${process.exitValue()}).",
                )
            parseResponse(line, process.exitValue())
        } finally {
            // The worker has served its one request; it must not be reused.
            if (process.isAlive) process.destroyForcibly()
            stateRef.set(if (state == State.DEAD) State.DEAD else State.CONSUMED)
        }
    }

    private fun parseResponse(
        line: String,
        exitValue: Int,
    ): EvaluatedScript {
        if (line.length >= WorkerProcessSupport.MAX_RESPONSE_LENGTH) {
            return EvaluatedScript.Failure(
                FailureKind.SANDBOX,
                "Script sandbox worker response exceeded the ${WorkerProcessSupport.MAX_RESPONSE_LENGTH}-char limit.",
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
            response.envelope ?: return EvaluatedScript.Failure(
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

    /** Forcibly terminates the worker process and marks it DEAD. Idempotent. */
    fun destroy() {
        markDead()
        if (process.isAlive) process.destroyForcibly()
    }

    private fun markDead() {
        stateRef.set(State.DEAD)
    }

    /** True while the underlying OS process is still running (test/diagnostic use). */
    fun isProcessAlive(): Boolean = process.isAlive

    /** The OS pid of the worker process (test/diagnostic use). */
    fun pid(): Long = process.pid()

    internal companion object {
        private const val READER_JOIN_MILLIS: Long = 2_000
        private const val MAX_STDERR_CAPTURE: Int = 16 * 1024
    }
}
