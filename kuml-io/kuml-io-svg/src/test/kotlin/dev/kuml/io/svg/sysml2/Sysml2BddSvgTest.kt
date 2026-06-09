package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.kerml.KermlFeature
import dev.kuml.kerml.KermlMultiplicity
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
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Smoke + structural tests für den SysML-2-BDD-SVG-Renderer.
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-bdd/<test-name>.svg`, sodass es
 * im Browser visuell überprüft werden kann. Die Assertion-Stärke bleibt
 * der Inline-Inhalt; das Sample-Output ist nur Komfort.
 */
class Sysml2BddSvgTest :
    StringSpec({

        "single PartDefinition with two attributes renders a 3-compartment BDD box" {
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
                            KermlFeature(
                                id = "Vehicle::topSpeed",
                                name = "topSpeed",
                                typeId = "Speed",
                                defaultExpression = "180.0[km/h]",
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

            val svg = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())

            // SvgBuilder pretty-prints with line breaks inside text tags — assert
            // the rendered content tokens rather than literal `>X<` adjacency.
            svg shouldContain "«part def»"
            svg shouldContain "Vehicle"
            svg shouldContain "curbWeight : Mass = 1500.0[kg]"
            svg shouldContain "topSpeed : Speed = 180.0[km/h]"

            SampleOutput.write("sysml2-bdd/single-part-with-attributes.svg", svg)
        }

        "abstract PartDefinition picks the title-abstract style class" {
            val vehicle =
                PartDefinition(
                    id = "Vehicle",
                    name = "Vehicle",
                    isAbstract = true,
                )
            val model = Sysml2Model(name = "Demo", definitions = listOf(vehicle))
            val bdd = BdDiagram(name = "Abs", elementIds = listOf("Vehicle"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(200f, 140f),
                    nodes = mapOf(NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(180f, 120f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())

            svg shouldContain "kuml-title kuml-title-abstract"
            svg shouldContain "font-style=\"italic\""
            SampleOutput.write("sysml2-bdd/abstract-part.svg", svg)
        }

        "PartUsage multiplicity appears with [n..m] suffix" {
            val v8 =
                PartDefinition(
                    id = "V8Engine",
                    name = "V8Engine",
                    features =
                        listOf(
                            KermlFeature(
                                id = "V8::cylinders",
                                name = "cylinders",
                                typeId = "Cylinder",
                                multiplicity = KermlMultiplicity(8, 8),
                            ),
                        ),
                )
            val model = Sysml2Model(name = "Engine", definitions = listOf(v8))
            val bdd = BdDiagram(name = "Engine", elementIds = listOf("V8Engine"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(260f, 160f),
                    nodes = mapOf(NodeId("V8Engine") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(240f, 140f)))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())

            // multiplicity 8..8 collapses to the single "[8]" form.
            svg shouldContain "cylinders : Cylinder [8]"
            SampleOutput.write("sysml2-bdd/v8-cylinder-multiplicity.svg", svg)
        }

        "Specialisation between two PartDefinitions renders as a generalisation edge" {
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
                    canvas = Size(420f, 320f),
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

            val svg = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())

            svg shouldContain "Vehicle"
            svg shouldContain "HybridVehicle"
            // The edge uses the default `kuml-edge` style — fallback path; the
            // SysML-2-specific Generalisation arrow lives in a follow-up V2.x wave.
            SampleOutput.write("sysml2-bdd/specialisation.svg", svg)
        }

        "deterministic output — same input renders byte-identically" {
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
            val one = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, bdd, layout, PlainTheme())
            one shouldBe two
        }
    })
