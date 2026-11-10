package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionDirection
import dev.kuml.sysml2.ConnectionLineStyle
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 47 (N-IBD-C, F a/b/c) measured on the SVG of the full pipeline (ELK layout, owner frame, labels).
 * Structure only: texts, ids and attributes; no byte comparison and no measured text widths.
 */
class Sysml2IbdWaveMTest :
    FunSpec(body = {
        val nbsp = ' '

        fun renderIbd(m: Sysml2Model): String {
            val d = m.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_IBD,
                    ).result
            val nested = Sysml2LayoutBridge.addIbdNestedFrames(model = m, diagram = d, layoutResult = raw)
            val framed = Sysml2LayoutBridge.addIbdOwnerFrame(model = m, diagram = d, layoutResult = nested)
            return KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = framed, theme = PlainTheme())
        }

        fun renderBdd(m: Sysml2Model): String {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val d = m.diagrams.filterIsInstance<BdDiagram>().single()
            val lr =
                LayoutEngineRegistry
                    .get("elk.layered")!!
                    .layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d), hints = LayoutHints.DEFAULT)
            return KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = lr, theme = PlainTheme())
        }

        fun plant(
            connections: Int,
            direction: ConnectionDirection = ConnectionDirection.NONE,
            withFlow: Boolean = false,
        ): Sysml2Model =
            sysml2Model(name = "Anlage") {
                val medium = partDef(name = "Medium")
                val link = connectionDef(name = "Leitung", style = ConnectionLineStyle.DASHED, color = "#1D2968")
                val port = portDef(name = "Anschluss")
                val quelle = partDef(name = "Quelle") { port(name = "aus", typeId = port.id, direction = PortDirection.Out) }
                val senke = partDef(name = "Senke") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                val anlage =
                    partDef(name = "Anlage") {
                        part(name = "quelle", typeId = quelle.id)
                        part(name = "senke", typeId = senke.id)
                        for (i in 1..connections) {
                            connect(
                                name = "c$i",
                                typeId = link.id,
                                sourceEndId = "quelle.aus",
                                targetEndId = "senke.ein",
                                direction = direction,
                            )
                        }
                        if (withFlow) flow(item = medium, from = "quelle.aus", to = "senke.ein")
                    }
                ibd(name = "Anlage", owner = anlage)
            }

        val headStyle = "stroke:#1D2968;stroke-width:1.5;fill:none;stroke-linejoin:round;"

        test("C: attribute values show unit and no raw decimal in IBD and BDD") {
            val ibd =
                sysml2Model(name = "Raum") {
                    val druck = attributeDef(name = "Raumdruck", unitSymbol = "Pa")
                    val temp = attributeDef(name = "Temperatur")
                    val sensor =
                        partDef(name = "Sensor") {
                            attribute(name = "messwert", typeId = druck.id, default = -5.0)
                            attribute(name = "temperatur", typeId = temp.id, defaultLiteral = "20.0[°C]")
                        }
                    val raum = partDef(name = "Raum") { part(name = "sensor", typeId = sensor.id) }
                    ibd(name = "Raum", owner = raum, showAttributes = true)
                }
            val ibdSvg = renderIbd(ibd)
            ibdSvg shouldContain "messwert : Raumdruck = -5${nbsp}Pa"
            // Nacharbeit 51: a defaultLiteral is shown verbatim, only the numeric default is normalised.
            ibdSvg shouldContain "= 20.0[°C]"
            ibdSvg shouldNotContain "-5.0"

            val bdd =
                sysml2Model(name = "Raum") {
                    val druck = attributeDef(name = "Raumdruck", unitSymbol = "Pa")
                    val temp = attributeDef(name = "Temperatur")
                    val sensor =
                        partDef(name = "Sensor") {
                            attribute(name = "messwert", typeId = druck.id, default = -5.0)
                            attribute(name = "temperatur", typeId = temp.id, defaultLiteral = "20.0[°C]")
                        }
                    bdd(name = "Raum") { include(sensor) }
                }
            val bddSvg = renderBdd(bdd)
            bddSvg shouldContain "messwert : Raumdruck = -5${nbsp}Pa"
            bddSvg shouldContain "= 20.0[°C]"
            bddSvg shouldNotContain "-5.0"
        }

        test("F-a: FORWARD draws an open head at the target in the connectionDef colour, without dash pattern") {
            val svg = renderIbd(plant(connections = 1, direction = ConnectionDirection.FORWARD))
            val head = Regex("""<path [^>]*style="${Regex.escape(headStyle)}"[^>]*/?>""").findAll(svg).toList()
            head.size shouldBe 1
            head.single().value shouldNotContain "stroke-dasharray"
            svg shouldContain "stroke-dasharray=\"6 4\""
        }

        test("F-a: NONE draws no head (regression guard)") {
            val svg = renderIbd(plant(connections = 1))
            svg shouldNotContain headStyle
        }

        test("F-b: flow without via next to one matching connection is one edge with a mid-arrow") {
            val svg = renderIbd(plant(connections = 1, withFlow = true))
            svg shouldContain "id=\"conn:Anlage::c1\""
            svg shouldNotContain "id=\"flow:"
            svg shouldContain "kuml-sysml2-port-dir"
            svg shouldContain Regex(">\\s*Medium\\s*<")
            svg shouldContain "stroke:#1D2968"
        }

        test("F-b: without a matching connection the flow keeps its own line") {
            val svg = renderIbd(plant(connections = 0, withFlow = true))
            svg shouldContain "id=\"flow:Anlage::flow1\""
        }

        test("F-b: two matching connections are ambiguous, the flow keeps its own line") {
            val svg = renderIbd(plant(connections = 2, withFlow = true))
            svg shouldContain "id=\"flow:Anlage::flow1\""
            svg shouldContain "id=\"conn:Anlage::c1\""
            svg shouldContain "id=\"conn:Anlage::c2\""
        }

        test("F-c: owner frame is drawn for an owner without ports and without notes") {
            val m =
                sysml2Model(name = "Anlage") {
                    val teil = partDef(name = "Teil")
                    val anlage = partDef(name = "Anlage") { part(name = "teil", typeId = teil.id) }
                    ibd(name = "Anlage", owner = anlage)
                }
            val svg = renderIbd(m)
            svg shouldContain "class=\"kuml-ibd-frame\""
            svg shouldContain "Anlage"
        }
    })
