@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PAL wave K: note anchor lines of the real "Doc 55" state machine (vault example, copied as the classpath fixture
 * `sysml2-stm-k/55-einfrieren-stm.kuml.kts`), measured on the rendered SVG of the production pipeline:
 *
 *  * T1 the end of an anchor line on a transition is at least 20 px from every transition label rectangle,
 *  * T2 no anchor segment comes closer than 8 px to a foreign label, none touches its own edge's label, none crosses a foreign state,
 *  * T3 the end point lies in no label rectangle,
 *  * T4 the anchor on the transition crosses at most two foreign edges (two is the topological minimum in Doc 55), never inside a label,
 *  * all other anchor lines of the diagram stay where they were.
 *
 * Only geometry, no fonts and no pixel or byte comparison.
 */
class Doc55NoteAnchorInvariantsTest :
    FunSpec({
        timeout = 300_000L

        class Label(
            val id: String,
            val rect: SvgRect,
        )

        class Anchor(
            val id: String,
            val pts: List<Pair<Double, Double>>,
            val dot: Pair<Double, Double>,
        ) {
            /** Target id: everything after the first `::` of `note-link:<note>::<target>`. */
            val target: String get() = id.substringAfter("::")
        }

        val anchorRe =
            Regex(
                """<g id="(note-link:[^"]+)">\s*<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)" class="kuml-note-anchor"/>\s*<circle cx="([-0-9.]+)" cy="([-0-9.]+)"""",
            )
        val edgeRe = Regex("""<g id="(transition:[^"]+)">\s*<path d="([^"]*)" class="kuml-edge"""")

        fun anchors(svg: String): List<Anchor> =
            anchorRe
                .findAll(svg)
                .map {
                    val v = it.groupValues
                    Anchor(
                        v[1],
                        listOf(v[2].toDouble() to v[3].toDouble(), v[4].toDouble() to v[5].toDouble()),
                        v[6].toDouble() to v[7].toDouble(),
                    )
                }.toList()

        fun edges(svg: String): Map<String, List<Pair<Double, Double>>> =
            edgeRe.findAll(svg).associate {
                it.groupValues[1] to
                    SvgGeometry.pathPoints(it.groupValues[2])
            }

        fun labels(svg: String): List<Label> =
            Regex(
                """<g class="kuml-edge-label" data-edge-id="([^"]*)" data-label-id="[^"]*" data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""",
            ).findAll(svg)
                .map {
                    val v = it.groupValues
                    Label(v[1], SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))
                }.toList()

        /** State boxes by id (notes `*-55` excluded); a pseudo state without a rectangle gets its 24 x 24 box. */
        fun states(svg: String): Map<String, SvgRect> =
            SvgGeometry
                .nodes(svg)
                .filter { !it.id.endsWith("-55") && it.id != "kuml-diagram-frame" }
                .associate {
                    val m = Regex("""<rect width="([-0-9.]+)" height="([-0-9.]+)"""").find(it.body)
                    it.id to SvgRect(it.tx, it.ty, m?.groupValues?.get(1)?.toDouble() ?: 24.0, m?.groupValues?.get(2)?.toDouble() ?: 24.0)
                }

        fun pointDistance(
            p: Pair<Double, Double>,
            r: SvgRect,
        ): Double = SvgGeometry.distance(listOf(p, p), r)

        fun cross(
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
            c: Pair<Double, Double>,
            d: Pair<Double, Double>,
        ): Pair<Double, Double>? {
            val r = b.first - a.first to b.second - a.second
            val s = d.first - c.first to d.second - c.second
            val den = r.first * s.second - r.second * s.first
            if (abs(den) < 1e-9) return null
            val t = ((c.first - a.first) * s.second - (c.second - a.second) * s.first) / den
            val u = ((c.first - a.first) * r.second - (c.second - a.second) * r.first) / den
            if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
            return a.first + t * r.first to a.second + t * r.second
        }

        fun script(): String =
            Doc55NoteAnchorInvariantsTest::class.java
                .getResourceAsStream("/sysml2-stm-k/55-einfrieren-stm.kuml.kts")!!
                .bufferedReader()
                .readText()

        val a4eTarget = "transition:Verzögerung Freigabe ausstehend::Eingefroren PAL"

        /** Anchors of the diagram before wave K (rendered `55k-design.svg`), other than the one on a transition. */
        val unchanged =
            mapOf(
                "note-link:a4-55::Verzögerung Freigabe ausstehend" to listOf(275.72 to 539.0, 336.99 to 539.0),
                "note-link:a5e-55::Regelung freigegeben" to listOf(769.94 to 172.0, 671.52 to 172.0),
                "note-link:a5-55::Eingefroren PAL" to listOf(275.72 to 355.5, 323.72 to 355.5),
                "note-link:m2-55::Start" to listOf(275.72 to 102.0, 531.52 to 102.0),
            )

        var cached: String? = null

        fun render(): String {
            cached?.let { return it }
            val r = VaultExampleRenderer.render(script = script())
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-stm-k/55-einfrieren-stm.svg", content = svg)
            cached = svg
            return svg
        }

        test("the fixture renders with labels and the anchor on the transition") {
            val svg = render()
            labels(svg).size shouldNotBe 0
            anchors(svg).size shouldBe 5
            anchors(svg).firstOrNull { it.target == a4eTarget } shouldNotBe null
        }

        test("T1 an anchor end on a transition is 20 px from every transition label") {
            val svg = render()
            val problems = ArrayList<String>()
            for (a in anchors(svg).filter { it.target.startsWith("transition:") }) {
                for (l in labels(svg)) {
                    val d = pointDistance(a.dot, l.rect)
                    if (d < 20.0 - 0.01) problems += "${a.id}: end is ${"%.1f".format(d)} px from label ${l.id}"
                }
            }
            problems shouldBe emptyList()
        }

        test("T2 anchor segments keep 8 px from foreign labels, never touch their own label or a foreign state") {
            val svg = render()
            val st = states(svg)
            val problems = ArrayList<String>()
            for (a in anchors(svg)) {
                val ownNodes =
                    if (a.target.startsWith("transition:")) {
                        a.target
                            .removePrefix("transition:")
                            .split("::")
                            .toSet()
                    } else {
                        setOf(a.target)
                    }
                for (l in labels(svg)) {
                    val d = SvgGeometry.distance(a.pts, l.rect)
                    val limit = if (l.id == a.target) 0.5 else 8.0
                    if (d < limit - 0.01) problems += "${a.id}: ${"%.1f".format(d)} px from label ${l.id}"
                }
                for ((id, r) in st) {
                    if (id in ownNodes) continue
                    val d = SvgGeometry.distance(a.pts, r)
                    if (d < 4.0 - 0.01) problems += "${a.id}: ${"%.1f".format(d)} px from foreign state $id"
                }
            }
            problems shouldBe emptyList()
        }

        test("T3 an anchor end lies in no label rectangle") {
            val svg = render()
            val problems = ArrayList<String>()
            for (a in anchors(svg)) {
                for (l in labels(svg)) {
                    if (pointDistance(a.dot, l.rect) == 0.0) problems += "${a.id}: end inside label ${l.id}"
                }
            }
            problems shouldBe emptyList()
        }

        test("T4 the anchor on the transition crosses at most two foreign edges, none inside a label") {
            val svg = render()
            val a = anchors(svg).first { it.target == a4eTarget }
            val ls = labels(svg)
            var n = 0
            for ((id, pts) in edges(svg)) {
                if (id == a4eTarget) continue
                for (i in 0 until pts.size - 1) {
                    val x = cross(a.pts[0], a.pts[1], pts[i], pts[i + 1]) ?: continue
                    n++
                    ls.any { pointDistance(x, it.rect) == 0.0 } shouldBe false
                }
            }
            // Two is the topological minimum in Doc 55.
            (n <= 2) shouldBe true
        }

        test("all other anchor lines stay where they were") {
            val svg = render()
            val now = anchors(svg).associateBy { it.id }
            for ((id, expected) in unchanged) {
                val a = now[id]!!
                for (k in expected.indices) {
                    (hypot(a.pts[k].first - expected[k].first, a.pts[k].second - expected[k].second) <= 0.5) shouldBe true
                }
            }
        }
    })
