package dev.kuml.style.worker

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the **style-check child JVM**.
 *
 * Launched by `dev.kuml.core.script.style.NamedArgumentStyleCheck`
 * (`:kuml-core:kuml-core-script`) as `java -cp <lib/style dir, wildcarded>
 * dev.kuml.style.worker.StyleWorkerMain`. Reads one
 * [StyleWorkerRequest] JSON line from stdin, runs the named-argument style
 * check against the referenced source file, writes one [StyleWorkerResponse]
 * JSON line to stdout, and **always calls [exitProcess]** — never returns
 * from `main` normally.
 *
 * ## Why `exitProcess` is mandatory, not a nicety
 *
 * The Kotlin Analysis API's standalone application environment
 * (`ApplicationImpl`) starts a non-daemon `ApplicationImpl pooled thread`
 * that survives even `Disposer.dispose()` on the session's root disposable —
 * confirmed empirically (see the plan's prototype notes). Without an explicit
 * `exitProcess`, this worker JVM would simply never terminate, and every
 * `kuml validate` / `kuml.validate` call would leak a hung child process.
 * [exitProcess] is a hard OS-level `System.exit`, which kills that thread
 * along with everything else — this is *why* the style check runs in a
 * disposable child process instead of an in-process isolated classloader in
 * the first place (an isolated classloader has the identical leak, but
 * cannot `exitProcess()` the whole host JVM to clean it up).
 *
 * ## Robustness
 *
 * Any failure — missing/garbled stdin, an unreadable source file, an
 * Analysis-API exception — becomes an `ok = false` response with a sanitised
 * message; this worker never throws past `main`. Script `println`/logging
 * (there should be none, since the Analysis API never *executes* the script)
 * is not redirected here the way [dev.kuml.core.script.ScriptWorkerMain]
 * redirects `System.out` — this worker never evaluates untrusted code, only
 * statically resolves symbols, so there is no risk of the analyzed script
 * itself writing to stdout and corrupting the response line.
 */
public object StyleWorkerMain {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    public fun main(args: Array<String>) {
        val response =
            try {
                val requestLine =
                    System.`in`.bufferedReader(Charsets.UTF_8).readLine()
                        ?: return respondAndExit(failure("No request received on stdin"))
                val request = json.decodeFromString(StyleWorkerRequest.serializer(), requestLine)
                handle(request)
            } catch (e: Throwable) {
                failure("Style worker request handling failed: ${e::class.simpleName}: ${e.message}")
            }
        respondAndExit(response)
    }

    private fun handle(request: StyleWorkerRequest): StyleWorkerResponse {
        val sourceFile = File(request.sourcePath)
        val source =
            try {
                sourceFile.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                return failure("Could not read source file: ${e::class.simpleName}")
            }

        val binaryRoots: List<Path> = request.binaryRoots.map { File(it).toPath() }

        return try {
            val wrapped = wrapKumlScript(source)
            val findings = KumlAnalysisSession.analyze(wrapped = wrapped, binaryRoots = binaryRoots)
            val mapped =
                findings.map { finding ->
                    val (line, column) =
                        mapToOriginalLineColumn(
                            originalSource = source,
                            offsetInWrappedSource = finding.offsetInWrappedSource,
                            prefixLen = wrapped.prefixLen,
                        )
                    StyleWorkerFinding(
                        line = line,
                        column = column,
                        paramName = finding.paramName,
                        calleeFqName = finding.calleeFqName,
                    )
                }
            StyleWorkerResponse(ok = true, findings = mapped)
        } catch (e: Throwable) {
            // Any Analysis-API failure (malformed session, unexpected PSI
            // shape, ...) degrades to a reported-but-non-fatal Unavailable
            // outcome on the launcher side — never crash the parent's
            // `kuml validate` invocation over a style-check internal error.
            failure("Analysis failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun failure(message: String): StyleWorkerResponse = StyleWorkerResponse(ok = false, error = message)

    private fun respondAndExit(response: StyleWorkerResponse): Nothing {
        val out = System.out
        out.print(json.encodeToString(StyleWorkerResponse.serializer(), response))
        out.print('\n')
        out.flush()
        exitProcess(0)
    }
}
