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
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-IBD-TikZ-Renderer (V2.0.6).
 *
 * V2.0.6 nutzt den UML-Klassenfallback-Pfad für Part-Usages (Rechteck mit
 * `name : Type`-Label). Die strukturelle Assertion zielt deshalb auf den
 * Label-Inhalt; ein dedizierter IBD-TikZ-Stil mit Stereotyp-Band landet in
 * V2.x — analog zum BDD-Renderer in V2.0.4.
 */
class Sysml2IbdLatexTest :
    StringSpec({

        "IBD-TikZ enthält die Part-Usage-Namen im Snippet (V2.0.6 fallback)" {
            val model =
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val batteryDef = partDef(name = "Battery")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                            part(name = "battery", typeId = batteryDef.id)
                        }
                    ibd(name = "Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 420f, height = 160f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 80f))),
                            NodeId("Vehicle::battery") to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 20f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = ibd, layoutResult = layout)

            tex shouldContain "\\begin{tikzpicture}"
            // Fallback emits `name : Type` as the rectangle label.
            tex shouldContain "engine : Engine"
            tex shouldContain "battery : Battery"

            SampleOutput.write(filename = "sysml2-ibd/vehicle-two-parts.tex", content = tex)
        }

        "deterministic IBD output" {
            val model =
                sysml2Model(name = "M") {
                    val engineDef = partDef(name = "Engine")
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = engineDef.id)
                        }
                    ibd(name = "Det", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 200f, height = 100f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = ibd, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = ibd, layoutResult = layout)
            one shouldBe two
        }
    })
