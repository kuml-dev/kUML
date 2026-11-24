@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

/** PAL Welle L: narrower label variants, the last-resort distance, the W013 reason and the work budget of the repair pass. */
class StmLabelVariantTest :
    FunSpec(body = {
        fun vertical(id: String) = id to EdgeRoute.Direct(source = Point(100f, 0f), target = Point(100f, 200f))

        fun horizontal(id: String) = id to EdgeRoute.Direct(source = Point(0f, 100f), target = Point(200f, 100f))

        fun plan(
            edge: Pair<String, EdgeRoute>,
            nodes: Map<String, Rect>,
            spec: StmLabelSpec,
            costs: LabelCostModel = Sysml2DiagramLabelPlan.STM_COSTS,
        ) = Sysml2DiagramLabelPlan.planStmLabels(
            edges = mapOf(edge),
            nodes = nodes,
            composites = emptyList(),
            labels = listOf(spec),
            frameTop = 0f,
            costs = costs,
        )

        /** Walls left and right of a vertical line at x = 100: 37 px of free corridor on each side, too narrow for 60 px. */
        val walls = mapOf("left" to box(-200f, 10f, 260f, 180f), "right" to box(140f, 10f, 260f, 180f))
        val wide = StmLabelSpec(id = "e", lines = listOf("a long label"), size = Size(60f, 18f))

        test("a label that fits no corridor at its size is put back in the first narrower variant that fits") {
            val spec =
                wide.copy(
                    variants =
                        listOf(
                            StmLabelVariant(lines = listOf("a long", "label"), size = Size(50f, 30f)),
                            StmLabelVariant(lines = listOf("a", "long", "label"), size = Size(20f, 42f)),
                        ),
                )
            val r = plan(edge = vertical("e"), nodes = walls, spec = spec)
            r.unresolved shouldBe emptyList()
            r.variantChosen shouldBe mapOf("e" to 1)
            r.lines.getValue("e") shouldBe listOf("a", "long", "label")
            val p = r.labels.getValue("e")
            p.clean shouldBe true
            p.rect.size shouldBe Size(20f, 42f)
            // Beside the line (x = 100) and clear of both walls.
            val left = p.rect.origin.x
            val right = left + p.rect.size.width
            ((right <= 100f && left >= 62f) || (left >= 100f && right <= 138f)) shouldBe true
        }

        test("the widest variant that fits is taken, so the index is the first that works") {
            val spec =
                wide.copy(
                    variants =
                        listOf(
                            StmLabelVariant(lines = listOf("first"), size = Size(30f, 30f)),
                            StmLabelVariant(lines = listOf("second"), size = Size(20f, 42f)),
                        ),
                )
            val r = plan(edge = vertical("e"), nodes = walls, spec = spec)
            r.variantChosen shouldBe mapOf("e" to 0)
            r.lines.getValue("e") shouldBe listOf("first")
        }

        test("a label that fits at its own size reports no variant") {
            val spec = wide.copy(variants = listOf(StmLabelVariant(lines = listOf("a"), size = Size(20f, 18f))))
            val r = plan(edge = vertical("e"), nodes = emptyMap(), spec = spec)
            r.variantChosen shouldBe emptyMap()
            r.unresolved shouldBe emptyList()
            r.lines.getValue("e") shouldBe listOf("a long label")
        }

        test("last resort: a position 18 px from the line is accepted when every nearer offset is blocked") {
            // Two thin node bands hug the line (y 86..99 and 101..114, 2 px node clearance): offsets 3..14 are blocked on both sides.
            val bands = mapOf("above" to box(-50f, 86f, 300f, 13f), "below" to box(-50f, 101f, 300f, 13f))
            val spec =
                StmLabelSpec(
                    id = "e",
                    lines = listOf("label"),
                    size = Size(60f, 18f),
                    variants = listOf(StmLabelVariant(listOf("lab"), Size(40f, 18f))),
                )
            val r = plan(edge = horizontal("e"), nodes = bands, spec = spec)
            r.unresolved shouldBe emptyList()
            val p = r.labels.getValue("e")
            p.clean shouldBe true
            val top = p.rect.origin.y
            val bottom = top + p.rect.size.height
            // Exactly the 18 px stage: 18 px between the line (y = 100) and the facing label edge.
            val gap = if (bottom <= 100f) 100f - bottom else top - 100f
            gap shouldBe 18f
            // Without variants the stage does not run (the label stays unresolved and keeps its compromise).
            val without = plan(edge = horizontal("e"), nodes = bands, spec = spec.copy(variants = emptyList()))
            without.unresolved shouldBe listOf("e")
            without.labels.getValue("e").clean shouldBe false
        }

        test("an unresolved label carries the rule that blocks most of its positions") {
            val nodes = mapOf("above" to box(-50f, 60f, 400f, 38f), "below" to box(-50f, 102f, 400f, 38f))
            val r =
                plan(
                    edge = horizontal("e"),
                    nodes = nodes,
                    spec =
                        StmLabelSpec(
                            id = "e",
                            lines = listOf("label"),
                            size = Size(60f, 18f),
                            variants = listOf(StmLabelVariant(listOf("l"), Size(20f, 18f))),
                        ),
                )
            r.unresolved shouldBe listOf("e")
            r.unresolvedReason shouldBe mapOf("e" to "node")
            r.variantChosen shouldBe emptyMap()
        }

        test("an exhausted work budget ends the repair without a result and without a crash") {
            val spec = wide.copy(variants = listOf(StmLabelVariant(lines = listOf("a"), size = Size(20f, 18f))))
            val r =
                plan(edge = vertical("e"), nodes = walls, spec = spec, costs = Sysml2DiagramLabelPlan.STM_COSTS.copy(totalWorkBudget = 1L))
            r.variantChosen shouldBe emptyMap()
            r.unresolved shouldBe listOf("e")
            r.unresolvedReason shouldBe mapOf("e" to "none")
            r.labels.getValue("e").clean shouldBe false
        }

        test("violationReason names the first broken rule and is null for a free position") {
            val e =
                LabelRequest(
                    id = "e",
                    role = LabelRole.EDGE_CENTER,
                    ownerId = "e",
                    size = Size(60f, 18f),
                    polyline = listOf(Point(0f, 100f), Point(200f, 100f)),
                )
            val index =
                dev.kuml.layout.bridge.note
                    .ObstacleIndex()
            index.addPolyline(kind = dev.kuml.layout.bridge.note.ObstacleKind.EDGE, pts = e.polyline, ownerId = "e")
            index.addRect(kind = dev.kuml.layout.bridge.note.ObstacleKind.NODE, r = box(60f, 104f, 80f, 40f), ownerId = "n")
            val costs = Sysml2DiagramLabelPlan.STM_COSTS
            StmLabelRepair.violationReason(
                req = e,
                rect = box(70f, 110f, 60f, 18f),
                others = emptyMap(),
                index = index,
                costs = costs,
            ) shouldBe
                "node"
            StmLabelRepair.violationReason(
                req = e,
                rect = box(70f, 76f, 60f, 18f),
                others = emptyMap(),
                index = index,
                costs = costs,
            ) shouldBe
                null
            StmLabelRepair.violationReason(
                req = e,
                rect = box(70f, 40f, 60f, 18f),
                others = emptyMap(),
                index = index,
                costs = costs,
            ) shouldBe
                "own-far"
            StmLabelRepair.violationReason(
                req = e,
                rect = box(150f, 90f, 40f, 18f),
                others = emptyMap(),
                index = index,
                costs = costs,
            ) shouldBe
                "own-edge"
            StmLabelRepair.violationReason(
                req = e,
                rect = box(70f, 70f, 60f, 18f),
                others = mapOf("o" to box(120f, 70f, 40f, 18f)),
                index = index,
                costs = costs,
            ) shouldBe
                "label"
        }
    })
