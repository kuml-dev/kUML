@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PAL wave 2D-a: font independent invariants of the transition labels and lines of SysML 2 state machines. Everything is
 * measured on the rendered SVG (`data-label-rect`, path points, node rectangles, arrow heads), never on pixels or text
 * widths. Per label: (a) at most 20 px from its own line, (b) strictly closer to its own line than to any foreign line,
 * (c) 2 px clear of every foreign line and of every arrow head. Per diagram: (d) parallel segments of different edges that
 * overlap keep 16 px apart, (e) edge ends at one side of one state keep 16 px apart. (d) and (e) are checked for the density
 * fixtures only: the vault examples end in pseudo-state circles, whose ends are fanned out by angle instead.
 */
class Sysml2StmLabelInvariantTest :
    FunSpec({
        class Label(
            val id: String,
            val rect: SvgRect,
        )

        val edgeRe = Regex("""<g id="([^"]+)">\s*(<line [^>]*class="kuml-edge"[^>]*/>|<path [^>]*class="kuml-edge"[^>]*/>)""")

        fun num(
            tag: String,
            attr: String,
        ): Double = Regex("""\b$attr="(-?[0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> {
            val start = svg.indexOf("""<g id="edges">""")
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in edgeRe.findAll(svg, start)) {
                val tag = m.groupValues[2]
                out[m.groupValues[1]] =
                    if (tag.startsWith("<line")) {
                        listOf(num(tag, "x1") to num(tag, "y1"), num(tag, "x2") to num(tag, "y2"))
                    } else {
                        SvgGeometry.pathPoints(Regex("""\bd="([^"]*)"""").find(tag)!!.groupValues[1])
                    }
            }
            return out
        }

        fun labels(svg: String): List<Label> =
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    Label(v[1], SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))
                }.toList()

        /** Simple states and composite frames as rectangles by id (pseudo-state circles and bars are not boxes). */
        fun boxes(svg: String): Map<String, SvgRect> {
            val out = LinkedHashMap<String, SvgRect>()
            for (n in SvgGeometry.nodes(svg)) {
                Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(n.body)?.let {
                    out[n.id] = SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                }
            }
            for (m in Regex(
                """<g id="([^"]+)" class="kuml-composite-state">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
            ).findAll(svg)) {
                val v = m.groupValues
                out[v[1]] = SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }
            return out
        }

        fun arrowBoxes(svg: String): List<Pair<String, SvgRect>> =
            Regex("""<g id="([^"]+)">\s*(?:<line|<path)[^>]*class="kuml-edge"""")
                .findAll(svg, svg.indexOf("""<g id="edges">"""))
                .flatMap { m -> SvgGeometry.arrowBoxes(svg, m.groupValues[1]).map { m.groupValues[1] to it } }
                .toList()

        fun labelProblems(
            name: String,
            svg: String,
        ): List<String> {
            val problems = ArrayList<String>()
            val ed = edges(svg)
            val arrows = arrowBoxes(svg)
            val bx = boxes(svg)
            for (l in labels(svg)) {
                val where = "$name label ${l.id}"
                for ((bid, r) in bx) {
                    val inside = r.x <= l.rect.x && r.y <= l.rect.y && r.right >= l.rect.right && r.bottom >= l.rect.bottom
                    val isFrame = svg.contains("""<g id="$bid" class="kuml-composite-state">""")
                    if (l.rect.overlaps(r, 0.5) && !(isFrame && inside)) problems += "$where overlaps the state $bid (c)"
                }
                val own = ed[l.id]
                if (own == null) {
                    problems += "$where has no edge"
                    continue
                }
                val d = SvgGeometry.distance(own, l.rect)
                if (d > 20.0 + 0.01) problems += "$where is ${"%.1f".format(d)} px from its line (a)"
                if (d < 2.0 - 0.01) problems += "$where touches its own line (a)"
                for ((eid, pts) in ed) {
                    if (eid == l.id) continue
                    val f = SvgGeometry.distance(pts, l.rect)
                    if (f <= d + 0.01) problems += "$where is not strictly closer to its own line than to $eid (b)"
                    if (f < 2.0 - 0.01) problems += "$where is ${"%.1f".format(f)} px from the line $eid (c)"
                }
                for ((eid, a) in arrows) if (l.rect.overlaps(a)) problems += "$where covers the arrow head of $eid (c)"
            }
            return problems
        }

        /** (d) overlapping parallel segments of different edges that are not the same line are at least 16 px apart. */
        fun parallelProblems(
            name: String,
            svg: String,
        ): List<String> {
            val problems = ArrayList<String>()

            class Seg(
                val id: String,
                val horizontal: Boolean,
                val fixed: Double,
                val lo: Double,
                val hi: Double,
            )
            val segs = ArrayList<Seg>()
            for ((id, pts) in edges(svg)) {
                for (i in 0 until pts.size - 1) {
                    val (a, b) = pts[i] to pts[i + 1]
                    if (abs(a.second - b.second) < 0.01 && abs(a.first - b.first) >= 0.01) {
                        segs += Seg(id, true, a.second, min(a.first, b.first), max(a.first, b.first))
                    } else if (abs(a.first - b.first) < 0.01 && abs(a.second - b.second) >= 0.01) {
                        segs += Seg(id, false, a.first, min(a.second, b.second), max(a.second, b.second))
                    }
                }
            }
            for (i in segs.indices) {
                for (j in i + 1 until segs.size) {
                    val a = segs[i]
                    val b = segs[j]
                    if (a.id == b.id || a.horizontal != b.horizontal) continue
                    val overlap = min(a.hi, b.hi) - max(a.lo, b.lo)
                    val gap = abs(a.fixed - b.fixed)
                    if (overlap > 4.0 && gap >= 0.5 && gap < 16.0 - 0.05) {
                        problems += "$name: ${a.id} and ${b.id} run ${"%.1f".format(gap)} px apart over ${"%.0f".format(overlap)} px (d)"
                    }
                }
            }
            return problems
        }

        /** (e) ends at the same side of the same state are at least 16 px apart. */
        fun endProblems(
            name: String,
            svg: String,
        ): List<String> {
            val problems = ArrayList<String>()
            val bx = boxes(svg)
            val byKey = HashMap<String, MutableList<Triple<String, Double, Double>>>()
            for ((eid, pts) in edges(svg)) {
                for ((e, p) in listOf(pts.first() to pts[1], pts.last() to pts[pts.size - 2])) {
                    for ((bid, r) in bx) {
                        val vertical = abs(e.first - p.first) < 0.5
                        val horizontal = abs(e.second - p.second) < 0.5
                        val side =
                            when {
                                vertical && e.first in r.x - 1.5..r.right + 1.5 && abs(e.second - r.y) <= 1.5 -> "top"
                                vertical && e.first in r.x - 1.5..r.right + 1.5 && abs(e.second - r.bottom) <= 1.5 -> "bottom"
                                horizontal && e.second in r.y - 1.5..r.bottom + 1.5 && abs(e.first - r.x) <= 1.5 -> "left"
                                horizontal && e.second in r.y - 1.5..r.bottom + 1.5 && abs(e.first - r.right) <= 1.5 -> "right"
                                else -> null
                            } ?: continue
                        val coord = if (side == "top" || side == "bottom") e.first else e.second
                        byKey.getOrPut("$bid/$side") { ArrayList() } += Triple(eid, coord, 0.0)
                    }
                }
            }
            for ((key, ends) in byKey) {
                val sorted = ends.sortedBy { it.second }
                for (i in 1 until sorted.size) {
                    val gap = sorted[i].second - sorted[i - 1].second
                    if (sorted[i].first != sorted[i - 1].first && gap < 16.0 - 0.05) {
                        problems +=
                            "$name: ends of ${sorted[i - 1].first} and ${sorted[i].first} at $key are ${"%.1f".format(gap)} px apart (e)"
                    }
                }
            }
            return problems
        }

        fun fixture(name: String): String =
            Sysml2StmLabelInvariantTest::class.java
                .getResourceAsStream("/sysml2-stm-d2a/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun textOf(svg: String): String =
            Regex("""<text[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(svg)
                .joinToString("") { it.groupValues[1].replace(Regex("<[^>]*>"), "") }
                .replace(Regex("\\s+"), "")

        val examples = LinkedHashMap<String, String>()
        for (ex in VaultExampleLoader.loadFromClasspath()) {
            if (Regex("^(04|42|43|44|45|53) ").containsMatchIn(ex.baseName)) examples[ex.sanitizedName] = ex.kumlScript
        }

        test("all expected examples are present") { examples.size shouldBe 6 }

        for (fx in listOf("density", "density-right")) {
            test("fixture $fx: labels, parallel segments and ends keep their distances, nothing is cut") {
                val r = VaultExampleRenderer.render(script = fixture(fx))
                r.error shouldBe null
                val svg = r.svg!!
                SampleOutput.write(filename = "sysml2-stm-d2a/$fx.svg", content = svg)
                labels(svg).size shouldBe 9
                labelProblems(fx, svg).joinToString("\n") shouldBe ""
                parallelProblems(fx, svg).joinToString("\n") shouldBe ""
                endProblems(fx, svg).joinToString("\n") shouldBe ""
                val text = textOf(svg)
                text shouldContain "stroemungOkandnotstbAusgeloestanddruckImBereich(p1,p22)"
                text shouldContain "T37:"
                text.contains("…") shouldBe false
            }
        }

        for ((name, script) in examples) {
            test("example $name: labels keep their distances") {
                val r = VaultExampleRenderer.render(script = script)
                r.error shouldBe null
                val svg = r.svg!!
                SampleOutput.write(filename = "sysml2-stm-d2a/$name.svg", content = svg)
                labelProblems(name, svg).joinToString("\n") shouldBe ""
            }
        }
    })
