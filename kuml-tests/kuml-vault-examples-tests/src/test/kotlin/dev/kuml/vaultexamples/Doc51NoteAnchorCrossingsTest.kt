@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PAL Welle Q: note anchor lines of the real "Doc 51" state machine (vault example, classpath fixture
 * `sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts`) cross as few foreign transitions as the geometry allows.
 *
 * Measured on the rendered SVG: per note anchor (`note-link:<note>::<target>`) the number of proper intersections between its
 * segments and the segments of foreign transition polylines. Foreign is every transition whose id is not the anchor's target.
 * Collinear contacts, intersections within 0.5 px of a segment end point and the composite frame lines (not transitions) are not counted.
 *
 * Before PAL Welle Q the anchors of `a4e-51` (on T19), `a9e-51-2` (on T18) and `a9e-51` (on the state in the composite frame) crossed
 * three foreign transitions in total (1 + 2 + 0). Welle Q (stage D) does not lower this for Doc 51: those counts are the geometric
 * minimum, so the test guards them against regression. Only geometry, no fonts, no pixel or byte comparison.
 */
class Doc51NoteAnchorCrossingsTest :
    FunSpec({
        timeout = 300_000L

        val anchorRe =
            Regex(
                """<g id="(note-link:[^"]+)">\s*(?:<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)"|<path d="([^"]*)") class="kuml-note-anchor"/>""",
            )
        val edgeRe = Regex("""<g id="(transition:[^"]+)">\s*<path d="([^"]*)" class="kuml-edge"""")

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

        fun near(
            p: Pair<Double, Double>,
            vararg q: Pair<Double, Double>,
        ) = q.any { hypot(p.first - it.first, p.second - it.second) < 0.5 }

        /** Anchor id to the list of crossed foreign transition ids (one entry per crossing). */
        fun crossings(svg: String): Map<String, List<String>> {
            val edges =
                edgeRe.findAll(svg).associate { it.groupValues[1] to SvgGeometry.pathPoints(it.groupValues[2]) }
            val out = LinkedHashMap<String, List<String>>()
            for (m in anchorRe.findAll(svg)) {
                val v = m.groupValues
                val pts =
                    if (v[6].isNotEmpty()) {
                        SvgGeometry.pathPoints(v[6])
                    } else {
                        listOf(v[2].toDouble() to v[3].toDouble(), v[4].toDouble() to v[5].toDouble())
                    }
                val target = v[1].substringAfter("::")
                val hits = ArrayList<String>()
                for ((id, e) in edges) {
                    if (id == target) continue
                    for (i in 0 until e.size - 1) {
                        for (k in 0 until pts.size - 1) {
                            val x = cross(pts[k], pts[k + 1], e[i], e[i + 1]) ?: continue
                            if (near(x, pts[k], pts[k + 1], e[i], e[i + 1])) continue
                            hits += id
                        }
                    }
                }
                out[v[1]] = hits
            }
            return out
        }

        var cached: Map<String, List<String>>? = null

        fun measure(): Map<String, List<String>> {
            cached?.let { return it }
            val script =
                Doc51NoteAnchorCrossingsTest::class.java
                    .getResourceAsStream("/sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts")!!
                    .bufferedReader()
                    .readText()
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            val m = crossings(r.svg!!)
            for ((id, hits) in m) println("[pal-q] $id crosses ${hits.size}: $hits")
            cached = m
            return m
        }

        fun of(
            m: Map<String, List<String>>,
            note: String,
        ) = m.filterKeys { it.startsWith("note-link:$note::") }.values.sumOf { it.size }

        test("the fixture renders the three measured notes") {
            val m = measure()
            listOf("a4e-51", "a9e-51-2", "a9e-51").forEach { n -> (m.keys.any { it.startsWith("note-link:$n::") }) shouldBe true }
        }

        // Geometric minimum of this layout (checked on the rendered SVG, Welle Q):
        // - a4e-51 sits left of the composite and anchors T19 (Wartezeit to Tür Korridor offen). The antiparallel T12 runs left of T19
        //   in the 43 px gap, so every anchor line coming from the left crosses T12 once; the right side of T19 is covered by labels.
        // - a9e-51-2 anchors T18 from below the composite: any downward line crosses T20 (horizontal at its start) and T14 (below
        //   Wartezeit); reaching the left or right of the composite crosses at least as many edges.
        // - a9e-51 anchors the state itself and crosses nothing.
        test("a4e-51 crosses at most the antiparallel T12, a9e-51-2 at most two foreign transitions") {
            val m = measure()
            (of(m, "a4e-51") <= 1) shouldBe true
            (of(m, "a9e-51-2") <= 2) shouldBe true
        }

        test("a9e-51 crosses no foreign transition, all anchors together at most three (regression guard)") {
            val m = measure()
            of(m, "a9e-51") shouldBe 0
            (m.values.sumOf { it.size } <= 3) shouldBe true
        }
    })
