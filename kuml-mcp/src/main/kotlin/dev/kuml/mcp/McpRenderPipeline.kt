package dev.kuml.mcp

import dev.kuml.core.model.DiagramType
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
        // V3.0.x — see CLI's RenderPipeline.kt for the full rationale: UML sequence
        // diagrams are the one diagram type where declaration order is semantically
        // meaningful, so pin it via LayoutHints.preserveNodeOrder.
        val hints = LayoutHints.DEFAULT.copy(preserveNodeOrder = diagram.type == DiagramType.SEQUENCE)
        val layoutResult = layoutEngine.layout(layoutGraph, hints)
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
