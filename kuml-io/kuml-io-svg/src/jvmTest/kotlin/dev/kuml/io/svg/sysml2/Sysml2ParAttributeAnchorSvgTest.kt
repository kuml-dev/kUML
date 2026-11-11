package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.ConstraintParameterDirection.Out
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.pin
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.projection.BddBoxGeometry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.shouldBeBetween
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 65: part-attribute bindings dock at the attribute row on the side edge of the part, and the lanes of
 * nested bindings keep a minimum clearance. Pure geometry on the layout result, so no font is needed.
 */
class Sysml2ParAttributeAnchorSvgTest :
    StringSpec({
        timeout = 60.seconds.inWholeMilliseconds

        fun layoutOf(
            model: Sysml2Model,
            enrich: Boolean = false,
        ): LayoutResult {
            val diagram = model.diagrams.filterIsInstance<ParDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            val raw =
                LayoutSpecRunner
                    .layout(
                        engine = ElkLayoutEngine(),
                        graph = graph,
                        hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = diagram),
                        spec = diagram.layout,
                        kind = SpecDiagramKind.SYSML2_PAR,
                    ).result
            return if (enrich) Sysml2LayoutBridge.parEnrich(model = model, diagram = diagram, layoutResult = raw) else raw
        }

        fun points(route: EdgeRoute): List<Point> =
            when (route) {
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + route.target
                else -> listOf(route.source, route.target)
            }

        fun unitModel(sourceOfSecond: String = "Unit::a2"): Sysml2Model =
            sysml2Model(name = "Anchors") {
                attributeDef(name = "Real")
                val rule =
                    constraintDef(
                        name = "Rule",
                        expression = "z = x + y",
                        parameters =
                            listOf(
                                ConstraintParameter(name = "x", typeId = "Real", direction = In),
                                ConstraintParameter(name = "y", typeId = "Real", direction = In),
                                ConstraintParameter(name = "z", typeId = "Real", direction = Out),
                            ),
                    )
                val unit =
                    partDef(name = "Unit") {
                        attribute(name = "a1", typeId = "Real")
                        attribute(name = "a2", typeId = "Real")
                        attribute(name = "a3", typeId = "Real")
                    }
                bind(name = "second_to_x", source = sourceOfSecond, target = rule.pin("x"))
                bind(name = "first_to_y", source = "Unit::a1", target = rule.pin("y"))
                bind(name = "z_to_third", source = rule.pin("z"), target = "Unit::a3")
                parDiagram(name = "Anchors") {
                    include(definition = rule)
                    include(definition = unit)
                }
            }

        /** The route end that lies on the part box (the one whose y is inside the box). */
        fun endAtPart(
            layout: LayoutResult,
            idPart: String,
            box: Rect,
        ): Point {
            val route =
                layout.edges.entries
                    .single { it.key.value.contains(idPart) }
                    .value
            val ends = listOf(route.source, route.target)
            return ends.single { it.y in box.origin.y..(box.origin.y + box.size.height) && isOnSide(p = it, box = box) }
        }

        "an attribute binding ends at the centre of its row on the side edge of the part" {
            val layout = layoutOf(unitModel())
            val box = layout.nodes.getValue(NodeId("Unit")).bounds
            val second = endAtPart(layout = layout, idPart = "Unit::a2", box = box)
            val first = endAtPart(layout = layout, idPart = "Unit::a1", box = box)
            val third = endAtPart(layout = layout, idPart = "Unit::a3", box = box)
            for ((end, index) in listOf(first to 0, second to 1, third to 2)) {
                end.y.shouldBeBetween(
                    box.origin.y + BddBoxGeometry.featureRowCentreY(index) - 2f,
                    box.origin.y + BddBoxGeometry.featureRowCentreY(index) + 2f,
                    0f,
                )
            }
            // In-pins sit on the west side of the constraint, so those bindings dock at the west edge, the out-pin at the east edge.
            abs(second.x - box.origin.x) shouldBeLessOrEqual 1f
            abs(first.x - box.origin.x) shouldBeLessOrEqual 1f
            abs(third.x - (box.origin.x + box.size.width)) shouldBeLessOrEqual 1f
            setOf(first.y, second.y, third.y).size shouldBe 3
        }

        "a binding without an attribute name or with an unknown attribute creates no anchor port" {
            val plain = layoutOf(unitModel(sourceOfSecond = "Unit"))
            val unknown = layoutOf(unitModel(sourceOfSecond = "Unit::missing"))
            // Only the attributes a1 and a3 are addressed in these models, a2 never gets a port.
            for (layout in listOf(plain, unknown)) {
                val ports =
                    layout.nodes
                        .getValue(NodeId("Unit"))
                        .ports.keys
                        .map { it.value }
                ports.none { it.endsWith("::attr::a2") || it.endsWith("::attr::missing") } shouldBe true
            }
        }

        "a part without any attribute binding gets no port at all" {
            val model =
                sysml2Model(name = "NoAnchors") {
                    attributeDef(name = "Real")
                    val rule =
                        constraintDef(
                            name = "Rule",
                            expression = "z = x",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "x", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "z", typeId = "Real", direction = Out),
                                ),
                        )
                    val unit = partDef(name = "Unit") { attribute(name = "a1", typeId = "Real") }
                    bind(name = "whole", source = "Unit", target = rule.pin("x"))
                    parDiagram(name = "NoAnchors") {
                        include(definition = rule)
                        include(definition = unit)
                    }
                }
            layoutOf(model)
                .nodes
                .getValue(NodeId("Unit"))
                .ports.keys
                .toList()
                .shouldBeEmpty()
        }

        "lanes of nested bindings keep at least 12 px to the owner edge and sibling lanes to the child" {
            val model =
                sysml2Model(name = "Clearance") {
                    attributeDef(name = "Real")
                    val outer =
                        constraintDef(
                            name = "Outer",
                            expression = "o = f(i1, i2)",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "i1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "i2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "o1", typeId = "Real", direction = Out),
                                ),
                        )
                    val a =
                        constraintDef(
                            name = "StageA",
                            expression = "a_out = a1",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "a1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "a_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    val b =
                        constraintDef(
                            name = "StageB",
                            expression = "b_out = b1 + b2",
                            parameters =
                                listOf(
                                    ConstraintParameter(name = "b1", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b2", typeId = "Real", direction = In),
                                    ConstraintParameter(name = "b_out", typeId = "Real", direction = Out),
                                ),
                            parent = outer,
                        )
                    bind(name = "i1_a1", source = outer.pin("i1"), target = a.pin("a1"))
                    bind(name = "i2_b2", source = outer.pin("i2"), target = b.pin("b2"))
                    bind(name = "a_to_b1", source = a.pin("a_out"), target = b.pin("b1"))
                    bind(name = "b_to_o1", source = b.pin("b_out"), target = outer.pin("o1"))
                    parDiagram(name = "Clearance") {
                        include(definition = outer)
                        include(definition = a)
                        include(definition = b)
                    }
                }
            val layout = layoutOf(model = model, enrich = true)
            val outerBox = layout.nodes.getValue(NodeId("Outer")).bounds
            val childA = layout.nodes.getValue(NodeId("StageA")).bounds
            var checked = 0
            for ((id, route) in layout.edges) {
                val pts = points(route)
                if (pts.size < 4 || !id.value.startsWith("binding:")) continue
                // Interior segments only: the first one leaves the pin, the last one is the approach stub into the pin.
                for ((p, q) in pts.drop(1).dropLast(1).zipWithNext()) {
                    if (p.x != q.x) continue
                    val left = p.x - outerBox.origin.x
                    val right = outerBox.origin.x + outerBox.size.width - p.x
                    if (left >= 0f && right >= 0f) {
                        minOf(left, right).shouldBeGreaterThanOrEqual(12f)
                        checked++
                    }
                }
            }
            (checked > 0) shouldBe true
            // The short sibling lane between StageA and StageB keeps its distance to the child it leaves.
            val short =
                points(
                    layout.edges.entries
                        .single { it.key.value.contains("StageA::a_out::StageB::b1") }
                        .value,
                )
            val mid = short.drop(1).dropLast(1)
            mid.size shouldBe 2
            (mid.first().x - (childA.origin.x + childA.size.width)).shouldBeGreaterThanOrEqual(12f)
        }
    })

private fun isOnSide(
    p: Point,
    box: Rect,
): Boolean = abs(p.x - box.origin.x) <= 1f || abs(p.x - (box.origin.x + box.size.width)) <= 1f

private infix fun Float.shouldBeLessOrEqual(max: Float) {
    (this <= max) shouldBe true
}
