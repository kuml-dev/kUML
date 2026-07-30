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
                sysml2Model(name = "TrafficLight") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red", entryAction = "switchLights('red')")
                    val green = stateDef(name = "Green")
                    transition(name = "init", source = initial, target = red)
                    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
                    stmDiagram(name = "STM") {
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
                    canvas = Size(width = 600f, height = 240f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 100f), size = Size(width = 24f, height = 24f))),
                            NodeId("Red") to
                                NodeLayout(bounds = Rect(origin = Point(x = 80f, y = 80f), size = Size(width = 180f, height = 80f))),
                            NodeId("Green") to
                                NodeLayout(bounds = Rect(origin = Point(x = 320f, y = 80f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = stm, layoutResult = layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Red"
            tex shouldContain "Green"
            // V2.0.9-Fallback emittiert den `«state»`-Stereotyp-Header für
            // reguläre States; Pseudo-State-Marker erscheinen als
            // `«initial pseudo-state»` / `«final pseudo-state»`.
            tex shouldContain "state"
            tex shouldContain "initial pseudo-state"

            SampleOutput.write(filename = "sysml2-stm/traffic-light-stm.tex", content = tex)
        }

        "deterministic STM output" {
            val model =
                sysml2Model(name = "Det") {
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    transition(name = "redToGreen", source = red, target = green)
                    stmDiagram(name = "STM") {
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Red") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 180f, height = 80f))),
                            NodeId("Green") to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 0f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = stm, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = stm, layoutResult = layout)
            one shouldBe two
        }
    })
