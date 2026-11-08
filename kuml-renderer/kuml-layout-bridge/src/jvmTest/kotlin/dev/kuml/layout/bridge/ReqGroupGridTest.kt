package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Wave 2E-a - the pure parts of the requirement group grid. */
class ReqGroupGridTest :
    FunSpec({
        val g = LayoutLimits.GRID_GUTTER_PX
        val s100 = Size(width = 100f, height = 50f)

        test("pack: 2 x 2 grid, row major, gutter 40") {
            val p = ReqGroupGrid.pack(cells = List(4) { s100 }, cols = 2, horizontal = false, sameLayerRows = emptyList())
            p.positions shouldBe
                listOf(Point(x = 0f, y = 0f), Point(x = 100f + g, y = 0f), Point(x = 0f, y = 50f + g), Point(x = 100f + g, y = 50f + g))
            p.size shouldBe Size(width = 200f + g, height = 100f + g)
            p.widened shouldBe false
        }

        test("pack: uneven heights keep the columns aligned, rows take the maximum height") {
            val cells =
                listOf(
                    Size(width = 100f, height = 50f),
                    Size(width = 80f, height = 120f),
                    Size(width = 60f, height = 40f),
                    Size(width = 90f, height = 30f),
                )
            val p = ReqGroupGrid.pack(cells = cells, cols = 2, horizontal = false, sameLayerRows = emptyList())
            p.positions[2].x shouldBe 0f
            p.positions[0].x shouldBe 0f
            p.positions[1].x shouldBe 100f + g
            p.positions[3].x shouldBe 100f + g
            p.positions[2].y shouldBe 120f + g
            p.positions[3].y shouldBe 120f + g
        }

        test("pack: horizontal flow transposes the grid (cells are filled column by column)") {
            val p = ReqGroupGrid.pack(cells = List(4) { s100 }, cols = 2, horizontal = true, sameLayerRows = emptyList())
            // A C / B D
            p.positions[0] shouldBe Point(x = 0f, y = 0f)
            p.positions[1] shouldBe Point(x = 0f, y = 50f + g)
            p.positions[2] shouldBe Point(x = 100f + g, y = 0f)
            p.positions[3] shouldBe Point(x = 100f + g, y = 50f + g)
        }

        test("pack: Up and Left mirror the rows and columns") {
            val up = ReqGroupGrid.pack(cells = List(4) { s100 }, cols = 2, horizontal = false, sameLayerRows = emptyList(), flipRows = true)
            up.positions[0].y shouldBe 50f + g
            up.positions[2].y shouldBe 0f
            val left =
                ReqGroupGrid.pack(
                    cells = List(4) { s100 },
                    cols = 2,
                    horizontal = false,
                    sameLayerRows = emptyList(),
                    flipCols = true,
                )
            left.positions[0].x shouldBe 100f + g
            left.positions[1].x shouldBe 0f
        }

        test("pack: sameLayer cells start a row together; a row longer than the column count widens (I001)") {
            val three = ReqGroupGrid.pack(cells = List(5) { s100 }, cols = 2, horizontal = false, sameLayerRows = listOf(listOf(1, 2, 3)))
            // cell 0 alone in the first row, then 1 2 3 together
            three.positions[0].y shouldBe 0f
            listOf(1, 2, 3).map { three.positions[it].y }.toSet() shouldBe setOf(50f + g)
            three.widened shouldBe true
            val fits = ReqGroupGrid.pack(cells = List(4) { s100 }, cols = 2, horizontal = false, sameLayerRows = listOf(listOf(0, 3)))
            fits.widened shouldBe false
            fits.positions[0].y shouldBe fits.positions[3].y
        }

        test("chooseColumns: wrapColumns wins and is capped at the cell count") {
            ReqGroupGrid.chooseColumns(cells = List(4) { s100 }, spec = LayoutSpec(wrapColumns = 2), horizontal = false) shouldBe 2
            ReqGroupGrid.chooseColumns(cells = List(3) { s100 }, spec = LayoutSpec(wrapColumns = 9), horizontal = false) shouldBe 3
            ReqGroupGrid.chooseColumns(cells = listOf(s100), spec = LayoutSpec(wrapColumns = 3), horizontal = false) shouldBe 1
        }

        test("chooseColumns: without wrapColumns the canvas gets close to the aspect target, ties go to fewer columns") {
            val cells = List(6) { Size(width = 200f, height = 100f) }
            // 3 columns: 3*200+80 = 680 x 2*100+40 = 240 -> 2.83; 2 columns: 440 x 340 -> 1.29; target 1.6 -> 2 columns is closer (0.31 vs 1.23)
            ReqGroupGrid.chooseColumns(cells = cells, spec = LayoutSpec(aspectRatio = 1.6), horizontal = false) shouldBe 2
            // identical cells, target between: a tie would pick the smaller count
            ReqGroupGrid.chooseColumns(cells = List(1) { s100 }, spec = LayoutSpec(aspectRatio = 1.6), horizontal = false) shouldBe 1
        }

        test("chooseColumns: maxWidth is respected") {
            val cells = List(6) { Size(width = 200f, height = 100f) }
            val cols = ReqGroupGrid.chooseColumns(cells = cells, spec = LayoutSpec(aspectRatio = 3.0, maxWidth = 500), horizontal = false)
            val width = ReqGroupGrid.pack(cells = cells, cols = cols, horizontal = false, sameLayerRows = emptyList()).size.width
            (width <= 500f) shouldBe true
        }

        test("applies: only with groups and an explicit grid option") {
            val node = LayoutNode(id = NodeId("n"), intrinsicSize = s100, groupId = GroupId("group:A"))
            val grouped = LayoutGraph(nodes = listOf(node), edges = emptyList(), groups = listOf(LayoutGroup(id = GroupId("group:A"))))
            val flat = LayoutGraph(nodes = listOf(node.copy(groupId = null)), edges = emptyList())
            ReqGroupGrid.applies(graph = grouped, spec = null) shouldBe false
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec()) shouldBe false
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(first = listOf("n"))) shouldBe false
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(wrapColumns = 2)) shouldBe true
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(aspectRatio = 1.6)) shouldBe true
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(maxWidth = 900)) shouldBe true
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(sameLayer = listOf(listOf("group:A", "group:B")))) shouldBe true
            ReqGroupGrid.applies(graph = grouped, spec = LayoutSpec(sameLayer = listOf(listOf("n", "m")))) shouldBe false
            ReqGroupGrid.applies(graph = flat, spec = LayoutSpec(wrapColumns = 2)) shouldBe false
        }

        // planAttachments: ellipse band and C lines on one side
        fun rectAt(
            x: Float,
            y: Float,
        ) = dev.kuml.layout.Rect(origin = Point(x = x, y = y), size = Size(width = 100f, height = 50f))

        fun graphOf(vararg ids: String): LayoutGraph =
            LayoutGraph(
                nodes = listOf("a", "b").map { LayoutNode(id = NodeId(it), intrinsicSize = s100) },
                edges =
                    ids.map {
                        dev.kuml.layout.LayoutEdge(
                            id = dev.kuml.layout.EdgeId(it),
                            source = dev.kuml.layout.EndpointRef(nodeId = NodeId("a")),
                            target = dev.kuml.layout.EndpointRef(nodeId = NodeId("b")),
                        )
                    },
            )

        test("planAttachments: the ends at an ellipse stay in the middle 60 percent of the side") {
            val nodes = mapOf(NodeId("a") to rectAt(0f, 0f), NodeId("b") to rectAt(0f, 200f))
            val g = graphOf("e1", "e2", "e3", "e4", "e5")
            val ellipse = ReqGroupGrid.planAttachments(graph = g, nodes = nodes, ellipse = { it == NodeId("a") })
            val xs = ellipse.values.map { it.sourceAt.x }
            xs.all { it >= 20f && it <= 80f } shouldBe true
            // a plain box uses the wider band (12 px margin)
            val plain = ReqGroupGrid.planAttachments(graph = g, nodes = nodes).values.map { it.sourceAt.x }
            (plain.max() - plain.min() > xs.max() - xs.min()) shouldBe true
            plain.all { it >= 12f && it <= 88f } shouldBe true
        }

        test("planAttachments: a C line leaves and arrives on the same (east or west) side") {
            val nodes = mapOf(NodeId("a") to rectAt(0f, 0f), NodeId("b") to rectAt(0f, 200f))
            val g = graphOf("e1")
            val east = ReqGroupGrid.planAttachments(graph = g, nodes = nodes, sameSide = mapOf(dev.kuml.layout.EdgeId("e1") to true))
            val pe = east.getValue(dev.kuml.layout.EdgeId("e1"))
            pe.sameSide shouldBe true
            pe.vertical shouldBe false
            pe.sourceAt.x shouldBe 100f
            pe.targetAt.x shouldBe 100f
            val west = ReqGroupGrid.planAttachments(graph = g, nodes = nodes, sameSide = mapOf(dev.kuml.layout.EdgeId("e1") to false))
            val pw = west.getValue(dev.kuml.layout.EdgeId("e1"))
            pw.sourceAt.x shouldBe 0f
            pw.targetAt.x shouldBe 0f
        }
    })
