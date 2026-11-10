package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-ACT-SVG-Renderer (V2.0.10).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-act/<name>.svg`, sodass es im
 * Browser visuell überprüft werden kann.
 */
class Sysml2ActSvgTest :
    StringSpec({

        fun initialGroup(svg: String): String {
            val start = svg.indexOf("<g id=\"Initial\"")
            val end = svg.indexOf("</g>", startIndex = start)
            return svg.substring(start, end)
        }

        data class Centre(
            val x: Double,
            val y: Double,
            val r: Double,
        )

        data class Box(
            val x: Double,
            val y: Double,
            val w: Double,
            val h: Double,
        )

        fun translateOf(tag: String): Pair<Double, Double> {
            val m = Regex("""translate\(([-0-9.]+),([-0-9.]+)\)""").find(tag)!!
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }

        fun symbolCentre(
            svg: String,
            id: String,
        ): Centre {
            val start = svg.indexOf("<g id=\"$id\"")
            check(start >= 0) { "no group $id" }
            val group = svg.substring(start, svg.indexOf("</g>", startIndex = start))
            val (tx, ty) = translateOf(tag = group)
            val circle = Regex("""<circle[^>]*>""").find(group)!!.value

            fun attr(n: String) = Regex("""\b$n="([-0-9.]+)"""").find(circle)!!.groupValues[1].toDouble()
            return Centre(x = tx + attr("cx"), y = ty + attr("cy"), r = attr("r"))
        }

        fun laneBounds(
            svg: String,
            partitionId: String,
        ): Box {
            val start = svg.indexOf("<g id=\"activityPartition:$partitionId\"")
            check(start >= 0) { "no lane $partitionId" }
            val group = svg.substring(start, svg.indexOf("</g>", startIndex = start))
            val (tx, ty) = translateOf(tag = group)
            val rect = Regex("""<rect[^>]*>""").find(group)!!.value

            fun attr(n: String) = Regex("""\b$n="([-0-9.]+)"""").find(rect)!!.groupValues[1].toDouble()
            return Box(x = tx, y = ty, w = attr("width"), h = attr("height"))
        }

        // Tiny order-processing model: initial → Validate → Decision → (Fork → A, B → Join) → Final.
        // Used by most tests; each test only asserts the shape of one node kind.
        fun orderModel(): Pair<Sysml2Model, ActDiagram> {
            val model =
                sysml2Model(name = "OrderProcessing") {
                    val initial = initialNode()
                    val validate = actionDef(name = "Validate", action = "validate(order)")
                    val decide = decisionNode(name = "Valid?")
                    val fork = forkNode(name = "Split")
                    val pay = actionDef(name = "ProcessPayment")
                    val reserve = actionDef(name = "ReserveInventory")
                    val join = joinNode(name = "Sync")
                    val ship = actionDef(name = "ShipOrder")
                    val finalN = finalNode()
                    val cancel = actionDef(name = "CancelOrder")
                    val ff = flowFinalNode()
                    controlFlow(name = "start", source = initial, target = validate)
                    controlFlow(name = "vToD", source = validate, target = decide)
                    controlFlow(name = "yes", source = decide, target = fork, guard = "valid")
                    controlFlow(name = "fToP", source = fork, target = pay)
                    controlFlow(name = "fToR", source = fork, target = reserve)
                    controlFlow(name = "pToJ", source = pay, target = join)
                    controlFlow(name = "rToJ", source = reserve, target = join)
                    controlFlow(name = "jToS", source = join, target = ship)
                    controlFlow(name = "end", source = ship, target = finalN)
                    controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
                    controlFlow(name = "cancelEnd", source = cancel, target = ff)
                    actDiagram(name = "Workflow") {
                        include(initial)
                        include(validate)
                        include(decide)
                        include(fork)
                        include(pay)
                        include(reserve)
                        include(join)
                        include(ship)
                        include(finalN)
                        include(cancel)
                        include(ff)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            return model to act
        }

        // A hand-crafted LayoutResult so the SVG is deterministic test-to-test.
        // Bounds per node mirror what the bridge produces by default
        // (see ACT_*-Konstanten in Sysml2LayoutBridge).
        fun fakeLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 1200f, height = 400f),
                nodes =
                    mapOf(
                        NodeId("Initial") to
                            NodeLayout(bounds = Rect(origin = Point(x = 20f, y = 180f), size = Size(width = 28f, height = 28f))),
                        NodeId("Validate") to
                            NodeLayout(bounds = Rect(origin = Point(x = 80f, y = 160f), size = Size(width = 160f, height = 60f))),
                        NodeId("Valid?") to
                            NodeLayout(bounds = Rect(origin = Point(x = 280f, y = 170f), size = Size(width = 50f, height = 50f))),
                        NodeId("Split") to
                            NodeLayout(bounds = Rect(origin = Point(x = 380f, y = 180f), size = Size(width = 120f, height = 10f))),
                        NodeId("ProcessPayment") to
                            NodeLayout(bounds = Rect(origin = Point(x = 540f, y = 100f), size = Size(width = 160f, height = 60f))),
                        NodeId("ReserveInventory") to
                            NodeLayout(bounds = Rect(origin = Point(x = 540f, y = 240f), size = Size(width = 160f, height = 60f))),
                        NodeId("Sync") to
                            NodeLayout(bounds = Rect(origin = Point(x = 740f, y = 180f), size = Size(width = 120f, height = 10f))),
                        NodeId("ShipOrder") to
                            NodeLayout(bounds = Rect(origin = Point(x = 900f, y = 160f), size = Size(width = 160f, height = 60f))),
                        NodeId("Final") to
                            NodeLayout(bounds = Rect(origin = Point(x = 1100f, y = 180f), size = Size(width = 28f, height = 28f))),
                        NodeId("CancelOrder") to
                            NodeLayout(bounds = Rect(origin = Point(x = 380f, y = 320f), size = Size(width = 160f, height = 60f))),
                        NodeId("FlowFinal") to
                            NodeLayout(bounds = Rect(origin = Point(x = 580f, y = 340f), size = Size(width = 28f, height = 28f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("start") to
                            EdgeRoute.Direct(
                                source = Point(x = 48f, y = 194f),
                                target = Point(x = 80f, y = 190f),
                            ),
                        EdgeId("vToD") to
                            EdgeRoute.Direct(
                                source = Point(x = 240f, y = 190f),
                                target = Point(x = 280f, y = 195f),
                            ),
                        EdgeId("yes") to
                            EdgeRoute.Direct(
                                source = Point(x = 330f, y = 195f),
                                target = Point(x = 380f, y = 185f),
                            ),
                        EdgeId("fToP") to
                            EdgeRoute.Direct(
                                source = Point(x = 500f, y = 182f),
                                target = Point(x = 540f, y = 130f),
                            ),
                        EdgeId("fToR") to
                            EdgeRoute.Direct(
                                source = Point(x = 500f, y = 188f),
                                target = Point(x = 540f, y = 270f),
                            ),
                        EdgeId("pToJ") to
                            EdgeRoute.Direct(
                                source = Point(x = 700f, y = 130f),
                                target = Point(x = 740f, y = 183f),
                            ),
                        EdgeId("rToJ") to
                            EdgeRoute.Direct(
                                source = Point(x = 700f, y = 270f),
                                target = Point(x = 740f, y = 187f),
                            ),
                        EdgeId("jToS") to
                            EdgeRoute.Direct(
                                source = Point(x = 860f, y = 185f),
                                target = Point(x = 900f, y = 190f),
                            ),
                        EdgeId("end") to
                            EdgeRoute.Direct(
                                source = Point(x = 1060f, y = 190f),
                                target = Point(x = 1100f, y = 194f),
                            ),
                        EdgeId("no") to
                            EdgeRoute.Direct(
                                source = Point(x = 305f, y = 220f),
                                target = Point(x = 380f, y = 350f),
                            ),
                        EdgeId("cancelEnd") to
                            EdgeRoute.Direct(
                                source = Point(x = 540f, y = 350f),
                                target = Point(x = 580f, y = 354f),
                            ),
                    ),
                groups = emptyMap(),
            )

        "ACT renders regular Action as rounded rect with body text" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"Validate\""
            svg shouldContain "<rect"
            // Rounded rect carries the rx/ry attribute.
            svg shouldContain "rx=\"14\""
            // Body text appears as a second line.
            svg shouldContain "validate(order)"

            SampleOutput.write(filename = "sysml2-act/order-action.svg", content = svg)
        }

        "ACT renders Initial pseudo-node as filled circle" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"Initial\""
            svg shouldContain "<circle"
            // The dot must use the dedicated filled class: `.kuml-class { fill: nodeFill }` beats a
            // `fill="currentColor"` presentation attribute and would render it hollow.
            initialGroup(svg = svg) shouldContain "class=\"kuml-pseudo-filled\""

            SampleOutput.write(filename = "sysml2-act/order-initial.svg", content = svg)
        }

        "ACT initial node is one filled dot that differs from the node fill" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            val group = initialGroup(svg = svg)
            Regex("<circle").findAll(group).count() shouldBe 1
            group shouldNotContain "kuml-class"
            group shouldNotContain "currentColor"

            val dotFill = Regex("""\.kuml-pseudo-filled \{ fill: (#[0-9A-Fa-f]{3,8}); stroke: none; \}""").find(svg)?.groupValues?.get(1)
            val nodeFill = Regex("""\.kuml-class \{ fill: (#[0-9A-Fa-f]{3,8});""").find(svg)?.groupValues?.get(1)
            dotFill shouldNotBe null
            nodeFill shouldNotBe null
            dotFill shouldNotBe nodeFill
        }

        "ACT several unnamed flow finals in different lanes get their own symbols inside their lanes" {
            val model =
                sysml2Model(name = "Lanes") {
                    val laneA = activityPartition(name = "LaneA")
                    val laneB = activityPartition(name = "LaneB")
                    val start = initialNode()
                    val fork = forkNode(name = "Split")
                    val a1 = actionDef(name = "StepA", partition = laneA)
                    val b1 = actionDef(name = "StepB", partition = laneB)
                    val ff1 = flowFinalNode(partition = laneA)
                    val ff2 = flowFinalNode(partition = laneB)
                    controlFlow(name = "s", source = start, target = fork)
                    controlFlow(name = "f1", source = fork, target = a1)
                    controlFlow(name = "f2", source = fork, target = b1)
                    controlFlow(name = "e1", source = a1, target = ff1)
                    controlFlow(name = "e2", source = b1, target = ff2)
                    actDiagram(name = "Lanes") {
                        include(node = start)
                        include(node = fork)
                        include(node = a1)
                        include(node = b1)
                        include(node = ff1)
                        include(node = ff2)
                    }
                }
            val act = model.diagrams.single() as ActDiagram
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val layoutResult =
                engine.layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act),
                    hints = LayoutHints.DEFAULT,
                )
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = layoutResult, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-act/two-flow-finals-in-lanes.svg", content = svg)

            // The two symbols are two distinct layout nodes and two distinct SVG groups.
            layoutResult.nodes.keys
                .map { it.value }
                .filter { it.startsWith("FlowFinal") }
                .toSet() shouldBe
                setOf("FlowFinal", "FlowFinal-2")
            val a = symbolCentre(svg = svg, id = "FlowFinal")
            val b = symbolCentre(svg = svg, id = "FlowFinal-2")
            val laneA = laneBounds(svg = svg, partitionId = "LaneA")
            val laneB = laneBounds(svg = svg, partitionId = "LaneB")

            // Each symbol sits inside the bounds of its own lane.
            (a.x >= laneA.x && a.x <= laneA.x + laneA.w && a.y >= laneA.y && a.y <= laneA.y + laneA.h) shouldBe true
            (b.x >= laneB.x && b.x <= laneB.x + laneB.w && b.y >= laneB.y && b.y <= laneB.y + laneB.h) shouldBe true
            // They do not overlap: centre distance is at least two radii.
            val dist = kotlin.math.hypot(a.x - b.x, a.y - b.y)
            dist shouldBeGreaterThanOrEqual (2.0 * a.r)
            Regex("<g id=\"FlowFinal(-2)?\"").findAll(svg).count() shouldBe 2
        }

        "ACT renders Final pseudo-node as a donut (two concentric circles)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"Final\""
            // Donut: outer ring (white fill) + inner filled disc (currentColor).
            svg shouldContain "fill=\"white\""

            SampleOutput.write(filename = "sysml2-act/order-final.svg", content = svg)
        }

        "ACT renders FlowFinal as a circle with diagonal X lines inside" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"FlowFinal\""
            // X-form: two diagonal line elements in the FlowFinal group.
            // Find the substring starting at the FlowFinal id and look for <line.
            val ffIdx = svg.indexOf("id=\"FlowFinal\"")
            ffIdx shouldNotBe -1
            val ffEnd = svg.indexOf("</g>", ffIdx)
            val ffBlock = svg.substring(ffIdx, ffEnd)
            ffBlock shouldContain "<line"

            SampleOutput.write(filename = "sysml2-act/order-flowfinal.svg", content = svg)
        }

        "ACT renders Decision/Merge node as a diamond polygon" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"Valid?\""
            // Diamond emitted as a polygon with four points.
            svg shouldContain "<polygon"

            SampleOutput.write(filename = "sysml2-act/order-decision.svg", content = svg)
        }

        "ACT renders Fork/Join as a filled bar (thick rect with currentColor fill)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            svg shouldContain "id=\"Split\""
            svg shouldContain "id=\"Sync\""
            // Bars use a filled rect — the currentColor fill identifies them.
            val splitIdx = svg.indexOf("id=\"Split\"")
            val splitEnd = svg.indexOf("</g>", splitIdx)
            val splitBlock = svg.substring(splitIdx, splitEnd)
            splitBlock shouldContain "<rect"
            splitBlock shouldContain "currentColor"

            SampleOutput.write(filename = "sysml2-act/order-fork.svg", content = svg)
        }

        "ACT is deterministic — same input renders byte-identically" {
            val (model, act) = orderModel()
            val svg1 = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())
            val svg2 = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())
            svg1 shouldBe svg2
        }

        "ACT control-flow edges surface as rendered paths in the SVG output" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())
            // fakeLayout has direct-route control-flow edges; each lowers to a <line>
            // with the kuml-edge CSS class applied.
            svg shouldContain "<line"
            svg shouldContain "class=\"kuml-edge\""
        }

        // ── V2.0.16 Partitions + Pins ─────────────────────────────────────

        // Tiny two-partition model: Customer lane with PlaceOrder,
        // OrderSystem lane with ValidateOrder (input + output pin) and
        // ProcessPayment.
        fun partitionedModel(): Pair<Sysml2Model, ActDiagram> {
            val model =
                sysml2Model(name = "OrderProcessingPartitions") {
                    val customer = activityPartition(name = "Customer")
                    val orderSys = activityPartition(name = "OrderSystem")
                    val place =
                        actionDef(
                            name = "PlaceOrder",
                            partition = customer,
                            pins = listOf(ActionPin(name = "orderDetails", direction = PinDirection.Output)),
                        )
                    val validate =
                        actionDef(
                            name = "ValidateOrder",
                            partition = orderSys,
                            pins =
                                listOf(
                                    ActionPin(name = "orderDetails", direction = PinDirection.Input),
                                    ActionPin(name = "validation", direction = PinDirection.Output),
                                ),
                        )
                    val pay = actionDef(name = "ProcessPayment", partition = orderSys)
                    controlFlow(name = "p2v", source = place, target = validate)
                    controlFlow(name = "v2p", source = validate, target = pay)
                    actDiagram(name = "Partitioned Workflow") {
                        include(place)
                        include(validate)
                        include(pay)
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            return model to act
        }

        // Layout with two lanes side-by-side and three action boxes
        // distributed inside the lanes. Lane bounds are wide enough to
        // contain the action box and a header bar.
        fun partitionedLayout(): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 600f, height = 400f),
                nodes =
                    mapOf(
                        NodeId("PlaceOrder") to
                            NodeLayout(bounds = Rect(origin = Point(x = 40f, y = 80f), size = Size(width = 160f, height = 60f))),
                        NodeId("ValidateOrder") to
                            NodeLayout(bounds = Rect(origin = Point(x = 280f, y = 80f), size = Size(width = 160f, height = 60f))),
                        NodeId("ProcessPayment") to
                            NodeLayout(bounds = Rect(origin = Point(x = 280f, y = 200f), size = Size(width = 160f, height = 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("p2v") to
                            EdgeRoute.Direct(
                                source = Point(x = 200f, y = 110f),
                                target = Point(x = 280f, y = 110f),
                            ),
                        EdgeId("v2p") to
                            EdgeRoute.Direct(
                                source = Point(x = 360f, y = 140f),
                                target = Point(x = 360f, y = 200f),
                            ),
                    ),
                groups =
                    mapOf(
                        GroupId("Customer") to
                            GroupLayout(bounds = Rect(origin = Point(x = 20f, y = 20f), size = Size(width = 220f, height = 340f))),
                        GroupId("OrderSystem") to
                            GroupLayout(bounds = Rect(origin = Point(x = 260f, y = 20f), size = Size(width = 220f, height = 340f))),
                    ),
            )

        "ACT partition renders as a lane column with header containing the partition name" {
            val (model, act) = partitionedModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = partitionedLayout(), theme = PlainTheme())

            // One <g id="activityPartition:Customer"> group element with a
            // dashed outer rectangle (stroke-dasharray) + header text.
            svg shouldContain "id=\"activityPartition:Customer\""
            svg shouldContain "id=\"activityPartition:OrderSystem\""
            svg shouldContain "class=\"kuml-lane\""
            // Partition names surface in the lane headers (the SVG builder
            // pretty-prints text content on its own indented line).
            svg shouldContain "Customer"
            svg shouldContain "OrderSystem"

            SampleOutput.write(filename = "sysml2-act/order-partitions.svg", content = svg)
        }

        "ACT actions in different partitions appear in different lane bounds" {
            val (model, act) = partitionedModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = partitionedLayout(), theme = PlainTheme())

            // PlaceOrder's group translate-X is around 20 + padding,
            // OrderSystem's around 260 + padding. Assert relative X by
            // checking the X attribute substring of each partition group's
            // `transform="translate(...)"`. The padding adds the same
            // constant offset to both, so the relative ordering is
            // preserved.
            val customerIdx = svg.indexOf("id=\"activityPartition:Customer\"")
            val orderSysIdx = svg.indexOf("id=\"activityPartition:OrderSystem\"")
            (customerIdx < orderSysIdx) shouldBe true

            // The PlaceOrder action's X (40 + padding) is less than the
            // ValidateOrder action's X (280 + padding) — confirms different
            // horizontal lane positions.
            val placeIdx = svg.indexOf("id=\"PlaceOrder\"")
            val validateIdx = svg.indexOf("id=\"ValidateOrder\"")
            val placeTransform = svg.substring(placeIdx, placeIdx + 80)
            val validateTransform = svg.substring(validateIdx, validateIdx + 80)
            placeTransform shouldContain "translate("
            validateTransform shouldContain "translate("
        }

        "ACT action with pins renders small squares with pin names" {
            val (model, act) = partitionedModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = partitionedLayout(), theme = PlainTheme())

            // ValidateOrder has both an Input and an Output pin — find its
            // <g> block and assert both pin labels surface inside it.
            val vIdx = svg.indexOf("id=\"ValidateOrder\"")
            val vEnd = svg.indexOf("</g>", vIdx)
            val vBlock = svg.substring(vIdx, vEnd)
            // Pin squares — kuml-class rects with width=10.
            vBlock shouldContain "width=\"10\""
            // Pin names render as small text labels adjacent to the squares
            // (pretty-printed on their own indented line).
            // PAL 2C-b2b: the pin labels are planned with the edge labels and drawn after the edges, as label groups.
            svg shouldContain "data-label-id=\"ValidateOrder#"
            svg shouldContain "orderDetails"
            svg shouldContain "validation"

            SampleOutput.write(filename = "sysml2-act/order-pins.svg", content = svg)
        }

        "ACT action without pins is unchanged from V2.0.10 (regression guard)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = fakeLayout(), theme = PlainTheme())

            // Validate has no pins — its <g> block must not contain a
            // 10×10 pin rect (the action box itself is 160×60).
            val vIdx = svg.indexOf("id=\"Validate\"")
            val vEnd = svg.indexOf("</g>", vIdx)
            val vBlock = svg.substring(vIdx, vEnd)
            // No pin square (would be `width="10"`).
            (vBlock.contains("width=\"10\"")) shouldBe false
        }

        "ACT partitioned output is deterministic — same input renders byte-identically" {
            val (model, act) = partitionedModel()
            val svg1 = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = partitionedLayout(), theme = PlainTheme())
            val svg2 = KumlSvgRenderer.toSvg(model = model, diagram = act, layoutResult = partitionedLayout(), theme = PlainTheme())
            svg1 shouldBe svg2
        }
    })

private infix fun Int.shouldNotBe(n: Int) {
    if (this == n) error("Expected value to not be $n, got $this")
}
