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
    /** Hard cap on a single preview render invocation (CLI cold-start + render). */
    private const val TIMEOUT_SECONDS = 30L

    /** Maximum SVG file size accepted for preview rendering (50 MB). */
    private const val MAX_SVG_BYTES = 50L * 1024L * 1024L

    /** Hard cap on an export invocation (PNG rasterisation can be slower). */
    private const val EXPORT_TIMEOUT_SECONDS = 60L

    /** Allowed theme values — validated before passing to ProcessBuilder. */
    private val ALLOWED_THEMES: Set<String> = setOf("kuml", "plain", "elegant", "playful")

    fun render(
        binary: File,
        scriptText: String,
        scriptBaseName: String,
        theme: String = KumlPreviewSettings.DEFAULT_THEME,
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
        val safeTheme = if (theme in ALLOWED_THEMES) theme else KumlPreviewSettings.DEFAULT_THEME

        return try {
            scriptFile.writeText(scriptText)

            val proc =
                ProcessBuilder(
                    *buildRenderArgs(binary, scriptFile, svgFile, "svg", safeTheme),
                ).redirectErrorStream(false)
                    .start()

            val stderr = drainStderr(proc)

            val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return KumlPreviewRenderer.Outcome.Failure("kuml render Timeout (> ${TIMEOUT_SECONDS}s)")
            }
            stderr.join(1000)

            val exit = proc.exitValue()
            when {
                exit == 0 && svgFile.isFile && svgFile.length() > 0L -> {
                    if (svgFile.length() > MAX_SVG_BYTES) {
                        KumlPreviewRenderer.Outcome.Failure(
                            "SVG-Ausgabe zu groß (${svgFile.length()} Bytes > ${MAX_SVG_BYTES} Bytes Limit). Rendering abgebrochen.",
                        )
                    } else {
                        KumlPreviewRenderer.Outcome.Svg(svgFile.readText())
                    }
                }

                else -> {
                    val detail = stderr.result().trim().ifBlank { "(keine Ausgabe)" }
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

    /**
     * Export [scriptText] to [outputFile] using [format] and [theme].
     *
     * Writes the script to a temporary file, invokes the CLI, and on success
     * verifies that [outputFile] exists and is non-empty. Returns [Result.success]
     * on success, [Result.failure] with a descriptive message on any error.
     * The [outputFile] is NOT deleted on failure — the caller decides.
     */
    fun exportToFile(
        binary: File,
        scriptText: String,
        scriptBaseName: String,
        outputFile: File,
        format: KumlExportFormat,
        theme: String,
    ): Result<Unit> {
        val tmpDir =
            try {
                File.createTempFile("kuml-export", "").let { f ->
                    f.delete()
                    f.mkdirs()
                    f
                }
            } catch (t: Throwable) {
                return Result.failure(RuntimeException("Temp-Verzeichnis konnte nicht angelegt werden: ${t.message}", t))
            }

        val base = sanitiseBase(scriptBaseName)
        val scriptFile = File(tmpDir, "$base.kuml.kts")
        val safeTheme = if (theme in ALLOWED_THEMES) theme else KumlPreviewSettings.DEFAULT_THEME

        return try {
            scriptFile.writeText(scriptText)

            val proc =
                ProcessBuilder(
                    *buildRenderArgs(binary, scriptFile, outputFile, format.cliFormat, safeTheme),
                ).redirectErrorStream(false)
                    .start()

            val stderr = drainStderr(proc)

            val finished = proc.waitFor(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return Result.failure(RuntimeException("kuml render Timeout beim Export (> ${EXPORT_TIMEOUT_SECONDS}s)"))
            }
            stderr.join(1000)

            val exit = proc.exitValue()
            if (exit == 0 && outputFile.isFile && outputFile.length() > 0L) {
                Result.success(Unit)
            } else {
                val detail = stderr.result().trim().ifBlank { "(keine Ausgabe)" }
                Result.failure(
                    RuntimeException(
                        "kuml export fehlgeschlagen (Exit $exit):\n$detail",
                    ),
                )
            }
        } catch (t: Throwable) {
            Result.failure(RuntimeException("kuml export Aufruf-Fehler: ${t::class.java.name}: ${t.message}", t))
        } finally {
            scriptFile.delete()
            tmpDir.delete()
        }
    }

    /**
     * Builds the CLI argument array for a `kuml render` invocation.
     *
     * Extracted so that [render] and [exportToFile] share the same arg-list
     * logic and it can be independently unit-tested.
     */
    internal fun buildRenderArgs(
        binary: File,
        scriptFile: File,
        outputFile: File,
        cliFormat: String,
        theme: String,
    ): Array<String> =
        arrayOf(
            binary.absolutePath,
            "render",
            scriptFile.absolutePath,
            "-o",
            outputFile.absolutePath,
            "-f",
            cliFormat,
            "--theme",
            theme,
        )

    /** Drains the process stderr on a daemon thread and returns a handle to join and read the result. */
    private fun drainStderr(proc: Process): StderrDrainer {
        val buf = StringBuilder()
        val thread =
            Thread {
                proc.errorStream.bufferedReader().forEachLine { buf.appendLine(it) }
            }.apply {
                isDaemon = true
                start()
            }
        return StderrDrainer(thread, buf)
    }

    private class StderrDrainer(
        private val thread: Thread,
        private val buf: StringBuilder,
    ) {
        fun join(millis: Long) = thread.join(millis)

        fun result(): String = buf.toString()
    }

    /** Keep only filesystem-safe characters for the temp file base name. */
    private fun sanitiseBase(name: String): String {
        val raw = File(name).name.removeSuffix(".kuml.kts").removeSuffix(".kts")
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "preview" }
    }
}
