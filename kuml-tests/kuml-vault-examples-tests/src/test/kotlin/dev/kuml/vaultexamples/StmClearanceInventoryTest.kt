package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.StmClearance
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.sysml2.StmDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * PAL wave J inventory: every state-machine example (vault examples and STM fixtures) is laid out with and without the
 * clearance gate. The gate may only change diagrams that had a violation, and it never makes a diagram worse.
 * The list of diagrams with violations is printed (`[stm-clearance]`).
 */
class StmClearanceInventoryTest :
    FunSpec({
        timeout = 300_000L

        val fixtureNames =
            listOf(
                "sysml2-stm-a1/choice-junction",
                "sysml2-stm-a1/composite-history",
                "sysml2-stm-a1/orthogonal-fork-join",
                "sysml2-stm-d2a/density",
                "sysml2-stm-d2a/density-right",
                "sysml2-stm-d2b/main",
                "sysml2-stm-d2b/main-right",
            )

        fun scripts(): List<Pair<String, String>> {
            val fromVault = VaultExampleLoader.loadFromClasspath().map { it.baseName to it.kumlScript }
            val fromFixtures =
                fixtureNames.map { n ->
                    n to
                        StmClearanceInventoryTest::class.java
                            .getResourceAsStream("/$n.kuml.kts")!!
                            .bufferedReader()
                            .readText()
                }
            return (fromVault + fromFixtures).filter { it.second.contains("stmDiagram") }
        }

        fun layouts(script: String): Triple<dev.kuml.layout.LayoutGraph, LayoutResult, LayoutResult>? {
            VaultExampleRenderer.init()
            val ok = KumlScriptHost.eval(code = script) as? ResultWithDiagnostics.Success ?: return null
            val ex =
                DiagramExtractor.extractAny(returnValue = ok.value.returnValue, input = File("inline.kuml.kts")) as? ExtractedDiagram.Sysml2
                    ?: return null
            val d = ex.diagram as? StmDiagram ?: return null
            val engine = LayoutEngineRegistry.get("elk.layered") ?: return null
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = ex.model, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = ex.model, diagram = d)

            fun run(gate: Boolean) =
                LayoutSpecRunner
                    .layout(
                        engine = engine,
                        graph = graph,
                        hints = hints,
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_STM,
                        clearanceGate = gate,
                    ).result
            return Triple(graph, run(gate = false), run(gate = true))
        }

        test("the gate changes only diagrams with a violation and never makes one worse") {
            val rows = ArrayList<String>()
            var checked = 0
            for ((name, script) in scripts()) {
                val (graph, before, after) = layouts(script) ?: continue
                checked++
                val b = StmClearance.violationCount(result = before, graph = graph)
                val a = StmClearance.violationCount(result = after, graph = graph)
                val changed = before.edges != after.edges || before.nodes != after.nodes
                rows += "$name\tbefore=$b\tafter=$a\tchanged=$changed"
                if (a > 0) StmClearance.describe(result = after, graph = graph).forEach { println("[stm-clearance]   detail $name: $it") }
                (a <= b) shouldBe true
                if (b == 0) changed shouldBe false
            }
            rows.forEach { println("[stm-clearance] $it") }
            println("[stm-clearance] diagrams checked: $checked, with violations: ${rows.count { !it.contains("before=0\t") }}")
            (checked > 0) shouldBe true
        }
    })
