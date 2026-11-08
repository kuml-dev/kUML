package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 43: cost model of the state-machine label plan. The 4 px clearance to foreign edges and the "crossing the
 * own line is not clean" rule apply to state machines only; every other cost model keeps its old values.
 */
class StmBackEdgeLabelTest :
    FunSpec({
        fun pt(
            x: Float,
            y: Float,
        ) = Point(x = x, y = y)

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = pt(x = x, y = y), size = Size(width = w, height = h))

        test("only the state-machine model has 4 px foreign clearance and the own-line rule") {
            Sysml2DiagramLabelPlan.STM_COSTS.foreignLineClearance shouldBe 4f
            Sysml2DiagramLabelPlan.STM_COSTS.lineClearance shouldBe 2f
            Sysml2DiagramLabelPlan.STM_COSTS.ownCrossUnclean shouldBe true
            for (other in listOf(LabelCostModel(), Sysml2DiagramLabelPlan.PAR_COSTS, LabelCostModel(lineClearance = 2f))) {
                other.foreignLineClearance shouldBe other.lineClearance
                other.ownCrossUnclean shouldBe false
            }
        }

        // One horizontal own line; a foreign line 3 px below the spot (103..111) where the label would sit first.
        fun place(costs: LabelCostModel): PlacedLabel {
            val own = listOf(pt(x = 0f, y = 100f), pt(x = 200f, y = 100f))
            val foreign = listOf(pt(x = 0f, y = 114f), pt(x = 200f, y = 114f))
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = own, ownerId = "own")
            index.addPolyline(kind = ObstacleKind.EDGE, pts = foreign, ownerId = "foreign")
            val req =
                LabelRequest(
                    id = "own",
                    role = LabelRole.EDGE_CENTER,
                    ownerId = "own",
                    size = Size(width = 40f, height = 8f),
                    polyline = own,
                )
            return LabelPlacer.place(requests = listOf(req), index = index, costs = costs).getValue("own")
        }

        test("a larger foreign clearance moves a label away from a foreign line the 2 px model tolerates") {
            val tight = place(costs = LabelCostModel(lineClearance = 2f, edgeOffsets = listOf(3f)))
            val roomy = place(costs = LabelCostModel(lineClearance = 2f, foreignLineClearance = 4f, edgeOffsets = listOf(3f)))
            // 2 px clearance: the label below the own line (bottom 111) is 3 px from the foreign line at y=114, it stays there
            (tight.rect.origin.y >= 100f) shouldBe true
            // 4 px clearance: that spot now touches the foreign line, the label takes the free side above the own line
            (roomy.rect.origin.y + roomy.rect.size.height <= 100f) shouldBe true
        }

        test("a label that has to cross its own line is not clean with the state-machine rule, and clean without it") {
            // a vertical own line in a corridor 30 px wide: a 60 px wide label fits on neither side
            val own = listOf(pt(x = 100f, y = 0f), pt(x = 100f, y = 200f))

            fun run(costs: LabelCostModel): PlacedLabel {
                val index = ObstacleIndex()
                index.addPolyline(kind = ObstacleKind.EDGE, pts = own, ownerId = "e")
                index.addRect(kind = ObstacleKind.NODE, r = rect(x = -400f, y = -400f, w = 485f, h = 1000f), ownerId = "left")
                index.addRect(kind = ObstacleKind.NODE, r = rect(x = 115f, y = -400f, w = 400f, h = 1000f), ownerId = "right")
                val req =
                    LabelRequest(
                        id = "e",
                        role = LabelRole.EDGE_CENTER,
                        ownerId = "e",
                        size = Size(width = 60f, height = 12f),
                        polyline = own,
                    )
                return LabelPlacer.place(requests = listOf(req), index = index, costs = costs).getValue("e")
            }
            val withRule = run(costs = Sysml2DiagramLabelPlan.STM_COSTS)
            withRule.clean shouldBe false
            val without = run(costs = LabelCostModel(edgeCandidateMode = EdgeCandidateMode.ARC_LENGTH))
            // without the rule the placement is whatever it was before: still decided by the same costs, never an exception
            (without.rect.size.width) shouldBe 60f
        }

        test("a spacious label stays clean under the state-machine rule") {
            val own = listOf(pt(x = 0f, y = 100f), pt(x = 200f, y = 100f))
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = own, ownerId = "e")
            val req =
                LabelRequest(id = "e", role = LabelRole.EDGE_CENTER, ownerId = "e", size = Size(width = 40f, height = 12f), polyline = own)
            val placed = LabelPlacer.place(requests = listOf(req), index = index, costs = Sysml2DiagramLabelPlan.STM_COSTS).getValue("e")
            placed.clean shouldBe true
            placed.onLine shouldBe false
        }

        test("planStmLabels reports a crowded back edge label as not clean, and a narrower label as clean") {
            // the crowded layout of the SVG test: the 200 px label of the back edge fits nowhere clear of its own line
            val edges =
                mapOf(
                    "init" to EdgeRoute.Direct(source = pt(x = 142f, y = 346f), target = pt(x = 142f, y = 382f)),
                    "ab" to
                        EdgeRoute.OrthogonalRounded(
                            source = pt(x = 71.2f, y = 382f),
                            target = pt(x = 290.6f, y = 68f),
                            waypoints =
                                listOf(
                                    pt(x = 71.2f, y = 292f),
                                    pt(x = 64.6f, y = 292f),
                                    pt(x = 64.6f, y = 113f),
                                    pt(x = 290.6f, y = 113f),
                                ),
                            cornerRadiusPx = 0f,
                        ),
                    "ba" to
                        EdgeRoute.OrthogonalRounded(
                            source = pt(x = 346.1f, y = 68f),
                            target = pt(x = 118.4f, y = 382f),
                            waypoints =
                                listOf(
                                    pt(x = 346.1f, y = 153f),
                                    pt(x = 107.6f, y = 153f),
                                    pt(x = 107.6f, y = 292f),
                                    pt(x = 118.4f, y = 292f),
                                ),
                            cornerRadiusPx = 0f,
                        ),
                    "bc" to EdgeRoute.Direct(source = pt(x = 401.6f, y = 68f), target = pt(x = 401.6f, y = 203f)),
                    "ca" to
                        EdgeRoute.OrthogonalRounded(
                            source = pt(x = 401.6f, y = 247f),
                            target = pt(x = 212.8f, y = 382f),
                            waypoints = listOf(pt(x = 401.6f, y = 292f), pt(x = 212.8f, y = 292f)),
                            cornerRadiusPx = 0f,
                        ),
                )
            val nodes =
                mapOf(
                    "init" to rect(x = 130f, y = 322f, w = 24f, h = 24f),
                    "alpha" to rect(x = 24f, y = 382f, w = 236f, h = 44f),
                    "beta" to rect(x = 235.1f, y = 24f, w = 222f, h = 44f),
                    "gamma" to rect(x = 297.6f, y = 203f, w = 208f, h = 44f),
                )

            fun specs(backWidth: Float) =
                listOf(
                    StmLabelSpec(id = "ab", lines = listOf("advance"), size = Size(width = 56.4f, height = 18f)),
                    StmLabelSpec(id = "ba", lines = listOf("retreat"), size = Size(width = backWidth, height = 54f)),
                    StmLabelSpec(id = "bc", lines = listOf("next"), size = Size(width = 34.8f, height = 18f)),
                    StmLabelSpec(id = "ca", lines = listOf("restart"), size = Size(width = 150f, height = 30f)),
                )

            fun plan(backWidth: Float) =
                Sysml2DiagramLabelPlan
                    .planStmLabels(edges = edges, nodes = nodes, composites = emptyList(), labels = specs(backWidth), frameTop = 0f)
                    .labels
                    .getValue("ba")
            plan(backWidth = 200.4f).clean shouldBe false
            plan(backWidth = 100f).clean shouldBe true
            plan(backWidth = 100f) shouldBe plan(backWidth = 100f)
        }
    })
