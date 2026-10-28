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
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ParDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * Router obstacle invariant (wave C4): no edge segment may cut through the interior (shrunk by 2 px) of a
 * node that is neither its source nor its target. Checked on the engine's layout result (after
 * `parEnrich` for parametric diagrams), so the test does not depend on fonts.
 *
 * Violations that exist outside the scope of wave C4 are listed in [KNOWN_VIOLATIONS] with a reason; the test
 * also fails when such an entry no longer applies, so the list cannot silently grow stale.
 */
class Sysml2RouterInvariantTest :
    FunSpec({
        fun pointsOf(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                is EdgeRoute.Bezier -> listOf(r.source) + r.controlPoints + listOf(r.target)
            }

        fun segmentCrossesBox(
            a: Point,
            b: Point,
            l: Float,
            t: Float,
            r: Float,
            bt: Float,
        ): Boolean {
            if (r <= l || bt <= t) return false
            // sample the segment; the router emits axis-parallel segments, sampling every 1 px is exact enough
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val n = kotlin.math.max(1, len.toInt())
            for (i in 0..n) {
                val x = a.x + dx * i / n
                val y = a.y + dy * i / n
                if (x > l && x < r && y > t && y < bt) return true
            }
            return false
        }

        /** Violations as `edgeId -> nodeId` strings. */
        fun violations(layout: LayoutResult): List<String> {
            val out = mutableListOf<String>()
            for ((eid, route) in layout.edges) {
                val pts = pointsOf(route)
                for ((nid, nl) in layout.nodes) {
                    val b = nl.bounds
                    val l = b.origin.x + 2f
                    val t = b.origin.y + 2f
                    val r = b.origin.x + b.size.width - 2f
                    val bt = b.origin.y + b.size.height - 2f
                    // endpoints on the node's own border do not count; interior sampling excludes the 2 px frame
                    val hit = (0 until pts.size - 1).any { segmentCrossesBox(pts[it], pts[it + 1], l, t, r, bt) }
                    if (hit && !isEndpointNode(edgeId = eid.value, nodeId = nid.value, layout = layout)) out += "${eid.value}->${nid.value}"
                }
            }
            return out
        }

        fun layoutOf(script: String): LayoutResult? {
            VaultExampleRenderer.init()
            val res = KumlScriptHost.eval(code = script)
            val ok = res as? ResultWithDiagnostics.Success ?: return null
            val ex =
                DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = java.io.File("inline.kuml.kts"))
                    as? ExtractedDiagram.Sysml2 ?: return null
            val elk = LayoutEngineRegistry.get("elk.layered")!!
            val model = ex.model
            return when (val d = ex.diagram) {
                is ActDiagram ->
                    elk.layout(
                        graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d),
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                    )
                is ParDiagram ->
                    Sysml2LayoutBridge.parEnrich(
                        model = model,
                        diagram = d,
                        layoutResult =
                            elk.layout(
                                graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d),
                                hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d),
                            ),
                    )
                else -> null
            }
        }

        test("no edge cuts through a node it does not connect, for all ACT and PAR examples") {
            val scripts = mutableMapOf<String, String>()
            VaultExampleLoader
                .loadFromClasspath()
                .filter { " ACT " in it.fileName || " PAR " in it.fileName }
                .forEach { scripts[it.fileName] = it.kumlScript }
            scripts["sysml2-c4/par-nested"] =
                Sysml2RouterInvariantTest::class.java
                    .getResourceAsStream("/sysml2-c4/par-nested.kuml.kts")!!
                    .bufferedReader()
                    .readText()
            val found = mutableMapOf<String, List<String>>()
            for ((name, script) in scripts) {
                val layout = layoutOf(script) ?: continue
                val v = violations(layout)
                if (v.isNotEmpty()) found[name] = v
            }
            val unexpected = found.filterKeys { it !in KNOWN_VIOLATIONS }
            val stale = KNOWN_VIOLATIONS.keys.filter { it in scripts && it !in found }
            println("router-invariant violations: $found")
            unexpected shouldBe emptyMap()
            stale shouldBe emptyList()
        }
    }) {
    companion object {
        /** Example file name to the reason the violation is accepted for now. */
        val KNOWN_VIOLATIONS: Map<String, String> = emptyMap()

        /** An edge id contains its endpoints only by convention; resolve by the layout's own endpoint data when possible. */
        private fun isEndpointNode(
            edgeId: String,
            nodeId: String,
            layout: LayoutResult,
        ): Boolean {
            val route =
                layout.edges.entries
                    .first { it.key.value == edgeId }
                    .value
            val near = { p: Point ->
                val b =
                    layout.nodes.entries
                        .first { it.key.value == nodeId }
                        .value.bounds
                p.x >= b.origin.x - 6f &&
                    p.x <= b.origin.x + b.size.width + 6f &&
                    p.y >= b.origin.y - 6f &&
                    p.y <= b.origin.y + b.size.height + 6f
            }
            return near(route.source) || near(route.target)
        }
    }
}
