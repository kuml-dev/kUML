package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-STM-TikZ-Renderer
 * (V2.0.9).
 *
 * V2.0.9 nutzt den BDD-Compartment-Pfad als Fallback für States (Rechteck
 * mit `«state»`-Header + Name). Abgerundet-rechteckige TikZ-Form und
 * Pseudo-State-Marker (Initial-Kreis / Final-Donut) landen in V2.x —
 * analog zur BDD/IBD/UC/REQ-Geschichte im LaTeX-Renderer.
 */
class Sysml2StmLatexTest :
    StringSpec({

        "STM-TikZ enthält Zustandsnamen und «state»-Stereotyp (V2.0.9 fallback)" {
            val model =
                sysml2Model("TrafficLight") {
                    val initial = stateDef("Initial", isInitial = true)
                    val red = stateDef("Red", entryAction = "switchLights('red')")
                    val green = stateDef("Green")
                    transition("init", initial, red)
                    transition("redToGreen", red, green, trigger = "timer60s")
                    stmDiagram("STM") {
                        include(initial)
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(600f, 240f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 100f), Size(24f, 24f))),
                            NodeId("Red") to NodeLayout(bounds = Rect(Point(80f, 80f), Size(180f, 80f))),
                            NodeId("Green") to NodeLayout(bounds = Rect(Point(320f, 80f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, stm, layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Red"
            tex shouldContain "Green"
            // V2.0.9-Fallback emittiert den `«state»`-Stereotyp-Header für
            // reguläre States; Pseudo-State-Marker erscheinen als
            // `«initial pseudo-state»` / `«final pseudo-state»`.
            tex shouldContain "state"
            tex shouldContain "initial pseudo-state"

            SampleOutput.write("sysml2-stm/traffic-light-stm.tex", tex)
        }

        "deterministic STM output" {
            val model =
                sysml2Model("Det") {
                    val red = stateDef("Red")
                    val green = stateDef("Green")
                    transition("redToGreen", red, green)
                    stmDiagram("STM") {
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Red") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(180f, 80f))),
                            NodeId("Green") to NodeLayout(bounds = Rect(Point(220f, 0f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, stm, layout)
            val two = KumlLatexRenderer.toLatex(model, stm, layout)
            one shouldBe two
        }
    })
