@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave L: the transition labels of the real "Doc 55" state machine (classpath fixture
 * `sysml2-stm-k/55-einfrieren-stm.kuml.kts`) sit beside their own line, measured on the rendered SVG of the production pipeline
 * (every edge, straight `<line>` edges as well as `<path>` polylines):
 *
 *  * a the label rectangle (2 px larger) crosses no segment of its own polyline,
 *  * b its distance to its own line is at most 18 px,
 *  * c the rectangle (4 px larger) crosses no foreign edge,
 *  * d no foreign edge is as close to the label as its own line.
 *
 * a, b, c and d hold for every label except T55 and T56. For those two, a and b hold, but c and d cannot: the routes are
 * fixed (T55 and T56 are squeezed between the nodes `Eingefroren PAL`, `Verzögerung Freigabe ausstehend`, the straight edges T35
 * and T36 and the long edges T34 and T37), and an exhaustive grid search over all label positions within 18 px of the own line
 * (box sizes 99.6 x 66/78, the narrowest wrap that keeps `T55: ANNAHME:` together, and the normal wraps) finds none that
 * is clear of every node, foreign edge and foreign line distance at once; only dropping the node rule leaves positions. So the
 * planner reports them (KUML-LAYOUT-W013) and keeps the best compromise. Fixing that needs a different edge routing, not another
 * label position.
 *
 * Only geometry, no fonts and no pixel or byte comparison.
 */
class Doc55StmLabelInvariantsTest :
    FunSpec({
        timeout = 300_000L

        val fixture = "/sysml2-stm-k/55-einfrieren-stm.kuml.kts"
        val t55 = "transition:Verzögerung Freigabe ausstehend::Eingefroren PAL"
        val t56 = "transition:Verzögerung Freigabe ausstehend::Eingefroren PAL und Mixing"

        val edgeRe = Regex("""<g id="(transition:[^"]+)">\s*<path d="([^"]*)" class="kuml-edge"""")
        val lineRe =
            Regex(
                """<g id="(transition:[^"]+)">\s*<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-edge"""",
            )
        val labelRe =
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            )

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> =
            edgeRe.findAll(svg).associate { it.groupValues[1] to SvgGeometry.pathPoints(it.groupValues[2]) } +
                lineRe.findAll(svg).associate {
                    val v = it.groupValues
                    v[1] to listOf(v[2].toDouble() to v[3].toDouble(), v[4].toDouble() to v[5].toDouble())
                }

        fun labels(svg: String): Map<String, SvgRect> =
            labelRe.findAll(svg).associate {
                val v = it.groupValues
                v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }

        fun grow(
            r: SvgRect,
            m: Double,
        ) = SvgRect(r.x - m, r.y - m, r.w + 2 * m, r.h + 2 * m)

        fun render(): String {
            val script =
                Doc55StmLabelInvariantsTest::class.java
                    .getResourceAsStream(fixture)!!
                    .bufferedReader()
                    .readText()
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        fun problems(
            svg: String,
            ids: Collection<String>,
            strict: Boolean,
        ): List<String> {
            val ln = edges(svg)
            val ls = labels(svg)
            val out = ArrayList<String>()
            for (id in ids) {
                val r =
                    ls[id] ?: run {
                        out += "$id: no label"
                        continue
                    }
                val own = ln.getValue(id)
                val own2 = SvgGeometry.distance(own, grow(r, 2.0))
                val d = SvgGeometry.distance(own, r)
                val foreign =
                    ln.filterKeys { it != id }.mapValues { SvgGeometry.distance(it.value, r) }
                val nearest = foreign.minByOrNull { it.value }
                val info =
                    "$id rect=$r own=${"%.1f".format(d)} nearestForeign=${nearest?.key}:${"%.1f".format(nearest?.value ?: -1.0)}"
                if (own2 <= 0.01) out += "$info: crosses its own line (a)"
                if (d > 18.0 + 0.01) out += "$info: too far from its own line (b)"
                if (!strict) continue
                if (foreign.any { SvgGeometry.distance(ln.getValue(it.key), grow(r, 4.0)) <= 0.01 }) {
                    out +=
                        "$info: crosses a foreign edge (c)"
                }
                if (nearest != null && nearest.value <= d) out += "$info: foreign edge as close as the own one (d)"
            }
            return out
        }

        test("every label of the diagram sees all its edges (straight and polyline)") {
            val svg = render()
            edges(svg).size shouldBe 8
            labels(svg).size shouldBe 8
        }

        test("T55 and T56 sit beside their own line, never on it and at most 18 px away") {
            val svg = render()
            SampleOutput.write(filename = "sysml2-stm-l/55-einfrieren-stm.svg", content = svg)
            problems(svg, listOf(t55, t56), strict = false).joinToString("\n") shouldBe ""
        }

        test("all other labels also cross no foreign edge and have no foreign edge as close as their own") {
            val svg = render()
            val others = labels(svg).keys.filter { it != t55 && it != t56 && edges(svg).containsKey(it) }
            others.size shouldBe 6
            problems(svg, others, strict = true).joinToString("\n") shouldBe ""
        }
    })
