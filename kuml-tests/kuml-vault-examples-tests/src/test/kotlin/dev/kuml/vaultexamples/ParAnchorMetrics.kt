@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One finding of [ParAnchorMetrics]: [kind] is P1, P2 or P3, [valuePx] the overlap length (P1) or the gap (P2, P3). */
internal data class ParHit(
    val kind: String,
    val edgeId: String,
    val other: String,
    val valuePx: Double,
    val distPx: Double,
) {
    override fun toString(): String =
        "$kind $edgeId vs $other value=${"%.1f".format(
            java.util.Locale.ROOT,
            valuePx,
        )} dist=${"%.1f".format(java.util.Locale.ROOT, distPx)}"
}

internal data class ParAnchorReport(
    val p1: List<ParHit>,
    val p2: List<ParHit>,
    val p3: List<ParHit>,
) {
    val total: Int get() = p1.size + p2.size + p3.size

    override fun toString(): String = "P1=${p1.size} P2=${p2.size} P3=${p3.size}"

    fun details(): List<String> = (p1 + p2 + p3).map { it.toString() }
}

/**
 * PAL Welle X8 detector: how the anchors of parametric binding lines sit at the node frame, measured on the rendered SVG with
 * rectangles and polylines only (never glyph metrics, so it does not depend on fonts or on the platform).
 *
 * - P1 a bound polyline runs along a frame side of one of its own end boxes (the box at which an end of the line sits) or of the
 *   owner composite (the smallest box enclosing the end box) closer than [P1_MAX_DIST] px, over at least [P1_MIN_RUN] px. Foreign
 *   boxes do not count; the pin squares are no frame.
 * - P2 two edge label rectangles are closer than [P2_MIN_GAP] px.
 * - P3 an edge label rectangle is closer than [P3_MIN_GAP] px to a segment of its own edge.
 */
internal object ParAnchorMetrics {
    const val P1_MIN_RUN = 24.0
    const val P1_MAX_DIST = 4.0
    const val P2_MIN_GAP = 6.0
    const val P3_MIN_GAP = 6.0

    /** A pin sits on the box side and its outer edge is up to this far away from the end point of the line. */
    private const val END_TOL = 5.0
    private const val EPS = 1e-6

    private data class Box(
        val id: String,
        val rect: SvgRect,
    )

    private val boxRe = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"""")
    private val edgeStart = Regex("""<g id="([^"]+)">\s*(?=<(?:path|polyline)[^>]*class="kuml-edge")""")
    private val labelRe = Regex("""<g class="kuml-edge-label"[^>]*?data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""")

    private fun boxes(svg: String): List<Box> =
        SvgGeometry.nodes(svg).mapNotNull { n ->
            val m = boxRe.find(n.body) ?: return@mapNotNull null
            Box(n.id, SvgRect(n.tx, n.ty, m.groupValues[1].toDouble(), m.groupValues[2].toDouble()))
        }

    private fun pointGap(
        p: Pair<Double, Double>,
        r: SvgRect,
    ): Double {
        val dx = max(0.0, max(r.x - p.first, p.first - r.right))
        val dy = max(0.0, max(r.y - p.second, p.second - r.bottom))
        return kotlin.math.hypot(dx, dy)
    }

    private fun encloses(
        outer: SvgRect,
        inner: SvgRect,
    ): Boolean =
        outer.x <= inner.x + EPS &&
            outer.y <= inner.y + EPS &&
            outer.right >= inner.right - EPS &&
            outer.bottom >= inner.bottom - EPS &&
            outer.w * outer.h > inner.w * inner.h + EPS

    /** The end box (smallest box near [p]) and the smallest box enclosing it, if any. */
    private fun frameBoxes(
        all: List<Box>,
        p: Pair<Double, Double>,
    ): List<Box> {
        val end = all.filter { pointGap(p, it.rect) <= END_TOL }.minByOrNull { it.rect.w * it.rect.h } ?: return emptyList()
        val owner = all.filter { encloses(it.rect, end.rect) }.minByOrNull { it.rect.w * it.rect.h }
        return listOfNotNull(end, owner)
    }

    private fun overlap(
        a0: Double,
        a1: Double,
        b0: Double,
        b1: Double,
    ): Double = min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1))

    private fun p1(
        all: List<Box>,
        edgeId: String,
        poly: List<Pair<Double, Double>>,
        out: MutableList<ParHit>,
    ) {
        if (poly.size < 2) return
        val frames = (frameBoxes(all, poly.first()) + frameBoxes(all, poly.last())).distinctBy { it.id }
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            val horizontal = abs(a.second - b.second) < EPS && abs(a.first - b.first) > EPS
            val vertical = abs(a.first - b.first) < EPS && abs(a.second - b.second) > EPS
            for (f in frames) {
                val r = f.rect
                if (horizontal) {
                    for ((name, sy) in listOf("top" to r.y, "bottom" to r.bottom)) {
                        val d = abs(a.second - sy)
                        val run = overlap(a.first, b.first, r.x, r.right)
                        if (d < P1_MAX_DIST - EPS && run >= P1_MIN_RUN - EPS) out += ParHit("P1", edgeId, "${f.id}:$name", run, d)
                    }
                } else if (vertical) {
                    for ((name, sx) in listOf("left" to r.x, "right" to r.right)) {
                        val d = abs(a.first - sx)
                        val run = overlap(a.second, b.second, r.y, r.bottom)
                        if (d < P1_MAX_DIST - EPS && run >= P1_MIN_RUN - EPS) out += ParHit("P1", edgeId, "${f.id}:$name", run, d)
                    }
                }
            }
        }
    }

    fun measure(svg: String): ParAnchorReport {
        val all = boxes(svg)
        val polylines = SvgGeometry.edgePolylines(svg)
        val p1 = ArrayList<ParHit>()
        val p2 = ArrayList<ParHit>()
        val p3 = ArrayList<ParHit>()
        for ((id, poly) in polylines) p1(all, id, poly, p1)
        val starts = edgeStart.findAll(svg).toList()
        val labels = ArrayList<Pair<String, SvgRect>>()
        for ((i, m) in starts.withIndex()) {
            val id = m.groupValues[1]
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else svg.length
            val body = svg.substring(m.range.last + 1, end)
            for (l in labelRe.findAll(body)) {
                val v = l.groupValues
                val r = SvgRect(v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble())
                labels += id to r
                val poly = polylines[id] ?: continue
                val d = SvgGeometry.distance(poly, r)
                if (d < P3_MIN_GAP - EPS) p3 += ParHit("P3", id, "own-edge", d, d)
            }
        }
        for (i in labels.indices) {
            for (j in i + 1 until labels.size) {
                val g = labels[i].second.gapTo(labels[j].second)
                if (g < P2_MIN_GAP - EPS) p2 += ParHit("P2", labels[i].first, labels[j].first, g, g)
            }
        }
        return ParAnchorReport(p1, p2, p3)
    }
}
