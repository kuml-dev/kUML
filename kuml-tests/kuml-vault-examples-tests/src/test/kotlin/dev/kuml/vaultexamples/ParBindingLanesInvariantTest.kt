package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ParDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * Nacharbeit 41: bindings inside nested parametric constraints run on their own lanes. Checked on the polylines of
 * the enriched layout result (after the pin clamp), so no fonts and no SVG text are involved.
 */
class ParBindingLanesInvariantTest :
    FunSpec({
        timeout = 60_000L

        fun pts(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + listOf(r.target)
            }

        fun script(name: String): String =
            ParBindingLanesInvariantTest::class.java
                .getResourceAsStream("/sysml2-c4/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        /** Layout after parEnrich; [reverseBindings] reverses the declaration order of the bindings before layout. */
        fun layoutOf(
            code: String,
            reverseBindings: Boolean = false,
        ): Pair<LayoutResult, List<String>> {
            VaultExampleRenderer.init()
            val res = KumlScriptHost.eval(code = code) as ResultWithDiagnostics.Success
            val ex =
                DiagramExtractor.extractAny(returnValue = res.value.returnValue, input = java.io.File("inline.kuml.kts"))
                    as ExtractedDiagram.Sysml2
            val d = ex.diagram as ParDiagram
            var model = ex.model
            if (reverseBindings) {
                val bindings = model.usages.filterIsInstance<BindingConnectorUsage>()
                val others = model.usages.filter { it !is BindingConnectorUsage }
                model = model.copy(usages = others + bindings.reversed())
            }
            val elk = LayoutEngineRegistry.get("elk.layered")!!
            val layout =
                Sysml2LayoutBridge.parEnrich(
                    model = model,
                    diagram = d,
                    layoutResult =
                        elk.layout(
                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d),
                            hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                        ),
                )
            // Bindings that live inside one composite: both ends are pins of constraints (no Part:: endpoint).
            val inner =
                model.usages
                    .filterIsInstance<BindingConnectorUsage>()
                    .filter { !it.sourceEndId.contains("Vehicle") }
                    .map { it.id }
            return layout to inner
        }

        data class Seg(
            val id: String,
            val a: Point,
            val b: Point,
        ) {
            val horizontal get() = abs(a.y - b.y) < 0.001f
            val vertical get() = abs(a.x - b.x) < 0.001f
            val len get() = abs(a.x - b.x) + abs(a.y - b.y)
        }

        fun segs(
            layout: LayoutResult,
            ids: List<String>,
        ): List<Seg> =
            layout.edges
                .filterKeys { it.value in ids }
                .flatMap { (k, r) -> pts(r).zipWithNext().map { (a, b) -> Seg(k.value, a, b) } }
                .filter { it.len > 0.001f }

        fun overlap(
            lo1: Float,
            hi1: Float,
            lo2: Float,
            hi2: Float,
        ): Float = min(hi1, hi2) - max(lo1, lo2)

        val fixtures = listOf("par-binding-lanes", "par-binding-channel7")

        for (f in fixtures) {
            test("$f: all segments are axis-parallel (I4)") {
                val (layout, ids) = layoutOf(script(f))
                ids.isNotEmpty() shouldBe true
                for (s in segs(layout, ids)) (s.horizontal || s.vertical) shouldBe true
            }

            test("$f: no two connectors share a collinear piece, parallel neighbours are 8 px apart (I1, I2)") {
                val (layout, ids) = layoutOf(script(f))
                val all = segs(layout, ids)
                val problems = mutableListOf<String>()
                for (i in all.indices) {
                    for (j in i + 1 until all.size) {
                        val p = all[i]
                        val q = all[j]
                        if (p.id == q.id) continue
                        if (p.horizontal && q.horizontal) {
                            val o = overlap(min(p.a.x, p.b.x), max(p.a.x, p.b.x), min(q.a.x, q.b.x), max(q.a.x, q.b.x))
                            val d = abs(p.a.y - q.a.y)
                            // up to 2 px apart is the pin-share bundle at one pin, everything else needs the full lane pitch
                            if (o > 0.5f && d > 0.001f && d < 7.99f && d > 2.01f) problems += "H ${p.id}/${q.id} d=$d"
                            if (o > 0.5f && d <= 0.001f) problems += "H collinear ${p.id}/${q.id}"
                        }
                        if (p.vertical && q.vertical) {
                            val o = overlap(min(p.a.y, p.b.y), max(p.a.y, p.b.y), min(q.a.y, q.b.y), max(q.a.y, q.b.y))
                            val d = abs(p.a.x - q.a.x)
                            if (o > 0.5f && d > 2.01f && d < 7.99f) problems += "V ${p.id}/${q.id} d=$d"
                            if (o > 0.5f && d <= 0.001f) problems += "V collinear ${p.id}/${q.id}"
                        }
                    }
                }
                problems.shouldBeEmpty()
            }

            test("$f: all end points are pairwise at least 2 px apart (I3)") {
                val (layout, ids) = layoutOf(script(f))
                val ends =
                    layout.edges.filterKeys { it.value in ids }.flatMap { (k, r) ->
                        listOf(
                            k.value to pts(r).first(),
                            k.value to pts(r).last(),
                        )
                    }
                val bad = mutableListOf<String>()
                for (i in ends.indices) {
                    for (j in i + 1 until ends.size) {
                        val d = abs(ends[i].second.x - ends[j].second.x) + abs(ends[i].second.y - ends[j].second.y)
                        if (d < 1.99f) bad += "${ends[i].first}/${ends[j].first}"
                    }
                }
                bad.shouldBeEmpty()
            }

            test("$f: the routes do not depend on the declaration order of the bindings (I5)") {
                val (a, ids) = layoutOf(script(f))
                val (b, _) = layoutOf(script(f), reverseBindings = true)
                for (id in ids) {
                    val ra =
                        a.edges.entries
                            .first { it.key.value == id }
                            .value
                    val rb =
                        b.edges.entries
                            .first { it.key.value == id }
                            .value
                    pts(ra) shouldBe pts(rb)
                }
            }
        }

        test("par-binding-channel7: seven lanes, seven distinct channel heights, children below the lowest lane") {
            val (layout, ids) = layoutOf(script("par-binding-channel7"))
            val lowestOuterPin =
                layout.nodes.entries
                    .first { it.key.value == "Outer7" }
                    .value.ports.values
                    .maxOf { it.y }
            val childTop =
                layout.nodes.entries
                    .first { it.key.value.contains("Inner7") }
                    .value.bounds.origin.y
            val channelYs =
                segs(layout, ids)
                    .filter { it.horizontal && abs(it.a.x - it.b.x) > 20f && it.a.y > lowestOuterPin + 4f && it.a.y < childTop }
                    .map { it.a.y }
                    .distinct()
            channelYs.size shouldBe 7
            val child =
                layout.nodes.entries
                    .first { it.key.value.contains("Inner7") }
                    .value.bounds
            (channelYs.max() < child.origin.y) shouldBe true
            (channelYs.sorted().zipWithNext().minOf { it.second - it.first } >= 7.99f) shouldBe true
        }

        test("par-binding-lanes: crossings stay below a regression bound (comb rule)") {
            val (layout, ids) = layoutOf(script("par-binding-lanes"))
            val all = segs(layout, ids)
            var crossings = 0
            for (i in all.indices) {
                for (j in i + 1 until all.size) {
                    val p = all[i]
                    val q = all[j]
                    if (p.id == q.id) continue
                    val (h, v) =
                        if (p.horizontal && q.vertical) {
                            p to q
                        } else if (q.horizontal && p.vertical) {
                            q to p
                        } else {
                            continue
                        }
                    val x = v.a.x
                    val y = h.a.y
                    if (x > min(h.a.x, h.b.x) + 0.5f &&
                        x < max(h.a.x, h.b.x) - 0.5f &&
                        y > min(v.a.y, v.b.y) + 0.5f &&
                        y < max(v.a.y, v.b.y) - 0.5f
                    ) {
                        crossings++
                    }
                }
            }
            println("par-binding-lanes crossings: $crossings")
            (crossings <= 9) shouldBe true
            layout.edges.size shouldNotBe 0
        }
    })
