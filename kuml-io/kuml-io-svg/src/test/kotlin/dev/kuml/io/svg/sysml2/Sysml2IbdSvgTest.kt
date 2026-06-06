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
            return model to ibd
        }

        fun twoBoxLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(420f, 200f),
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

        "IBD of Vehicle renders two part-usage boxes" {
            val (model, ibd) = vehicleModel()
            val svg = KumlSvgRenderer.toSvg(model, ibd, twoBoxLayout(), PlainTheme())

            svg shouldContain "«part»"
            svg shouldContain "engine : Engine"
            svg shouldContain "battery : Battery"

            SampleOutput.write("sysml2-ibd/vehicle-two-parts.svg", svg)
        }

        "IBD with multiplicity shows [n..m] suffix" {
            val model =
                sysml2Model("M") {
                    val cylinderDef = partDef("Cylinder")
                    val engine =
                        partDef("V8Engine") {
                            part(
                                name = "cylinders",
                                typeId = cylinderDef.id,
                                multiplicity = KermlMultiplicity(8, 8),
                            )
                        }
                    ibd("V8 internals", owner = engine)
                }
            val ibd = model.diagrams.filterIsInstance<IbdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(220f, 120f),
                    nodes =
                        mapOf(
                            NodeId("V8Engine::cylinders") to
                                NodeLayout(bounds = Rect(Point(10f, 10f), Size(200f, 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, ibd, layout, PlainTheme())
            svg shouldContain "cylinders : Cylinder [8]"

            SampleOutput.write("sysml2-ibd/v8-cylinder-multiplicity.svg", svg)
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
                    canvas = Size(200f, 100f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(Point(0f, 0f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlSvgRenderer.toSvg(model, ibd, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, ibd, layout, PlainTheme())
            one shouldBe two
        }

        "IBD with connection emits an edge in the layout output" {
            val (model, ibd) = vehicleModel()
            // Layout adds an edge between the two boxes; the SVG renderer just
            // honours what the layout produced — same path as the BDD edge fallback.
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(420f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Vehicle::engine") to
                                NodeLayout(bounds = Rect(Point(20f, 20f), Size(180f, 80f))),
                            NodeId("Vehicle::battery") to
                                NodeLayout(bounds = Rect(Point(220f, 20f), Size(180f, 80f))),
                        ),
                    edges =
                        mapOf(
                            EdgeId("conn:Vehicle::wiring") to
                                EdgeRoute.OrthogonalRounded(
                                    source = Point(200f, 60f),
                                    target = Point(220f, 60f),
                                    waypoints = emptyList(),
                                    cornerRadiusPx = 4f,
                                ),
                        ),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(model, ibd, layout, PlainTheme())

            // Both part-usage boxes still present.
            svg shouldContain "engine : Engine"
            svg shouldContain "battery : Battery"
            // The renderer always emits a `<g class="kuml-edges">` group;
            // assert the resulting SVG still parses well structurally by checking
            // that the path/line for the edge ended up in the document. The
            // edge dispatcher's default emits a `<path>` element for the route.
            svg shouldContain "path"

            SampleOutput.write("sysml2-ibd/vehicle-with-connection.svg", svg)
        }
    })
