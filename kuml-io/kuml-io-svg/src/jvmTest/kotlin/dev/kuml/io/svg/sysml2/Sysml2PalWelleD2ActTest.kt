package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** PAL Welle D2 (point 10 Activity): geometry read from the SVG, no byte comparison. */
class Sysml2PalWelleD2ActTest :
    FunSpec({
        fun model(withLegend: Boolean): Sysml2Model =
            sysml2Model(name = "Legend") {
                val a = activityPartition(name = "Lane A")
                val b = activityPartition(name = "Lane B")
                val a1 = actionDef(name = "Action 1", partition = a)
                val a2 = actionDef(name = "Action 2", partition = b)
                val a3 = actionDef(name = "Action 3", partition = b)
                controlFlow(name = "f1", source = a1, target = a2)
                controlFlow(name = "f2", source = a2, target = a3)
                val legend =
                    if (withLegend) {
                        comment(
                            text = "Legende: freistehend, ohne Anker und ohne Position, einmal quer durchs Bild",
                            id = "legend",
                        )
                    } else {
                        null
                    }
                actDiagram(name = "Legend") {
                    include(a1)
                    include(a2)
                    include(a3)
                    if (legend != null) include(legend)
                }
            }

        fun svgOf(m: Sysml2Model): String {
            val d = m.diagrams.filterIsInstance<ActDiagram>().single()
            val engineResult =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d),
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_ACT,
                    ).result
            return KumlSvgRenderer.toSvg(
                model = m,
                diagram = d,
                layoutResult = engineResult,
                theme = PlainTheme(),
                options = SvgRenderOptions(paddingPx = 64f),
            )
        }

        fun viewBox(svg: String): Pair<Float, Float> {
            val vb =
                Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""")
                    .find(svg)!!
                    .destructured
                    .toList()
                    .map { it.toFloat() }
            return vb[0] to vb[1]
        }

        test("10b: a free-standing legend does not widen the image and does not overlap the actions") {
            val without = svgOf(model(withLegend = false))
            val with = svgOf(model(withLegend = true))
            val (w0, h0) = viewBox(without)
            val (w1, h1) = viewBox(with)
            (abs(w1 - w0) < 1.5f) shouldBe true
            h1 shouldBeGreaterThan h0

            // the legend group lies below every action group
            fun top(
                svg: String,
                id: String,
            ): Float =
                Regex("""<g id="$id" transform="translate\(([-0-9.]+),([-0-9.]+)\)"""")
                    .find(svg)!!
                    .groupValues[2]
                    .toFloat()
            val legendTop = top(with, "legend")
            val lowestAction = listOf("Action 1", "Action 2", "Action 3").maxOf { top(with, it) }
            (legendTop > lowestAction) shouldBe true
        }
    })
