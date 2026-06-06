package dev.kuml.cli

import dev.kuml.core.config.KumlConfig
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Orchestrates the kUML render pipeline: Script → Layout → Renderer → File.
 *
 * Branches on UML vs C4 at the extraction step. The downstream stages
 * (layout, renderer, file write) are dispatched on [ExtractedDiagram].
 *
 * This object is the internal glue between all pipeline stages. It is not part
 * of the public API; all user-facing interaction goes through [RenderCommand].
 */
internal object RenderPipeline {
    private val layoutEngine = ElkLayoutEngine()

    /**
     * Runs the full render pipeline for the given input script.
     *
     * @param input Path to the `*.kuml.kts` script file.
     * @param output Destination path for the rendered output file.
     * @param format Output format: `"svg"` or `"png"`.
     * @param width Width in pixels (used only for PNG output).
     * @param themeName Optional theme name from CLI; takes precedence over config.
     * @param config Loaded `kuml.config.kts` configuration (or [KumlConfig.DEFAULT]).
     * @throws ScriptEvaluationException if the script fails to compile or evaluate.
     * @throws IOException if the output file cannot be written.
     */
    internal fun run(
        input: File,
        output: Path,
        format: String,
        width: Int,
        themeName: String?,
        config: KumlConfig = KumlConfig.DEFAULT,
    ) {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(input)

        // Check for compilation/evaluation errors
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed:\n$message")
        }

        // 2. Extract diagram — UML or C4
        val successResult =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation did not produce a result")

        val extracted = DiagramExtractor.extractAny(successResult.value.returnValue, input)

        // 3. Theme — layering: CLI flag > config file > built-in default "plain"
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
        val resolvedThemeName =
            themeName
                ?: config.render.themeName
                ?: "plain"
        val baseTheme: KumlTheme =
            ThemeRegistry.get(resolvedThemeName)
                ?: throw ScriptEvaluationException(
                    "Unknown theme: '$resolvedThemeName'. " +
                        "Registered themes: ${ThemeRegistry.names()}",
                )
        val theme: KumlTheme =
            config.render.stereotypeOverrides
                ?.let { patch -> baseTheme.copy(stereotypes = patch.applyTo(baseTheme.stereotypes)) }
                ?: baseTheme

        // 4–6. Branch on diagram kind: layout → render → write
        try {
            when (extracted) {
                is ExtractedDiagram.Uml -> renderUml(extracted, output, format, width, theme)
                is ExtractedDiagram.C4 -> renderC4(extracted, output, format, width, theme)
                is ExtractedDiagram.Sysml2 -> renderSysml2(extracted, output, format, theme)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (e is ScriptEvaluationException) throw e
            throw IOException("Failed to write output file: ${e.message}", e)
        }
    }

    private fun renderUml(
        extracted: ExtractedDiagram.Uml,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
    ) {
        val diagram = extracted.diagram
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
        when (format) {
            "svg" -> KumlSvgRenderer.toSvgFile(diagram, layoutResult, output, theme)
            "png" -> {
                val pngBytes =
                    KumlPngRenderer.toPng(diagram, layoutResult, theme, PngRenderOptions(widthPx = width))
                writeBinary(output, pngBytes)
            }
            "latex" -> {
                // V2.0.2 MVP: snippet mode (`\begin{tikzpicture}…\end{tikzpicture}`).
                // Standalone mode (`\documentclass{standalone}`) is reachable today
                // via the library API; a CLI `--latex-standalone` flag lands later
                // when the V2.x options pipeline grows. The theme is implicit —
                // the renderer ships a plain monochrome `\tikzset{…}` block that
                // users can override in their preamble.
                val tex = KumlLatexRenderer.toLatex(diagram, layoutResult, LatexRenderOptions.DEFAULT)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    /**
     * SysML 2 BDD render-Branch (V2.0.4).
     *
     * Bridge → ELK Layout → Render (SVG/LaTeX). PNG ist V2.x — der Batik-PNG-
     * Renderer braucht für SysML 2 noch ein paar Stereotyp-Style-Mappings, das
     * lohnt eine eigene Welle. Bis dahin emittet die PNG-Code-Path einen klaren
     * Fehler statt einer halben Render-Pipeline.
     */
    private fun renderSysml2(
        extracted: ExtractedDiagram.Sysml2,
        output: Path,
        format: String,
        theme: KumlTheme,
    ) {
        val model = extracted.model
        val diagram = extracted.diagram
        val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
        val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
        when (format) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
                writeText(output, svg)
            }
            "latex" -> {
                val tex = KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT)
                writeText(output, tex)
            }
            "png" -> throw ScriptEvaluationException(
                "PNG-Export für SysML 2 BDDs ist V2.x — bis dahin bitte SVG oder LaTeX nutzen.",
            )
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    private fun renderC4(
        extracted: ExtractedDiagram.C4,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
    ) {
        val diagram = extracted.diagram
        val model = extracted.model
        val layoutGraph = C4LayoutBridge.toLayoutGraph(diagram, model)
        val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
        when (format) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, theme)
                writeText(output, svg)
            }
            "png" -> {
                val pngBytes =
                    KumlPngRenderer.toPng(
                        diagram,
                        model,
                        layoutResult,
                        theme,
                        PngRenderOptions(widthPx = width),
                    )
                writeBinary(output, pngBytes)
            }
            "latex" -> {
                // C4 LaTeX export reuses the UML class-renderer fallback — each
                // C4 element gets a labelled rectangle. Full C4-style boxes
                // (Person / SoftwareSystem / Container with role labels) land
                // in a later V2.x wave; for V2.0.2 the MVP focus is class
                // diagrams.
                val placeholderDiagram = KumlDiagram(name = diagram.name)
                val tex = KumlLatexRenderer.toLatex(placeholderDiagram, layoutResult, LatexRenderOptions.DEFAULT)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    private fun writeBinary(
        out: Path,
        bytes: ByteArray,
    ) {
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun writeText(
        out: Path,
        text: String,
    ) {
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
