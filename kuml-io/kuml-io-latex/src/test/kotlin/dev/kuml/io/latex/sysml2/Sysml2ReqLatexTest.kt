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
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-REQ-TikZ-Renderer (V2.0.8).
 *
 * V2.0.8 nutzt den BDD-Compartment-Pfad als Fallback für Requirements
 * (Rechteck mit `«requirement»`-Header + Name). Dreikompartiment-Stil in
 * TikZ (mit wort-gewrapptem Anforderungstext) landet in V2.x — analog zur
 * BDD/IBD/UC-Geschichte im LaTeX-Renderer.
 */
class Sysml2ReqLatexTest :
    StringSpec({

        "REQ-TikZ enthält Requirement-Name und «requirement»-Stereotyp (V2.0.8 fallback)" {
            val model =
                sysml2Model(name = "VehicleReqs") {
                    val topSpeed =
                        requirementDef(
                            name = "TopSpeedRequirement",
                            reqId = "R-001",
                            text = "≥180 km/h",
                        )
                    val vehicle = partDef(name = "Vehicle")
                    reqDiagram(name = "REQ") {
                        include(topSpeed)
                        include(vehicle)
                        satisfy(source = vehicle, requirement = topSpeed)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 600f, height = 240f),
                    nodes =
                        mapOf(
                            NodeId("TopSpeedRequirement") to
                                NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 40f), size = Size(width = 220f, height = 120f))),
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 340f, y = 60f), size = Size(width = 220f, height = 140f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model = model, diagram = req, layoutResult = layout)
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "TopSpeedRequirement"
            tex shouldContain "Vehicle"
            // V2.0.8-Fallback emittiert den `«requirement»`-Stereotyp-Header.
            tex shouldContain "requirement"

            SampleOutput.write(filename = "sysml2-req/vehicle-req.tex", content = tex)
        }

        "deterministic REQ output" {
            val model =
                sysml2Model(name = "Det") {
                    val r = requirementDef(name = "R1", reqId = "R-001")
                    reqDiagram(name = "REQ") {
                        include(r)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 300f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("R1") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 220f, height = 120f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model = model, diagram = req, layoutResult = layout)
            val two = KumlLatexRenderer.toLatex(model = model, diagram = req, layoutResult = layout)
            one shouldBe two
        }
    })
