@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Wave 2I-b - visual invariants of analysis / calculation / perform / exhibit boxes in a BDD, measured on the rendered
 * SVG of the full pipeline (never on layout results). Geometry only, so the checks are font independent.
 */
class Sysml2Wave2IbInvariantTest :
    FunSpec({
        fun fixture(name: String): String =
            Sysml2Wave2IbInvariantTest::class.java
                .getResourceAsStream("/wave-2ib/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        val cache = HashMap<String, String>()

        fun svgOf(name: String): String =
            cache.getOrPut(name) {
                val r = VaultExampleRenderer.render(script = fixture(name))
                r.error shouldBe null
                r.svg!!.also { SampleOutput.write("wave-2ib/invariants-$name.svg", it) }
            }

        /** Box rectangles of the node groups: id -> rectangle in diagram coordinates. */
        fun boxes(svg: String): Map<String, SvgRect> =
            SvgGeometry
                .nodes(svg)
                .mapNotNull { n ->
                    Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)" class="kuml-class"""")
                        .find(n.body)
                        ?.let { n.id to SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
                }.toMap()

        /** Polylines of the edge groups; a straight edge is a `<line>`, a bent one a `<path>`. */
        fun edgeLines(svg: String): Map<String, List<Pair<Double, Double>>> {
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in Regex("""<g id="((?:comp|subject):[^"]+)">\s*<(line|path) ([^>]*?)class="kuml-edge"""").findAll(svg)) {
                val tag = m.groupValues[3]
                val d = Regex("""d="([^"]+)"""").find(tag)?.groupValues?.get(1)
                out[m.groupValues[1]] =
                    if (d != null) {
                        SvgGeometry.pathPoints(d)
                    } else {
                        fun attr(n: String) = Regex("""$n="([-0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                        listOf(attr("x1") to attr("y1"), attr("x2") to attr("y2"))
                    }
            }
            return out
        }

        val analysis = "analysis-calc"
        val behaviours = "part-exhibit-perform"

        test("analysis def and calc def boxes carry their stereotype, the compartments and the wrapped expression") {
            val svg = svgOf(analysis)
            svg shouldContain "«analysis def»"
            svg shouldContain "«calc def»"
            for (header in listOf("parameters", "expression", "return", "objective")) svg shouldContain ">\n        $header\n"
            svg shouldContain "in chassis : Mass"
            svg shouldContain "out margin : Mass"
            // The long expression is wrapped over several lines and nothing is cut off.
            val expr =
                Regex("""<text class="kuml-body" x="6" y="[-0-9.]+" style="font-family: monospace">\s*([^<]*?)\s*</text>""")
                    .findAll(svg)
                    .map { it.groupValues[1] }
                    .toList()
            (expr.size >= 3) shouldBe true
            expr.joinToString(" ") shouldContain "chassis + battery + payload + sum(accessories) * safetyFactor + tolerance"
            svg shouldNotContain "…"
        }

        test("each calc is a composition with its name, the subject is a dashed edge with a «subject» label") {
            val svg = svgOf(analysis)
            val edges = edgeLines(svg)
            edges.keys.toSet() shouldBe setOf("comp:MassAnalysis::total", "comp:MassAnalysis::margin", "subject:MassAnalysis")
            svg shouldContain """<g id="subject:MassAnalysis">"""
            Regex(
                """<g id="subject:MassAnalysis">\s*<path d="[^"]+" class="kuml-edge" stroke-dasharray="5 4"/>""",
            ).containsMatchIn(svg) shouldBe
                true
            svg shouldContain "«subject»"
            Regex("""<g id="comp:MassAnalysis::total">\s*<line [^>]*class="kuml-edge"/>\s*<polygon""").containsMatchIn(svg) shouldBe true
            // Names of the calcs are on the edges, not repeated in the analysis box (the edge is drawn).
            svg shouldNotContain "total : TotalMass"
        }

        test("every edge starts on the border of the analysis box and ends on the border of its target") {
            val svg = svgOf(analysis)
            val b = boxes(svg)
            val ends =
                mapOf(
                    "comp:MassAnalysis::total" to "TotalMass",
                    "comp:MassAnalysis::margin" to "PayloadMargin",
                    "subject:MassAnalysis" to "Vehicle",
                )

            fun onBorder(
                p: Pair<Double, Double>,
                r: SvgRect,
            ): Boolean =
                p.first >= r.x - 1 &&
                    p.first <= r.right + 1 &&
                    p.second >= r.y - 1 &&
                    p.second <= r.bottom + 1 &&
                    (p.first <= r.x + 1 || p.first >= r.right - 1 || p.second <= r.y + 1 || p.second >= r.bottom - 1)
            for ((id, pts) in edgeLines(svg)) {
                onBorder(pts.first(), b.getValue("MassAnalysis")) shouldBe true
                onBorder(pts.last(), b.getValue(ends.getValue(id))) shouldBe true
            }
        }

        test("no two edge labels overlap and no label sits on a box") {
            val svg = svgOf(analysis)
            val ls = SvgGeometry.labelRects(svg, "kuml-edge-label").map { it.second }
            (ls.size >= 3) shouldBe true
            for (i in ls.indices) for (j in i + 1 until ls.size) ls[i].overlaps(ls[j], eps = 0.0) shouldBe false
            for (l in ls) for (r in boxes(svg).values) l.overlaps(r, eps = 0.0) shouldBe false
        }

        test("no box overlaps another and no text leaves its box (lines inside the height, text inside the width)") {
            for (name in listOf(analysis, behaviours)) {
                val svg = svgOf(name)
                val b = boxes(svg)
                val all = b.entries.toList()
                for (i in all.indices) for (j in i + 1 until all.size) all[i].value.overlaps(all[j].value, eps = 0.0) shouldBe false
                for (n in SvgGeometry.nodes(svg)) {
                    val r = b[n.id] ?: continue
                    for (m in Regex("""<text class="kuml-body" x="6" y="([-0-9.]+)"[^>]*>\s*([^<]*?)\s*</text>""").findAll(n.body)) {
                        (m.groupValues[1].toDouble() <= r.h - 2.0) shouldBe true
                        (6.0 + m.groupValues[2].length * 6.6 <= r.w) shouldBe true
                    }
                }
            }
        }

        test("a part lists its perform and exhibit lines in declaration order") {
            val svg = svgOf(behaviours)
            svg shouldContain "perform actions"
            svg shouldContain "exhibit states"
            val order = listOf("perform drive : Drive", "perform brake : Brake", "exhibit idle : Idle", "exhibit charging : Charging")
            val at = order.map { svg.indexOf(it) }
            at.all { it >= 0 } shouldBe true
            at shouldBe at.sorted()
        }

        test("rendering is deterministic") {
            for (name in listOf(analysis, behaviours)) {
                VaultExampleRenderer.render(script = fixture(name)).svg shouldBe svgOf(name)
            }
        }
    })
