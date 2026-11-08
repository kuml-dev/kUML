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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V2.0.13 — verifies the `trigger [guard] / effect` STM transition label
 * format appears verbatim in the SVG output.
 *
 * Nacharbeit 43: the gap between the states is wide enough for the label on one line; a label wider than its gap is
 * wrapped narrower by the renderer, which would change the line count these tests assert.
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
                canvas = Size(width = 640f, height = 200f),
                nodes =
                    mapOf(
                        NodeId("Off") to
                            NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 60f), size = Size(width = 120f, height = 60f))),
                        NodeId("On") to
                            NodeLayout(bounds = Rect(origin = Point(x = 440f, y = 60f), size = Size(width = 120f, height = 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("transition:Off::On") to
                            EdgeRoute.Direct(source = Point(x = 160f, y = 90f), target = Point(x = 440f, y = 90f)),
                    ),
                groups = emptyMap(),
            )

        "STM transition label trigger [guard] / effect appears in SVG" {
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "powerOn [ready] / boot()"
            SampleOutput.write(filename = "sysml2-edge-labels/stm-transition.svg", content = svg)
        }

        "STM label is a halo group without a backdrop rectangle" {
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            val group = Regex("""<g class="kuml-edge-label"[^>]*>.*?</g>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!.value
            group shouldContain "data-edge-id=\"transition:Off::On\""
            group shouldContain "data-label-id="
            Regex("""data-label-rect="[-0-9. ]+"""").containsMatchIn(group) shouldBe true
            group shouldNotContain "<rect"
            Regex("""<title>\s*powerOn \[ready] / boot\(\)\s*</title>""").containsMatchIn(group) shouldBe true
            Regex("""<text [^>]*stroke-width:2[^>]*>""").findAll(group).count() shouldBe 1
            Regex("""<text [^>]*>""").findAll(group).count() shouldBe 2
        }

        "STM label of a long guard is wrapped, never cut" {
            val guard = "stroemungOk and not stbAusgeloest and druckImBereich(p1,p22)"
            val long =
                model.copy(
                    usages = listOf((model.usages.single() as TransitionUsage).copy(trigger = "tick", guard = guard, effect = null)),
                )
            val svg = KumlSvgRenderer.toSvg(model = long, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            svg shouldNotContain "…"
            val group = Regex("""<g class="kuml-edge-label"[^>]*>.*?</g>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!.value
            val texts =
                Regex("""<text [^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).findAll(group).map { it.groupValues[1].trim() }.toList()
            texts.size shouldBe 2 * (texts.size / 2)
            (texts.size / 2 >= 2) shouldBe true
            Regex("""<title>\s*tick \[${Regex.escape(guard)}]\s*</title>""").containsMatchIn(group) shouldBe true
            val fill = texts.takeLast(texts.size / 2).joinToString("").replace(" ", "")
            fill shouldContain guard.replace(" ", "").replace("&&", "&amp;&amp;")
        }

        "STM label with showName starts with the transition name" {
            val named =
                model.copy(
                    usages = listOf((model.usages.single() as TransitionUsage).copy(name = "T37", showName = true)),
                )
            val svg = KumlSvgRenderer.toSvg(model = named, diagram = diagram, layoutResult = layout, theme = PlainTheme())
            svg shouldContain "T37: powerOn [ready] / boot()"
        }
    })
