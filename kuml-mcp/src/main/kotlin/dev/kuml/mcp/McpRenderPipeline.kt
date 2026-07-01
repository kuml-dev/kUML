package dev.kuml.mcp

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.toSvgFile
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal object McpRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()

    /** Evaluates a script file, returning (SVG string, null) or (null, PNG bytes). */
    internal fun render(
        scriptFile: File,
        format: String,
        widthPx: Int = 1024,
    ): RenderResult {
        val evalResult = KumlScriptHost.eval(scriptFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed:\n$msg")
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation produced no result")

        val diagram = DiagramExtractor.extract(success.value.returnValue, scriptFile)
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
        val theme = PlainTheme()

        return when (format) {
            "svg" -> {
                val tmp = Files.createTempFile("kuml-mcp-", ".svg")
                try {
                    KumlSvgRenderer.toSvgFile(diagram, layoutResult, tmp, theme)
                    RenderResult.Svg(tmp.toFile().readText())
                } finally {
                    tmp.toFile().delete()
                }
            }
            "png" -> {
                val bytes = KumlPngRenderer.toPng(diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
                RenderResult.Png(bytes)
            }
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    internal sealed class RenderResult {
        internal data class Svg(
            val content: String,
        ) : RenderResult()

        internal data class Png(
            val bytes: ByteArray,
        ) : RenderResult()
    }
}
