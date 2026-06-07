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
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-ACT-TikZ-Renderer
 * (V2.0.10).
 *
 * V2.0.10 nutzt den BDD-Compartment-Pfad als Fallback für Action-Knoten
 * (Rechteck mit kind-spezifischem Stereotyp wie `«action»`, `«initial node»`,
 * `«decision node»`, `«fork node»` etc.). Das shape-spezifische TikZ-Pendant
 * (abgerundete Rechtecke, Kreise, Rauten, Bars) landet in V2.x — analog zur
 * BDD/IBD/UC/REQ/STM-Geschichte im LaTeX-Renderer.
 */
class Sysml2ActLatexTest :
    StringSpec({

        "ACT-TikZ enthält Action-Namen und kind-spezifische Stereotypes (V2.0.10 fallback)" {
            val model =
                sysml2Model("OrderProcessing") {
                    val initial = initialNode()
                    val validate = actionDef("Validate", action = "validate(order)")
                    val decide = decisionNode("Valid?")
                    val finalN = finalNode()
                    controlFlow("start", initial, validate)
                    controlFlow("vToD", validate, decide)
                    controlFlow("end", decide, finalN, guard = "valid")
                    actDiagram("Workflow") {
                        include(initial)
                        include(validate)
                        include(decide)
                        include(finalN)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(800f, 240f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 100f), Size(28f, 28f))),
                            NodeId("Validate") to NodeLayout(bounds = Rect(Point(80f, 80f), Size(160f, 60f))),
                            NodeId("Valid?") to NodeLayout(bounds = Rect(Point(280f, 90f), Size(50f, 50f))),
                            NodeId("Final") to NodeLayout(bounds = Rect(Point(400f, 100f), Size(28f, 28f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, act, layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Validate"
            // V2.0.10-Fallback emittiert kind-spezifische Stereotypes —
            // `«action»` für reguläre Actions, `«initial node»` / `«final node»` /
            // `«decision node»` etc. für die anderen Kinds.
            tex shouldContain "action"
            tex shouldContain "initial node"
            tex shouldContain "decision node"

            SampleOutput.write("sysml2-act/order-processing-act.tex", tex)
        }

        "deterministic ACT output" {
            val model =
                sysml2Model("Det") {
                    val a = actionDef("A")
                    val b = actionDef("B")
                    controlFlow("aToB", a, b)
                    actDiagram("ACT") {
                        include(a)
                        include(b)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("A") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(160f, 60f))),
                            NodeId("B") to NodeLayout(bounds = Rect(Point(220f, 0f), Size(160f, 60f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, act, layout)
            val two = KumlLatexRenderer.toLatex(model, act, layout)
            one shouldBe two
        }
    })
