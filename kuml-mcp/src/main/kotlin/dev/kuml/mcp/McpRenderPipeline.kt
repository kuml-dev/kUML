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
import dev.kuml.mcp.tools.McpToolException
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files

/** Built-in default theme name — matches the CLI default (see kuml-cli RenderPipeline). */
internal const val DEFAULT_THEME_NAME: String = "kuml"

internal object McpRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()
    private val json = Json { prettyPrint = false }

    internal object ErrorCodes {
        const val UNKNOWN_THEME = "KUML-MCP-E-RENDER-UNKNOWN-THEME"
    }

    /**
     * Resolves a theme name against [ThemeRegistry], mirroring the CLI's layering
     * (explicit argument > built-in default "kuml"). The MCP server has no
     * `kuml.config.kts` layer, so there is no config tier between the two.
     *
     * @throws McpToolException if [themeName] is not a registered theme.
     */
    internal fun resolveTheme(themeName: String? = null): KumlTheme {
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
        val resolved = themeName?.takeIf { it.isNotBlank() } ?: DEFAULT_THEME_NAME
        return ThemeRegistry.get(resolved)
            ?: throw McpToolException(message = unknownThemeError(resolved))
    }

    private fun unknownThemeError(themeName: String): String =
        json.encodeToString(
            buildJsonObject {
                putJsonObject("error") {
                    put("code", ErrorCodes.UNKNOWN_THEME)
                    put("message", "Unknown theme '$themeName'.")
                    putJsonArray("validThemes") {
                        ThemeRegistry.names().forEach { add(JsonPrimitive(it)) }
                    }
                }
            },
        )

    /**
     * Lays out and renders an already-extracted UML [diagram].
     *
     * V0.23.3: script evaluation + extraction moved out of this pipeline into
     * the sandboxed [dev.kuml.core.script.ScriptEvaluator] (see
     * [McpScriptEvaluator]); this method now only does layout + render, which
     * are pure/trusted operations on a validated model.
     *
     * @param themeName Optional registered theme name; `null` → the built-in default
     *   `"kuml"` (same default as the CLI).
     * @throws McpToolException if [themeName] is not a registered theme.
     * @return (SVG string, null) or (null, PNG bytes).
     */
    internal fun render(
        diagram: KumlDiagram,
        format: String,
        widthPx: Int = 1024,
        themeName: String? = null,
    ): RenderResult {
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram = diagram)
        // V3.0.x — see CLI's RenderPipeline.kt for the full rationale: UML sequence
        // diagrams are the one diagram type where declaration order is semantically
        // meaningful, so pin it via LayoutHints.preserveNodeOrder.
        val hints = LayoutHints.DEFAULT.copy(preserveNodeOrder = diagram.type == DiagramType.SEQUENCE)
        val layoutResult = layoutEngine.layout(graph = layoutGraph, hints = hints)
        val theme = resolveTheme(themeName = themeName)

        return when (format) {
            "svg" -> {
                val tmp = Files.createTempFile("kuml-mcp-", ".svg")
                try {
                    KumlSvgRenderer.toSvgFile(diagram = diagram, layoutResult = layoutResult, out = tmp, theme = theme)
                    RenderResult.Svg(tmp.toFile().readText())
                } finally {
                    tmp.toFile().delete()
                }
            }
            "png" -> {
                val bytes =
                    KumlPngRenderer.toPng(
                        diagram = diagram,
                        layoutResult = layoutResult,
                        theme = theme,
                        options = PngRenderOptions(widthPx = widthPx),
                    )
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
