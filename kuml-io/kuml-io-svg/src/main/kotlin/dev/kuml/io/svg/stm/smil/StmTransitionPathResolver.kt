package dev.kuml.io.svg.stm.smil

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.uml.UmlTransition

/**
 * Resolves SVG path `d`-strings for UML [UmlTransition]s from a [LayoutResult].
 *
 * Geometry helpers replicate the edge-path logic from
 * [dev.kuml.io.svg.bpmn.smil.BpmnFlowPathResolver] — kept internal to this object
 * to avoid cross-package coupling.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
internal object StmTransitionPathResolver {
    /**
     * Builds a map from [UmlTransition.id] to SVG path `d`-string.
     *
     * Transitions whose [UmlTransition.id] has no corresponding entry in
     * [LayoutResult.edges] are silently omitted — the animation for those
     * transitions will be skipped (no crash).
     *
     * @param transitions The list of transitions to resolve.
     * @param layoutResult The layout result containing edge routes keyed by edge id.
     * @param padding The canvas padding in pixels (same value as `SvgRenderOptions.paddingPx`).
     * @return Map from transition id to path `d`-string.
     */
    fun buildTransitionPaths(
        transitions: List<UmlTransition>,
        layoutResult: LayoutResult,
        padding: Float,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (transition in transitions) {
            val route = layoutResult.edges[EdgeId(transition.id)] ?: continue
            val shifted = shiftRoute(route, padding)
            result[transition.id] = edgePathD(shifted)
        }
        return result
    }

    // ── geometry helpers (self-contained copies from BpmnFlowPathResolver) ────

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

    private fun fmtF(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
    }

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
