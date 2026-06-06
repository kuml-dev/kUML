package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
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
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-REQ-SVG-Renderer (V2.0.8).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-req/<name>.svg`, sodass es
 * im Browser visuell überprüft werden kann.
 */
class Sysml2ReqSvgTest :
    StringSpec({

        fun vehicleReqModel(): Pair<Sysml2Model, ReqDiagram> {
            val model =
                sysml2Model("VehicleReqs") {
                    val topSpeed =
                        requirementDef(
                            "TopSpeedRequirement",
                            reqId = "R-001",
                            text = "The vehicle shall reach at least 180 km/h on flat road",
                        )
                    val vehicle = partDef("Vehicle")
                    reqDiagram("REQ") {
                        include(topSpeed)
                        include(vehicle)
                        satisfy(vehicle, topSpeed)
                    }
                }
            val req = model.diagrams.filterIsInstance<ReqDiagram>().single()
            return model to req
        }

        fun layoutFor(
            model: Sysml2Model,
            req: ReqDiagram,
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(600f, 240f),
                nodes =
                    mapOf(
                        NodeId("TopSpeedRequirement") to
                            NodeLayout(bounds = Rect(Point(40f, 40f), Size(220f, 120f))),
                        NodeId("Vehicle") to
                            NodeLayout(bounds = Rect(Point(340f, 60f), Size(220f, 140f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("satisfy:Vehicle::TopSpeedRequirement") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(340f, 130f),
                                target = Point(260f, 100f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
                groups = emptyMap(),
            ).also { _ ->
                require(model.name.isNotEmpty())
                require(req.elementIds.isNotEmpty())
            }

        "REQ renders requirement box with «requirement» stereotype and text" {
            val (model, req) = vehicleReqModel()
            val svg = KumlSvgRenderer.toSvg(model, req, layoutFor(model, req), PlainTheme())

            svg shouldContain "«requirement»"
            // Title compartment with R-NNN :: Name format.
            svg shouldContain "R-001 :: TopSpeedRequirement"
            // The text compartment word-wraps the requirement statement; first
            // word always appears.
            svg shouldContain "The vehicle"
            // Vehicle Part still renders (BDD box path).
            svg shouldContain "Vehicle"

            SampleOutput.write("sysml2-req/vehicle-req.svg", svg)
        }

        "REQ requirement with empty text omits the text compartment" {
            val model =
                Sysml2Model(
                    name = "Empty",
                    definitions =
                        listOf(
                            RequirementDefinition(
                                id = "R1",
                                name = "R1",
                                reqId = "R-001",
                                text = "",
                            ),
                        ),
                )
            val req = ReqDiagram(name = "REQ", elementIds = listOf("R1"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes =
                        mapOf(
                            NodeId("R1") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(220f, 120f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model, req, layout, PlainTheme())
            svg shouldContain "«requirement»"
            svg shouldContain "R-001 :: R1"
            // No divider line ⇒ no `<line ... class="kuml-divider"/>` element in
            // the node group. (The `.kuml-divider` CSS class is always present
            // in the embedded stylesheet — that's the document-level theme,
            // independent of whether any divider line is actually drawn.)
            svg shouldNotContain "class=\"kuml-divider\""

            SampleOutput.write("sysml2-req/empty-text.svg", svg)
        }

        "REQ requirement with reqId shows R-NNN :: Name format" {
            val model =
                Sysml2Model(
                    name = "IdShape",
                    definitions =
                        listOf(
                            RequirementDefinition(
                                id = "TopSpeed",
                                name = "TopSpeed",
                                reqId = "R-042",
                                text = "must be fast",
                            ),
                            RequirementDefinition(
                                id = "NoId",
                                name = "NoId",
                                reqId = "",
                                text = "no reqId here",
                            ),
                        ),
                )
            val req = ReqDiagram(name = "REQ", elementIds = listOf("TopSpeed", "NoId"))
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(600f, 200f),
                    nodes =
                        mapOf(
                            NodeId("TopSpeed") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(220f, 120f))),
                            NodeId("NoId") to NodeLayout(bounds = Rect(Point(260f, 0f), Size(220f, 120f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model, req, layout, PlainTheme())
            // With reqId set: prefixed title.
            svg shouldContain "R-042 :: TopSpeed"
            // Without reqId: bare name only — never prefixed.
            svg shouldContain "NoId"
            svg shouldNotContain " :: NoId"

            SampleOutput.write("sysml2-req/with-and-without-reqId.svg", svg)
        }

        "deterministic output — same input renders byte-identically" {
            val model =
                Sysml2Model(
                    name = "Det",
                    definitions =
                        listOf(
                            RequirementDefinition(
                                id = "R1",
                                name = "R1",
                                reqId = "R-001",
                                text = "Stable across runs",
                            ),
                        ),
                )
            val req =
                ReqDiagram(
                    name = "Det",
                    elementIds = listOf("R1"),
                    satisfies =
                        listOf(
                            ReqSatisfy(
                                id = "satisfy:Vehicle::R1",
                                sourceId = "Vehicle",
                                requirementId = "R1",
                            ),
                        ),
                    verifies = emptyList(),
                    derives = emptyList(),
                    contains = emptyList(),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(300f, 200f),
                    nodes =
                        mapOf(
                            NodeId("R1") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(220f, 120f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val one = KumlSvgRenderer.toSvg(model, req, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, req, layout, PlainTheme())
            one shouldBe two

            SampleOutput.write("sysml2-req/deterministic.svg", one)
        }
    })
