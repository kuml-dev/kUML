@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * PAL Welle X3: geometry detectors for SysML 2 activity diagrams, measured on SVG rectangles and polylines only (no
 * glyph metrics, no byte comparison).
 *
 * - A1: two nodes closer than 8 px (touching or overlapping counts).
 * - A2: a flow runs 8 px or more inside (or within 4 px of) a foreign node; the first and last 12 px of a route are exempt.
 * - A3: an object flow label sits farther than 16 px from its own line or overlaps a foreign node.
 * - A4: SVG `width` / `height` deviate from the `viewBox`, or the PNG height differs by more than 1 px from the aspect ratio.
 * - A5: a free note (no anchor link) leaves the canvas or sits closer than 12 px to a node, a flow or a lane boundary.
 */
internal object ActGeometryClearance {
    const val NODE_GAP = 8.0
    const val EDGE_CLEAR = 4.0
    const val EDGE_RUN = 8.0
    const val ROUTE_END = 12.0
    const val LABEL_MAX = 16.0
    const val NOTE_CLEAR = 12.0

    class Measure(
        val counts: Map<String, List<String>>,
        /** Number of notes without an anchor link (free notes / legends), measured by A5. */
        val freeNotes: Int = 0,
    )

    private class N(
        val id: String,
        val r: SvgRect,
    )

    private fun bbox(pts: List<Pair<Double, Double>>): SvgRect {
        val x0 = pts.minOf { it.first }
        val y0 = pts.minOf { it.second }
        return SvgRect(x0, y0, pts.maxOf { it.first } - x0, pts.maxOf { it.second } - y0)
    }

    /** Bounding box (local coordinates) of the first shape of a node group body. */
    private fun shapeBox(body: String): SvgRect? {
        val first = Regex("""<(polygon|circle|rect|path|ellipse)\b[^>]*>""").find(body) ?: return null
        val tag = first.value
        return when (first.groupValues[1]) {
            "polygon" ->
                Regex("""points="([^"]+)"""").find(tag)?.let { m ->
                    bbox(
                        SvgGeometry.numbers(m.groupValues[1]).chunked(2).map {
                            it[0] to
                                it[1]
                        },
                    )
                }
            "circle" -> {
                val cx = Regex("""\bcx="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                val cy = Regex("""\bcy="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                val r = Regex("""\br="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                SvgRect(cx - r, cy - r, 2 * r, 2 * r)
            }
            "rect" -> {
                fun a(n: String) =
                    Regex("""\b$n="([-0-9.]+)"""")
                        .find(tag)
                        ?.groupValues
                        ?.get(1)
                        ?.toDouble() ?: 0.0
                SvgRect(a("x"), a("y"), a("width"), a("height"))
            }
            "path" ->
                Regex("""\bd="([^"]+)"""").find(tag)?.let { m ->
                    SvgGeometry.pathPoints(m.groupValues[1]).takeIf { it.size >= 2 }?.let(::bbox)
                }
            else -> null
        }
    }

    private fun route(svg: String): Map<String, List<Pair<Double, Double>>> {
        val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
        for (m in Regex("""<g id="((?:controlFlow|objectFlow|exceptionHandler)[^"]*)">\s*<(path|line|polyline)\b([^>]*)>""").findAll(svg)) {
            val tag = m.groupValues[3]
            val pts =
                when (m.groupValues[2]) {
                    "path" -> Regex("""\bd="([^"]+)"""").find(tag)?.let { SvgGeometry.pathPoints(it.groupValues[1]) }
                    "line" -> {
                        val v =
                            listOf("x1", "y1", "x2", "y2").map { n ->
                                Regex("""\b$n="([-0-9.]+)"""")
                                    .find(tag)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toDouble()
                            }
                        if (v.any { it == null }) null else listOf(v[0]!! to v[1]!!, v[2]!! to v[3]!!)
                    }
                    else ->
                        Regex("""points="([^"]+)"""").find(tag)?.let { s ->
                            SvgGeometry.numbers(s.groupValues[1]).chunked(2).map {
                                it[0] to
                                    it[1]
                            }
                        }
                }
            if (pts != null && pts.size >= 2) out[m.groupValues[1]] = pts
        }
        return out
    }

    private fun endpoints(id: String): Set<String> = id.substringAfter(":").split("::").toSet()

    fun measure(svg: String): Measure {
        val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg)
        val vbW = vb?.groupValues?.get(1)?.toDouble() ?: 0.0
        val vbH = vb?.groupValues?.get(2)?.toDouble() ?: 0.0
        val nodes = ArrayList<N>()
        val notes = ArrayList<N>()
        val lanes = ArrayList<SvgRect>()
        for (n in SvgGeometry.nodes(svg)) {
            if (n.id.startsWith("activityPartitionHeader:") || n.id == "kuml-diagram-frame") continue
            val sb = shapeBox(n.body) ?: continue
            val abs = sb.translated(n.tx, n.ty)
            val firstTag = Regex("""<(?:polygon|circle|rect|path|ellipse)\b[^>]*>""").find(n.body)?.value.orEmpty()
            when {
                n.id.startsWith("activityPartition:") -> lanes += abs
                firstTag.contains("kuml-comment") -> notes += N(n.id, abs)
                else -> nodes += N(n.id, abs)
            }
        }
        val routes = route(svg)
        val a1 = ArrayList<String>()
        val a2 = ArrayList<String>()
        val a3 = ArrayList<String>()
        val a5 = ArrayList<String>()

        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val g = nodes[i].r.gapTo(nodes[j].r)
                if (g < NODE_GAP) a1 += "${nodes[i].id} / ${nodes[j].id} gap=${"%.1f".format(g)}"
            }
        }

        val flowRoutes = routes.filterKeys { it.startsWith("controlFlow:") || it.startsWith("objectFlow:") }
        for ((id, pts) in flowRoutes) {
            val own = endpoints(id)
            val len = pts.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
            for (nd in nodes) {
                if (nd.id in own) continue
                val box = SvgRect(nd.r.x - EDGE_CLEAR, nd.r.y - EDGE_CLEAR, nd.r.w + 2 * EDGE_CLEAR, nd.r.h + 2 * EDGE_CLEAR)
                var run = 0.0
                var maxRun = 0.0
                var at = 0.0
                for ((a, b) in pts.zipWithNext()) {
                    val l = hypot(b.first - a.first, b.second - a.second)
                    val steps = max(1, l.toInt())
                    for (k in 0 until steps) {
                        val t = k.toDouble() / steps
                        val pos = at + l * t
                        val x = a.first + (b.first - a.first) * t
                        val y = a.second + (b.second - a.second) * t
                        val inside = x > box.x && x < box.right && y > box.y && y < box.bottom && pos >= ROUTE_END && pos <= len - ROUTE_END
                        if (inside) {
                            run += l / steps
                            maxRun = max(maxRun, run)
                        } else {
                            run = 0.0
                        }
                    }
                    at += l
                }
                if (maxRun >= EDGE_RUN) a2 += "$id crosses ${nd.id} for ${"%.1f".format(maxRun)} px"
            }
        }

        for ((id, rect) in SvgGeometry.labelRects(svg, "kuml-edge-label")) {
            if (!id.startsWith("objectFlow:")) continue
            val own = routes[id]
            if (own == null) {
                a3 += "$id: label without own line"
                continue
            }
            val d = SvgGeometry.distance(own, rect)
            if (d > LABEL_MAX) a3 += "$id: label ${"%.1f".format(d)} px from its line"
            val ends = endpoints(id)
            for (nd in nodes) {
                if (nd.id in ends) continue
                if (rect.overlaps(nd.r)) a3 += "$id: label overlaps ${nd.id}"
            }
        }

        val linked = Regex("""<g id="note-link:([^":]+)::""").findAll(svg).map { it.groupValues[1] }.toSet()
        val canvas = SvgRect(0.0, 0.0, vbW, vbH)
        for (nt in notes) {
            if (nt.id in linked) continue
            if (!canvas.contains(nt.r, 0.0)) a5 += "${nt.id}: outside the canvas"
            for (nd in nodes) if (nt.r.gapTo(nd.r) < NOTE_CLEAR) a5 += "${nt.id}: ${"%.1f".format(nt.r.gapTo(nd.r))} px to node ${nd.id}"
            for ((id, pts) in routes) {
                val d = SvgGeometry.distance(pts, nt.r)
                if (d < NOTE_CLEAR) a5 += "${nt.id}: ${"%.1f".format(d)} px to flow $id"
            }
            for (l in lanes) {
                val yOver = nt.r.y < l.bottom + NOTE_CLEAR && nt.r.bottom > l.y - NOTE_CLEAR
                if (yOver) {
                    for (bx in listOf(l.x, l.right)) {
                        if (nt.r.x - NOTE_CLEAR < bx &&
                            bx < nt.r.right + NOTE_CLEAR
                        ) {
                            a5 +=
                                "${nt.id}: within ${NOTE_CLEAR.toInt()} px of lane boundary x=${"%.1f".format(bx)}"
                        }
                    }
                }
            }
        }
        return Measure(counts = mapOf("A1" to a1, "A2" to a2, "A3" to a3, "A5" to a5), freeNotes = notes.count { it.id !in linked })
    }

    /** A4 for the SVG part: `width` / `height` against the `viewBox`. */
    fun svgSizeDeviation(svg: String): Double {
        val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg) ?: return 0.0
        val w =
            Regex("""<svg[^>]*\swidth="([-0-9.]+)"""")
                .find(svg)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: return 0.0
        val h =
            Regex("""<svg[^>]*\sheight="([-0-9.]+)"""")
                .find(svg)
                ?.groupValues
                ?.get(1)
                ?.toDouble() ?: return 0.0
        return max(abs(w - vb.groupValues[1].toDouble()), abs(h - vb.groupValues[2].toDouble()))
    }

    /** A4 for the PNG part: pixel height minus the height the aspect ratio of the `viewBox` demands (width fixed). */
    fun pngHeightDeviation(
        svg: String,
        widthPx: Int = 1200,
    ): Int {
        val vb = Regex("""viewBox="0 0 ([-0-9.]+) ([-0-9.]+)"""").find(svg) ?: return 0
        val expected = Math.round(widthPx * vb.groupValues[2].toDouble() / vb.groupValues[1].toDouble()).toInt()
        val bytes =
            dev.kuml.io.png.KumlPngRenderer
                .toPng(
                    svg = svg,
                    options =
                        dev.kuml.io.png
                            .PngRenderOptions(widthPx = widthPx),
                )
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        check(img.width == widthPx) { "PNG width ${img.width} != $widthPx" }
        return img.height - expected
    }
}
