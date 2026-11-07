package dev.kuml.cli.export

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.cli.RenderPipeline
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexFamily
import dev.kuml.sysml2.Sysml2PackageDiagram
import java.io.File
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

/** Outcome of [LatexExportService.exportFile], the per-file entry of the folder export. */
public sealed interface LatexFileExportResult {
    /** The snippet of the first diagram, written for a shared preamble. */
    public data class Rendered(
        val tex: String,
        val family: LatexFamily,
        val diagramCount: Int,
        val diagramName: String?,
    ) : LatexFileExportResult

    /** The diagram family has no LaTeX output; not an error of the script. */
    public data class Skipped(
        val reason: String,
    ) : LatexFileExportResult

    public data class Failed(
        val reason: String,
    ) : LatexFileExportResult
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
            Files.writeString(input, script, Charsets.UTF_8)
            val core =
                renderCore(
                    input = input.toFile(),
                    workDir = workDir,
                    themeName = themeName,
                    standalone = standalone,
                    shared = false,
                    abortUnsupported = false,
                )
            LatexExportResult.Success(
                tex = core.tex,
                diagramCount = core.diagramCount,
                firstDiagramName = core.diagramName,
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

    /**
     * Renders the script [input] in place (relative references resolve like in `kuml render`) as a snippet that
     * relies on a shared preamble. Output goes to a private temporary directory that is removed afterwards.
     * Diagram families without LaTeX output give [LatexFileExportResult.Skipped] before any layout work.
     */
    public fun exportFile(
        input: File,
        themeName: String?,
        sharedPreamble: Boolean,
    ): LatexFileExportResult {
        var dir: Path? = null
        return try {
            val workDir = createPrivateTempDir().also { dir = it }
            val core =
                renderCore(
                    input = input,
                    workDir = workDir,
                    themeName = themeName,
                    standalone = false,
                    shared = sharedPreamble,
                    abortUnsupported = true,
                )
            LatexFileExportResult.Rendered(
                tex = core.tex,
                family = checkNotNull(core.family) { "rendered diagram without a LaTeX family" },
                diagramCount = core.diagramCount,
                diagramName = core.diagramName,
            )
        } catch (e: UnsupportedFamily) {
            LatexFileExportResult.Skipped(e.reason)
        } catch (e: ScriptEvaluationException) {
            LatexFileExportResult.Failed(cleanReason(e.message ?: "Script evaluation failed"))
        } catch (e: IllegalArgumentException) {
            LatexFileExportResult.Failed(cleanReason(e.message ?: "Invalid input"))
        } catch (e: IOException) {
            LatexFileExportResult.Failed(cleanReason(e.message ?: e.javaClass.simpleName))
        } catch (e: UnsupportedOperationException) {
            LatexFileExportResult.Failed(cleanReason(e.message ?: "Not supported"))
        } finally {
            dir?.let { runCatching { it.toFile().deleteRecursively() } }
        }
    }

    /** `null` when [extracted] has LaTeX output, otherwise the reason it has none. */
    public fun unsupportedReason(extracted: ExtractedDiagram): String? =
        when (extracted) {
            is ExtractedDiagram.Erm -> "ERM diagrams have no LaTeX output yet"
            is ExtractedDiagram.Sysml2 ->
                if (extracted.diagram is Sysml2PackageDiagram) KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED else null
            is ExtractedDiagram.Bpmn ->
                when (extracted.diagram) {
                    is ChoreographyDiagram -> "BPMN choreography diagrams have no LaTeX output yet"
                    is ConversationDiagram -> "BPMN conversation diagrams have no LaTeX output yet"
                    else -> null
                }
            is ExtractedDiagram.Uml, is ExtractedDiagram.C4, is ExtractedDiagram.Blueprint -> null
        }

    /** The family [extracted] belongs to for the shared preamble, `null` for diagrams without LaTeX output. */
    public fun familyOf(extracted: ExtractedDiagram): LatexFamily? =
        when (extracted) {
            is ExtractedDiagram.Uml -> LatexFamily.UML
            is ExtractedDiagram.C4 -> LatexFamily.C4
            is ExtractedDiagram.Sysml2 -> LatexFamily.SYSML2
            is ExtractedDiagram.Bpmn -> LatexFamily.BPMN
            is ExtractedDiagram.Blueprint -> LatexFamily.BLUEPRINT
            is ExtractedDiagram.Erm -> null
        }

    private class CoreResult(
        val tex: String,
        val diagramCount: Int,
        val diagramName: String?,
        val family: LatexFamily?,
    )

    /** Aborts the pipeline right after extraction, before any layout work. */
    private class UnsupportedFamily(
        val reason: String,
    ) : RuntimeException(reason)

    private fun renderCore(
        input: File,
        workDir: Path,
        themeName: String?,
        standalone: Boolean,
        shared: Boolean,
        abortUnsupported: Boolean,
    ): CoreResult {
        val output = workDir.resolve("diagram.tex")
        var extracted: ExtractedDiagram? = null
        val outcome =
            RenderPipeline.run(
                input = input,
                output = output,
                format = "latex",
                width = DEFAULT_WIDTH,
                themeName = themeName,
                latexStandalone = standalone,
                latexSharedPreamble = shared,
                extractedSink = {
                    extracted = it
                    if (abortUnsupported) unsupportedReason(it)?.let { reason -> throw UnsupportedFamily(reason) }
                },
            )
        return CoreResult(
            tex = Files.readString(output, Charsets.UTF_8),
            diagramCount = outcome.diagramCount,
            diagramName = extracted?.let(::diagramName),
            family = extracted?.let(::familyOf),
        )
    }

    private fun cleanReason(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) sb.append(if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') ' ' else c)
        return sb.toString().trim().take(MAX_REASON_LENGTH)
    }

    private const val MAX_REASON_LENGTH = 300

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
