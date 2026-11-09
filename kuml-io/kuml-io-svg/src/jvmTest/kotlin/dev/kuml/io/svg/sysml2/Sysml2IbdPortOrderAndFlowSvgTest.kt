package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * Nacharbeit 48 (N-IBD-F d, e, f, g) measured on the full pipeline (ELK layout, owner frame, label plan). Structure and
 * geometry only: attributes, ids and coordinates; no byte comparison and no measured text widths.
 */
class Sysml2IbdPortOrderAndFlowSvgTest :
    FunSpec(body = {
        fun layoutOf(m: Sysml2Model): LayoutResult {
            val d = m.diagrams.filterIsInstance<IbdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                        spec = d.layout,
                        kind = SpecDiagramKind.SYSML2_IBD,
                    ).result
            val nested = Sysml2LayoutBridge.addIbdNestedFrames(model = m, diagram = d, layoutResult = raw)
            return Sysml2LayoutBridge.addIbdOwnerFrame(model = m, diagram = d, layoutResult = nested)
        }

        fun svgOf(m: Sysml2Model): String {
            val d = m.diagrams.filterIsInstance<IbdDiagram>().single()
            return KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = layoutOf(m), theme = PlainTheme())
        }

        fun centre(
            lr: LayoutResult,
            id: String,
        ): Point {
            val b = lr.nodes.getValue(NodeId(id)).bounds
            return Point(x = b.origin.x + b.size.width / 2f, y = b.origin.y + b.size.height / 2f)
        }

        fun frameOf(lr: LayoutResult) = lr.groups.getValue(Sysml2LayoutBridge.ibdFrameGroupId("Frame")).bounds

        /** Source and sink with ports of every flow kind, owner ports `oi` (in) and `oo` (out). */
        fun flowModel(flow: LayoutFlow? = null): Sysml2Model =
            sysml2Model(name = "Fx") {
                val water = attributeDef(name = "Water")
                portDef(name = "Sig")
                portDef(name = "FIn") { flowProperty(name = "w", typeId = water.id, direction = PortDirection.In) }
                portDef(name = "FOut") { flowProperty(name = "w", typeId = water.id, direction = PortDirection.Out) }
                portDef(name = "FBoth") { flowProperty(name = "w", typeId = water.id, direction = PortDirection.InOut) }
                connectionDef(name = "Line")
                partDef(name = "Source") {
                    port(name = "q", typeId = "Sig", direction = PortDirection.Out)
                    port(name = "fo", typeId = "FOut")
                    port(name = "fb", typeId = "FBoth")
                    port(name = "plain", typeId = "Sig")
                }
                partDef(name = "Sink") {
                    port(name = "s", typeId = "Sig", direction = PortDirection.In)
                    port(name = "fi", typeId = "FIn")
                    port(name = "fc", typeId = "FOut", conjugated = true)
                    port(name = "plain", typeId = "Sig")
                }
                val frame =
                    partDef(name = "Frame") {
                        port(name = "oi", typeId = "Sig", direction = PortDirection.In)
                        port(name = "oo", typeId = "Sig", direction = PortDirection.Out)
                        part(name = "src", typeId = "Source")
                        part(name = "snk", typeId = "Sink")
                        connect(name = "kf", typeId = "Line", sourceEndId = "src.fo", targetEndId = "snk.fi")
                        connect(name = "kb", typeId = "Line", sourceEndId = "src.fb", targetEndId = "snk.fc")
                        connect(name = "kp", typeId = "Line", sourceEndId = "src.plain", targetEndId = "snk.plain")
                        connect(name = "ki", typeId = "Line", sourceEndId = "Frame::oi", targetEndId = "snk.s")
                        connect(name = "ko", typeId = "Line", sourceEndId = "src.q", targetEndId = "Frame::oo")
                        flow(item = water.id, from = "src.fo", to = "snk.fi", via = "kf", name = "fw")
                    }
                ibd(name = "Fx IBD", owner = frame) { if (flow != null) layout { direction = flow } }
            }

        test("N-IBD-F f - the flow glyph follows the declared or the derived direction, with conjugation, and plain ports have none") {
            val svg = svgOf(flowModel())
            val glyphs = Regex("""<polygon[^>]*class="kuml-sysml2-port-dir kuml-ibd-port-flow"[^>]*data-flow="(\w+)"""").findAll(svg)
            val byFlow = glyphs.map { it.groupValues[1] }.groupingBy { it }.eachCount()
            // in: snk.s, snk.fi, snk.fc (conjugated out), oi; out: src.q, src.fo, oo; inout: src.fb draws two triangles.
            byFlow["in"] shouldBe 4
            byFlow["out"] shouldBe 3
            byFlow["inout"] shouldBe 2
        }

        test("N-IBD-F e - in ports sit on the entering frame side, out ports on the leaving one (down and right)") {
            val down = layoutOf(flowModel())
            val fd = frameOf(down)
            (abs(centre(down, "Frame::oi").y - fd.origin.y) < 0.5f) shouldBe true
            (abs(centre(down, "Frame::oo").y - (fd.origin.y + fd.size.height)) < 0.5f) shouldBe true

            val right = layoutOf(flowModel(flow = LayoutFlow.Right))
            val fr = frameOf(right)
            (abs(centre(right, "Frame::oi").x - fr.origin.x) < 0.5f) shouldBe true
            (abs(centre(right, "Frame::oo").x - (fr.origin.x + fr.size.width)) < 0.5f) shouldBe true
        }

        test("N-IBD-F d - owner ports at the same coordinate keep their declaration order, not the id order") {
            val m =
                sysml2Model(name = "Tie") {
                    portDef(name = "Sig")
                    connectionDef(name = "Line")
                    partDef(name = "Leaf") { port(name = "x", typeId = "Sig") }
                    val frame =
                        partDef(name = "Frame") {
                            port(name = "zeta", typeId = "Sig")
                            port(name = "alpha", typeId = "Sig")
                            part(name = "leaf", typeId = "Leaf")
                            connect(name = "c1", typeId = "Line", sourceEndId = "Frame::zeta", targetEndId = "leaf.x")
                            connect(name = "c2", typeId = "Line", sourceEndId = "Frame::alpha", targetEndId = "leaf.x")
                        }
                    ibd(name = "Tie IBD", owner = frame)
                }
            val lr = layoutOf(m)
            val zeta = centre(lr, "Frame::zeta")
            val alpha = centre(lr, "Frame::alpha")
            val sameSideVertical = abs(zeta.y - alpha.y) < 0.5f
            val sameSideHorizontal = abs(zeta.x - alpha.x) < 0.5f
            (sameSideVertical || sameSideHorizontal) shouldBe true
            val along = if (sameSideVertical) zeta.x < alpha.x else zeta.y < alpha.y
            along shouldBe true
        }

        test("N-IBD-F g - the item-flow label is part of the connection label block and not drawn separately") {
            val svg = svgOf(flowModel())
            val groups = svg.split("<g class=\"kuml-edge-label\"").drop(1).map { it.substringBefore("</g>") }
            val withItem = groups.filter { it.contains("Water") }
            withItem.size shouldBe 1
            (withItem.single().contains("kf : Line")) shouldBe true

            // One block: its rectangle is taller than the one of a connection label without an item flow.
            fun height(block: String): Float =
                Regex("""data-label-rect="[-\d.]+ [-\d.]+ [-\d.]+ ([-\d.]+)"""").find(block)!!.groupValues[1].toFloat()
            val plain = groups.first { it.contains("kp : Line") }
            (height(withItem.single()) > height(plain)) shouldBe true
            // No stray item text outside the label group: every occurrence lies in that group.
            svg.split("Water").size shouldBe withItem.single().split("Water").size
        }
    })
