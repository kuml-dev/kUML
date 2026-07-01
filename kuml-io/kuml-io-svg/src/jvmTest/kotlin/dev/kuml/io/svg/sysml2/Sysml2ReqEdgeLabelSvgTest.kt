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
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UseCaseDefinition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.13 — verifies that all four SysML 2 traceability stereotypes
 * (`«satisfy»`, `«verify»`, `«deriveReqt»`, `«containment»`) appear in
 * the SVG output and that the edges render dashed.
 */
class Sysml2ReqEdgeLabelSvgTest :
    StringSpec({

        val model =
            Sysml2Model(
                name = "Reqs",
                definitions =
                    listOf(
                        PartDefinition(id = "Vehicle", name = "Vehicle"),
                        UseCaseDefinition(id = "TopSpeedTest", name = "TopSpeedTest"),
                        RequirementDefinition(id = "TopSpeedReq", name = "TopSpeedReq", reqId = "R-001"),
                        RequirementDefinition(id = "SafetyReq", name = "SafetyReq", reqId = "R-002"),
                        RequirementDefinition(id = "BrakingReq", name = "BrakingReq", reqId = "R-003"),
                    ),
            )
        val diagram =
            ReqDiagram(
                name = "Reqs",
                elementIds = listOf("Vehicle", "TopSpeedTest", "TopSpeedReq", "SafetyReq", "BrakingReq"),
                satisfies =
                    listOf(
                        ReqSatisfy(id = "satisfy:Vehicle::TopSpeedReq", sourceId = "Vehicle", requirementId = "TopSpeedReq"),
                    ),
                verifies =
                    listOf(
                        ReqVerify(
                            id = "verify:TopSpeedTest::TopSpeedReq",
                            sourceId = "TopSpeedTest",
                            requirementId = "TopSpeedReq",
                        ),
                    ),
                derives =
                    listOf(
                        ReqDerive(
                            id = "derive:BrakingReq::SafetyReq",
                            sourceRequirementId = "BrakingReq",
                            targetRequirementId = "SafetyReq",
                        ),
                    ),
                contains =
                    listOf(
                        ReqContains(
                            id = "contains:SafetyReq::BrakingReq",
                            parentRequirementId = "SafetyReq",
                            childRequirementId = "BrakingReq",
                        ),
                    ),
            )

        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(900f, 400f),
                nodes =
                    mapOf(
                        NodeId("Vehicle") to NodeLayout(bounds = Rect(Point(20f, 40f), Size(120f, 80f))),
                        NodeId("TopSpeedTest") to NodeLayout(bounds = Rect(Point(20f, 160f), Size(160f, 70f))),
                        NodeId("TopSpeedReq") to NodeLayout(bounds = Rect(Point(300f, 40f), Size(180f, 100f))),
                        NodeId("SafetyReq") to NodeLayout(bounds = Rect(Point(600f, 40f), Size(180f, 100f))),
                        NodeId("BrakingReq") to NodeLayout(bounds = Rect(Point(600f, 200f), Size(180f, 100f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("satisfy:Vehicle::TopSpeedReq") to
                            EdgeRoute.Direct(source = Point(140f, 80f), target = Point(300f, 80f)),
                        EdgeId("verify:TopSpeedTest::TopSpeedReq") to
                            EdgeRoute.Direct(source = Point(180f, 195f), target = Point(330f, 140f)),
                        EdgeId("derive:BrakingReq::SafetyReq") to
                            EdgeRoute.Direct(source = Point(690f, 200f), target = Point(690f, 140f)),
                        EdgeId("contains:SafetyReq::BrakingReq") to
                            EdgeRoute.Direct(source = Point(720f, 140f), target = Point(720f, 200f)),
                    ),
                groups = emptyMap(),
            )

        "all four REQ stereotypes appear in the SVG output" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "«satisfy»"
            svg shouldContain "«verify»"
            svg shouldContain "«deriveReqt»"
            svg shouldContain "«containment»"
            SampleOutput.write("sysml2-edge-labels/req-traceability.svg", svg)
        }

        "REQ edges are styled dashed" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "stroke-dasharray"
        }
    })
