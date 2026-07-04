package dev.kuml.mcp

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.toSvgFile
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import java.nio.file.Files

internal object McpRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()

    /**
     * Lays out and renders an already-extracted UML [diagram].
     *
     * V0.23.3: script evaluation + extraction moved out of this pipeline into
     * the sandboxed [dev.kuml.core.script.ScriptEvaluator] (see
     * [McpScriptEvaluator]); this method now only does layout + render, which
     * are pure/trusted operations on a validated model.
     *
     * @return (SVG string, null) or (null, PNG bytes).
     */
    internal fun render(
        diagram: KumlDiagram,
        format: String,
        widthPx: Int = 1024,
    ): RenderResult {
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
