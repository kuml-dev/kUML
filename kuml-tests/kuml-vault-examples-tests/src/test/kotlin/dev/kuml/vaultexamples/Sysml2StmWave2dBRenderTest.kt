@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

/**
 * PAL wave 2D-b: font independent invariants of the rendered SVG of the fixtures in `sysml2-stm-d2b`: filled initial dot,
 * initial transition short and straight, `sameLayer` centres, state compartments inside their boxes in the order
 * `[invariant]`, entry, do, exit, composite entry text not crossed by an edge, notes within 125 % of the width, and the UML
 * state compartment.
 */
class Sysml2StmWave2dBRenderTest :
    FunSpec({
        fun fixture(name: String): String =
            Sysml2StmWave2dBRenderTest::class.java
                .getResourceAsStream("/sysml2-stm-d2b/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        fun width(svg: String): Double = Regex("""<svg[^>]*\swidth="([0-9.]+)"""").find(svg)!!.groupValues[1].toDouble()

        /** Simple state boxes by id (rect of the node group). */
        fun boxes(svg: String): Map<String, SvgRect> {
            val out = LinkedHashMap<String, SvgRect>()
            for (n in SvgGeometry.nodes(svg)) {
                Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"[^>]*class="kuml-class"""").find(n.body)?.let {
                    out[n.id] = SvgRect(n.tx, n.ty, it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
                }
            }
            return out
        }

        fun edgePoints(svg: String): Map<String, List<Pair<Double, Double>>> {
            val start = svg.indexOf("""<g id="edges">""")
            val re = Regex("""<g id="([^"]+)">\s*(<line [^>]*class="kuml-edge"[^>]*/>|<path [^>]*class="kuml-edge"[^>]*/>)""")
            val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in re.findAll(svg, start)) {
                val tag = m.groupValues[2]

                fun num(a: String) = Regex("""\b$a="(-?[0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
                out[m.groupValues[1]] =
                    if (tag.startsWith("<line")) {
                        listOf(num("x1") to num("y1"), num("x2") to num("y2"))
                    } else {
                        SvgGeometry.pathPoints(Regex("""\bd="([^"]*)"""").find(tag)!!.groupValues[1])
                    }
            }
            return out
        }

        for (variant in listOf("main", "main-right")) {
            val right = variant == "main-right"
            test("fixture $variant: filled initial dot, short straight initial edge, centred sameLayer") {
                val svg = render(fixture(variant))
                SampleOutput.write(filename = "sysml2-stm-d2b/$variant.svg", content = svg)

                // P-STM-04a: the dot uses the filled class (radius 7) and no currentColor
                svg shouldContain """class="kuml-pseudo-filled""""
                svg.contains("currentColor") shouldBe false

                // P-STM-04b: the initial edge has two points, is straight and short, and ends on the centre of Idle
                val nodes = SvgGeometry.nodes(svg).associateBy { it.id }
                val idle = boxes(svg).getValue("Idle")
                val init = nodes.getValue("Start")
                val initCircle = Regex("""<circle cx="([-0-9.]+)" cy="([-0-9.]+)" r="([-0-9.]+)"""").find(init.body)!!
                initCircle.groupValues[3].toDouble() shouldBe 7.0
                val dot = SvgRect(init.tx, init.ty, 24.0, 24.0)
                val initEdge =
                    edgePoints(svg)
                        .entries
                        .first { (_, pts) ->
                            pts.any { (x, y) -> dot.gapTo(SvgRect(x, y, 0.0, 0.0)) < 1.5 }
                        }.value
                initEdge.size shouldBe 2
                if (right) {
                    (abs(initEdge[0].second - initEdge[1].second) < 0.5) shouldBe true
                    ((idle.x - dot.right) <= 40.0) shouldBe true
                    (abs(dot.cy - idle.cy) <= 1.0) shouldBe true
                } else {
                    (abs(initEdge[0].first - initEdge[1].first) < 0.5) shouldBe true
                    ((idle.y - dot.bottom) <= 40.0) shouldBe true
                    (abs(dot.cx - idle.cx) <= 1.0) shouldBe true
                }

                // P-STM-04c: Small and Big share their centre on the flow axis (0.5 px)
                val small = boxes(svg).getValue("Small")
                val big = boxes(svg).getValue("Big")
                (small.h != big.h) shouldBe true
                if (right) {
                    (abs(small.cx - big.cx) <= 0.5) shouldBe true
                } else {
                    (abs(small.cy - big.cy) <= 0.5) shouldBe true
                }
            }

            test("fixture $variant: compartment order, text inside the box, composite text not crossed, notes within 125 percent") {
                val svg = render(fixture(variant))
                val bx = boxes(svg)

                // P-STM-04f: [invariant], entry, do, exit in this order and every baseline inside the rect of Big
                val big = SvgGeometry.nodes(svg).first { it.id == "Big" }
                val order = listOf("[pressure &lt; 10]", "entry / open()", "do / regulate()", "exit / close()")
                val pos = order.map { big.body.indexOf(it) }
                pos.forEach { (it >= 0) shouldBe true }
                pos shouldBe pos.sorted()
                val rect = bx.getValue("Big")
                for (m in Regex("""<text class="kuml-body" x="8" y="([0-9.]+)"""").findAll(big.body)) {
                    (m.groupValues[1].toDouble() < rect.h) shouldBe true
                }

                // P-STM-04d: no edge point lies inside the action lines of the composite "Gehalten"
                val frame =
                    Regex(
                        """<g id="Gehalten" class="kuml-composite-state">\s*<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([-0-9.]+)" height="([-0-9.]+)"""",
                    ).find(svg)!!.groupValues.drop(1).map { it.toDouble() }
                val compartment = SvgRect(frame[0] + 4, frame[1] + 28 + 2, frame[2] - 8, 2 * 13.0 + 4)
                for ((id, pts) in edgePoints(svg)) {
                    for (i in 0 until pts.size - 1) {
                        for (t in 0..20) {
                            val x = pts[i].first + (pts[i + 1].first - pts[i].first) * t / 20.0
                            val y = pts[i].second + (pts[i + 1].second - pts[i].second) * t / 20.0
                            val inside = x > compartment.x && x < compartment.right && y > compartment.y && y < compartment.bottom
                            if (inside) error("edge $id crosses the entry/exit text of Gehalten at ($x, $y)")
                        }
                    }
                }

                // P-STM-04e: with eight notes the picture is at most 125 percent as wide as without them
                val withoutNotes = fixture(variant).lines().filterNot { it.trimStart().startsWith("note(") }.joinToString("\n")
                val plain = render(withoutNotes)
                println("[2d-b] $variant width with notes=${width(svg)} without=${width(plain)}")
                (svg.contains("n8")) shouldBe true
                (width(svg) <= width(plain) * 1.25 + 1.0) shouldBe true
            }
        }

        test("UML state with entry, do, exit and invariant draws a compartment inside the box") {
            val svg = render(fixture("uml-compartment"))
            SampleOutput.write(filename = "sysml2-stm-d2b/uml-compartment.svg", content = svg)
            val zu = SvgGeometry.nodes(svg).first { it.id == "zu" || it.id.endsWith("Zu") || it.id.lowercase().contains("zu") }
            val order = listOf("[gesichert]", "entry / verriegeln()", "do / pruefen()", "exit / entriegeln()")
            val pos = order.map { zu.body.indexOf(it) }
            pos.forEach { (it >= 0) shouldBe true }
            pos shouldBe pos.sorted()
            val h = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"""").find(zu.body)!!.groupValues[2].toDouble()
            val w = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"""").find(zu.body)!!.groupValues[1].toDouble()
            for (m in Regex("""<text[^>]* y="([0-9.]+)"""").findAll(zu.body)) {
                (m.groupValues[1].toDouble() < h) shouldBe true
            }
            zu.body shouldContain "kuml-divider"
            // the long entry line fits: the state box is at least as wide as its text estimate
            val lang = SvgGeometry.nodes(svg).first { it.body.contains("an extremely long entry") }
            val lw = Regex("""<rect width="([-0-9.]+)"""").find(lang.body)!!.groupValues[1].toDouble()
            val longLine = "entry / an extremely long entry expression that needs more room than the default state width"
            (lw >= longLine.length * 6.6 + 16 - 0.5) shouldBe true
            (w >= 160.0) shouldBe true
        }
    })
