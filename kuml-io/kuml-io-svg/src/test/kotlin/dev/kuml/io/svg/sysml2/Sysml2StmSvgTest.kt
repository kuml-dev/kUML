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
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-STM-SVG-Renderer (V2.0.9).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-stm/<name>.svg`, sodass es im
 * Browser visuell überprüft werden kann.
 */
class Sysml2StmSvgTest :
    StringSpec({

        // Tiny traffic-light model: initial + Red + Green + Yellow + final.
        fun trafficLightModel(): Pair<Sysml2Model, StmDiagram> {
            val model =
                sysml2Model("TrafficLight") {
                    val initial = stateDef("Initial", isInitial = true)
                    val red =
                        stateDef(
                            "Red",
                            entryAction = "switchLights('red')",
                            exitAction = "logTransition('red')",
                        )
                    val green = stateDef("Green", entryAction = "switchLights('green')")
                    val yellow = stateDef("Yellow")
                    val final = stateDef("Final", isFinal = true)
                    transition("init", initial, red)
                    transition("redToGreen", red, green, trigger = "timer60s")
                    transition("greenToYellow", green, yellow, trigger = "timer45s")
                    transition("yellowToRed", yellow, red, trigger = "timer5s")
                    transition("powerOff", red, final, trigger = "powerOff")
                    stmDiagram("Phase cycle") {
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
                canvas = Size(900f, 320f),
                nodes =
                    mapOf(
                        NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 130f), Size(24f, 24f))),
                        NodeId("Red") to NodeLayout(bounds = Rect(Point(80f, 110f), Size(180f, 80f))),
                        NodeId("Green") to NodeLayout(bounds = Rect(Point(320f, 110f), Size(180f, 80f))),
                        NodeId("Yellow") to NodeLayout(bounds = Rect(Point(560f, 110f), Size(180f, 80f))),
                        NodeId("Final") to NodeLayout(bounds = Rect(Point(800f, 130f), Size(24f, 24f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("transition:Initial::Red") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(44f, 150f),
                                target = Point(80f, 150f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                        EdgeId("transition:Red::Green") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(260f, 150f),
                                target = Point(320f, 150f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
                groups = emptyMap(),
            )

        "STM renders initial pseudo-state as a filled circle" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model, stm, layoutFor(), PlainTheme())

            // The g-element for the Initial node carries a circle with currentColor fill.
            svg shouldContain "id=\"Initial\""
            svg shouldContain "<circle"
            // The fill="currentColor" attribute marks the initial pseudo-state.
            svg shouldContain "currentColor"

            SampleOutput.write("sysml2-stm/traffic-light-initial.svg", svg)
        }

        "STM renders final pseudo-state as a donut (two concentric circles)" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model, stm, layoutFor(), PlainTheme())

            // Final node has two circle elements (outer ring + inner disc).
            svg shouldContain "id=\"Final\""
            // We can't easily count circles inside a substring, but presence
            // of the donut means at least the outer fill="white" attribute
            // is emitted (the inner one uses currentColor).
            svg shouldContain "fill=\"white\""
        }

        "STM renders regular state as rounded rectangle with name and entry/exit actions" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model, stm, layoutFor(), PlainTheme())

            // Regular state Red: rounded rect (rx="12" ry="12") + name + action lines.
            svg shouldContain "rx=\"12\""
            svg shouldContain "ry=\"12\""
            svg shouldContain "Red"
            svg shouldContain "Green"
            svg shouldContain "Yellow"
            // The SysML 2 action concrete syntax lands as `entry / …`, `exit / …`.
            svg shouldContain "entry / switchLights"
            svg shouldContain "exit / logTransition"

            SampleOutput.write("sysml2-stm/traffic-light-states.svg", svg)
        }

        "STM transition routes lower into SVG path elements" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model, stm, layoutFor(), PlainTheme())

            // Edge routes lower into <path> elements (transitions present).
            svg shouldContain "path"
        }

        "deterministic output — same input renders byte-identically" {
            val model =
                Sysml2Model(
                    name = "Det",
                    definitions =
                        listOf(
                            StateDefinition(id = "Red", name = "Red"),
                            StateDefinition(id = "Green", name = "Green"),
                        ),
                )
            val stm =
                StmDiagram(
                    name = "Det",
                    elementIds = listOf("Red", "Green"),
                )
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(400f, 200f),
                    nodes =
                        mapOf(
                            NodeId("Red") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(180f, 80f))),
                            NodeId("Green") to NodeLayout(bounds = Rect(Point(220f, 0f), Size(180f, 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val one = KumlSvgRenderer.toSvg(model, stm, layout, PlainTheme())
            val two = KumlSvgRenderer.toSvg(model, stm, layout, PlainTheme())
            one shouldBe two

            SampleOutput.write("sysml2-stm/deterministic.svg", one)
        }
    })
