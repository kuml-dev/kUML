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
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 72 (g2) and (b): the drawn stereotype and literal text, string level. */
class Sysml2BddExternalStereotypeTest :
    StringSpec({
        val model =
            sysml2Model(name = "E") {
                val mode = enumerationDef(name = "Mode") { literal(name = "A") }
                val ext = partDef(name = "Ext1", external = true) { attribute(name = "m", typeId = mode.id) }
                val plain = partDef(name = "Plain1")
                bdd(name = "D") {
                    include(ext)
                    include(plain)
                }
            }
        val diagram = model.diagrams.single() as BdDiagram
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 600f, height = 200f),
                nodes =
                    mapOf(
                        NodeId("Ext1") to
                            NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 220f, height = 90f))),
                        NodeId("Plain1") to
                            NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 20f), size = Size(width = 140f, height = 70f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        "external block shows part def, external; plain block keeps part def; literals appear" {
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "«part def, external»"
            svg shouldContain "«part def»"
            svg shouldContain "m : Mode {A}"
            svg shouldNotContain "«part def, external»«"
        }
    })
