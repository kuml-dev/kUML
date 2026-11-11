package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.NoteKind
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
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 71 (i): [KumlSvgRenderer.actFinalCanvas] reports the size of the image [KumlSvgRenderer.toSvg] draws
 * (swimlane columns and note columns included), not the engine canvas. Geometry only.
 */
class Sysml2ActFinalCanvasTest :
    FunSpec({
        fun model(withNotes: Boolean): Sysml2Model =
            sysml2Model(name = "Repro") {
                val a = activityPartition(name = "Lane A")
                val b = activityPartition(name = "Lane B")
                val a1 = actionDef(name = "Action 1", partition = a)
                val a2 = actionDef(name = "Action 2", partition = b)
                controlFlow(name = "f1", source = a1, target = a2)
                if (withNotes) {
                    comment(text = "WARNING: Warnung", firstAnchor = a1, id = "n1", kind = NoteKind.WARNING)
                    comment(text = "Second note", firstAnchor = a2, id = "n2")
                }
                actDiagram(name = "Repro") {
                    include(a1)
                    include(a2)
                }
            }

        fun run(withNotes: Boolean): Triple<Float, Float, Pair<Boolean, Pair<Float, Float>>> {
            val m = model(withNotes = withNotes)
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
            val final = KumlSvgRenderer.actFinalCanvas(model = m, diagram = d, layoutResult = engineResult, paddingPx = 64f)
            val svg =
                KumlSvgRenderer.toSvg(
                    model = m,
                    diagram = d,
                    layoutResult = engineResult,
                    theme = PlainTheme(),
                    options = SvgRenderOptions(paddingPx = 64f),
                )
            val vb =
                Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""")
                    .find(svg)!!
                    .destructured
                    .toList()
                    .map { it.toFloat() }
            return Triple(final.width, final.height, final.hasNotes to (vb[0] to vb[1]))
        }

        test("the final canvas matches the drawn image, with a note flag, and differs from the engine canvas")
            .config(timeout = 30.seconds) {
                val (w, h, rest) = run(withNotes = true)
                rest.first shouldBe true
                (abs(w - rest.second.first) < 1.5f) shouldBe true
                (abs(h - rest.second.second) < 1.5f) shouldBe true
            }

        test("without notes the flag is false")
            .config(timeout = 30.seconds) {
                run(withNotes = false).third.first shouldBe false
            }
    })
