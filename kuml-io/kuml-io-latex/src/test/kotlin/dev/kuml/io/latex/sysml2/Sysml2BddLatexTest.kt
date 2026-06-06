package dev.kuml.io.latex.sysml2

import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.SampleOutput
import dev.kuml.kerml.KermlFeature
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * BDD-TikZ-Render-Tests, schreiben ihre Outputs in `build/sample-output/sysml2-bdd/`
 * sodass man sie mit `pdflatex` (oder ein passendes `\input{}`-Snippet)
 * visuell verifizieren kann.
 */
class Sysml2BddLatexTest :
    StringSpec({

        "PartDefinition mit Attributen produziert eine BDD-Box im TikZ-Snippet" {
            val mass = AttributeDefinition(id = "Mass", name = "Mass")
            val vehicle =
                PartDefinition(
                    id = "Vehicle",
                    name = "Vehicle",
                    features =
                        listOf(
                            KermlFeature(
                                id = "Vehicle::curbWeight",
                                name = "curbWeight",
                                typeId = "Mass",
                                defaultExpression = "1500.0[kg]",
                            ),
                        ),
                )
            val model = Sysml2Model(name = "Demo", definitions = listOf(mass, vehicle))
            val bdd = BdDiagram(name = "Overview", elementIds = listOf("Vehicle"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(280f, 200f),
                    nodes = mapOf(NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(240f, 160f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, bdd, layout)

            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\guillemotleft{}part def\\guillemotright{}"
            tex shouldContain "Vehicle"
            tex shouldContain "curbWeight : Mass = 1500.0[kg]"

            SampleOutput.write("sysml2-bdd/single-part-with-attribute.tex", tex)
        }

        "Standalone-Modus produziert kompilierbares .tex-File" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle", isAbstract = true)
            val model = Sysml2Model(name = "Standalone", definitions = listOf(vehicle))
            val bdd = BdDiagram(name = "Abs", elementIds = listOf("Vehicle"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(220f, 160f),
                    nodes = mapOf(NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(200f, 140f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex =
                KumlLatexRenderer.toLatex(
                    model,
                    bdd,
                    layout,
                    LatexRenderOptions(standalone = true),
                )

            tex shouldContain "\\documentclass[border=10pt]{standalone}"
            tex shouldContain "\\usepackage{tikz}"
            tex shouldContain "\\begin{document}"
            tex shouldContain "\\end{document}"
            tex shouldContain "kuml-classname-abstract"

            SampleOutput.write("sysml2-bdd/abstract-part-standalone.tex", tex)
        }

        "AttributeDefinition + PortDefinition picken die jeweiligen Stereotype" {
            val attrs = AttributeDefinition(id = "Mass", name = "Mass")
            val port = PortDefinition(id = "PowerPort", name = "PowerPort")
            val model = Sysml2Model(name = "Mixed", definitions = listOf(attrs, port))
            val bdd = BdDiagram(name = "Mixed", elementIds = listOf("Mass", "PowerPort"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(440f, 180f),
                    nodes =
                        mapOf(
                            NodeId("Mass") to NodeLayout(bounds = Rect(Point(20f, 20f), Size(180f, 120f))),
                            NodeId("PowerPort") to NodeLayout(bounds = Rect(Point(240f, 20f), Size(180f, 120f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, bdd, layout)

            tex shouldContain "attribute def"
            tex shouldContain "port def"

            SampleOutput.write("sysml2-bdd/attribute-and-port-defs.tex", tex)
        }

        "Specialisation between two parts renders as a generalisation edge" {
            val vehicle = PartDefinition(id = "Vehicle", name = "Vehicle", isAbstract = true)
            val hybrid =
                PartDefinition(
                    id = "HybridVehicle",
                    name = "HybridVehicle",
                    specializations = listOf(KermlSpecialization(specificId = "HybridVehicle", generalId = "Vehicle")),
                )
            val model = Sysml2Model(name = "Spec", definitions = listOf(vehicle, hybrid))
            val bdd = BdDiagram(name = "Inheritance", elementIds = listOf("Vehicle", "HybridVehicle"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(420f, 360f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(120f, 20f), Size(180f, 100f))),
                            NodeId("HybridVehicle") to NodeLayout(bounds = Rect(Point(120f, 180f), Size(180f, 100f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("gen:HybridVehicle::Vehicle") to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(210f, 180f),
                                    target = Point(210f, 120f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 4f,
                                ),
                        ),
                    groups = emptyMap(),
                )

            val tex = KumlLatexRenderer.toLatex(model, bdd, layout)
            tex shouldContain "Vehicle"
            tex shouldContain "HybridVehicle"
            SampleOutput.write("sysml2-bdd/specialisation.tex", tex)
        }

        "deterministic output" {
            val v = PartDefinition(id = "V", name = "V")
            val model = Sysml2Model(name = "M", definitions = listOf(v))
            val bdd = BdDiagram(name = "Det", elementIds = listOf("V"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(200f, 140f),
                    nodes = mapOf(NodeId("V") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(200f, 140f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlLatexRenderer.toLatex(model, bdd, layout)
            val two = KumlLatexRenderer.toLatex(model, bdd, layout)
            one shouldBe two
        }
    })
