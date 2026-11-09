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
        // Nacharbeit 46 (N-IBD-E): the style of a connection comes from its own definition, also when two definitions
        // share a name. (LaTeX IBD draws neither nested frames nor ports; only the edge style is shared with the SVG.)
        "two same-named connection definitions keep their own line style" {
            val model =
                sysml2Model(name = "M") {
                    val plain = connectionDef(name = "Signal")
                    val dashed = connectionDef(name = "Signal", style = dev.kuml.sysml2.ConnectionLineStyle.DASHED)
                    val a = partDef(name = "Regler")
                    val b = partDef(name = "Klappe")
                    val owner =
                        partDef(name = "Anlage") {
                            part(name = "regler", typeId = a.id)
                            part(name = "klappe", typeId = b.id)
                            connect(name = "c1", typeId = plain.id, sourceEndId = "regler", targetEndId = "klappe")
                            connect(name = "c2", typeId = dashed.id, sourceEndId = "regler", targetEndId = "klappe")
                        }
                    ibd(name = "Anlage", owner = owner)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 420f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Anlage::regler") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 120f, height = 60f))),
                            NodeId("Anlage::klappe") to
                                NodeLayout(bounds = Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 120f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            dev.kuml.layout.EdgeId("conn:Anlage::c1") to
                                dev.kuml.layout.EdgeRoute
                                    .Direct(source = Point(x = 140f, y = 40f), target = Point(x = 260f, y = 40f)),
                            dev.kuml.layout.EdgeId("conn:Anlage::c2") to
                                dev.kuml.layout.EdgeRoute
                                    .Direct(source = Point(x = 140f, y = 60f), target = Point(x = 260f, y = 60f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = ibd, layoutResult = layout)
            // Uses of the styles, not their `/.style` definitions in the preamble.
            Regex("kuml-sysml2-edge-dashed-noarrow(?![/\\w])").containsMatchIn(tex) shouldBe true
            Regex("kuml-sysml2-edge-binding(?![/\\w])").containsMatchIn(tex) shouldBe true
        }

        // Nacharbeit 47 (N-IBD-F a, b): a FORWARD connection carries the open head, a flow without via next to the
        // matching connection is no own line. (C and F c do not concern LaTeX: no attribute compartment, no frame.)
        "FORWARD connection draws an open head and a derived flow adds no own edge" {
            val model =
                sysml2Model(name = "M") {
                    val link = connectionDef(name = "Leitung", style = dev.kuml.sysml2.ConnectionLineStyle.DASHED)
                    val medium = partDef(name = "Medium")
                    val a = partDef(name = "Regler")
                    val b = partDef(name = "Klappe")
                    val owner =
                        partDef(name = "Anlage") {
                            part(name = "regler", typeId = a.id)
                            part(name = "klappe", typeId = b.id)
                            connect(
                                name = "c1",
                                typeId = link.id,
                                sourceEndId = "regler",
                                targetEndId = "klappe",
                                direction = dev.kuml.sysml2.ConnectionDirection.FORWARD,
                            )
                            flow(item = medium, from = "regler", to = "klappe")
                        }
                    ibd(name = "Anlage", owner = owner)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 420f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Anlage::regler") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 120f, height = 60f))),
                            NodeId("Anlage::klappe") to
                                NodeLayout(bounds = Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 120f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            dev.kuml.layout.EdgeId("conn:Anlage::c1") to
                                dev.kuml.layout.EdgeRoute
                                    .Direct(source = Point(x = 140f, y = 40f), target = Point(x = 260f, y = 40f)),
                        ),
                    groups = emptyMap(),
                )
            val tex = KumlLatexRenderer.toLatex(model = model, diagram = ibd, layoutResult = layout)
            tex shouldContain "kuml-sysml2-edge-dashed-noarrow, -{Straight Barb"
        }
    })
