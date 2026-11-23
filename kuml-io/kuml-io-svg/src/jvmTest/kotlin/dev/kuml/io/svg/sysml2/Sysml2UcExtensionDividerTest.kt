package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** PAL wave E1 (15a): the extension-points divider ends inside the ellipse, not on its contour. */
class Sysml2UcExtensionDividerTest :
    StringSpec({
        "both ends of the divider satisfy ((x-cx)/rx)^2 + ((y-cy)/ry)^2 <= 0.85" {
            val model =
                sysml2Model(name = "Ext") {
                    val base = useCaseDef(name = "Bedienen")
                    val ext = useCaseDef(name = "Stoerung melden")
                    ucDiagram(name = "UC") {
                        include(base)
                        include(ext)
                        extend(source = ext, target = base, extensionPoint = "bei Stoerung")
                    }
                }
            val uc = model.diagrams.filterIsInstance<UcDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = uc)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = uc))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = uc, layoutResult = layout, theme = PlainTheme())
            val groupRe = Regex("""<g id="Bedienen"[^>]*>(.*?)</g>""", RegexOption.DOT_MATCHES_ALL)
            val group = groupRe.find(svg)!!.groupValues[1]
            val e =
                Regex("""<ellipse cx="([\d.]+)" cy="([\d.]+)" rx="([\d.]+)" ry="([\d.]+)"""").find(group)!!.groupValues.drop(1).map {
                    it.toDouble()
                }
            val (cx, cy, rx, ry) = e
            val l =
                Regex(
                    """<line x1="([\d.]+)" y1="([\d.]+)" x2="([\d.]+)" y2="([\d.]+)"[^>]*class="kuml-divider"""",
                ).find(group)!!.groupValues.drop(1).map {
                    it.toDouble()
                }
            for ((x, y) in listOf(l[0] to l[1], l[2] to l[3])) {
                val r = ((x - cx) / rx) * ((x - cx) / rx) + ((y - cy) / ry) * ((y - cy) / ry)
                (r <= 0.85) shouldBe true
            }
        }
    })
