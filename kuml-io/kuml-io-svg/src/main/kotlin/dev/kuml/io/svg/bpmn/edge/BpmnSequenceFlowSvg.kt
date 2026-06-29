package dev.kuml.io.svg.bpmn.edge

import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.layout.EdgeRoute
import dev.kuml.renderer.theme.core.KumlTheme
import kotlin.math.sqrt

/**
 * Rendert einen [SequenceFlow] als BPMN-Sequence-Flow-Pfeil.
 *
 * - Normaler Flow: durchgezogene Linie mit gefülltem Pfeilkopf
 * - Default-Flow: Schrägstrich nahe dem Quellknoten
 * - Benannter Flow: Label an der Mitte der Route
 *
 * V3.1.3 — BPMN Process SVG-Renderer
 */
internal fun renderBpmnSequenceFlow(
    flow: SequenceFlow,
    route: EdgeRoute,
    builder: SvgBuilder,
    theme: KumlTheme,
) {
    val src = route.source
    val tgt = route.target

    val edgeColor = theme.colors.edge.toHex()
    val labelColor = theme.colors.muted.toHex()
    val fontFamily = theme.typography.body.family

    // Pfad-Daten
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

    // Marker-ID eindeutig per Flow-ID
    val safeId = flow.id.replace(Regex("[^a-zA-Z0-9]"), "_")
    val markerId = "bpmn-seq-arrow-$safeId"

    // Pfeilkopf-Definition
    builder.rawXml(
        """<defs><marker id="$markerId" markerWidth="8" markerHeight="6" """ +
            """refX="7" refY="3" orient="auto">""" +
            """<polygon points="0,0 8,3 0,6" fill="$edgeColor"/></marker></defs>""",
    )

    // Sequence-Flow-Linie
    builder.rawXml(
        """<path d="$pathD" fill="none" stroke="$edgeColor" stroke-width="1.5" """ +
            """marker-end="url(#$markerId)"/>""",
    )

    // Default-Flow: Schrägstrich nahe dem Quellknoten
    if (flow.isDefault) {
        val p0 = src
        val p1 =
            when (route) {
                is EdgeRoute.OrthogonalRounded -> route.waypoints.firstOrNull() ?: tgt
                is EdgeRoute.TreeRounded -> route.waypoints.firstOrNull() ?: tgt
                else -> tgt
            }
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.001f) {
            val nx = -dy / len
            val ny = dx / len
            val mx = p0.x + dx * 0.15f
            val my = p0.y + dy * 0.15f
            builder.rawXml(
                """<line x1="${fmtF(mx - nx * 5f)}" y1="${fmtF(my - ny * 5f)}" """ +
                    """x2="${fmtF(mx + nx * 5f)}" y2="${fmtF(my + ny * 5f)}" """ +
                    """stroke="$edgeColor" stroke-width="1.5"/>""",
            )
        }
    }

    // Condition-Label (Name des Flows)
    val label = flow.name
    if (!label.isNullOrBlank()) {
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
        builder.tag(
            "text",
            mapOf(
                "x" to fmtF(midX + 4f),
                "y" to fmtF(midY - 4f),
                "font-family" to fontFamily,
                "font-size" to "10",
                "fill" to labelColor,
            ),
        ) { text(label) }
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
