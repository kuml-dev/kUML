@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.IbdViewBox
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
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
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL 2C-b1: the IBD view box contains every text. Font independent: the texts are measured with the renderer's own
 * measure function ([IbdViewBox.measure]); the rendered font is never consulted.
 */
class IbdViewBoxTest :
    FunSpec({
        val model =
            sysml2Model(name = "M") {
                portDef(name = "Signal")
                connectionDef(name = "Signalleitung")
                partDef(name = "Sensor") { port(name = "messung", typeId = "Signal") }
                partDef(name = "Auswertung") { port(name = "eingang", typeId = "Signal") }
                partDef(name = "Messsystem") {
                    part(name = "sensor", typeId = "Sensor")
                    part(name = "auswertung", typeId = "Auswertung")
                    connect(
                        name = "messleitung",
                        typeId = "Signalleitung",
                        sourceEndId = "sensor.messung",
                        targetEndId = "auswertung.eingang",
                    )
                }
            }
        val ibd = IbdDiagram(name = "mess", ownerId = "Messsystem")

        // The right part touches the canvas border: its connector and port labels reach beyond the layout canvas.
        val layout =
            LayoutResult(
                engineId = LayoutEngineId("t"),
                seed = 1L,
                canvas = Size(width = 330f, height = 140f),
                nodes =
                    mapOf(
                        NodeId("Messsystem::sensor") to NodeLayout(bounds = Rect(Point(0f, 20f), Size(120f, 60f))),
                        NodeId("Messsystem::auswertung") to NodeLayout(bounds = Rect(Point(210f, 20f), Size(120f, 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("conn:Messsystem::messleitung") to
                            EdgeRoute.Direct(Point(120f, 50f), Point(210f, 50f)),
                    ),
                groups = emptyMap(),
            )

        test("every text lies inside the view box and width/height equal the view box size") {
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())
            val vb = IbdViewBox.rootViewBox(svg)!!
            val boxes = IbdViewBox.textBoxes(svg = svg, measure = IbdViewBox::measure)
            (boxes.size >= 4) shouldBe true
            for (t in boxes) {
                val ok =
                    t.rect.origin.x >= vb.origin.x - 1f &&
                        t.rect.origin.y >= vb.origin.y - 1f &&
                        t.rect.origin.x + t.rect.size.width <= vb.origin.x + vb.size.width + 1f &&
                        t.rect.origin.y + t.rect.size.height <= vb.origin.y + vb.size.height + 1f
                (t.content to ok) shouldBe (t.content to true)
            }
            Regex("""<svg[^>]*\swidth="([^"]+)"""").find(svg)!!.groupValues[1].toFloat() shouldBe vb.size.width
            Regex("""<svg[^>]*\sheight="([^"]+)"""").find(svg)!!.groupValues[1].toFloat() shouldBe vb.size.height
        }

        test("an IBD that fits keeps the origin at 0 0") {
            val small = layout.copy(canvas = Size(width = 900f, height = 400f))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = small, theme = PlainTheme())
            val vb = IbdViewBox.rootViewBox(svg)!!
            vb.origin shouldBe Point(0f, 0f)
        }

        test("watermark adds exactly one band to the fitted view box (no extra empty strip)") {
            val plain = KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = layout, theme = PlainTheme())
            val marked =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = ibd,
                    layoutResult = layout,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(watermark = true),
                )
            val a = IbdViewBox.rootViewBox(plain)!!
            val b = IbdViewBox.rootViewBox(marked)!!
            a.origin shouldBe b.origin
            a.size.width shouldBe b.size.width
            b.size.height shouldBe a.size.height + 18f
        }

        test("fit treats the watermark band as chrome, not as free canvas") {
            val svg =
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 118" width="200" height="118">""" +
                    """<text x="10" y="108" font-size="12px">hello</text></svg>"""
            val empty =
                LayoutResult(
                    engineId = LayoutEngineId("t"),
                    seed = 1L,
                    canvas = Size(width = 168f, height = 68f),
                    nodes = emptyMap(),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            // Without a band the text lies inside the view box; with the 18 px band it reaches into the chrome area.
            IbdViewBox.fit(svg = svg, layout = empty, padding = 16f) shouldBe null
            val grown = IbdViewBox.fit(svg = svg, layout = empty, padding = 16f, watermarkBand = 18f)!!
            (grown.origin.y + grown.size.height >= 108f + 18f) shouldBe true
        }
    })
