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
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.13 — verifies that ACT ControlFlow `[guard]` and ObjectFlow
 * `[ObjectType]` labels appear in the SVG output.
 */
class Sysml2ActEdgeLabelSvgTest :
    StringSpec({

        val model =
            Sysml2Model(
                name = "Order",
                definitions =
                    listOf(
                        ActionDefinition(id = "Start", name = "Start", kind = ActivityNodeKind.Initial),
                        ActionDefinition(id = "Validate", name = "Validate"),
                        ActionDefinition(id = "Ship", name = "Ship"),
                    ),
                usages =
                    listOf(
                        ControlFlowUsage(
                            id = "controlFlow:Start::Validate",
                            name = "f1",
                            sourceNodeId = "Start",
                            targetNodeId = "Validate",
                            guard = "ready",
                        ),
                        ObjectFlowUsage(
                            id = "objectFlow:Validate::Ship",
                            name = "f2",
                            sourceNodeId = "Validate",
                            targetNodeId = "Ship",
                            objectType = "Order",
                        ),
                    ),
            )
        val diagram = ActDiagram(name = "Order", elementIds = listOf("Start", "Validate", "Ship"))
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(600f, 200f),
                nodes =
                    mapOf(
                        NodeId("Start") to NodeLayout(bounds = Rect(Point(40f, 80f), Size(30f, 30f))),
                        NodeId("Validate") to NodeLayout(bounds = Rect(Point(160f, 60f), Size(140f, 60f))),
                        NodeId("Ship") to NodeLayout(bounds = Rect(Point(380f, 60f), Size(140f, 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("controlFlow:Start::Validate") to
                            EdgeRoute.Direct(source = Point(70f, 95f), target = Point(160f, 90f)),
                        EdgeId("objectFlow:Validate::Ship") to
                            EdgeRoute.Direct(source = Point(300f, 90f), target = Point(380f, 90f)),
                    ),
                groups = emptyMap(),
            )

        "ControlFlow [guard] label appears in SVG" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "[ready]"
            SampleOutput.write("sysml2-edge-labels/act-flows.svg", svg)
        }

        "ObjectFlow [Order] label appears in SVG" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "[Order]"
        }
    })
