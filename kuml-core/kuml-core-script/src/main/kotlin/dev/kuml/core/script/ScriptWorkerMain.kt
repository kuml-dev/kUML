package dev.kuml.core.script

import kotlinx.serialization.json.Json

/**
 * Entry point of a **script-evaluation child JVM**.
 *
 * The parent ([ChildProcessScriptEvaluator]) launches
 * `java -cp <same classpath> dev.kuml.core.script.ScriptWorkerMain`, writes a
 * single [WorkerRequest] JSON line to this process's stdin, and reads a single
 * [WorkerResponse] JSON line from this process's stdout. The child then exits.
 *
 * ## Two launch modes
 *
 *  - **Cold (Welle 2, default, no args):** boot the JVM, read exactly one
 *    request line, evaluate, write one response line, exit. The Kotlin
 *    scripting host is warmed up *lazily* on that first (and only) evaluation —
 *    which is where the ~1.5 s cold-start cost lives.
 *  - **Warm (Welle 3, arg [ARG_WARM]):** boot the JVM, **pre-warm the Kotlin
 *    scripting host** by evaluating a trivial throwaway script (so the embedded
 *    compiler is fully initialised), then emit a single [READY_SENTINEL] line on
 *    stdout to actively signal readiness to the parent, and only *then* block on
 *    stdin for its one real request. Because the expensive warm-up already
 *    happened before the parent hands over a request, the request is served
 *    almost as fast as in-process. The worker still handles exactly **one**
 *    request and exits ("use-once + recycle") — no state leaks between scripts.
 *
 * The parent decides which signal it is reading: in warm mode the first stdout
 * line is [READY_SENTINEL] (produced before any request is consumed); the
 * second line is the [WorkerResponse]. In cold mode there is no ready line.
 *
 * ## Robustness / trust boundary
 *
 * This child runs **untrusted** script code (that is its whole purpose). It
 * therefore treats *its own* success/failure conservatively:
 *
 *  - It reads exactly one request line. Missing/garbled input → a SANDBOX
 *    failure response, then exit.
 *  - It never prints anything else to stdout — the ready line (warm mode only)
 *    and the single response line are the entire protocol. (Script
 *    `println`/logging goes to **stderr**, which the parent drains separately so
 *    it can never corrupt the response line.)
 *  - The pre-warm evaluation runs a **fixed, trusted** trivial script — never
 *    anything from the untrusted request. It only forces compiler
 *    initialisation; its result is discarded.
 *  - All evaluation goes through [ScriptEvaluationCore], so guard + eval +
 *    extract + message sanitisation are identical to the in-process path.
 *
 * A wall-clock timeout and heap cap are enforced by the **parent** (via
 * `-Xmx` and `Process.destroyForcibly()`), not here — a runaway script cannot
 * be trusted to honour an in-JVM deadline.
 *
 * V0.23.3 — Welle 2 (cold) + Welle 3 (warm).
 */
public object ScriptWorkerMain {
    private val json = Json { ignoreUnknownKeys = true }

    /** Argument that switches the worker into warm (pre-warmed, ready-signalling) mode. */
    public const val ARG_WARM: String = "--warm"

    /**
     * Line the warm worker writes to stdout *after* the scripting host is fully
     * initialised and *before* it blocks on stdin. The parent treats a worker as
     * "idle / assignable" only once it has seen exactly this line.
     */
    public const val READY_SENTINEL: String = "KUML-WORKER-READY"

    @JvmStatic
    public fun main(args: Array<String>) {
        // Redirect any stray stdout writes from deep inside the script or the
        // Kotlin compiler to stderr, so the *only* thing on our stdout is the
        // ready line (warm mode) and the single response line. System.out is
        // captured before we install the redirect so we can still write those.
        val realStdout = System.out
        val stderrStream = System.err
        System.setOut(stderrStream)

        val warm = args.any { it == ARG_WARM }
        if (warm) {
            // Pre-warm the embedded Kotlin compiler with a fixed trivial script,
            // so the request served after READY pays no compiler-init cost. Any
            // failure here is non-fatal — we still signal ready and let the real
            // request run (it will just pay the lazy warm-up like a cold worker).
            try {
                ScriptEvaluationCore.evaluateAndExtract(WARMUP_SCRIPT, "warmup.kuml.kts")
            } catch (_: Throwable) {
                // Ignore: worst case is a cold-start-like latency on the real call.
            }
            realStdout.print(READY_SENTINEL)
            realStdout.print('\n')
            realStdout.flush()
        }

        val response =
            try {
                val requestLine =
                    System.`in`.bufferedReader(Charsets.UTF_8).readLine()
                        ?: return respond(realStdout, sandboxFailure("No request received on stdin"))
                val request = json.decodeFromString(WorkerRequest.serializer(), requestLine)
                evaluate(request)
            } catch (e: Throwable) {
                // Any framing/decoding error is a sandbox-level failure. Do not
                // leak the exception's toString (may contain internals) beyond
                // the class name.
                sandboxFailure("Worker request handling failed: ${e::class.simpleName}")
            }

        respond(realStdout, response)
    }

    /**
     * A minimal, fixed, **trusted** script used only to force the Kotlin
     * scripting host to compile+evaluate once, initialising the embedded
     * compiler. Its result is discarded. It never contains untrusted input.
     */
    private const val WARMUP_SCRIPT: String =
        """diagram(name = "warmup", type = DiagramType.CLASS) {}"""

    private fun evaluate(request: WorkerRequest): WorkerResponse =
        when (val result = ScriptEvaluationCore.evaluateAndExtract(request.source, request.fileName)) {
            is EvaluatedScript.Success ->
                WorkerResponse(ok = true, envelope = ExtractedDiagramCodec.encode(result.diagram))
            is EvaluatedScript.Failure ->
                WorkerResponse(ok = false, failureKind = result.kind.name, message = result.message)
        }

    private fun sandboxFailure(message: String): WorkerResponse =
        WorkerResponse(ok = false, failureKind = FailureKind.SANDBOX.name, message = message)

    private fun respond(
        out: java.io.PrintStream,
        response: WorkerResponse,
    ) {
        out.print(json.encodeToString(WorkerResponse.serializer(), response))
        out.print('\n')
        out.flush()
    }
}
