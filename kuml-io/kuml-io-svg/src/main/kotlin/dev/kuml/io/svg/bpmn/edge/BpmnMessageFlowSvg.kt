package dev.kuml.io.svg.bpmn.edge

import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeContent
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
    val labelColor = theme.colors.muted.toHex()
    val fontFamily = theme.typography.body.family

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

    // Optional label at the midpoint of the route
    val flowName = flow.name
    if (!flowName.isNullOrBlank()) {
        val midX: Float
        val midY: Float
        when (route) {
            is EdgeRoute.OrthogonalRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                val mid = allPts[allPts.size / 2]
                midX = mid.x
                midY = mid.y
            }

            is EdgeRoute.TreeRounded -> {
                val allPts = listOf(src) + route.waypoints + listOf(tgt)
                val mid = allPts[allPts.size / 2]
                midX = mid.x
                midY = mid.y
            }

            else -> {
                midX = (src.x + tgt.x) / 2f
                midY = (src.y + tgt.y) / 2f
            }
        }
        builder.rawXml(
            """<text x="${fmtF(midX + 4f)}" y="${fmtF(midY - 4f)}" """ +
                """font-family="$fontFamily" font-size="10" fill="$labelColor">${xmlEscapeContent(flowName)}</text>""",
        )
    }
}

private fun buildPolyline(points: List<dev.kuml.layout.Point>): String {
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
