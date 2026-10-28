package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Wave C2b — font-independent visual invariants of the SysML 2 REQ / UC / ACT examples 46 to 49 (and
 * the swimlane example 07), checked on the SVG geometry only (rects and path polylines, no text metrics).
 */
class Sysml2C2bVisualInvariantsTest :
    FunSpec({
        data class P(
            val x: Float,
            val y: Float,
        )

        data class R(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
        )

        val cache = HashMap<String, String>()

        fun svgOf(prefix: String): String =
            cache.getOrPut(prefix) {
                val ex = VaultExampleLoader.loadFromClasspath().first { it.fileName.startsWith(prefix) }
                val r = VaultExampleRenderer.render(script = ex.kumlScript)
                r.error shouldBe null
                SampleOutput.write(filename = "c2b/${ex.sanitizedName}.svg", content = r.svg!!)
                r.svg!!
            }

        val num = Regex("-?\\d+(?:\\.\\d+)?")

        /** Parses a path `d` into its corner polyline; arc corners are restored to the sharp corner. */
        fun pathPoints(d: String): List<P> {
            val pts = ArrayList<P>()
            val tokens = Regex("[MLAQC]|-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value }.toList()
            var i = 0
            var cmd = 'M'
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.length == 1 && t[0].isLetter()) {
                    cmd = t[0]
                    i++
                    continue
                }
                when (cmd) {
                    'M', 'L' -> {
                        pts += P(tokens[i].toFloat(), tokens[i + 1].toFloat())
                        i += 2
                    }
                    'A' -> {
                        val after = P(tokens[i + 5].toFloat(), tokens[i + 6].toFloat())
                        val before = pts.removeLast()
                        val prev = pts.lastOrNull() ?: before
                        val horizontalPrev = abs(before.x - prev.x) > abs(before.y - prev.y)
                        pts += if (horizontalPrev) P(after.x, before.y) else P(before.x, after.y)
                        i += 7
                    }
                    else -> i++
                }
            }
            return pts
        }

        fun edges(svg: String): Map<String, List<P>> {
            val out = LinkedHashMap<String, List<P>>()
            val start = svg.indexOf("<g id=\"edges\"")
            val body = svg.substring(start)
            for (m in Regex("<g id=\"([^\"]+)\">\\s*<(path|polyline|line)([^>]*)class=\"kuml-edge[^\"]*\"([^>]*)/>").findAll(body)) {
                val attrs = m.groupValues[3] + m.groupValues[4]
                val pts =
                    when (m.groupValues[2]) {
                        "path" -> pathPoints(Regex("d=\"([^\"]+)\"").find(attrs)!!.groupValues[1])
                        "polyline" ->
                            num
                                .findAll(Regex("points=\"([^\"]+)\"").find(attrs)!!.groupValues[1])
                                .map { it.value.toFloat() }
                                .toList()
                                .chunked(2)
                                .map { P(it[0], it[1]) }
                        else -> {
                            fun a(n: String) = Regex("$n=\"(-?[0-9.]+)\"").find(attrs)!!.groupValues[1].toFloat()
                            listOf(P(a("x1"), a("y1")), P(a("x2"), a("y2")))
                        }
                    }
                out[m.groupValues[1].replace("&amp;", "&")] = pts
            }
            return out
        }

        fun labels(svg: String): Map<String, R> =
            Regex(
                "<g class=\"kuml-edge-label\" data-edge-id=\"([^\"]+)\">\\s*<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"",
            ).findAll(svg)
                .associate {
                    it.groupValues[1] to
                        R(
                            it.groupValues[2].toFloat(),
                            it.groupValues[3].toFloat(),
                            it.groupValues[4].toFloat(),
                            it.groupValues[5].toFloat(),
                        )
                }

        fun segHitsRect(
            a: P,
            b: P,
            r: R,
        ): Boolean {
            var t0 = 0f
            var t1 = 1f
            val dx = b.x - a.x
            val dy = b.y - a.y

            fun clip(
                p: Float,
                q: Float,
            ): Boolean {
                if (abs(p) < 1e-6f) return q >= 0f
                val t = q / p
                if (p < 0f) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
                return true
            }
            return clip(-dx, a.x - r.x) && clip(dx, r.x + r.w - a.x) && clip(-dy, a.y - r.y) && clip(dy, r.y + r.h - a.y)
        }

        fun distToPolyline(
            p: P,
            pts: List<P>,
        ): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
                val ex = a.x + t * dx - p.x
                val ey = a.y + t * dy - p.y
                best = min(best, sqrt(ex * ex + ey * ey))
            }
            return best
        }

        fun partitions(svg: String): Map<String, R> =
            Regex(
                "<g id=\"activityPartition:([^\"]+)\" transform=\"translate\\(([-0-9.]+),([-0-9.]+)\\)\">\\s*<rect width=\"([-0-9.]+)\" height=\"([-0-9.]+)\"",
            ).findAll(svg)
                .associate {
                    it.groupValues[1] to
                        R(
                            it.groupValues[2].toFloat(),
                            it.groupValues[3].toFloat(),
                            it.groupValues[4].toFloat(),
                            it.groupValues[5].toFloat(),
                        )
                }

        test("1. swimlane partitions never overlap and keep 16 px apart (07 SysML, 48)") {
            for (prefix in listOf("07 SysML", "48")) {
                val ps = partitions(svgOf(prefix)).entries.toList()
                for (i in ps.indices) {
                    for (j in i + 1 until ps.size) {
                        val a = ps[i].value
                        val b = ps[j].value
                        val gapX = max(a.x - (b.x + b.w), b.x - (a.x + a.w))
                        val gapY = max(a.y - (b.y + b.h), b.y - (a.y + a.h))
                        val gap = max(gapX, gapY)
                        check(gap >= 16f) { "$prefix: ${ps[i].key} / ${ps[j].key} gap=$gap  a=$a b=$b" }
                    }
                }
            }
        }

        test("2. pin edges are axis parallel and enter the pin perpendicular (48, 49)") {
            val cases =
                mapOf(
                    "49" to null,
                    "48" to setOf("objectFlow:PlaceOrder::ChargeCard"),
                )
            for ((prefix, ids) in cases) {
                val es = edges(svgOf(prefix)).filterKeys { ids == null || it in ids }
                check(es.isNotEmpty()) { "no pin edges in $prefix" }
                for ((id, pts) in es) {
                    for (k in 0 until pts.size - 1) {
                        check(abs(pts[k + 1].x - pts[k].x) <= 0.5f || abs(pts[k + 1].y - pts[k].y) <= 0.5f) {
                            "$prefix $id segment $k diagonal: ${pts[k]} -> ${pts[k + 1]}"
                        }
                    }
                    val a = pts[pts.size - 2]
                    val b = pts.last()
                    check(abs(a.x - b.x) + abs(a.y - b.y) >= 12f) { "$prefix $id last segment shorter than 12 px" }
                }
            }
        }

        test("3. labels are centred on their own line and touch no foreign edge (46-49)") {
            for (prefix in listOf("46", "47", "48", "49")) {
                val svg = svgOf(prefix)
                val es = edges(svg)
                for ((id, r) in labels(svg)) {
                    val own = es[id] ?: continue
                    val c = P(r.x + r.w / 2f, r.y + r.h / 2f)
                    check(distToPolyline(c, own) <= 2.5f) { "$prefix label $id not centred on its line (d=${distToPolyline(c, own)})" }
                    for ((other, pts) in es) {
                        if (other == id) continue
                        for (k in 0 until pts.size - 1) {
                            check(
                                !segHitsRect(pts[k], pts[k + 1], r),
                            ) { "$prefix label $id crosses edge $other" }
                        }
                    }
                }
            }
        }

        test("4. labels do not overlap each other (46-49)") {
            for (prefix in listOf("46", "47", "48", "49")) {
                val ls = labels(svgOf(prefix)).entries.toList()
                for (i in ls.indices) {
                    for (j in i + 1 until ls.size) {
                        val a = ls[i].value
                        val b = ls[j].value
                        val overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
                        check(!overlap) { "$prefix labels ${ls[i].key} and ${ls[j].key} overlap" }
                    }
                }
            }
        }

        test("5. frame labels are free of edges, no occluded fallback (46, 47, 48)") {
            for (prefix in listOf("46", "47", "48")) {
                svgOf(prefix) shouldNotContain "data-kuml-fallback"
            }
        }

        test("6. fork / join arrival points are at least 12 px apart (48)") {
            val es = edges(svgOf("48"))
            for (bar in listOf("pack and notify", "done")) {
                val arrivals = es.filterKeys { it.endsWith("::$bar") }.values.map { it.last() }
                val departures = es.filterKeys { it.contains(":$bar::") }.values.map { it.first() }
                for (group in listOf(arrivals, departures)) {
                    for (i in group.indices) {
                        for (j in i + 1 until group.size) {
                            check(
                                abs(group[i].x - group[j].x) >= 12f,
                            ) { "$bar anchors too close" }
                        }
                    }
                }
            }
        }

        test("7. pin labels touch neither the last edge segment nor the arrow head (48, 49)") {
            for (prefix in listOf("48", "49")) {
                val svg = svgOf(prefix)
                val es = edges(svg)
                // node origin by id and pin squares / labels inside the node groups
                for (g in Regex(
                    "<g id=\"([^\"]+)\" transform=\"translate\\(([-0-9.]+),([-0-9.]+)\\)\">(.*?)</g>",
                    RegexOption.DOT_MATCHES_ALL,
                ).findAll(svg)) {
                    val ox = g.groupValues[2].toFloat()
                    val oy = g.groupValues[3].toFloat()
                    for (t in Regex(
                        "<text class=\"kuml-body\" x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" text-anchor=\"(start|end)\">\\s*([^<\\s][^<]*?)\\s*</text>",
                    ).findAll(g.groupValues[4])) {
                        val x = ox + t.groupValues[1].toFloat()
                        val y = oy + t.groupValues[2].toFloat()
                        val w = t.groupValues[4].length * 6.2f
                        val r = R(if (t.groupValues[3] == "end") x - w else x, y - 10f, w, 13f)
                        for ((id, pts) in es) {
                            // Only the final approach of the edge (last segment plus arrow head) is checked.
                            val a = pts[pts.size - 2]
                            val b = pts.last()
                            check(!segHitsRect(a, b, r)) { "$prefix pin label '${t.groupValues[4]}' crosses last segment of $id" }
                        }
                    }
                }
            }
        }
    })
