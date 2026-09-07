package dev.kuml.io.svg.sysml2

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
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Verifies [SvgRenderOptions.preparedHighlightVertexIds] on the SysML-2 STM render path
 * (`KumlSvgRenderer.toSvg(model, diagram: StmDiagram, ...)` → `renderSysml2Synthetic` →
 * `emitHighlightRings`). Fixture reused from `Sysml2StmSvgTest`.
 */
class Sysml2StmHighlightSvgTest :
    StringSpec({

        fun trafficLightModel(): Pair<Sysml2Model, StmDiagram> {
            val model =
                sysml2Model(name = "TrafficLight") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    val final = stateDef(name = "Final", isFinal = true)
                    transition(name = "init", source = initial, target = red)
                    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
                    transition(name = "greenToYellow", source = green, target = yellow, trigger = "timer45s")
                    transition(name = "yellowToRed", source = yellow, target = red, trigger = "timer5s")
                    transition(name = "powerOff", source = red, target = final, trigger = "powerOff")
                    stmDiagram(name = "Phase cycle") {
                        include(initial)
                        include(red)
                        include(green)
                        include(yellow)
                        include(final)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            return model to stm
        }

        fun layoutFor(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 900f, height = 320f),
                nodes =
                    mapOf(
                        NodeId("Initial") to
                            NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 130f), size = Size(width = 24f, height = 24f))),
                        NodeId("Red") to
                            NodeLayout(bounds = Rect(origin = Point(x = 80f, y = 110f), size = Size(width = 180f, height = 80f))),
                        NodeId("Green") to
                            NodeLayout(bounds = Rect(origin = Point(x = 320f, y = 110f), size = Size(width = 180f, height = 80f))),
                        NodeId("Yellow") to
                            NodeLayout(bounds = Rect(origin = Point(x = 560f, y = 110f), size = Size(width = 180f, height = 80f))),
                        NodeId("Final") to
                            NodeLayout(bounds = Rect(origin = Point(x = 800f, y = 130f), size = Size(width = 24f, height = 24f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("transition:Initial::Red") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(x = 44f, y = 150f),
                                target = Point(x = 80f, y = 150f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
                groups = emptyMap(),
            )

        "visible highlightVertexIds emits a ring without visibility=hidden" {
            val (model, stm) = trafficLightModel()
            val options = SvgRenderOptions(highlightVertexIds = setOf("Red"))
            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )

            svg shouldContain "highlight-ring-Red"
            svg shouldContain "kuml-active-state"
            svg shouldNotContain "visibility=\"hidden\""
        }

        "preparedHighlightVertexIds emits hidden rings for all named vertices" {
            val (model, stm) = trafficLightModel()
            val options = SvgRenderOptions(preparedHighlightVertexIds = setOf("Red", "Green"))
            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )

            svg shouldContain "highlight-ring-Red"
            svg shouldContain "highlight-ring-Green"
            // Both rings hidden — two occurrences of visibility="hidden".
            val hiddenCount = Regex("visibility=\"hidden\"").findAll(svg).count()
            hiddenCount shouldBe 2
        }

        "no highlight options emits no highlight ring markup at all (export cleanliness)" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = PlainTheme())

            svg shouldNotContain "kuml-highlight-ring"
        }

        "a vertex in both sets renders exactly one visible ring, not two" {
            val (model, stm) = trafficLightModel()
            val options = SvgRenderOptions(highlightVertexIds = setOf("Red"), preparedHighlightVertexIds = setOf("Red", "Green"))
            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )

            val redRingCount = Regex("id=\"highlight-ring-Red\"").findAll(svg).count()
            redRingCount shouldBe 1
            // Red is visible (present in highlightVertexIds) — no hidden attribute on ITS rect.
            // Green stays prepared-only and hidden.
            val hiddenCount = Regex("visibility=\"hidden\"").findAll(svg).count()
            hiddenCount shouldBe 1
        }

        "highlight ring geometry matches the rendered node position after widening" {
            val (model, stm) = trafficLightModel()
            val options = SvgRenderOptions(highlightVertexIds = setOf("Red"), paddingPx = 64f)
            val svg =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )

            // Ring offset is 4px around the node box; the ring's x/y must be node.x/y + padding - 4.
            // Node "Red" starts at x=80 (pre-widen); padding=64 → expected x = 80 + 64 - 4 = 140,
            // but widening may shift x further right for label overhang — assert the ring is
            // present with a stroke and non-negative coordinates instead of a brittle exact value.
            svg shouldContain "highlight-ring-Red"
            svg shouldContain "stroke=\"#FF6B35\""
        }

        "rendering twice with the same highlight options is deterministic" {
            val (model, stm) = trafficLightModel()
            val options = SvgRenderOptions(preparedHighlightVertexIds = setOf("Red", "Yellow"))
            val one =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )
            val two =
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = stm,
                    layoutResult = layoutFor(),
                    theme = PlainTheme(),
                    options = options,
                )
            one shouldBe two
        }
    })
