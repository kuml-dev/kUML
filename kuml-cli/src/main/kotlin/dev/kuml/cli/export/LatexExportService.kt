package dev.kuml.cli.export

import dev.kuml.cli.RenderPipeline
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Outcome of [LatexExportService.export]. A failure always carries the reason; nothing is swallowed. */
public sealed interface LatexExportResult {
    /**
     * The TikZ source of the first diagram of the script.
     *
     * @property tex A complete `\documentclass{standalone}` document, or a bare `tikzpicture` snippet.
     * @property diagramCount How many renderable diagrams the script holds; only the first is exported.
     * @property firstDiagramName Name of the exported diagram, `null` when it could not be determined.
     * @property warnings Non-fatal findings of the render (currently always empty; they go to stderr like in the CLI).
     */
    public data class Success(
        val tex: String,
        val diagramCount: Int,
        val firstDiagramName: String?,
        val warnings: List<String>,
    ) : LatexExportResult

    public data class Failure(
        val message: String,
    ) : LatexExportResult
}

/**
 * Programmatic entry to the `kuml render --format latex` path, for hosts that hold the script as text (the Desktop
 * app). It runs the CLI's own pipeline, so LaTeX output has exactly one implementation and the Desktop can never
 * drift from `kuml render`.
 *
 * The script is evaluated again from its text: nothing of the screen state (preview SVG, simulation base render,
 * highlights) can reach the output.
 */
public object LatexExportService {
    /**
     * @param script Source of a `.kuml.kts` script.
     * @param themeName Theme name as for `kuml render --theme`. LaTeX output does not use a theme, but the pipeline
     *   still resolves it, so an unknown name is reported as a failure; `null` uses the default.
     * @param standalone `true` for a complete document, `false` for the bare TikZ picture.
     */
    public fun export(
        script: String,
        themeName: String?,
        standalone: Boolean,
    ): LatexExportResult {
        var dir: Path? = null
        return try {
            val workDir = createPrivateTempDir().also { dir = it }
            val input = workDir.resolve("diagram.kuml.kts")
            val output = workDir.resolve("diagram.tex")
            Files.writeString(input, script, Charsets.UTF_8)
            var extracted: ExtractedDiagram? = null
            val outcome =
                RenderPipeline.run(
                    input = input.toFile(),
                    output = output,
                    format = "latex",
                    width = DEFAULT_WIDTH,
                    themeName = themeName,
                    latexStandalone = standalone,
                    extractedSink = { extracted = it },
                )
            LatexExportResult.Success(
                tex = Files.readString(output, Charsets.UTF_8),
                diagramCount = outcome.diagramCount,
                firstDiagramName = extracted?.let(::diagramName),
                warnings = emptyList(),
            )
        } catch (e: ScriptEvaluationException) {
            LatexExportResult.Failure(e.message ?: "Script evaluation failed")
        } catch (e: IllegalArgumentException) {
            LatexExportResult.Failure(e.message ?: "Invalid input")
        } catch (e: IOException) {
            LatexExportResult.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: UnsupportedOperationException) {
            LatexExportResult.Failure(e.message ?: "Not supported")
        } finally {
            dir?.let { runCatching { it.toFile().deleteRecursively() } }
        }
    }

    private const val DEFAULT_WIDTH = 1024

    private fun createPrivateTempDir(): Path =
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createTempDirectory(
                "kuml-latex-export",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        } else {
            Files.createTempDirectory("kuml-latex-export")
        }

    private fun diagramName(extracted: ExtractedDiagram): String? =
        when (extracted) {
            is ExtractedDiagram.Uml -> extracted.diagram.name
            is ExtractedDiagram.C4 -> extracted.diagram.name
            is ExtractedDiagram.Sysml2 -> extracted.diagram.name
            is ExtractedDiagram.Bpmn -> extracted.diagram.name
            is ExtractedDiagram.Blueprint -> extracted.diagram.name
            is ExtractedDiagram.Erm -> extracted.diagram.name
        }
}
