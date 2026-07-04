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
 * ## Robustness / trust boundary
 *
 * This child runs **untrusted** script code (that is its whole purpose). It
 * therefore treats *its own* success/failure conservatively:
 *
 *  - It reads exactly one request line. Missing/garbled input → a SANDBOX
 *    failure response, then exit.
 *  - It never prints anything else to stdout — the single response line is the
 *    entire protocol. (Script `println`/logging goes to **stderr**, which the
 *    parent drains separately so it can never corrupt the response line.)
 *  - All evaluation goes through [ScriptEvaluationCore], so guard + eval +
 *    extract + message sanitisation are identical to the in-process path.
 *
 * A wall-clock timeout and heap cap are enforced by the **parent** (via
 * `-Xmx` and `Process.destroyForcibly()`), not here — a runaway script cannot
 * be trusted to honour an in-JVM deadline.
 *
 * V0.23.3 — Welle 2.
 */
public object ScriptWorkerMain {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    public fun main(args: Array<String>) {
        // Redirect any stray stdout writes from deep inside the script or the
        // Kotlin compiler to stderr, so the *only* thing on our stdout is the
        // single response line. System.out is captured before we install the
        // redirect so we can still write the response to the real stdout.
        val realStdout = System.out
        val stderrStream = System.err
        System.setOut(stderrStream)

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
