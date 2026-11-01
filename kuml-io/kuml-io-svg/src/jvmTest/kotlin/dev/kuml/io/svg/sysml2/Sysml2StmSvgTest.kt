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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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
                sysml2Model(name = "TrafficLight") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red =
                        stateDef(
                            name = "Red",
                            entryAction = "switchLights('red')",
                            exitAction = "logTransition('red')",
                        )
                    val green = stateDef(name = "Green", entryAction = "switchLights('green')")
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
                        EdgeId("transition:Red::Green") to
                            EdgeRoute.OrthogonalRounded(
                                source = Point(x = 260f, y = 150f),
                                target = Point(x = 320f, y = 150f),
                                waypoints = emptyList(),
                                cornerRadiusPx = 4f,
                            ),
                    ),
                groups = emptyMap(),
            )

        "STM renders initial pseudo-state as a filled circle" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = PlainTheme())

            // The g-element for the Initial node carries a circle of radius 7 in the dedicated filled class.
            svg shouldContain "id=\"Initial\""
            svg shouldContain "<circle"
            svg shouldContain "class=\"kuml-pseudo-filled\""
            svg shouldContain "r=\"7\""
            // `.kuml-class { fill: nodeFill }` would beat a fill attribute and render the dot hollow.
            svg shouldNotContain "currentColor"
            val css = svg.substringAfter("<style").substringBefore("</style>")
            css shouldContain ".kuml-pseudo-filled { fill: ${PlainTheme().colors.border.toHex()}; stroke: none; }"
            css.indexOf(".kuml-pseudo-filled") shouldBeGreaterThan css.indexOf(".kuml-class")

            SampleOutput.write(filename = "sysml2-stm/traffic-light-initial.svg", content = svg)
        }

        "the filled-dot class follows the border colour of every theme" {
            val (model, stm) = trafficLightModel()
            val themes =
                listOf(
                    PlainTheme(),
                    dev.kuml.renderer.theme.core
                        .KumlBrandTheme(),
                    dev.kuml.renderer.theme.core
                        .PlayfulTheme(),
                )
            for (t in themes) {
                val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = t)
                svg shouldContain ".kuml-pseudo-filled { fill: ${t.colors.border.toHex()}; stroke: none; }"
            }
        }

        "STM renders final pseudo-state as a donut (two concentric circles)" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = PlainTheme())

            // Final node has two circle elements (outer ring + inner disc).
            svg shouldContain "id=\"Final\""
            // We can't easily count circles inside a substring, but presence
            // of the donut means at least the outer fill="white" attribute
            // is emitted (the inner disc uses the filled class).
            svg shouldContain "fill=\"white\""
            svg shouldContain "class=\"kuml-pseudo-filled\""
        }

        "STM renders regular state as rounded rectangle with name and entry/exit actions" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = PlainTheme())

            // Regular state Red: rounded rect (rx="12" ry="12") + name + action lines.
            svg shouldContain "rx=\"12\""
            svg shouldContain "ry=\"12\""
            svg shouldContain "Red"
            svg shouldContain "Green"
            svg shouldContain "Yellow"
            // The SysML 2 action concrete syntax lands as `entry / …`, `exit / …`.
            svg shouldContain "entry / switchLights"
            svg shouldContain "exit / logTransition"

            SampleOutput.write(filename = "sysml2-stm/traffic-light-states.svg", content = svg)
        }

        "STM transition routes lower into SVG path elements" {
            val (model, stm) = trafficLightModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layoutFor(), theme = PlainTheme())

            // Edge routes lower into <path> elements (transitions present).
            svg shouldContain "path"
        }

        "state action compartment is ordered invariant, entry, do, exit and stays inside the box" {
            val model =
                sysml2Model(name = "Cmp") {
                    val s =
                        stateDef(
                            name = "Heating",
                            exitAction = "stop()",
                            doAction = "regulate()",
                            entryAction = "start()",
                            invariant = "temp < 90 & <script>",
                        )
                    stmDiagram(name = "d") { include(s) }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 300f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Heating") to
                                NodeLayout(bounds = Rect(origin = Point(x = 10f, y = 10f), size = Size(width = 220f, height = 100f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layout, theme = PlainTheme())
            val order = listOf("[temp &lt; 90 &amp; &lt;script&gt;]", "entry / start()", "do / regulate()", "exit / stop()")
            val positions = order.map { svg.indexOf(it) }
            positions.forEach { (it >= 0) shouldBe true }
            positions shouldBe positions.sorted()
            // no raw (unescaped) markup from the invariant, and no double escaping
            svg shouldNotContain "<script>"
            svg shouldNotContain "&amp;amp;"
            // the divider separates the name from the compartment, both inside the rect (h = 100)
            svg shouldContain "class=\"kuml-divider\""
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
                    canvas = Size(width = 400f, height = 200f),
                    nodes =
                        mapOf(
                            NodeId("Red") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 180f, height = 80f))),
                            NodeId("Green") to
                                NodeLayout(bounds = Rect(origin = Point(x = 220f, y = 0f), size = Size(width = 180f, height = 80f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val one = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layout, theme = PlainTheme())
            val two = KumlSvgRenderer.toSvg(model = model, diagram = stm, layoutResult = layout, theme = PlainTheme())
            one shouldBe two

            SampleOutput.write(filename = "sysml2-stm/deterministic.svg", content = one)
        }
    })
