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
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val batteryDef = partDef("Battery")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                            part("battery", typeId = batteryDef.id)
                        }
                    ibd("Vehicle wiring", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(420f, 160f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(Point(20f, 20f), Size(180f, 80f))),
                            NodeId("Vehicle::battery") to
                                NodeLayout(bounds = Rect(Point(220f, 20f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, ibd, layout)

            tex shouldContain "\\begin{tikzpicture}"
            // Fallback emits `name : Type` as the rectangle label.
            tex shouldContain "engine : Engine"
            tex shouldContain "battery : Battery"

            SampleOutput.write("sysml2-ibd/vehicle-two-parts.tex", tex)
        }

        "deterministic IBD output" {
            val model =
                sysml2Model("M") {
                    val engineDef = partDef("Engine")
                    val vehicle =
                        partDef("Vehicle") {
                            part("engine", typeId = engineDef.id)
                        }
                    ibd("Det", owner = vehicle)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(200f, 100f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(Point(0f, 0f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, ibd, layout)
            val two = KumlLatexRenderer.toLatex(model, ibd, layout)
            one shouldBe two
        }
    })
