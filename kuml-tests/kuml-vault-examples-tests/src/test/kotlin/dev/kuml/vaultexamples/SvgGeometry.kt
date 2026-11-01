@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned rectangle read from a rendered SVG (PAL wave 2C-b2b tests work on the SVG, never on the layout result). */
internal data class SvgRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val cx: Double get() = x + w / 2
    val cy: Double get() = y + h / 2

    fun translated(
        dx: Double,
        dy: Double,
    ) = SvgRect(x + dx, y + dy, w, h)

    /** Shortest distance between the two rectangles; 0 when they touch or overlap. */
    fun gapTo(o: SvgRect): Double {
        val dx = max(0.0, max(o.x - right, x - o.right))
        val dy = max(0.0, max(o.y - bottom, y - o.bottom))
        return hypot(dx, dy)
    }

    /** True when the interiors overlap by more than [eps]. */
    fun overlaps(
        o: SvgRect,
        eps: Double = 0.01,
    ): Boolean = x + eps < o.right && o.x + eps < right && y + eps < o.bottom && o.y + eps < bottom

    fun contains(
        o: SvgRect,
        margin: Double,
    ): Boolean =
        o.x - x >= margin - 0.01 && o.y - y >= margin - 0.01 && right - o.right >= margin - 0.01 && bottom - o.bottom >= margin - 0.01
}

/** One node group of the SVG: id, absolute origin and the text of the group up to the next node group. */
internal class SvgNode(
    val id: String,
    val tx: Double,
    val ty: Double,
    val body: String,
)

internal object SvgGeometry {
    private val num = Regex("-?\\d+(?:\\.\\d+)?")

    fun numbers(s: String): List<Double> = num.findAll(s).map { it.value.toDouble() }.toList()

    /** Vertices of an SVG path made of M, L and A commands (an arc contributes its end point). */
    fun pathPoints(d: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (m in Regex("([MLA])([^MLA]*)").findAll(d)) {
            val n = numbers(m.groupValues[2])
            if (n.size >= 2) out += n[n.size - 2] to n[n.size - 1]
        }
        return out
    }

    /** Node groups (`<g id=".." transform="translate(x,y)">`), each with the text up to the next node group. */
    fun nodes(svg: String): List<SvgNode> {
        val head = Regex("""<g id="([^"]+)" transform="translate\(([-0-9.]+),([-0-9.]+)\)">""").findAll(svg).toList()
        return head.mapIndexed { i, m ->
            val end = if (i + 1 < head.size) head[i + 1].range.first else svg.length
            SvgNode(m.groupValues[1], m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), svg.substring(m.range.last, end))
        }
    }

    /** The label rectangles (`data-label-rect`) of the groups whose tag contains [classPart], in the coordinates of the text. */
    fun labelRects(
        text: String,
        classPart: String,
    ): List<Pair<String, SvgRect>> =
        Regex("""<g class="([^"]*)"(?: data-label-id="([^"]*)")? data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""")
            .findAll(text)
            .filter { it.groupValues[1].contains(classPart) }
            .map {
                val v = it.groupValues
                v[2] to SvgRect(v[3].toDouble(), v[4].toDouble(), v[5].toDouble(), v[6].toDouble())
            }.toList()

    /** Polylines of all `<g id="..."><path d=".." class="kuml-edge"/>` edges, by group id. */
    fun edgePolylines(svg: String): Map<String, List<Pair<Double, Double>>> {
        val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
        for (m in Regex("""<g id="([^"]+)">\s*<path d="([^"]+)" class="kuml-edge"""").findAll(svg)) {
            out[m.groupValues[1]] =
                pathPoints(m.groupValues[2])
        }
        for (m in Regex("""<g id="([^"]+)">\s*<polyline points="([^"]+)"[^>]*kuml-edge""").findAll(svg)) {
            val n = numbers(m.groupValues[2])
            out[m.groupValues[1]] = n.chunked(2).map { it[0] to it[1] }
        }
        return out
    }

    /** Shortest distance between the polyline [pts] and [r]; 0 when they touch or cross. */
    fun distance(
        pts: List<Pair<Double, Double>>,
        r: SvgRect,
    ): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) best = min(best, segmentDistance(pts[i], pts[i + 1], r))
        return best
    }

    private fun segmentDistance(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        r: SvgRect,
    ): Double {
        // Sampled: segments are axis parallel and short; 0.25 px steps are exact enough for 2 px clearances.
        val len = hypot(b.first - a.first, b.second - a.second)
        val steps = max(1, (len / 0.25).toInt())
        var best = Double.MAX_VALUE
        for (k in 0..steps) {
            val x = a.first + (b.first - a.first) * k / steps
            val y = a.second + (b.second - a.second) * k / steps
            val dx = max(0.0, max(r.x - x, x - r.right))
            val dy = max(0.0, max(r.y - y, y - r.bottom))
            best = min(best, hypot(dx, dy))
        }
        return best
    }

    /** Bounding boxes of all `<polygon points="..">` of the edge group [id] (arrow heads). */
    fun arrowBoxes(
        svg: String,
        id: String,
    ): List<SvgRect> {
        val start = svg.indexOf("<g id=\"$id\"")
        if (start < 0) return emptyList()
        val end = svg.indexOf("</g>", start)
        return Regex("""<polygon points="([^"]+)"""")
            .findAll(svg.substring(start, end))
            .map { m ->
                val n = numbers(m.groupValues[1]).chunked(2)
                val xs = n.map { it[0] }
                val ys = n.map { it[1] }
                SvgRect(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
            }.toList()
    }
}
