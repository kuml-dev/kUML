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
 *  * T1 the end of an anchor line on a transition is at least 20 px from every transition label rectangle (PAL Welle O: 8 px for
 *    the one anchor on T55, see [a4eMinLabelDistance]),
 *  * T2 no anchor segment comes closer than 8 px to a foreign label, none touches its own edge's label, none crosses a foreign state,
 *  * T3 the end point lies in no label rectangle,
 *  * T4 the anchor on the transition crosses at most two foreign edges (two is the topological minimum in Doc 55), never inside a label,
 *  * all other anchor lines of the diagram stay where they were (PAL Welle O: shifted, see [unchanged]).
 *
 * PAL Welle O (deliberate change, not a silent loosening): the label of T55 is now the narrow name-alone wrap (70.8 x 90 px, beside the
 * lower segment of its own line instead of lying across other edges as a wide box before). That box fills the corridor beside the
 * 90 px lower segment of T55 (y 427..517), so no point of the line keeps 20 px from both the label and the node `Verzögerung Freigabe
 * ausstehend`; the placer also cannot hold its 16 px hard label clearance for the old straight line from the right. The anchor of
 * note A4-E therefore comes from the left (an L-shaped path) and ends 8 px or more from the label. T1 for that anchor and the exact
 * positions of the other notes (a4 moves because A4-E took its place on the left; the diagram is 5.28 px wider on the left) are adapted
 * accordingly; every other rule (T2, T3, T4) is unchanged and now also measures the L-shaped path.
 *
 * Known limitation (honest trade-off, review finding): the L-shape is NOT forced by the relaxed rules. The horizontal leg
 * (281,491)-(529.84,491) crosses its own target transition T55 at (523.47, 491) and then runs about 64 px upward at x = 529.84, only
 * 6.4 px beside T55's vertical segment, ending on the jog at (529.84, 427). A straight anchor from the left ending at the first
 * contact with T55 would satisfy T1-T4 without the self-crossing, but the note placer does not yet end an anchor at its first contact
 * with its own edge. T2 only checks the own label and T4 skips the own edge, so nothing measured this; the test "known limitation"
 * below pins the current behaviour (one self-crossing plus the end contact) so it is visible and must be updated when the placer is fixed.
 * The trade: T55/T56 labels no longer
 * lie across edges and the render has no KUML-LAYOUT-W013 (see Doc55StmLabelInvariantsTest).
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
                """<g id="(note-link:[^"]+)">\s*(?:<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)"|<path d="([^"]*)") class="kuml-note-anchor"/>\s*<circle cx="([-0-9.]+)" cy="([-0-9.]+)"""",
            )
        val edgeRe = Regex("""<g id="(transition:[^"]+)">\s*<path d="([^"]*)" class="kuml-edge"""")

        fun anchors(svg: String): List<Anchor> =
            anchorRe
                .findAll(svg)
                .map {
                    val v = it.groupValues
                    Anchor(
                        v[1],
                        if (v[6].isNotEmpty()) {
                            SvgGeometry.pathPoints(v[6])
                        } else {
                            listOf(v[2].toDouble() to v[3].toDouble(), v[4].toDouble() to v[5].toDouble())
                        },
                        v[7].toDouble() to v[8].toDouble(),
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

        /** PAL Welle O: smallest distance from the end of the anchor on T55 to a label (the narrow T55 label leaves no 20 px). */
        val a4eMinLabelDistance = 8.0

        /**
         * Anchors of the diagram other than the one on a transition, as of PAL Welle O (before: a5e 769.94/671.52, a5 275.72/323.72,
         * m2 275.72/531.52, all 5.28 px further left, and a4 the horizontal line (275.72,539)-(336.99,539)).
         */
        val unchanged =
            mapOf(
                "note-link:a4-55::Verzögerung Freigabe ausstehend" to listOf(318.27 to 595.0, 342.27 to 561.0),
                "note-link:a5e-55::Regelung freigegeben" to listOf(775.22 to 172.0, 676.80 to 172.0),
                "note-link:a5-55::Eingefroren PAL" to listOf(281.0 to 355.5, 329.0 to 355.5),
                "note-link:m2-55::Start" to listOf(281.0 to 102.0, 536.80 to 102.0),
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
                    val min = if (a.target == a4eTarget) a4eMinLabelDistance else 20.0
                    if (d < min - 0.01) problems += "${a.id}: end is ${"%.1f".format(d)} px from label ${l.id}"
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
                    for (k in 0 until a.pts.size - 1) {
                        val x = cross(a.pts[k], a.pts[k + 1], pts[i], pts[i + 1]) ?: continue
                        n++
                        ls.any { pointDistance(x, it.rect) == 0.0 } shouldBe false
                    }
                }
            }
            // Two is the topological minimum in Doc 55.
            (n <= 2) shouldBe true
        }

        test("known limitation: the anchor on T55 touches and crosses its own target edge twice (placer does not stop at first contact)") {
            val svg = render()
            val a = anchors(svg).first { it.target == a4eTarget }
            val own = edges(svg).getValue(a4eTarget)
            var n = 0
            for (i in 0 until own.size - 1) {
                for (k in 0 until a.pts.size - 1) {
                    if (cross(a.pts[k], a.pts[k + 1], own[i], own[i + 1]) != null) n++
                }
            }
            // Documented trade-off, not a goal: expected 0 once the placer ends the anchor at its first contact with its own edge.
            // (the intended end contact on the jog counts as one touch, the self-crossing at (523.47, 491) as the second)
            n shouldBe 2
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
