@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.max
import kotlin.math.min

/**
 * PAL Welle X1 detector: how much room a label of an STM or BDD edge keeps from its own edge. It works on the rendered SVG
 * (label rectangles `data-label-rect`, edge polylines, arrowhead paths and the multiplicity texts), with heuristic text
 * extents only, never on glyph outlines, so the result does not depend on fonts or on the platform.
 *
 * Definitions (eps = [EPS] px):
 * - E1: the distance between a label rectangle and the polyline of its own edge is below [MIN_GAP] (touching is 0).
 * - E2: that distance is above [MAX_GAP].
 * - E3: the rectangle of a multiplicity text overlaps the bounding box of an arrowhead (or other end marker) of its own
 *   edge, the box inflated by [MARKER_INFLATE] px.
 */
object EdgeLabelClearance {
    const val EPS: Double = 0.5
    const val MIN_GAP: Double = 6.0
    const val MAX_GAP: Double = 16.0
    const val MARKER_INFLATE: Double = 2.0

    /** Heuristic width of one multiplicity character at the 9 px edge font. */
    const val MULT_CHAR_PX: Double = 5.0
    const val MULT_ASCENT_PX: Double = 7.0
    const val MULT_DESCENT_PX: Double = 2.0

    data class Counts(
        val e1: Int,
        val e2: Int,
        val e3: Int,
        val details: List<String>,
    ) {
        val sum: Int get() = e1 + e2 + e3

        override fun toString(): String = "E1=$e1 E2=$e2 E3=$e3"
    }

    private val groupStart =
        Regex("""<g id="([^"]+)">\s*(?=<(?:line|path|polyline)[^>]*class="kuml-edge")""")
    private val lineRe = Regex("""<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-edge"""")
    private val pathRe = Regex("""<path d="([^"]*)" class="kuml-edge"""")
    private val markerPathRe = Regex("""<path d="([^"]*)"(?![^>]*class="kuml-edge)[^>]*/>""")
    private val polygonRe = Regex("""<polygon points="([^"]*)"""")
    private val labelRe = Regex("""<g class="kuml-edge-label" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""")
    private val labelBlockRe = Regex("""<g class="kuml-edge-label".*?</g>""", RegexOption.DOT_MATCHES_ALL)
    private val multRe =
        Regex("""<text class="kuml-body" x="([-0-9.]+)" y="([-0-9.]+)" text-anchor="(start|end|middle)">\s*([^<]*?)\s*</text>""")
    private val globalLabelRe =
        Regex(
            """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
        )

    /** Measures one rendered diagram. [name] only prefixes the detail lines. */
    fun measure(
        name: String,
        svg: String,
    ): Counts {
        val starts = groupStart.findAll(svg).toList()
        val details = ArrayList<String>()
        var e1 = 0
        var e2 = 0
        var e3 = 0
        val globalLabels = globalLabelRe.findAll(svg).groupBy({ it.groupValues[1] }) { rectOf(it, 2) }
        for ((i, m) in starts.withIndex()) {
            val id = m.groupValues[1]
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else svg.length
            val body = svg.substring(m.range.last + 1, end)
            val poly = polylineOf(body) ?: continue
            val labels = labelRe.findAll(body).map { rectOf(it, 1) }.toList() + (globalLabels[id] ?: emptyList())
            for (r in labels) {
                val d = SvgGeometry.distance(poly, r)
                if (d < MIN_GAP - EPS) {
                    e1++
                    details += "$name $id E1 d=${"%.1f".format(d)} rect=$r"
                }
                if (d > MAX_GAP + EPS) {
                    e2++
                    details += "$name $id E2 d=${"%.1f".format(d)} rect=$r"
                }
            }
            val markers = markersOf(body)
            val stripped = body.replace(labelBlockRe, "")
            for (t in multRe.findAll(stripped)) {
                val text = t.groupValues[4]
                if (text.isEmpty()) continue
                val w = text.length * MULT_CHAR_PX
                val x = t.groupValues[1].toDouble()
                val y = t.groupValues[2].toDouble()
                val left =
                    when (t.groupValues[3]) {
                        "start" -> x
                        "end" -> x - w
                        else -> x - w / 2
                    }
                val rect = SvgRect(left, y - MULT_ASCENT_PX, w, MULT_ASCENT_PX + MULT_DESCENT_PX)
                for (mk in markers) {
                    if (rect.overlaps(inflate(mk, MARKER_INFLATE), eps = 0.0)) {
                        e3++
                        details += "$name $id E3 mult='$text' rect=$rect marker=$mk"
                        break
                    }
                }
            }
        }
        return Counts(e1, e2, e3, details)
    }

    private fun rectOf(
        m: MatchResult,
        from: Int,
    ) = SvgRect(
        m.groupValues[from].toDouble(),
        m.groupValues[from + 1].toDouble(),
        m.groupValues[from + 2].toDouble(),
        m.groupValues[
            from +
                3,
        ].toDouble(),
    )

    private fun inflate(
        r: SvgRect,
        d: Double,
    ) = SvgRect(r.x - d, r.y - d, r.w + 2 * d, r.h + 2 * d)

    private fun polylineOf(body: String): List<Pair<Double, Double>>? {
        val l = lineRe.find(body)
        val p = pathRe.find(body)
        if (l != null && (p == null || l.range.first < p.range.first)) {
            val v = l.groupValues
            return listOf(v[1].toDouble() to v[2].toDouble(), v[3].toDouble() to v[4].toDouble())
        }
        return p?.let { SvgGeometry.pathPoints(it.groupValues[1]) }
    }

    private fun markersOf(body: String): List<SvgRect> {
        val out = ArrayList<SvgRect>()
        val noLabels = body.replace(labelBlockRe, "")
        for (m in markerPathRe.findAll(noLabels)) bbox(SvgGeometry.pathPoints(m.groupValues[1]))?.let { out += it }
        for (m in polygonRe.findAll(noLabels)) {
            val n = SvgGeometry.numbers(m.groupValues[1])
            bbox(n.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] })?.let { out += it }
        }
        return out
    }

    private fun bbox(pts: List<Pair<Double, Double>>): SvgRect? {
        if (pts.size < 2) return null
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for ((x, y) in pts) {
            x0 = min(x0, x)
            y0 = min(y0, y)
            x1 = max(x1, x)
            y1 = max(y1, y)
        }
        return SvgRect(x0, y0, x1 - x0, y1 - y0)
    }
}
