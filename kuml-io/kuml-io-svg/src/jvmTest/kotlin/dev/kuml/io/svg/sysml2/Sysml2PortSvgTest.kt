package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class Sysml2PortSvgTest :
    StringSpec({
        "bdd box shows direction words, conjugation, unit and compartments; text is escaped" {
            val evil = "A<b>&\"x"
            val model =
                sysml2Model(name = "M") {
                    attributeDef(name = "Mass", unitSymbol = "k<g>")
                    portDef(name = "P") { flowProperty(name = "f", typeId = "Mass", direction = PortDirection.Out) }
                    partDef(name = "Blk", id = "Blk") {
                        port(name = "p", typeId = "P", direction = PortDirection.In, conjugated = true)
                        action(name = evil, typeId = "Act")
                    }
                    bdd(name = "b") {
                        includeById("Blk")
                        includeById("Mass")
                    }
                }
            val d = model.diagrams.filterIsInstance<BdDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 600f, height = 300f),
                    nodes =
                        mapOf(
                            NodeId("Blk") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 220f, height = 160f))),
                            NodeId("Mass") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 10f), size = Size(width = 220f, height = 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "in p : ~P"
            svg shouldContain "actions"
            svg shouldContain "unit = k&lt;g&gt;"
            svg shouldNotContain "<b>"
            svg shouldNotContain "k<g>"
            (svg.contains("A&lt;b&gt;&amp;")) shouldBe true
        }
    })
