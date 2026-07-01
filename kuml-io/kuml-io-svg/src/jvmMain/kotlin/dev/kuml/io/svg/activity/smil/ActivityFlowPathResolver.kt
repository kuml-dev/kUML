package dev.kuml.io.svg.activity.smil

import dev.kuml.io.svg.fmt2
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.uml.UmlActivityEdge

/**
 * Resolves SVG path `d`-strings for [UmlActivityEdge]s from a [LayoutResult].
 *
 * Geometry helpers mirror [dev.kuml.io.svg.bpmn.smil.BpmnFlowPathResolver]
 * but operate on [UmlActivityEdge] rather than BPMN SequenceFlow.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
internal object ActivityFlowPathResolver {
    /**
     * Builds a map from [UmlActivityEdge.id] to SVG path `d`-string.
     *
     * Edges with no corresponding [LayoutResult.edges] entry are omitted.
     *
     * @param edges The activity edges to resolve.
     * @param layoutResult The layout result containing edge routes keyed by edge id.
     * @param padding The canvas padding in pixels (same as `SvgRenderOptions.paddingPx`).
     */
    fun buildEdgePaths(
        edges: List<UmlActivityEdge>,
        layoutResult: LayoutResult,
        padding: Float,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (edge in edges) {
            val route = layoutResult.edges[EdgeId(edge.id)] ?: continue
            val shifted = shiftRoute(route, padding)
            result[edge.id] = edgePathD(shifted)
        }
        return result
    }

    /**
     * Builds a lookup index from `"<sourceId>-><targetId>"` to [UmlActivityEdge].
     *
     * Used for O(1) edge lookup during trace traversal.
     */
    fun buildFlowIndex(edges: List<UmlActivityEdge>): Map<String, UmlActivityEdge> =
        edges
            .associateBy { "${it.sourceId}->${it.targetId}" }

    // ── geometry helpers (mirrors BpmnFlowPathResolver, self-contained) ──────

    internal fun edgePathD(route: EdgeRoute): String {
        val src = route.source
        val tgt = route.target
        return when (route) {
            is EdgeRoute.Direct ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"

            is EdgeRoute.OrthogonalRounded -> {
                val pts = listOf(src) + route.waypoints + listOf(tgt)
                buildPolyline(pts)
            }

            is EdgeRoute.TreeRounded -> {
                val pts = listOf(src) + route.waypoints + listOf(tgt)
                buildPolyline(pts)
            }

            is EdgeRoute.Bezier -> buildBezierPath(src, tgt, route.controlPoints)
        }
    }

    private fun buildBezierPath(
        src: Point,
        tgt: Point,
        controlPoints: List<Point>,
    ): String =
        when {
            controlPoints.size >= 2 -> {
                val cp1 = controlPoints[0]
                val cp2 = controlPoints[1]
                "M ${fmtF(src.x)} ${fmtF(src.y)} " +
                    "C ${fmtF(cp1.x)} ${fmtF(cp1.y)} ${fmtF(cp2.x)} ${fmtF(cp2.y)} " +
                    "${fmtF(tgt.x)} ${fmtF(tgt.y)}"
            }
            controlPoints.size == 1 -> {
                val cp = controlPoints[0]
                "M ${fmtF(src.x)} ${fmtF(src.y)} " +
                    "C ${fmtF(cp.x)} ${fmtF(cp.y)} ${fmtF(cp.x)} ${fmtF(cp.y)} " +
                    "${fmtF(tgt.x)} ${fmtF(tgt.y)}"
            }
            else -> "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"
        }

    private fun buildPolyline(points: List<Point>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("M ${fmtF(points.first().x)} ${fmtF(points.first().y)}")
        points.drop(1).forEach { sb.append(" L ${fmtF(it.x)} ${fmtF(it.y)}") }
        return sb.toString()
    }

    private fun fmtF(v: Float): String = fmt2(v)

    internal fun shiftRoute(
        route: EdgeRoute,
        padding: Float,
    ): EdgeRoute {
        fun Point.shift() = Point(x + padding, y + padding)
        return when (route) {
            is EdgeRoute.Direct ->
                route.copy(source = route.source.shift(), target = route.target.shift())

            is EdgeRoute.OrthogonalRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )

            is EdgeRoute.TreeRounded ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    waypoints = route.waypoints.map { it.shift() },
                )

            is EdgeRoute.Bezier ->
                route.copy(
                    source = route.source.shift(),
                    target = route.target.shift(),
                    controlPoints = route.controlPoints.map { it.shift() },
                )
        }
    }
}
