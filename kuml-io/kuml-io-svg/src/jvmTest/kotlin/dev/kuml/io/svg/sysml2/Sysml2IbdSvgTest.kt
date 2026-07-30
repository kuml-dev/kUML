package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.kerml.KermlMultiplicity
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
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-IBD-SVG-Renderer (V2.0.6).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-ibd/<name>.svg`, sodass es
 * im Browser visuell überprüft werden kann. Die Assertion-Stärke bleibt
 * der Inline-Inhalt; das Sample-Output ist nur Komfort.
 */
class Sysml2IbdSvgTest :
    StringSpec({

        // Helper to build a small Vehicle/Engine/Battery model with two part-usages.
        fun vehicleModel(): Pair<Sysml2Model, IbdDiagram> {
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
            return model to ibd
        }

        fun twoBoxLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 420f, height = 200f),
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

        "IBD of Vehicle renders two part-usage boxes" {
            val (model, ibd) = vehicleModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = twoBoxLayout(), theme = PlainTheme())

            svg shouldContain "«part»"
            svg shouldContain "engine : Engine"
            svg shouldContain "battery : Battery"

            SampleOutput.write(filename = "sysml2-ibd/vehicle-two-parts.svg", content = svg)
        }

        "IBD with multiplicity shows [n..m] suffix" {
            val model =
                sysml2Model(name = "M") {
                    val cylinderDef = partDef(name = "Cylinder")
                    val engine =
                        partDef(name = "V8Engine") {
                            part(
                                name = "cylinders",
                                typeId = cylinderDef.id,
                                multiplicity = KermlMultiplicity(lower = 8, upper = 8),
                            )
                        }
                    ibd(name = "V8 internals", owner = engine)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 220f, height = 120f),
                    nodes =
                        mapOf(
                            NodeId("V8Engine::cylinders") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 200f, height = 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "cylinders : Cylinder [8]"

            SampleOutput.write(filename = "sysml2-ibd/v8-cylinder-multiplicity.svg", content = svg)
        }

        "deterministic output — same input renders byte-identically" {
            // Use a hand-constructed PartUsage so the model isn't mutated by the
            // DSL each invocation — kotlin-script `sysml2Model` is itself
            // deterministic, but constructing twice for this test avoids the
            // overhead.
            val model =
                Sysml2Model(
                    name = "Det",
                    usages =
                        listOf(
                            PartUsage(
                                id = "Vehicle::engine",
                                name = "engine",
                                qualifiedName = "Vehicle::engine",
                                definitionId = "Engine",
                            ),
                        ),
                )
            val ibd = IbdDiagram(name = "Det", ownerId = "Vehicle")
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
            val one = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())
            val two = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())
            one shouldBe two
        }

        "IBD with ports renders port squares at edge attachment points" {
            val model =
                sysml2Model(name = "HybridSystem") {
                    val powerLine = connectionDef(name = "PowerLine")
                    val dcPort = portDef(name = "DcPort")
                    val battery =
                        partDef(name = "Battery") {
                            port(name = "dcOut", typeId = dcPort.id)
                        }
                    val motor =
                        partDef(name = "ElectricMotor") {
                            port(name = "dcIn", typeId = dcPort.id)
                        }
                    val hybrid =
                        partDef(name = "HybridVehicle") {
                            part(name = "battery", typeId = battery.id)
                            part(name = "electricMotor", typeId = motor.id)
                            connect(
                                name = "batteryToMotor",
                                typeId = powerLine.id,
                                sourceEndId = "HybridVehicle::battery::dcOut",
                                targetEndId = "HybridVehicle::electricMotor::dcIn",
                            )
                        }
                    ibd(name = "HybridVehicle IBD", owner = hybrid)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 500f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("HybridVehicle::battery") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 200f, height = 80f))),
                            NodeId("HybridVehicle::electricMotor") to
                                NodeLayout(bounds = Rect(origin = Point(x = 280f, y = 20f), size = Size(width = 200f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("conn:HybridVehicle::batteryToMotor") to
                                EdgeRoute.Direct(
                                    source = Point(x = 220f, y = 60f), // right edge of battery box
                                    target = Point(x = 280f, y = 60f), // left edge of motor box
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())

            svg shouldContain "kuml-port"
            svg shouldContain "dcOut"
            svg shouldContain "dcIn"

            SampleOutput.write(filename = "sysml2-ibd/hybrid-with-ports.svg", content = svg)
        }

        "IBD with connection emits an edge in the layout output" {
            val (model, ibd) = vehicleModel()
            // Layout adds an edge between the two boxes; the SVG renderer just
            // honours what the layout produced — same path as the BDD edge fallback.
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 420f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 180f, height = 80f))),
                            NodeId("Vehicle::battery") to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 20f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("conn:Vehicle::wiring") to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(x = 200f, y = 60f),
                                    target = Point(x = 220f, y = 60f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 4f,
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())

            // Both part-usage boxes still present.
            svg shouldContain "engine : Engine"
            svg shouldContain "battery : Battery"
            // The renderer always emits a `<g class="kuml-edges">` group;
            // assert the resulting SVG still parses well structurally by checking
            // that the path/line for the edge ended up in the document. The
            // edge dispatcher's default emits a `<path>` element for the route.
            svg shouldContain "path"

            SampleOutput.write(filename = "sysml2-ibd/vehicle-with-connection.svg", content = svg)
        }
    })
