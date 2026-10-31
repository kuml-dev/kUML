@file:Suppress("RequireNamedArguments") // geometry literals in tests

package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** P-COM-01 Welle 2A-b: anchors end at what is drawn (port squares) and stay short and clear (lifelines). */
class Sysml2NoteAnchorRoutingRenderTest :
    FunSpec(body = {
        fun engine(): dev.kuml.layout.KumlLayoutEngine {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            return LayoutEngineRegistry.get("elk.layered")!!
        }

        fun anchorPaths(svg: String): List<List<Pair<Double, Double>>> {
            val paths =
                Regex("<path d=\"([^\"]+)\" class=\"kuml-note-anchor\"").findAll(svg).map { m ->
                    Regex("[ML] ([-0-9.]+) ([-0-9.]+)")
                        .findAll(m.groupValues[1])
                        .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                        .toList()
                }
            val lines =
                Regex(
                    "<line x1=\"([-0-9.]+)\" y1=\"([-0-9.]+)\" x2=\"([-0-9.]+)\" y2=\"([-0-9.]+)\" class=\"kuml-note-anchor\"",
                ).findAll(svg).map { m ->
                    listOf(
                        m.groupValues[1].toDouble() to m.groupValues[2].toDouble(),
                        m.groupValues[3].toDouble() to m.groupValues[4].toDouble(),
                    )
                }
            return (paths + lines).toList()
        }

        test("ibd: the port note ends on the drawn port square, which sits on the connection end, and has no dot") {
            val model =
                sysml2Model(name = "M") {
                    val powerLine = connectionDef(name = "PowerLine")
                    val dcPort = portDef(name = "DcPort")
                    val battery = partDef(name = "Battery") { port(name = "dcOut", typeId = dcPort.id) }
                    val motor = partDef(name = "ElectricMotor") { port(name = "dcIn", typeId = dcPort.id) }
                    val hybrid =
                        partDef(name = "HybridVehicle") {
                            part(name = "battery", typeId = battery.id)
                            part(name = "electricMotor", typeId = motor.id)
                            connect(
                                name = "batteryToMotor",
                                typeId = powerLine.id,
                                sourceEndId = "HybridVehicle::battery::dcOut",
                                targetEndId = "HybridVehicle::electricMotor::dcIn",
                            )
                        }
                    comment(text = "On the port", "Battery::dcOut", id = "np")
                    ibd(name = "I", owner = hybrid)
                }
            val d = model.diagrams.single() as dev.kuml.sysml2.IbdDiagram
            val lr = engine().layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d), hints = LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = lr, theme = PlainTheme())
            // absolute square of the battery port
            val (gx, gy) =
                Regex("<g id=\"HybridVehicle::battery\" transform=\"translate\\(([-0-9.]+),([-0-9.]+)\\)").find(svg)!!.destructured
            val sq = Regex("<rect x=\"([-0-9.]+)\" y=\"([-0-9.]+)\" width=\"12\" height=\"12\" class=\"kuml-port").find(svg)!!.destructured
            val left = gx.toDouble() + sq.component1().toDouble()
            val top = gy.toDouble() + sq.component2().toDouble()
            val path = anchorPaths(svg).single()
            val end = path.last()
            // the end lies on the border of the drawn square (within 1 px) ...
            val onBorder =
                (end.first in left - 1..left + 13 && (abs(end.second - top) < 1 || abs(end.second - (top + 12)) < 1)) ||
                    (end.second in top - 1..top + 13 && (abs(end.first - left) < 1 || abs(end.first - (left + 12)) < 1))
            onBorder shouldBe true
            // ... and the square is where the connection starts (its centre is the part-side end of the wire)
            val cx = left + 6
            val cy = top + 6
            (cx > gx.toDouble() && cx < gx.toDouble() + 400) shouldBe true
            (cy >= gy.toDouble() - 7) shouldBe true
            // a port anchor has no dot
            Regex("<circle[^>]*note-anchor-dot").findAll(svg).count() shouldBe 0
            // the route never enters the square
            path.zipWithNext().forEach { (a, b) ->
                val inside = (
                    maxOf(a.first, b.first) > left + 0.5 &&
                        minOf(a.first, b.first) < left + 11.5 &&
                        maxOf(a.second, b.second) > top + 0.5 &&
                        minOf(a.second, b.second) < top + 11.5
                )
                inside shouldBe false
            }
        }

        test("seq: the lifeline note has a single straight anchor line that is not vertical") {
            val model =
                sysml2Model(name = "M") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    val c = lifelineDef(name = "C")
                    message(label = "ping", source = a, target = b, seqNo = 0)
                    message(label = "pong", source = b, target = c, seqNo = 1)
                    comment(text = "On the inner lifeline", firstAnchor = b, id = "nb")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val d = model.diagrams.single() as dev.kuml.sysml2.SeqDiagram
            val lr = engine().layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d), hints = LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = lr, theme = PlainTheme())
            val path = anchorPaths(svg).single()
            path.size shouldBe 2
            // horizontal: same y, so it can never be mistaken for a lifeline continuing
            (abs(path[0].second - path[1].second) < 0.6) shouldBe true
        }
    })
