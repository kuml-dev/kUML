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
                sysml2Model(name = "OrderProcessing") {
                    val initial = initialNode()
                    val validate = actionDef(name = "Validate", action = "validate(order)")
                    val decide = decisionNode(name = "Valid?")
                    val finalN = finalNode()
                    controlFlow(name = "start", source = initial, target = validate)
                    controlFlow(name = "vToD", source = validate, target = decide)
                    controlFlow(name = "end", source = decide, target = finalN, guard = "valid")
                    actDiagram(name = "Workflow") {
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
                    canvas = Size(width = 800f, height = 240f),
                    nodes =
                        mapOf(
                            NodeId("Initial") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 100f), size = Size(width = 28f, height = 28f))),
                            NodeId("Validate") to
                                NodeLayout(bounds = Rect(origin = Point(x = 80f, y = 80f), size = Size(width = 160f, height = 60f))),
                            NodeId("Valid?") to
                                NodeLayout(bounds = Rect(origin = Point(x = 280f, y = 90f), size = Size(width = 50f, height = 50f))),
                            NodeId("Final") to
                                NodeLayout(bounds = Rect(origin = Point(x = 400f, y = 100f), size = Size(width = 28f, height = 28f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = act, layoutResult = layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "Validate"
            // V2.0.10-Fallback emittiert kind-spezifische Stereotypes —
            // `«action»` für reguläre Actions, `«initial node»` / `«final node»` /
            // `«decision node»` etc. für die anderen Kinds.
            tex shouldContain "action"
            tex shouldContain "initial node"
            tex shouldContain "decision node"

            SampleOutput.write(filename = "sysml2-act/order-processing-act.tex", content = tex)
        }

        "deterministic ACT output" {
            val model =
                sysml2Model(name = "Det") {
                    val a = actionDef(name = "A")
                    val b = actionDef(name = "B")
                    controlFlow(name = "aToB", source = a, target = b)
                    actDiagram(name = "ACT") {
                        include(a)
                        include(b)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("A") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 160f, height = 60f))),
                            NodeId("B") to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 0f), size = Size(width = 160f, height = 60f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = act, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = act, layoutResult = layout)
            one shouldBe two
        }
    })
