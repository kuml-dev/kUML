package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Regression: a `null`, empty or blank optional text (time-observation label, extend condition /
 * extension point) renders as if it were absent. Font-independent: only the text skeleton is asserted.
 */
class Sysml2NullableLabelSvgTest :
    StringSpec({
        "time observation with null, empty and blank label shows only its name" {
            val model =
                sysml2Model(name = "T") {
                    val a = lifelineDef(name = "a")
                    timeObservation(lifeline = a, seqNo = 0, name = "tNull")
                    timeObservation(lifeline = a, seqNo = 1, name = "tEmpty", label = "")
                    timeObservation(lifeline = a, seqNo = 2, name = "tBlank", label = "   ")
                    timeObservation(lifeline = a, seqNo = 3, name = "tSet", label = "now")
                    seqDiagram(name = "S") { include(a) }
                }
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
            for (n in listOf("tNull", "tEmpty", "tBlank")) {
                Regex(">\\s*$n\\s*<").containsMatchIn(svg) shouldBe true
                svg shouldNotContain "$n ="
            }
            svg shouldContain "tSet = now"
            svg shouldNotContain "null"
            svg shouldNotContain "undefined"
        }

        "extend with null, empty and blank condition / extension point renders without brackets or 'null'" {
            val model =
                sysml2Model(name = "U") {
                    val u1 = useCaseDef(name = "U1")
                    val u2 = useCaseDef(name = "U2")
                    val u3 = useCaseDef(name = "U3")
                    val u4 = useCaseDef(name = "U4")
                    ucDiagram(name = "UC") {
                        include(u1)
                        include(u2)
                        include(u3)
                        include(u4)
                        extend(source = u1, target = u2)
                        extend(source = u3, target = u2, condition = "", extensionPoint = "")
                        extend(source = u4, target = u2, condition = "   ", extensionPoint = "   ")
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = uc))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = uc, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "«extend»"
            svg shouldNotContain "[]"
            svg shouldNotContain "[ ]"
            svg shouldNotContain "extension points"
            svg shouldNotContain "null"
        }
    })
