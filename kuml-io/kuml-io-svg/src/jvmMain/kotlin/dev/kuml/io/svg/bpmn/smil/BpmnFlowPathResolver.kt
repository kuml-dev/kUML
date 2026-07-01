package dev.kuml.io.svg.bpmn.smil

import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.io.svg.fmt2
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point

/**
 * Resolves SVG path `d`-strings for BPMN [SequenceFlow]s and maps adjacent node-id
 * pairs to the [SequenceFlow] connecting them.
 *
 * Path geometry replicates the exact same logic as [dev.kuml.io.svg.bpmn.edge.renderBpmnSequenceFlow]
 * so that the token motion path is collinear with the drawn arrow.
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
internal object BpmnFlowPathResolver {
    /**
     * Builds a map from [SequenceFlow.id] to SVG path `d`-string.
     *
     * Uses [shiftRoute] with [padding] to match the coordinate shift that the SVG
     * renderer applies to every edge route (padding offset = top-left canvas margin).
     *
     * @param process The BPMN process whose sequence flows are resolved.
     * @param layoutResult The layout result containing edge routes keyed by edge id.
     * @param padding The canvas padding in pixels (same value as `SvgRenderOptions.paddingPx`).
     * @return Map from flow id to path `d`-string. Flows with no layout entry are omitted.
     */
    fun buildEdgePaths(
        process: BpmnProcess,
        layoutResult: LayoutResult,
        padding: Float,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val allFlows = collectAllFlows(process)
        for (flow in allFlows) {
            val route = layoutResult.edges[dev.kuml.layout.EdgeId(flow.id)] ?: continue
            val shifted = shiftRoute(route, padding)
            result[flow.id] = bpmnFlowPathD(shifted)
        }
        return result
    }

    /**
     * Given two consecutively-visited BPMN node ids `(fromId, toId)`, returns the
     * [SequenceFlow] in [process] whose `sourceRef == fromId` and `targetRef == toId`,
     * or `null` if no such flow exists.
     */
    fun nodeToFlow(
        process: BpmnProcess,
        fromId: String,
        toId: String,
    ): SequenceFlow? =
        collectAllFlows(process).firstOrNull { flow ->
            flow.sourceRef == fromId && flow.targetRef == toId
        }

    /**
     * Builds a lookup index from `"$sourceRef->$targetRef"` to [SequenceFlow] for all flows
     * in [process], including flows inside expanded sub-processes.
     *
     * Pre-computing this index once before a traversal loop avoids the O(N*M) cost that
     * arises when [nodeToFlow] (and thus [collectAllFlows]) is called on every iteration.
     */
    fun buildFlowIndex(process: BpmnProcess): Map<String, SequenceFlow> {
        val allFlows = collectAllFlows(process)
        return allFlows.associateBy { "${it.sourceRef}->${it.targetRef}" }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    internal fun collectAllFlows(process: BpmnProcess): List<SequenceFlow> {
        val acc = mutableListOf<SequenceFlow>()
        acc += process.sequenceFlows
        process.flowNodes
            .filterIsInstance<BpmnSubProcess>()
            .filter { it.expanded }
            .forEach { acc += it.innerSequenceFlows }
        return acc
    }

    /**
     * Converts an [EdgeRoute] to an SVG path `d`-string.
     *
     * Replicates the same `when (route)` dispatch as [dev.kuml.io.svg.bpmn.edge.renderBpmnSequenceFlow]
     * so token motion paths are identical to the drawn arrows.
     */
    internal fun bpmnFlowPathD(route: EdgeRoute): String {
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

    /**
     * Builds a cubic Bezier SVG path string from [src] to [tgt] using [controlPoints].
     *
     * SVG cubic Bezier requires exactly two control points (C command). If fewer control
     * points are present the path falls back to a straight line so the token remains on
     * a valid path rather than producing malformed SVG. If more than two control points
     * are present only the first two are used (matching typical BPMN spline semantics).
     */
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
                // Quadratic-style: promote single control point to cubic
                val cp = controlPoints[0]
                "M ${fmtF(src.x)} ${fmtF(src.y)} " +
                    "C ${fmtF(cp.x)} ${fmtF(cp.y)} ${fmtF(cp.x)} ${fmtF(cp.y)} " +
                    "${fmtF(tgt.x)} ${fmtF(tgt.y)}"
            }
            else ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"
        }

    private fun buildPolyline(points: List<Point>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("M ${fmtF(points.first().x)} ${fmtF(points.first().y)}")
        points.drop(1).forEach { sb.append(" L ${fmtF(it.x)} ${fmtF(it.y)}") }
        return sb.toString()
    }

    private fun fmtF(v: Float): String = fmt2(v)

    private fun shiftRoute(
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
