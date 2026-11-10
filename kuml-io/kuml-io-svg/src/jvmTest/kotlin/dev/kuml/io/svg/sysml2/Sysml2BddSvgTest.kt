package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.KumlMetaValue
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
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.projection.DEFAULT_LITERAL_META_KEY
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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
                    canvas = Size(width = 280f, height = 140f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 240f, height = 94f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())

            // SvgBuilder pretty-prints with line breaks inside text tags — assert
            // the rendered content tokens rather than literal `>X<` adjacency.
            svg shouldContain "«part def»"
            svg shouldContain "Vehicle"
            // wave 2E-b: number without ".0", unit after a non-breaking space
            svg shouldContain "curbWeight : Mass = 1500\u00A0kg"
            svg shouldContain "topSpeed : Speed = 180\u00A0km/h"

            SampleOutput.write(filename = "sysml2-bdd/single-part-with-attributes.svg", content = svg)
        }

        "defaultLiteral is drawn verbatim while a numeric default is normalised" {
            val num = AttributeDefinition(id = "Zahl", name = "Zahl")
            val box =
                PartDefinition(
                    id = "Kennwert",
                    name = "Kennwert",
                    features =
                        listOf(
                            KermlFeature(
                                id = "Kennwert::textEins",
                                name = "textEins",
                                typeId = "Zahl",
                                defaultExpression = "1.0",
                                metadata = mapOf(DEFAULT_LITERAL_META_KEY to KumlMetaValue.Flag(value = true)),
                            ),
                            KermlFeature(id = "Kennwert::zahlEins", name = "zahlEins", typeId = "Zahl", defaultExpression = "1.0"),
                        ),
                )
            val model = Sysml2Model(name = "Demo", definitions = listOf(num, box))
            val bdd = BdDiagram(name = "Literal", elementIds = listOf("Kennwert"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 280f, height = 140f),
                    nodes =
                        mapOf(
                            NodeId("Kennwert") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 240f, height = 94f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "textEins : Zahl = 1.0"
            svg shouldContain "zahlEins : Zahl = 1"
            svg shouldNotContain "zahlEins : Zahl = 1.0"
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
                    canvas = Size(width = 200f, height = 90f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 180f, height = 60f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-title kuml-title-abstract"
            svg shouldContain "font-style=\"italic\""
            SampleOutput.write(filename = "sysml2-bdd/abstract-part.svg", content = svg)
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
                                multiplicity = KermlMultiplicity(lower = 8, upper = 8),
                            ),
                        ),
                )
            val model = Sysml2Model(name = "Engine", definitions = listOf(v8))
            val bdd = BdDiagram(name = "Engine", elementIds = listOf("V8Engine"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 260f, height = 110f),
                    nodes =
                        mapOf(
                            NodeId("V8Engine") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 240f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())

            // multiplicity 8..8 collapses to the single "[8]" form.
            svg shouldContain "cylinders : Cylinder [8]"
            SampleOutput.write(filename = "sysml2-bdd/v8-cylinder-multiplicity.svg", content = svg)
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
                    canvas = Size(width = 420f, height = 220f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 20f), size = Size(width = 180f, height = 60f))),
                            NodeId("HybridVehicle") to
                                NodeLayout(bounds = Rect(origin = Point(x = 120f, y = 140f), size = Size(width = 180f, height = 60f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("gen:HybridVehicle::Vehicle") to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(x = 210f, y = 140f),
                                    target = Point(x = 210f, y = 80f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 4f,
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "Vehicle"
            svg shouldContain "HybridVehicle"
            // The edge uses the default `kuml-edge` style — fallback path; the
            // SysML-2-specific Generalisation arrow lives in a follow-up V2.x wave.
            SampleOutput.write(filename = "sysml2-bdd/specialisation.svg", content = svg)
        }

        "multiplicity 1 and 4 are both drawn at the edge end, a flow port shows once as a flow line (Nacharbeit 52)" {
            val model =
                sysml2Model(name = "N52") {
                    attributeDef(name = "Mass")
                    portDef(name = "FuelPort") { flowProperty(name = "fuel", typeId = "Mass", direction = PortDirection.In) }
                    val lock = partDef(name = "DoorLock")
                    val ctl = partDef(name = "Ctl")
                    partDef(name = "Pump") { port(name = "fuelIn", typeId = "FuelPort", direction = PortDirection.In) }
                    val door =
                        partDef(name = "Door") {
                            part(name = "lock", typeId = lock.id)
                            part(name = "ctl", typeId = ctl.id, multiplicity = KermlMultiplicity(lower = 4, upper = 4))
                        }
                    bdd(name = "B") {
                        include(door)
                        include(lock)
                        include(ctl)
                    }
                }
            val bdd = BdDiagram(name = "B", elementIds = listOf("Door", "DoorLock", "Ctl", "Pump", "Mass"))

            fun box(
                x: Float,
                y: Float,
            ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = 160f, height = 70f)))

            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 620f, height = 400f),
                    nodes =
                        mapOf(
                            NodeId("Door") to box(x = 220f, y = 20f),
                            NodeId("DoorLock") to box(x = 40f, y = 200f),
                            NodeId("Ctl") to box(x = 400f, y = 200f),
                            NodeId("Pump") to box(x = 220f, y = 300f),
                            NodeId("Mass") to box(x = 440f, y = 300f),
                        ),
                    edges =
                        mapOf(
                            EdgeId("comp:Door::lock") to
                                EdgeRoute.Direct(source = Point(x = 260f, y = 90f), target = Point(x = 120f, y = 200f)),
                            EdgeId("comp:Door::ctl") to
                                EdgeRoute.Direct(source = Point(x = 340f, y = 90f), target = Point(x = 480f, y = 200f)),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())

            SampleOutput.write(filename = "sysml2-bdd/multiplicity-one-and-flow-port.svg", content = svg)
            val texts = Regex("""<text[^>]*>([^<]*)</text>""").findAll(svg).map { it.groupValues[1].trim() }.toList()
            texts.count { it == "1" } shouldBe 1
            texts.count { it == "4" } shouldBe 1
            texts.count { it == "in fuelIn.fuel : Mass" } shouldBe 1
            texts.none { it.contains("fuelIn : FuelPort") } shouldBe true
        }

        "deterministic output — same input renders byte-identically" {
            val v = PartDefinition(id = "V", name = "V")
            val model = Sysml2Model(name = "M", definitions = listOf(v))
            val bdd = BdDiagram(name = "Det", elementIds = listOf("V"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 200f, height = 80f),
                    nodes =
                        mapOf(
                            NodeId("V") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 200f, height = 60f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())
            val two = KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = layout, theme = PlainTheme())
            one shouldBe two
        }
    })
