package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun overlap(
    a: Rect,
    b: Rect,
): Boolean =
    a.origin.x < b.origin.x + b.size.width &&
        b.origin.x < a.origin.x + a.size.width &&
        a.origin.y < b.origin.y + b.size.height &&
        b.origin.y < a.origin.y + a.size.height

/**
 * Wave F (Nacharbeit 40), named regression test "I19 I7 IBD" (stand-in model: three flows on adjacent connectors with
 * long item names and three ports per part). No two placed labels (flow labels, connector labels, port labels) overlap,
 * and the plan is deterministic. Geometry only, no measured text and no byte comparison.
 */
class Sysml2IbdLabelCollisionTest :
    FunSpec({
        fun model(): Sysml2Model =
            sysml2Model(name = "Flows") {
                val powerLine = connectionDef(name = "PowerLine")
                val dcPort = portDef(name = "DcPort")
                val fuel = attributeDef(name = "FuelFlowWithLongName")
                val tank =
                    partDef(name = "Tank") {
                        port(name = "outletPrimary", typeId = dcPort.id)
                        port(name = "outletSecondary", typeId = dcPort.id)
                        port(name = "outletReturn", typeId = dcPort.id)
                    }
                val engine =
                    partDef(name = "Engine") {
                        port(name = "inletPrimary", typeId = dcPort.id)
                        port(name = "inletSecondary", typeId = dcPort.id)
                        port(name = "inletReturn", typeId = dcPort.id)
                    }
                val car =
                    partDef(name = "Car") {
                        part(name = "tank", typeId = tank.id)
                        part(name = "engine", typeId = engine.id)
                        connect(name = "c1", typeId = powerLine.id, sourceEndId = "tank.outletPrimary", targetEndId = "engine.inletPrimary")
                        connect(
                            name = "c2",
                            typeId = powerLine.id,
                            sourceEndId = "tank.outletSecondary",
                            targetEndId = "engine.inletSecondary",
                        )
                        connect(name = "c3", typeId = powerLine.id, sourceEndId = "engine.inletReturn", targetEndId = "tank.outletReturn")
                        flow(item = fuel, from = "tank.outletPrimary", to = "engine.inletPrimary", name = "fuelFlow")
                        flow(item = fuel, from = "tank.outletSecondary", to = "engine.inletSecondary", name = "secondaryFuelFlow")
                        flow(item = fuel, from = "engine.inletReturn", to = "tank.outletReturn", name = "returnFlowLongName")
                    }
                ibd(name = "Flows IBD", owner = car)
            }

        fun plan(m: Sysml2Model): Pair<LayoutResult, Map<String, Rect>> {
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
            val framed = Sysml2LayoutBridge.addIbdOwnerFrame(model = m, diagram = d, layoutResult = nested)
            val enriched =
                if (dev.kuml.sysml2.projection
                        .sysml2IbdProjection(model = m, diagram = d)
                        .showFrame
                ) {
                    framed
                } else {
                    Sysml2LayoutBridge.enrichIbdPortPositions(model = m, diagram = d, layoutResult = framed)
                }
            val labels =
                KumlSvgRenderer.planSysml2EdgeLabels(
                    layout = enriched,
                    adapter = IbdEdgeAdapter(model = m, diagram = d),
                    portLabels = Sysml2LayoutBridge.ibdPortLabelSpecs(model = m, diagram = d, layout = enriched),
                    titleRects = Sysml2LayoutBridge.ibdTitleRects(model = m, layout = enriched),
                )
            return enriched to labels.mapValues { it.value.rect }
        }

        test("I19 I7 IBD: no two placed labels overlap and the plan is deterministic") {
            val m = model()
            val (_, rects) = plan(m)
            val sorted = rects.entries.sortedBy { it.key }
            (sorted.size >= 6) shouldBe true
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    if (overlap(a = sorted[i].value, b = sorted[j].value)) {
                        throw AssertionError("labels ${sorted[i].key} and ${sorted[j].key} overlap")
                    }
                }
            }
            plan(m).second shouldBe rects
        }
    })
