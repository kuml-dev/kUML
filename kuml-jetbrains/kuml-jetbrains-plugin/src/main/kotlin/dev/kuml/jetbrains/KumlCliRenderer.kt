package dev.kuml.jetbrains

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders a `.kuml.kts` script to SVG by invoking the external `kuml` CLI.
 *
 * The current (possibly unsaved) editor text is written to a temporary
 * `*.kuml.kts` file and rendered with `kuml render … -o out.svg -f svg`. The
 * resulting SVG is read back and returned. Temp files are always cleaned up.
 */
internal object KumlCliRenderer {
    /** Hard cap on a single render invocation (CLI cold-start + render). */
    private const val TIMEOUT_SECONDS = 30L

    fun render(
        binary: File,
        scriptText: String,
        scriptBaseName: String,
    ): KumlPreviewRenderer.Outcome {
        val tmpDir =
            try {
                File.createTempFile("kuml-preview", "").let { f ->
                    f.delete()
                    f.mkdirs()
                    f
                }
            } catch (t: Throwable) {
                return KumlPreviewRenderer.Outcome.Failure("Temp-Verzeichnis konnte nicht angelegt werden: ${t.message}")
            }

        val base = sanitiseBase(scriptBaseName)
        val scriptFile = File(tmpDir, "$base.kuml.kts")
        val svgFile = File(tmpDir, "$base.svg")

        return try {
            scriptFile.writeText(scriptText)

            val proc =
                ProcessBuilder(
                    binary.absolutePath,
                    "render",
                    scriptFile.absolutePath,
                    "-o",
                    svgFile.absolutePath,
                    "-f",
                    "svg",
                    "--theme",
                    "plain",
                ).redirectErrorStream(false)
                    .start()

            // Drain stderr so the process never blocks on a full pipe buffer.
            val stderr = StringBuilder()
            val drainer =
                Thread {
                    proc.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
                }.apply {
                    isDaemon = true
                    start()
                }

            val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return KumlPreviewRenderer.Outcome.Failure("kuml render Timeout (> ${TIMEOUT_SECONDS}s)")
            }
            drainer.join(1000)

            val exit = proc.exitValue()
            when {
                exit == 0 && svgFile.isFile && svgFile.length() > 0L ->
                    KumlPreviewRenderer.Outcome.Svg(svgFile.readText())

                else -> {
                    val detail = stderr.toString().trim().ifBlank { "(keine Ausgabe)" }
                    KumlPreviewRenderer.Outcome.Failure(
                        "kuml render fehlgeschlagen (Exit $exit, Binary: ${binary.absolutePath}):\n$detail",
                    )
                }
            }
        } catch (t: Throwable) {
            KumlPreviewRenderer.Outcome.Failure("kuml render Aufruf-Fehler: ${t::class.java.name}: ${t.message}")
        } finally {
            scriptFile.delete()
            svgFile.delete()
            tmpDir.delete()
        }
    }

    /** Keep only filesystem-safe characters for the temp file base name. */
    private fun sanitiseBase(name: String): String {
        val raw = File(name).name.removeSuffix(".kuml.kts").removeSuffix(".kts")
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "preview" }
    }
}
