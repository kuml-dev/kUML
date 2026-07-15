package dev.kuml.markdown

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Internal glue: evaluate a kUML script string and render to SVG/PNG bytes.
 *
 * Mirrors the CLI's `RenderPipeline` but operates on in-memory script source
 * instead of a `.kuml.kts` file.
 */
internal object MarkdownRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()
    private val theme = PlainTheme()

    /** Evaluate [source] and return the extracted [KumlDiagram]. */
    internal fun evaluate(
        source: String,
        virtualName: String,
    ): KumlDiagram {
        val evalResult = KumlScriptHost.eval(source, virtualName)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed in '$virtualName':\n$message")
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script '$virtualName' produced no result")
        // DiagramExtractor takes a File only for error messages; use a stub file.
        return DiagramExtractor.extract(success.value.returnValue, File(virtualName))
    }

    /** Render [diagram] to a self-contained SVG string. */
    internal fun renderSvg(diagram: KumlDiagram): String {
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val layoutResult = layoutEngine.layout(layoutGraph, hintsFor(diagram))
        return KumlSvgRenderer.toSvg(diagram, layoutResult, theme)
    }

    /** Render [diagram] to PNG bytes at [widthPx]. */
    internal fun renderPng(
        diagram: KumlDiagram,
        widthPx: Int,
    ): ByteArray {
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val layoutResult = layoutEngine.layout(layoutGraph, hintsFor(diagram))
        return KumlPngRenderer.toPng(diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
    }

    // V3.0.x — see RenderPipeline.kt (CLI) for the full rationale: UML sequence
    // diagrams are the one diagram type where declaration order is semantically
    // meaningful, so pin it via LayoutHints.preserveNodeOrder.
    private fun hintsFor(diagram: KumlDiagram): LayoutHints =
        LayoutHints.DEFAULT.copy(preserveNodeOrder = diagram.type == DiagramType.SEQUENCE)
}
