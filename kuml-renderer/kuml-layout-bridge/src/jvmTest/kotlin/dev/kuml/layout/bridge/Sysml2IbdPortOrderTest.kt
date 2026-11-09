package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 48 (N-IBD-F d, e): pure helpers of the port order and the owner port side. */
class Sysml2IbdPortOrderTest :
    FunSpec(body = {
        val box = Size(width = 400f, height = 100f)
        val labels = mapOf("a" to "a", "b" to "b")

        test("N-IBD-F d - ports at the same coordinate keep their declaration order, not the key order") {
            val ports = mapOf("a" to Point(x = 200f, y = 0f), "b" to Point(x = 200f, y = 0f))
            val declared =
                Sysml2LayoutBridge.ibdSpreadPorts(
                    boxSize = box,
                    ports = ports,
                    labels = labels,
                    declarationIndex =
                        mapOf(
                            "b" to 0,
                            "a" to 1,
                        ),
                )
            (declared.getValue("b").x < declared.getValue("a").x) shouldBe true
            val byKey = Sysml2LayoutBridge.ibdSpreadPorts(boxSize = box, ports = ports, labels = labels)
            (byKey.getValue("a").x < byKey.getValue("b").x) shouldBe true
        }

        test("N-IBD-F d - a different coordinate still decides before the declaration order") {
            val ports = mapOf("a" to Point(x = 100f, y = 0f), "b" to Point(x = 300f, y = 0f))
            val out =
                Sysml2LayoutBridge.ibdSpreadPorts(
                    boxSize = box,
                    ports = ports,
                    labels = labels,
                    declarationIndex =
                        mapOf(
                            "b" to 0,
                            "a" to 1,
                        ),
                )
            (out.getValue("a").x < out.getValue("b").x) shouldBe true
        }

        test("N-IBD-F d - the declaration index is the position of the port usage in the model") {
            val m =
                sysml2Model(name = "Dec") {
                    portDef(name = "Sig")
                    partDef(name = "Leaf") {
                        port(name = "zeta", typeId = "Sig")
                        port(name = "alpha", typeId = "Sig")
                    }
                    partDef(name = "Top") { part(name = "leaf", typeId = "Leaf") }
                }
            val order =
                Sysml2LayoutBridge.ibdPortDeclarationOrder(
                    model = m,
                    nodeId = "Top::leaf",
                    keys = listOf("alpha", "zeta", "missing"),
                )
            (order.getValue("zeta") < order.getValue("alpha")) shouldBe true
            order.containsKey("missing") shouldBe false
        }

        test("N-IBD-F e - side by direction and flow") {
            val side = Sysml2LayoutBridge::ibdPreferredOwnerSide
            side(PortDirection.In, null) shouldBe Sysml2LayoutBridge.IbdSide.Top
            side(PortDirection.Out, LayoutFlow.Down) shouldBe Sysml2LayoutBridge.IbdSide.Bottom
            side(PortDirection.In, LayoutFlow.Right) shouldBe Sysml2LayoutBridge.IbdSide.Left
            side(PortDirection.Out, LayoutFlow.Right) shouldBe Sysml2LayoutBridge.IbdSide.Right
            side(PortDirection.In, LayoutFlow.Left) shouldBe Sysml2LayoutBridge.IbdSide.Right
            side(PortDirection.Out, LayoutFlow.Up) shouldBe Sysml2LayoutBridge.IbdSide.Top
            side(PortDirection.InOut, LayoutFlow.Right) shouldBe null
            side(null, LayoutFlow.Right) shouldBe null
        }

        test("N-IBD-F e - a preferred side is used unless it is the excluded one") {
            val frame = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 400f, height = 300f))
            val part = Rect(origin = Point(x = 100f, y = 100f), size = Size(width = 100f, height = 50f))
            val targets = listOf(Point(x = 150f, y = 150f))
            Sysml2LayoutBridge
                .placeOwnerPort(frame = frame, targets = targets, partBox = part, prefer = Sysml2LayoutBridge.IbdSide.Right)
                .first shouldBe Sysml2LayoutBridge.IbdSide.Right
            val fallback =
                Sysml2LayoutBridge.placeOwnerPort(
                    frame = frame,
                    targets = targets,
                    partBox = part,
                    exclude = Sysml2LayoutBridge.IbdSide.Right,
                    prefer = Sysml2LayoutBridge.IbdSide.Right,
                )
            val nearest =
                Sysml2LayoutBridge.placeOwnerPort(
                    frame = frame,
                    targets = targets,
                    partBox = part,
                    exclude = Sysml2LayoutBridge.IbdSide.Right,
                )
            fallback shouldBe nearest
        }
    })
