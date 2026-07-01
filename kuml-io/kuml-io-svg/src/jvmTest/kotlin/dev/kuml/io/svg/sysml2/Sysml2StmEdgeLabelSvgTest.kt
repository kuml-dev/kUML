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
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.13 — verifies the `trigger [guard] / effect` STM transition label
 * format appears verbatim in the SVG output.
 */
class Sysml2StmEdgeLabelSvgTest :
    StringSpec({

        val model =
            Sysml2Model(
                name = "Engine",
                definitions =
                    listOf(
                        StateDefinition(id = "Off", name = "Off"),
                        StateDefinition(id = "On", name = "On"),
                    ),
                usages =
                    listOf(
                        TransitionUsage(
                            id = "transition:Off::On",
                            name = "powerOn",
                            sourceStateId = "Off",
                            targetStateId = "On",
                            trigger = "powerOn",
                            guard = "ready",
                            effect = "boot()",
                        ),
                    ),
            )
        val diagram = StmDiagram(name = "Engine", elementIds = listOf("Off", "On"))
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(500f, 200f),
                nodes =
                    mapOf(
                        NodeId("Off") to NodeLayout(bounds = Rect(Point(40f, 60f), Size(120f, 60f))),
                        NodeId("On") to NodeLayout(bounds = Rect(Point(300f, 60f), Size(120f, 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("transition:Off::On") to
                            EdgeRoute.Direct(source = Point(160f, 90f), target = Point(300f, 90f)),
                    ),
                groups = emptyMap(),
            )

        "STM transition label trigger [guard] / effect appears in SVG" {
            val svg = KumlSvgRenderer.toSvg(model, diagram, layout, PlainTheme())
            svg shouldContain "powerOn [ready] / boot()"
            SampleOutput.write("sysml2-edge-labels/stm-transition.svg", svg)
        }
    })
