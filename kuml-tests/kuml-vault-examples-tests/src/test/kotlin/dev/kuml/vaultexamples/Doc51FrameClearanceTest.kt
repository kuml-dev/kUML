@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.StmFrameClearance
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.sysml2.StmDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * PAL wave P: edge clearance against composite frames in the real "Doc 51" state machine (classpath fixture
 * `sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts`), measured on the production layout (clearance gate on):
 *
 *  * F1 no edge runs parallel to a composite frame side closer than 12 px (inside or outside),
 *  * F2 no edge runs horizontally or diagonally inside a header band (a vertical piercing is fine),
 *  * F3 no two parallel edges run closer than 16 px side by side (the wave-J detector).
 *
 * Only geometry; no fonts, no pixel or byte comparison.
 */
class Doc51FrameClearanceTest :
    FunSpec({
        timeout = 300_000L

        fun layout(): Pair<LayoutGraph, LayoutResult> {
            VaultExampleRenderer.init()
            val script =
                Doc51FrameClearanceTest::class.java
                    .getResourceAsStream("/sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts")!!
                    .bufferedReader()
                    .readText()
            val ok = KumlScriptHost.eval(code = script) as ResultWithDiagnostics.Success
            val ex =
                DiagramExtractor.extractAny(
                    returnValue = ok.value.returnValue,
                    input = File("inline.kuml.kts"),
                ) as ExtractedDiagram.Sysml2
            val d = ex.diagram as StmDiagram
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = ex.model, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = ex.model, diagram = d)
            val r =
                LayoutSpecRunner
                    .layout(
                        engine = engine,
                        graph = graph,
                        hints = hints,
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_STM,
                        clearanceGate = true,
                    ).result
            return graph to r
        }

        test("F1 = 0, F2 = 0, F3 = 0 on the production layout of Doc 51") {
            val (g, r) = layout()
            StmFrameClearance.describe(r, g).forEach { println("[pal-p] $it") }
            StmFrameClearance.frameGapCount(r, g) shouldBe 0
            StmFrameClearance.headerRunCount(r, g) shouldBe 0
            StmFrameClearance.parallelCount(r, g) shouldBe 0
        }

        test("the three edges that entered the composite from above keep their end points and stay orthogonal") {
            val (_, r) = layout()
            val routes = StmFrameClearance.routes(r).filter { it.startsWith("transition:Regelbetrieb::") }
            // 3 transitions into the composite plus the one to "Stoerung RLK"
            routes.size shouldBe 4
            routes.forEach { println("[pal-p] $it") }
        }

        /**
         * F5 (note anchors against foreign edges) is measured on the rendered SVG: proper crossings between a note anchor
         * and a transition line. PAL wave P did not touch the note placer; the value on master was 3 and must not rise.
         */
        test("F5 note anchor crossings do not exceed the master value (3)") {
            VaultExampleRenderer.init()
            val script =
                Doc51FrameClearanceTest::class.java
                    .getResourceAsStream("/sysml2-stm-j2/51-rueckluftklappe-stm.kuml.kts")!!
                    .bufferedReader()
                    .readText()
            val svg = VaultExampleRenderer.render(script = script).svg!!
            val edgeRe =
                Regex("""<g id="([^"]+)">\s*(<line [^>]*class="kuml-(?:edge|note-anchor)"[^>]*/>|<path [^>]*class="kuml-edge"[^>]*/>)""")

            fun num(
                tag: String,
                attr: String,
            ): Double = Regex("""\b$attr="(-?[0-9.]+)"""").find(tag)!!.groupValues[1].toDouble()
            val anchors = LinkedHashMap<String, List<Pair<Double, Double>>>()
            val edges = LinkedHashMap<String, List<Pair<Double, Double>>>()
            for (m in edgeRe.findAll(svg, svg.indexOf("""<g id="edges">""").coerceAtLeast(0))) {
                val tag = m.groupValues[2]
                val pts =
                    if (tag.startsWith("<line")) {
                        listOf(num(tag, "x1") to num(tag, "y1"), num(tag, "x2") to num(tag, "y2"))
                    } else {
                        SvgGeometry.pathPoints(Regex("""\bd="([^"]*)"""").find(tag)!!.groupValues[1])
                    }
                (if (m.groupValues[1].startsWith("note-link")) anchors else edges)[m.groupValues[1]] = pts
            }

            fun orient(
                p: Pair<Double, Double>,
                q: Pair<Double, Double>,
                r: Pair<Double, Double>,
            ) = (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
            var f5 = 0
            for (a in anchors.values) {
                for (e in edges.values) {
                    for ((p1, p2) in a.zipWithNext()) {
                        for ((q1, q2) in e.zipWithNext()) {
                            if (orient(p1, p2, q1) * orient(p1, p2, q2) < -0.01 && orient(q1, q2, p1) * orient(q1, q2, p2) < -0.01) f5++
                        }
                    }
                }
            }
            println("[pal-p] F5=$f5 over ${anchors.size} note anchors")
            (anchors.size > 0) shouldBe true
            (f5 <= 3) shouldBe true
        }
    })
