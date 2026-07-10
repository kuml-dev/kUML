package dev.kuml.jetbrains

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import dev.kuml.langsupport.diagnostics.KumlDiagnosticTsvParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs `kuml diagnostics` via the external CLI and parses its TSV output into
 * [KumlDiagnostic]s for the editor annotator.
 *
 * Mirrors [KumlCliRenderer]'s subprocess approach: the in-process Kotlin
 * scripting host is unreachable from the plugin classloader, so validation —
 * like rendering — is delegated to the CLI.
 *
 * Degrades gracefully: if the CLI cannot be located or the call fails, an empty
 * list is returned (no squiggles) rather than surfacing spurious errors. The
 * missing-CLI condition is already reported prominently by the preview panel.
 */
internal object KumlCliDiagnostics {
    private const val TIMEOUT_SECONDS = 30L

    fun analyze(
        scriptText: String,
        scriptName: String,
    ): List<KumlDiagnostic> {
        val hintDir = File(scriptName).absoluteFile.parentFile
        val binary = KumlCliLocator.resolve(hintDir) ?: return emptyList()

        val tmpDir =
            try {
                File.createTempFile("kuml-diag", "").let { f ->
                    f.delete()
                    f.mkdirs()
                    f
                }
            } catch (_: Throwable) {
                return emptyList()
            }
        val base = File(scriptName).name.removeSuffix(".kuml.kts").removeSuffix(".kts")
        val scriptFile = File(tmpDir, "${base.ifBlank { "buffer" }}.kuml.kts")

        return try {
            scriptFile.writeText(scriptText)
            val proc =
                ProcessBuilder(binary.absolutePath, "diagnostics", scriptFile.absolutePath)
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
            // Drain stderr too so a full pipe never blocks the process.
            Thread {
                proc.errorStream.bufferedReader().forEachLine { /* discard JVM warnings */ }
            }.apply {
                isDaemon = true
                start()
            }

            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
