package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
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
                edges = emptyMap(),
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
    })

private infix fun Int.shouldNotBe(n: Int) {
    if (this == n) error("Expected value to not be $n, got $this")
}
