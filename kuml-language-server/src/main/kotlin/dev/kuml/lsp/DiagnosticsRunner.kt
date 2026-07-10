package dev.kuml.lsp

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import dev.kuml.langsupport.diagnostics.KumlDiagnosticTsvParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs `<cliPath> diagnostics <file>` as a subprocess and parses its TSV output
 * into [KumlDiagnostic]s. Mirrors the JetBrains plugin's `KumlCliDiagnostics`
 * subprocess pattern: the in-process Kotlin scripting host is not reliably
 * reachable from an embedding classloader / mismatched Kotlin runtime, so
 * validation is delegated to the external CLI, which carries its own
 * consistent classpath.
 *
 * Pure subprocess primitive — CLI resolution, caching and missing-CLI UX all
 * live in [KumlTextDocumentService]; this object only knows how to run one
 * already-resolved binary against one already-read document text.
 *
 * Never throws: any failure (I/O error, non-existent/non-executable
 * `cliPath`, timeout) degrades to an empty diagnostic list rather than
 * surfacing spurious errors to the editor.
 */
object DiagnosticsRunner {
    /**
     * Writes [text] to a fresh temp `buffer.kuml.kts`, runs
     * `<cliPath> diagnostics <file>`, drains stdout/stderr on daemon threads so
     * neither pipe can fill and deadlock the subprocess, enforces [timeoutMs]
     * (destroying the process forcibly on expiry), and parses the resulting
     * TSV via [KumlDiagnosticTsvParser]. The CLI always exits 0 (see
     * `DiagnosticsCommand`) — exit code is never consulted.
     */
    fun run(
        text: String,
        cliPath: File,
        timeoutMs: Long,
    ): List<KumlDiagnostic> {
        val tmpDir =
            try {
                Files.createTempDirectory("kuml-lsp-diag").toFile()
            } catch (_: Throwable) {
                return emptyList()
            }
        // Fixed basename: the ".kuml.kts" extension is required by KumlScriptHost.
        // Line/col in the CLI output are relative to this file's content, which is
        // exactly the editor document's text, so the mapping stays correct.
        val scriptFile = File(tmpDir, "buffer.kuml.kts")

        return try {
            scriptFile.writeText(text)

            val proc =
                ProcessBuilder(cliPath.absolutePath, "diagnostics", scriptFile.absolutePath)
                    .redirectErrorStream(false)
                    .start()

            val stdout = StringBuilder()
            val drainer =
                Thread {
                    proc.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
                }.apply {
                    isDaemon = true
                    start()
                }
            // Drain stderr too so a full pipe never blocks the process (JVM
            // startup warnings and similar noise land here and are discarded).
            Thread {
                proc.errorStream.bufferedReader().forEachLine { /* discard */ }
            }.apply {
                isDaemon = true
                start()
            }

            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                return emptyList()
            }
            drainer.join(1000)

            KumlDiagnosticTsvParser.parse(stdout.toString())
        } catch (_: Throwable) {
            emptyList()
        } finally {
            scriptFile.delete()
            tmpDir.delete()
        }
    }
}
