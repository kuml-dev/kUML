package dev.kuml.layout.bridge

import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Wave C2 — ACT sizes for the new node kinds, pin growth and exception-handler edges. */
class Sysml2C2ActLayoutTest :
    StringSpec({
        fun size(
            model: dev.kuml.sysml2.Sysml2Model,
            id: String,
            kind: ActivityNodeKind,
        ) = Sysml2LayoutBridge.actContentAwareSizeProvider(model).sizeOf(elementId = id, elementKind = kind.name)

        "signal nodes use the 110 x 40 default, call-behavior is wider than an action" {
            val m =
                sysml2Model(name = "S") {
                    sendSignal(name = "S")
                    acceptEvent(name = "A")
                    callBehavior(name = "C")
                    actionDef(name = "X")
                }
            size(m, "S", ActivityNodeKind.SendSignal).let { it.width to it.height } shouldBe (110f to 40f)
            size(m, "A", ActivityNodeKind.AcceptEvent).let { it.width to it.height } shouldBe (110f to 40f)
            size(m, "C", ActivityNodeKind.CallBehavior).width shouldBe
                size(m, "X", ActivityNodeKind.Action).width + Sysml2LayoutBridge.ACT_CALL_FORK_EXTRA_W
        }

        "accept-time-event is a narrow hourglass node with room for its label, capped at 120 px" {
            val m =
                sysml2Model(name = "T") {
                    acceptTimeEvent(name = "short", time = "1s")
                    acceptTimeEvent(name = "long", time = "after ".repeat(40))
                }
            val s = size(m, "short", ActivityNodeKind.AcceptTimeEvent)
            s.height shouldBe Sysml2LayoutBridge.ACT_TIME_EVENT_H + Sysml2LayoutBridge.ACT_TIME_EVENT_LABEL_HEIGHT
            size(m, "long", ActivityNodeKind.AcceptTimeEvent).width shouldBe Sysml2LayoutBridge.ACT_TIME_EVENT_MAX_WIDTH
        }

        "explicit pin sides grow the box, Auto pins leave the size exactly as before" {
            val m =
                sysml2Model(name = "P") {
                    actionDef(
                        name = "auto",
                        pins = (1..8).map { ActionPin(name = "p$it", direction = PinDirection.Output) },
                    )
                    actionDef(
                        name = "top",
                        pins = (1..8).map { ActionPin(name = "p$it", direction = PinDirection.Output, side = PinSide.Top) },
                    )
                    actionDef(
                        name = "right",
                        pins = (1..8).map { ActionPin(name = "p$it", direction = PinDirection.Output, side = PinSide.Right) },
                    )
                }
            size(m, "auto", ActivityNodeKind.Action).let { it.width to it.height } shouldBe
                (Sysml2LayoutBridge.ACT_ACTION_WIDTH to Sysml2LayoutBridge.ACT_ACTION_HEIGHT)
            (size(m, "top", ActivityNodeKind.Action).width > Sysml2LayoutBridge.ACT_ACTION_WIDTH) shouldBe true
            size(m, "top", ActivityNodeKind.Action).height shouldBe Sysml2LayoutBridge.ACT_ACTION_HEIGHT
            (size(m, "right", ActivityNodeKind.Action).height > Sysml2LayoutBridge.ACT_ACTION_HEIGHT) shouldBe true
        }

        "exception handlers become layout edges, regions add no layout groups" {
            val m =
                sysml2Model(name = "E") {
                    val r = interruptibleRegion(name = "R")
                    val p = actionDef(name = "P", region = r)
                    val h = actionDef(name = "H")
                    exceptionHandler(protectedNode = p, handler = h, type = "Err")
                    actDiagram(name = "A") {
                        include(p)
                        include(h)
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.filterIsInstance<ActDiagram>().single())
            g.edges.map { it.id.value } shouldBe listOf("exceptionHandler:P::H")
            g.groups.size shouldBe 0
        }
    })
