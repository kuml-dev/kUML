package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
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
 * Nacharbeit 46 (N-IBD-A, -B, -D, -E) measured on the SVG of the full pipeline (ELK layout, nested frames, labels).
 * Structure only: texts, ids, attributes and relative y positions; no byte comparison and no measured text widths.
 */
class Sysml2IbdNestingSvgTest :
    FunSpec(body = {
        fun render(m: Sysml2Model): String {
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
            val enriched =
                if (dev.kuml.sysml2.projection
                        .sysml2IbdProjection(model = m, diagram = d)
                        .showFrame
                ) {
                    framed
                } else {
                    Sysml2LayoutBridge.enrichIbdPortPositions(model = m, diagram = d, layoutResult = framed)
                }
            return KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = enriched, theme = PlainTheme())
        }

        fun plant(
            withAttributes: Boolean = false,
            declareLevel1Attribute: Boolean = false,
        ): Sysml2Model =
            sysml2Model(name = "Anlage") {
                val signal = connectionDef(name = "Signal")
                val dashed = connectionDef(name = "Signal", style = ConnectionLineStyle.DASHED, color = "#1D2968")
                val port = portDef(name = "SignalPort")
                val num = attributeDef(name = "Wert")
                val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                val klappe = partDef(name = "Klappe") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                val logik =
                    partDef(name = "Logik") {
                        // The attribute is declared first on purpose (N-IBD-D).
                        attribute(name = "stellsignal", typeId = num.id)
                        port(name = "ein", typeId = port.id, direction = PortDirection.In)
                        port(name = "stellsignal", typeId = port.id, direction = PortDirection.Out)
                        part(name = "regler", typeId = regler.id)
                        part(name = "klappe", typeId = klappe.id)
                        connect(name = "l1", typeId = signal.id, sourceEndId = "ein", targetEndId = "regler.ein")
                        connect(name = "l2", typeId = dashed.id, sourceEndId = "stellsignal", targetEndId = "klappe.ein")
                    }
                val steuerung =
                    partDef(name = "Steuerung") {
                        part(name = "logik", typeId = logik.id)
                        if (declareLevel1Attribute) attribute(name = "stand", typeId = num.id)
                    }
                val anlage = partDef(name = "Anlage") { part(name = "steuerung", typeId = steuerung.id) }
                ibd(name = "Anlage", owner = anlage, showAttributes = withAttributes)
            }

        fun titleY(
            svg: String,
            text: String,
        ): Double {
            val m = Regex("""<text class="kuml-title" x="[-0-9.]+" y="([-0-9.]+)"[^>]*>\s*${Regex.escape(text)}\s*</text>""").find(svg)
            return m!!.groupValues[1].toDouble()
        }

        fun nestedTop(svg: String): Double =
            Regex("""<rect x="[-0-9.]+" y="([-0-9.]+)"[^>]*kuml-ibd-nested[^>]*data-level="2"""")
                .findAll(svg)
                .minOf { it.groupValues[1].toDouble() }

        test("N-IBD-A: the title of a box with nested frames sits in its head, above every child frame") {
            val svg = render(plant())
            svg shouldContain "steuerung : Steuerung"
            svg shouldContain "logik : Logik"
            (titleY(svg = svg, text = "steuerung : Steuerung") < nestedTop(svg)) shouldBe true
            // the stereotype line stays above the title
            (titleY(svg = svg, text = "steuerung : Steuerung") > 18.0) shouldBe true
        }

        test("N-IBD-A: a nested box with attributes keeps the same title position as without them") {
            val without = titleY(svg = render(plant(declareLevel1Attribute = false)), text = "steuerung : Steuerung")
            val with = titleY(svg = render(plant(withAttributes = true, declareLevel1Attribute = true)), text = "steuerung : Steuerung")
            with shouldBe without
        }

        test("N-IBD-B: both connections of the nested definition are drawn, styled by their own definition") {
            val svg = render(plant())
            svg shouldContain "id=\"conn:Anlage::steuerung::logik::l1\""
            svg shouldContain "id=\"conn:Anlage::steuerung::logik::l2\""
            svg shouldContain "l1 : Signal"
            svg shouldContain "l2 : Signal"
            // l2 uses the dashed definition of the same name (N-IBD-E): exactly one dashed edge.
            Regex("""id="conn:Anlage::steuerung::logik::l2"[^>]*>(?:(?!</g>).)*stroke-dasharray="6 4"""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(svg) shouldBe true
            Regex("""id="conn:Anlage::steuerung::logik::l1"[^>]*>(?:(?!</g>).)*stroke-dasharray""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(svg) shouldBe false
        }

        test("N-IBD-D: a port named like an attribute still shows its type and direction marker") {
            val svg = render(plant(withAttributes = true))
            svg shouldContain "stellsignal : SignalPort"
            svg shouldContain "kuml-sysml2-port-dir"
            svg shouldContain "stellsignal : Wert"
        }

        test("N-IBD-B: a definition used twice draws its connection twice") {
            val m =
                sysml2Model(name = "Anlage") {
                    val signal = connectionDef(name = "Signal")
                    val port = portDef(name = "SignalPort")
                    val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id) }
                    val logik =
                        partDef(name = "Logik") {
                            port(name = "ein", typeId = port.id)
                            part(name = "regler", typeId = regler.id)
                            connect(name = "l1", typeId = signal.id, sourceEndId = "ein", targetEndId = "regler.ein")
                        }
                    val steuerung =
                        partDef(name = "Steuerung") {
                            part(name = "a", typeId = logik.id)
                            part(name = "b", typeId = logik.id)
                        }
                    val anlage = partDef(name = "Anlage") { part(name = "steuerung", typeId = steuerung.id) }
                    ibd(name = "Anlage", owner = anlage)
                }
            val svg = render(m)
            svg shouldContain "id=\"conn:Anlage::steuerung::a::l1\""
            svg shouldContain "id=\"conn:Anlage::steuerung::b::l1\""
        }

        test("without nested connections there is no connection edge; the owner frame is always drawn (Nacharbeit 47)") {
            val m =
                sysml2Model(name = "Anlage") {
                    val a = partDef(name = "Regler")
                    val anlage = partDef(name = "Anlage") { part(name = "regler", typeId = a.id) }
                    ibd(name = "Anlage", owner = anlage)
                }
            val svg = render(m)
            svg shouldNotContain "id=\"conn:"
            svg shouldContain "class=\"kuml-ibd-frame\""
        }
    })
