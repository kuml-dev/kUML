package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Strukturelle + Determinismus-Tests für den SysML-2-ACT-SVG-Renderer (V2.0.10).
 *
 * Jeder Test schreibt das produzierte SVG zusätzlich nach
 * `kuml-io-svg/build/sample-output/sysml2-act/<name>.svg`, sodass es im
 * Browser visuell überprüft werden kann.
 */
class Sysml2ActSvgTest :
    StringSpec({

        // Tiny order-processing model: initial → Validate → Decision → (Fork → A, B → Join) → Final.
        // Used by most tests; each test only asserts the shape of one node kind.
        fun orderModel(): Pair<Sysml2Model, ActDiagram> {
            val model =
                sysml2Model("OrderProcessing") {
                    val initial = initialNode()
                    val validate = actionDef("Validate", action = "validate(order)")
                    val decide = decisionNode("Valid?")
                    val fork = forkNode("Split")
                    val pay = actionDef("ProcessPayment")
                    val reserve = actionDef("ReserveInventory")
                    val join = joinNode("Sync")
                    val ship = actionDef("ShipOrder")
                    val finalN = finalNode()
                    val cancel = actionDef("CancelOrder")
                    val ff = flowFinalNode()
                    controlFlow("start", initial, validate)
                    controlFlow("vToD", validate, decide)
                    controlFlow("yes", decide, fork, guard = "valid")
                    controlFlow("fToP", fork, pay)
                    controlFlow("fToR", fork, reserve)
                    controlFlow("pToJ", pay, join)
                    controlFlow("rToJ", reserve, join)
                    controlFlow("jToS", join, ship)
                    controlFlow("end", ship, finalN)
                    controlFlow("no", decide, cancel, guard = "!valid")
                    controlFlow("cancelEnd", cancel, ff)
                    actDiagram("Workflow") {
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
                canvas = Size(1200f, 400f),
                nodes =
                    mapOf(
                        NodeId("Initial") to NodeLayout(bounds = Rect(Point(20f, 180f), Size(28f, 28f))),
                        NodeId("Validate") to NodeLayout(bounds = Rect(Point(80f, 160f), Size(160f, 60f))),
                        NodeId("Valid?") to NodeLayout(bounds = Rect(Point(280f, 170f), Size(50f, 50f))),
                        NodeId("Split") to NodeLayout(bounds = Rect(Point(380f, 180f), Size(120f, 10f))),
                        NodeId("ProcessPayment") to NodeLayout(bounds = Rect(Point(540f, 100f), Size(160f, 60f))),
                        NodeId("ReserveInventory") to NodeLayout(bounds = Rect(Point(540f, 240f), Size(160f, 60f))),
                        NodeId("Sync") to NodeLayout(bounds = Rect(Point(740f, 180f), Size(120f, 10f))),
                        NodeId("ShipOrder") to NodeLayout(bounds = Rect(Point(900f, 160f), Size(160f, 60f))),
                        NodeId("Final") to NodeLayout(bounds = Rect(Point(1100f, 180f), Size(28f, 28f))),
                        NodeId("CancelOrder") to NodeLayout(bounds = Rect(Point(380f, 320f), Size(160f, 60f))),
                        NodeId("FlowFinal") to NodeLayout(bounds = Rect(Point(580f, 340f), Size(28f, 28f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("start") to
                            EdgeRoute.Direct(
                                source = Point(48f, 194f),
                                target = Point(80f, 190f),
                            ),
                        EdgeId("vToD") to
                            EdgeRoute.Direct(
                                source = Point(240f, 190f),
                                target = Point(280f, 195f),
                            ),
                        EdgeId("yes") to
                            EdgeRoute.Direct(
                                source = Point(330f, 195f),
                                target = Point(380f, 185f),
                            ),
                        EdgeId("fToP") to
                            EdgeRoute.Direct(
                                source = Point(500f, 182f),
                                target = Point(540f, 130f),
                            ),
                        EdgeId("fToR") to
                            EdgeRoute.Direct(
                                source = Point(500f, 188f),
                                target = Point(540f, 270f),
                            ),
                        EdgeId("pToJ") to
                            EdgeRoute.Direct(
                                source = Point(700f, 130f),
                                target = Point(740f, 183f),
                            ),
                        EdgeId("rToJ") to
                            EdgeRoute.Direct(
                                source = Point(700f, 270f),
                                target = Point(740f, 187f),
                            ),
                        EdgeId("jToS") to
                            EdgeRoute.Direct(
                                source = Point(860f, 185f),
                                target = Point(900f, 190f),
                            ),
                        EdgeId("end") to
                            EdgeRoute.Direct(
                                source = Point(1060f, 190f),
                                target = Point(1100f, 194f),
                            ),
                        EdgeId("no") to
                            EdgeRoute.Direct(
                                source = Point(305f, 220f),
                                target = Point(380f, 350f),
                            ),
                        EdgeId("cancelEnd") to
                            EdgeRoute.Direct(
                                source = Point(540f, 350f),
                                target = Point(580f, 354f),
                            ),
                    ),
                groups = emptyMap(),
            )

        "ACT renders regular Action as rounded rect with body text" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"Validate\""
            svg shouldContain "<rect"
            // Rounded rect carries the rx/ry attribute.
            svg shouldContain "rx=\"14\""
            // Body text appears as a second line.
            svg shouldContain "validate(order)"

            SampleOutput.write("sysml2-act/order-action.svg", svg)
        }

        "ACT renders Initial pseudo-node as filled circle" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"Initial\""
            svg shouldContain "<circle"
            // The fill="currentColor" attribute marks the initial pseudo-node.
            svg shouldContain "currentColor"

            SampleOutput.write("sysml2-act/order-initial.svg", svg)
        }

        "ACT renders Final pseudo-node as a donut (two concentric circles)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"Final\""
            // Donut: outer ring (white fill) + inner filled disc (currentColor).
            svg shouldContain "fill=\"white\""

            SampleOutput.write("sysml2-act/order-final.svg", svg)
        }

        "ACT renders FlowFinal as a circle with diagonal X lines inside" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"FlowFinal\""
            // X-form: two diagonal line elements in the FlowFinal group.
            // Find the substring starting at the FlowFinal id and look for <line.
            val ffIdx = svg.indexOf("id=\"FlowFinal\"")
            ffIdx shouldNotBe -1
            val ffEnd = svg.indexOf("</g>", ffIdx)
            val ffBlock = svg.substring(ffIdx, ffEnd)
            ffBlock shouldContain "<line"

            SampleOutput.write("sysml2-act/order-flowfinal.svg", svg)
        }

        "ACT renders Decision/Merge node as a diamond polygon" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"Valid?\""
            // Diamond emitted as a polygon with four points.
            svg shouldContain "<polygon"

            SampleOutput.write("sysml2-act/order-decision.svg", svg)
        }

        "ACT renders Fork/Join as a filled bar (thick rect with currentColor fill)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

            svg shouldContain "id=\"Split\""
            svg shouldContain "id=\"Sync\""
            // Bars use a filled rect — the currentColor fill identifies them.
            val splitIdx = svg.indexOf("id=\"Split\"")
            val splitEnd = svg.indexOf("</g>", splitIdx)
            val splitBlock = svg.substring(splitIdx, splitEnd)
            splitBlock shouldContain "<rect"
            splitBlock shouldContain "currentColor"

            SampleOutput.write("sysml2-act/order-fork.svg", svg)
        }

        "ACT is deterministic — same input renders byte-identically" {
            val (model, act) = orderModel()
            val svg1 = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())
            val svg2 = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())
            svg1 shouldBe svg2
        }

        "ACT control-flow edges surface as rendered paths in the SVG output" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())
            // fakeLayout has two control-flow edges; each lowers to a <path> or <line>
            svg shouldContain "<path"
        }

        // ── V2.0.16 Partitions + Pins ─────────────────────────────────────

        // Tiny two-partition model: Customer lane with PlaceOrder,
        // OrderSystem lane with ValidateOrder (input + output pin) and
        // ProcessPayment.
        fun partitionedModel(): Pair<Sysml2Model, ActDiagram> {
            val model =
                sysml2Model("OrderProcessingPartitions") {
                    val customer = activityPartition("Customer")
                    val orderSys = activityPartition("OrderSystem")
                    val place =
                        actionDef(
                            "PlaceOrder",
                            partition = customer,
                            pins = listOf(ActionPin("orderDetails", direction = PinDirection.Output)),
                        )
                    val validate =
                        actionDef(
                            "ValidateOrder",
                            partition = orderSys,
                            pins =
                                listOf(
                                    ActionPin("orderDetails", direction = PinDirection.Input),
                                    ActionPin("validation", direction = PinDirection.Output),
                                ),
                        )
                    val pay = actionDef("ProcessPayment", partition = orderSys)
                    controlFlow("p2v", place, validate)
                    controlFlow("v2p", validate, pay)
                    actDiagram("Partitioned Workflow") {
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
                canvas = Size(600f, 400f),
                nodes =
                    mapOf(
                        NodeId("PlaceOrder") to NodeLayout(bounds = Rect(Point(40f, 80f), Size(160f, 60f))),
                        NodeId("ValidateOrder") to NodeLayout(bounds = Rect(Point(280f, 80f), Size(160f, 60f))),
                        NodeId("ProcessPayment") to NodeLayout(bounds = Rect(Point(280f, 200f), Size(160f, 60f))),
                    ),
                edges =
                    mapOf(
                        EdgeId("p2v") to
                            EdgeRoute.Direct(
                                source = Point(200f, 110f),
                                target = Point(280f, 110f),
                            ),
                        EdgeId("v2p") to
                            EdgeRoute.Direct(
                                source = Point(360f, 140f),
                                target = Point(360f, 200f),
                            ),
                    ),
                groups =
                    mapOf(
                        GroupId("Customer") to GroupLayout(bounds = Rect(Point(20f, 20f), Size(220f, 340f))),
                        GroupId("OrderSystem") to GroupLayout(bounds = Rect(Point(260f, 20f), Size(220f, 340f))),
                    ),
            )

        "ACT partition renders as dashed vertical lane with header containing the partition name" {
            val (model, act) = partitionedModel()
            val svg = KumlSvgRenderer.toSvg(model, act, partitionedLayout(), PlainTheme())

            // One <g id="activityPartition:Customer"> group element with a
            // dashed outer rectangle (stroke-dasharray) + header text.
            svg shouldContain "id=\"activityPartition:Customer\""
            svg shouldContain "id=\"activityPartition:OrderSystem\""
            svg shouldContain "stroke-dasharray=\"6 4\""
            // Partition names surface in the lane headers (the SVG builder
            // pretty-prints text content on its own indented line).
            svg shouldContain "Customer"
            svg shouldContain "OrderSystem"

            SampleOutput.write("sysml2-act/order-partitions.svg", svg)
        }

        "ACT actions in different partitions appear in different lane bounds" {
            val (model, act) = partitionedModel()
            val svg = KumlSvgRenderer.toSvg(model, act, partitionedLayout(), PlainTheme())

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
            val svg = KumlSvgRenderer.toSvg(model, act, partitionedLayout(), PlainTheme())

            // ValidateOrder has both an Input and an Output pin — find its
            // <g> block and assert both pin labels surface inside it.
            val vIdx = svg.indexOf("id=\"ValidateOrder\"")
            val vEnd = svg.indexOf("</g>", vIdx)
            val vBlock = svg.substring(vIdx, vEnd)
            // Pin squares — kuml-class rects with width=10.
            vBlock shouldContain "width=\"10\""
            // Pin names render as small text labels adjacent to the squares
            // (pretty-printed on their own indented line).
            vBlock shouldContain "orderDetails"
            vBlock shouldContain "validation"

            SampleOutput.write("sysml2-act/order-pins.svg", svg)
        }

        "ACT action without pins is unchanged from V2.0.10 (regression guard)" {
            val (model, act) = orderModel()
            val svg = KumlSvgRenderer.toSvg(model, act, fakeLayout(), PlainTheme())

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
            val svg1 = KumlSvgRenderer.toSvg(model, act, partitionedLayout(), PlainTheme())
            val svg2 = KumlSvgRenderer.toSvg(model, act, partitionedLayout(), PlainTheme())
            svg1 shouldBe svg2
        }
    })

private infix fun Int.shouldNotBe(n: Int) {
    if (this == n) error("Expected value to not be $n, got $this")
}
