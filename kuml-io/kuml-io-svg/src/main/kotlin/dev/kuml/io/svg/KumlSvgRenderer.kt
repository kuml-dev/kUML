package dev.kuml.io.svg

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import java.nio.file.Path

/**
 * Rendert kUML-Diagramme als SVG-String oder -Datei.
 *
 * Der Renderer arbeitet direkt auf [KumlDiagram] / [C4Diagram] + [LayoutResult] und
 * benötigt keinen Compose-Kontext (GraalVM-Native-Image-tauglich).
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())
 * File("out.svg").writeText(svg)
 * ```
 *
 * @see SvgRenderOptions
 * @see dev.kuml.renderer.theme.core.PlainTheme
 */
public object KumlSvgRenderer {
    /**
     * Rendert ein UML-Diagramm + Layout-Ergebnis als SVG-String.
     *
     * @param diagram das UML-Diagramm mit allen Elementen
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return wohlgeformter SVG-String
     */
    public fun toSvg(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String =
        SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            // Nodes
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = diagram.elements.find { it.id == nodeId.value }
                if (element != null) {
                    val shifted =
                        nodeLayout.copy(
                            bounds =
                                nodeLayout.bounds.copy(
                                    origin =
                                        nodeLayout.bounds.origin.copy(
                                            x = nodeLayout.bounds.origin.x + padding,
                                            y = nodeLayout.bounds.origin.y + padding,
                                        ),
                                ),
                        )
                    NodeRendererDispatcher.dispatch(element, shifted, theme, nodesBuilder)
                }
            }

            // Groups (C4 SoftwareSystem groupings)
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                nodesBuilder.tag(
                    "g",
                    mapOf(
                        "id" to xmlEscapeAttr("system-${groupId.value}"),
                        "transform" to "translate(${fmt(gx)},${fmt(gy)})",
                    ),
                ) {
                    tag(
                        "rect",
                        mapOf(
                            "width" to fmt(gw),
                            "height" to fmt(gh),
                            "class" to "kuml-system",
                            "rx" to fmt(theme.borders.cornerRadiusPx),
                            "ry" to fmt(theme.borders.cornerRadiusPx),
                        ),
                    )
                }
            }

            // Edges
            val elementIndex = diagram.elements.associateBy { it.id }
            for ((edgeId, route) in layoutResult.edges) {
                val element = elementIndex[edgeId.value]
                if (element != null) {
                    val shiftedRoute = shiftRoute(route, padding)
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                }
            }
        }

    /**
     * Rendert ein C4-Diagramm + Layout-Ergebnis als SVG-String.
     *
     * @param diagram das C4-Diagramm
     * @param model das übergeordnete C4-Modell für Element-Lookup
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return wohlgeformter SVG-String
     */
    public fun toSvg(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): String =
        SvgDocument.render(layoutResult, theme, options) { nodesBuilder, edgesBuilder ->
            val padding = options.paddingPx

            val elementIndex = model.elements.associateBy { it.id }
            val relationshipIndex = model.relationships.associateBy { it.id }

            // Nodes
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = elementIndex[nodeId.value]
                if (element != null) {
                    val shifted =
                        nodeLayout.copy(
                            bounds =
                                nodeLayout.bounds.copy(
                                    origin =
                                        nodeLayout.bounds.origin.copy(
                                            x = nodeLayout.bounds.origin.x + padding,
                                            y = nodeLayout.bounds.origin.y + padding,
                                        ),
                                ),
                        )
                    NodeRendererDispatcher.dispatch(element, shifted, theme, nodesBuilder)
                }
            }

            // Groups (C4 SoftwareSystem groupings)
            for ((groupId, groupLayout) in layoutResult.groups) {
                val gx = groupLayout.bounds.origin.x + padding
                val gy = groupLayout.bounds.origin.y + padding
                val gw = groupLayout.bounds.size.width
                val gh = groupLayout.bounds.size.height
                nodesBuilder.tag(
                    "g",
                    mapOf(
                        "id" to xmlEscapeAttr("system-${groupId.value}"),
                        "transform" to "translate(${fmt(gx)},${fmt(gy)})",
                    ),
                ) {
                    tag(
                        "rect",
                        mapOf(
                            "width" to fmt(gw),
                            "height" to fmt(gh),
                            "class" to "kuml-system",
                            "rx" to fmt(theme.borders.cornerRadiusPx),
                            "ry" to fmt(theme.borders.cornerRadiusPx),
                        ),
                    )
                }
            }

            // Edges
            for ((edgeId, route) in layoutResult.edges) {
                val element = relationshipIndex[edgeId.value]
                if (element != null) {
                    val shiftedRoute = shiftRoute(route, padding)
                    EdgeRendererDispatcher.dispatch(element, shiftedRoute, theme, edgesBuilder)
                }
            }
        }

    /**
     * Schreibt ein UML-Diagramm als SVG in eine Datei und gibt sie zurück.
     *
     * @param diagram das UML-Diagramm
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param out Zieldatei-Pfad
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Renderer-Optionen; Standard: [SvgRenderOptions.DEFAULT]
     * @return die geschriebene Datei
     */
    public fun toSvgFile(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val svg = toSvg(diagram, layoutResult, theme, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(svg, Charsets.UTF_8)
        return file
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun shiftRoute(
        route: dev.kuml.layout.EdgeRoute,
        padding: Float,
    ): dev.kuml.layout.EdgeRoute {
        fun dev.kuml.layout.Point.shift() = dev.kuml.layout.Point(x + padding, y + padding)
        return when (route) {
            is dev.kuml.layout.EdgeRoute.Direct ->
                route.copy(source = route.source.shift(), target = route.target.shift())
            is dev.kuml.layout.EdgeRoute.OrthogonalRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )
            is dev.kuml.layout.EdgeRoute.TreeRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )
            is dev.kuml.layout.EdgeRoute.Bezier ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    controlPoints = route.controlPoints.map { it.shift() },
                )
        }
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }
}

// Suppress unused import warning for NodeId/EdgeId/GroupId — used via layoutResult
@Suppress("UnusedPrivateMember")
private fun unusedImportSuppressor(
    a: NodeId,
    b: EdgeId,
) = Unit
