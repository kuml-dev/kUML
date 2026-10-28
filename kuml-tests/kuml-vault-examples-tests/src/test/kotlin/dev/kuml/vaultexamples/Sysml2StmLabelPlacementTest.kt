package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Font-independent acceptance test for SysML 2 STM label placement. It renders the STM vault
 * examples and the wave A1 scripts and checks the SVG geometry (label rectangles against edge
 * polylines, state rectangles, frame bands, other labels, the diagram frame) — never pixels or
 * text widths.
 */
class Sysml2StmLabelPlacementTest :
    FunSpec({
        data class Pt(
            val x: Double,
            val y: Double,
        )

        data class R(
            val x0: Double,
            val y0: Double,
            val x1: Double,
            val y1: Double,
        ) {
            fun shrink(d: Double) = R(x0 + d, y0 + d, x1 - d, y1 - d)

            fun overlaps(o: R) = min(x1, o.x1) - max(x0, o.x0) > 0 && min(y1, o.y1) - max(y0, o.y0) > 0

            fun contains(o: R) = o.x0 >= x0 && o.y0 >= y0 && o.x1 <= x1 && o.y1 <= y1
        }

        fun num(
            tag: String,
            attr: String,
        ): Double? =
            Regex("""\b$attr="(-?[0-9.]+)"""")
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.toDouble()

        fun rectOf(tag: String): R? {
            val x = num(tag, "x") ?: 0.0
            val y = num(tag, "y") ?: 0.0
            val w = num(tag, "width") ?: return null
            val h = num(tag, "height") ?: return null
            return R(x, y, x + w, y + h)
        }

        fun polyOf(block: String): List<Pt> {
            val out = ArrayList<Pt>()
            Regex("""<line [^>]*>""").find(block)?.value?.let { l ->
                out += Pt(num(l, "x1")!!, num(l, "y1")!!)
                out += Pt(num(l, "x2")!!, num(l, "y2")!!)
            }
            Regex("""<path [^>]*\bd="([^"]*)"""").find(block)?.groupValues?.get(1)?.let { d ->
                val tokens = Regex("""[MLACQ]|-?[0-9.]+""").findAll(d).map { it.value }.toList()
                var i = 0
                var cmd = 'M'
                while (i < tokens.size) {
                    val t = tokens[i]
                    if (t.length == 1 && t[0].isLetter()) {
                        cmd = t[0]
                        i++
                        continue
                    }
                    val count =
                        when (cmd) {
                            'A' -> 7
                            'C' -> 6
                            'Q' -> 4
                            else -> 2
                        }
                    if (i + count > tokens.size) break
                    out += Pt(tokens[i + count - 2].toDouble(), tokens[i + count - 1].toDouble())
                    i += count
                }
            }
            return out
        }

        fun segHits(
            a: Pt,
            b: Pt,
            r: R,
        ): Boolean {
            var t0 = 0.0
            var t1 = 1.0
            val p = doubleArrayOf(-(b.x - a.x), b.x - a.x, -(b.y - a.y), b.y - a.y)
            val q = doubleArrayOf(a.x - r.x0, r.x1 - a.x, a.y - r.y0, r.y1 - a.y)
            for (i in 0..3) {
                if (p[i] == 0.0) {
                    if (q[i] < 0) return false
                } else {
                    val k = q[i] / p[i]
                    if (p[i] < 0) {
                        if (k > t1) return false
                        if (k > t0) t0 = k
                    } else {
                        if (k < t0) return false
                        if (k < t1) t1 = k
                    }
                }
            }
            return true
        }

        fun distance(
            poly: List<Pt>,
            r: R,
        ): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until poly.size - 1) {
                val a = poly[i]
                val b = poly[i + 1]
                val n = max(1, hypot(b.x - a.x, b.y - a.y).toInt())
                for (k in 0..n) {
                    val x = a.x + (b.x - a.x) * k / n
                    val y = a.y + (b.y - a.y) * k / n
                    val dx = max(max(r.x0 - x, 0.0), x - r.x1)
                    val dy = max(max(r.y0 - y, 0.0), y - r.y1)
                    best = min(best, hypot(dx, dy))
                }
            }
            return best
        }

        fun check(
            name: String,
            svg: String,
        ): List<String> {
            val problems = ArrayList<String>()
            val frame =
                rectOf(Regex("""<g id="kuml-diagram-frame">\s*(<rect [^>]*>)""").find(svg)!!.groupValues[1])!!
            val composites = ArrayList<R>()
            Regex("""<g id="[^"]*" class="kuml-composite-state">\s*(<rect [^>]*>)""").findAll(svg).forEach {
                composites += rectOf(it.groupValues[1])!!
            }
            val nodes = ArrayList<R>()
            Regex(
                """<g id="[^"]*" transform="translate\(([-0-9.]+),([-0-9.]+)\)">(.*?)</g>""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(svg).forEach { m ->
                val tx = m.groupValues[1].toDouble()
                val ty = m.groupValues[2].toDouble()
                val body = m.groupValues[3]
                Regex("""<rect [^>]*>""").find(body)?.value?.let { t ->
                    rectOf(t)?.let {
                        nodes +=
                            R(tx + it.x0, ty + it.y0, tx + it.x1, ty + it.y1)
                    }
                }
                    ?: Regex("""<circle [^>]*>""").find(body)?.value?.let { t ->
                        val r = num(t, "r")!!
                        val cx = num(t, "cx")!!
                        val cy = num(t, "cy")!!
                        nodes += R(tx + cx - r, ty + cy - r, tx + cx + r, ty + cy + r)
                    }
            }
            val edgesStart = svg.indexOf("""<g id="edges">""")
            val edges = LinkedHashMap<String, List<Pt>>()
            Regex("""<g id="([^"]*)">(.*?)</g>""", RegexOption.DOT_MATCHES_ALL).findAll(svg, edgesStart).forEach { m ->
                val poly = polyOf(m.groupValues[2])
                if (poly.size >= 2) edges[m.groupValues[1]] = poly
            }
            val labels = LinkedHashMap<String, R>()
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)">.*?(<rect [^>]*>)""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(svg).forEach {
                labels[it.groupValues[1]] = rectOf(it.groupValues[2])!!
            }
            for ((id, box) in labels) {
                val where = "$name label $id"
                val b = box.shrink(0.5)
                for ((eid, poly) in edges) {
                    for (i in 0 until poly.size - 1) if (segHits(poly[i], poly[i + 1], b)) problems += "$where crosses edge $eid"
                }
                for (n in nodes) if (n.overlaps(b)) problems += "$where overlaps a state"
                for (c in composites) if (c.overlaps(b) && !c.shrink(3.0).contains(b)) problems += "$where overlaps a composite frame"
                for ((oid, o) in labels) if (oid != id && o.overlaps(b)) problems += "$where overlaps label $oid"
                if (!frame.contains(box)) problems += "$where leaves the diagram frame"
                val own = edges[id]
                if (own == null) {
                    problems += "$where has no edge"
                } else {
                    val d = distance(own, box)
                    if (d > 23.0) problems += "$where is ${"%.1f".format(d)} px from its edge"
                    val other = edges.filterKeys { it != id }.values.minOfOrNull { distance(it, box) } ?: Double.MAX_VALUE
                    if (d + 2.0 > other &&
                        other < 23.0
                    ) {
                        problems +=
                            "$where is nearly as close to a foreign edge (${"%.1f".format(other)} vs ${"%.1f".format(d)})"
                    }
                }
            }
            return problems
        }

        fun a1(name: String): String =
            Sysml2StmLabelPlacementTest::class.java
                .getResourceAsStream("/sysml2-stm-a1/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        val sources = LinkedHashMap<String, String>()
        for (ex in VaultExampleLoader.loadFromClasspath()) {
            if (Regex("^(04|42|43|44|45) ").containsMatchIn(ex.baseName)) sources[ex.sanitizedName] = ex.kumlScript
        }
        for (n in listOf("composite-history", "orthogonal-fork-join", "choice-junction")) sources["a1-$n"] = a1(n)

        test("all expected examples are present") {
            sources.size shouldBe 8
        }

        for ((name, script) in sources) {
            test("labels of $name sit clear of edges, states, frames and each other") {
                val r = VaultExampleRenderer.render(script = script)
                r.error shouldBe null
                val svg = r.svg!!
                SampleOutput.write(filename = "sysml2-stm-label-placement/$name.svg", content = svg)
                check(name, svg).shouldBeEmpty()
            }
        }
    })
