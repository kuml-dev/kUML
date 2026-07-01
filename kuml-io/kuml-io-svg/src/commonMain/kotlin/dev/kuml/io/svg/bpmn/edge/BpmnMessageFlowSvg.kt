package dev.kuml.io.svg.bpmn.edge

import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.renderEdgeLabelWithHalo
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Rendert einen [MessageFlow] als gestrichelte BPMN-Nachrichtenpfeil-Linie.
 *
 * Darstellung:
 * - Gestrichelte Linie (`stroke-dasharray="5,3"`)
 * - Offener (nicht gefüllter) Pfeilkopf am Ziel
 * - Kleiner Kreis am Ursprungspunkt (Quelle)
 * - Optionales Label in der Mitte der Route
 *
 * V3.1.5 — BPMN Collaboration SVG-Renderer (MessageFlow)
 */
internal fun renderBpmnMessageFlow(
    flow: MessageFlow,
    route: EdgeRoute,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    val src = route.source
    val tgt = route.target

    val edgeColor = theme.colors.edge.toHex()
    val nodeFill = theme.colors.effectiveNodeFill.toHex()

    // Build path data from route
    val pathD =
        when (route) {
            is EdgeRoute.Direct ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"

            is EdgeRoute.OrthogonalRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildPolyline(allPts)
            }

            is EdgeRoute.TreeRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                buildPolyline(allPts)
            }

            is EdgeRoute.Bezier ->
                "M ${fmtF(src.x)} ${fmtF(src.y)} L ${fmtF(tgt.x)} ${fmtF(tgt.y)}"
        }

    // Unique marker ID derived from flow ID
    val safeId = flow.id.replace(Regex("[^a-zA-Z0-9]"), "_")
    val markerId = "bpmn-msg-arrow-$safeId"

    // Open arrowhead definition (node fill + edge stroke = "open" style)
    builder.rawXml(
        """<defs><marker id="$markerId" markerWidth="8" markerHeight="6" """ +
            """refX="7" refY="3" orient="auto">""" +
            """<polygon points="0,0 8,3 0,6" fill="$nodeFill" stroke="$edgeColor" stroke-width="1"/></marker></defs>""",
    )

    // Dashed line with open arrowhead
    builder.rawXml(
        """<path d="$pathD" fill="none" stroke="$edgeColor" stroke-width="1.2" """ +
            """stroke-dasharray="5,3" marker-end="url(#$markerId)"/>""",
    )

    // Small initiating circle at the source point
    builder.rawXml(
        """<circle cx="${fmtF(src.x)}" cy="${fmtF(src.y)}" r="4" fill="$nodeFill" stroke="$edgeColor" stroke-width="1"/>""",
    )

    // Optional label placed on the *longest* polyline segment (not the array
    // midpoint / corner kink), offset perpendicular to that segment, with a
    // halo so it stays readable even where it crosses the dashed line.
    //
    // Fix: the previous implementation anchored the label at `allPts[size/2]`
    // — for an L-shaped two-pool message flow that is the bend corner, so the
    // label was crammed 4 px next to the line and the "Purchase Order" glyphs
    // collided with the dashed edge. Reusing [EdgeLabelGeometry] (same path
    // C4/UML edges take) puts the label in the open whitespace beside the long
    // vertical run with proper clearance.
    val flowName = flow.name
    if (!flowName.isNullOrBlank()) {
        val anchor = EdgeLabelGeometry.midAnchor(route)
        val (labelX, labelY, textAnchor) =
            when (anchor.direction) {
                EdgeLabelGeometry.SegmentDirection.Horizontal ->
                    // Label centred a few px above the horizontal segment.
                    Triple(anchor.x, anchor.y - 4f, "middle")
                EdgeLabelGeometry.SegmentDirection.Vertical ->
                    // Label to the right of the vertical segment, left-aligned,
                    // with enough gap that the halo edge clears the line.
                    Triple(anchor.x + 10f, anchor.y + 4f, "start")
            }
        builder.renderEdgeLabelWithHalo(flowName, labelX, labelY, textAnchor)
    }
}

private fun buildPolyline(points: List<dev.kuml.layout.Point>): String {
    if (points.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("M ${fmtF(points.first().x)} ${fmtF(points.first().y)}")
    points.drop(1).forEach { sb.append(" L ${fmtF(it.x)} ${fmtF(it.y)}") }
    return sb.toString()
}

private fun fmtF(v: Float): String = fmt2(v)
