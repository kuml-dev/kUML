package dev.kuml.web.layout

import dev.kuml.core.model.DiagramType
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.LayoutHintWriter.GridCell
import dev.kuml.web.api.GridGeometry
import dev.kuml.web.api.NodeBox
import dev.kuml.web.render.NodeGeometryExtractor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GridCellResolverTest :
    FunSpec({

        val grid = GridGeometry(cols = 3, rows = 2, cellW = 100f, cellH = 50f, originX = 0f, originY = 0f)

        test("resolves a point inside cell (1, 0)") {
            GridCellResolver.resolve(grid, 150f, 25f) shouldBe GridCell(1, 0)
        }

        test("resolves the origin to cell (0, 0)") {
            GridCellResolver.resolve(grid, 0f, 0f) shouldBe GridCell(0, 0)
        }

        test("resolves the last valid point to the last cell (2, 1)") {
            GridCellResolver.resolve(grid, 299f, 99f) shouldBe GridCell(2, 1)
        }

        test("clamps a point below the origin to (0, 0)") {
            GridCellResolver.resolve(grid, -40f, -10f) shouldBe GridCell(0, 0)
        }

        test("clamps a point beyond the grid's extent to the last cell") {
            GridCellResolver.resolve(grid, 9999f, 9999f) shouldBe GridCell(2, 1)
        }

        test("degenerate cellW resolves col to 0 regardless of x") {
            val degenerate = grid.copy(cellW = 0f)
            GridCellResolver.resolve(degenerate, 12345f, 25f).col shouldBe 0
        }

        test("degenerate cellH resolves row to 0 regardless of y") {
            val degenerate = grid.copy(cellH = 0f)
            GridCellResolver.resolve(degenerate, 150f, 12345f).row shouldBe 0
        }

        test("round-trip: resolving each node's center of a 2x2 layout returns that node's band") {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(1000f, 1000f),
                    nodes =
                        mapOf(
                            NodeId("A") to NodeLayout(bounds = Rect(Point(0f, 0f), Size(100f, 50f))),
                            NodeId("B") to NodeLayout(bounds = Rect(Point(300f, 0f), Size(100f, 50f))),
                            NodeId("C") to NodeLayout(bounds = Rect(Point(0f, 200f), Size(100f, 50f))),
                            NodeId("D") to NodeLayout(bounds = Rect(Point(300f, 200f), Size(100f, 50f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout, paddingPx = 0f)
            val derivedGrid = requireNotNull(geometry.grid)

            val a = geometry.nodes.first { it.id == "A" }
            val b = geometry.nodes.first { it.id == "B" }
            val c = geometry.nodes.first { it.id == "C" }
            val d = geometry.nodes.first { it.id == "D" }

            val cellOf = { box: NodeBox ->
                GridCellResolver.resolve(derivedGrid, box.x + box.w / 2f, box.y + box.h / 2f)
            }

            cellOf(a) shouldBe GridCell(0, 0)
            cellOf(b) shouldBe GridCell(1, 0)
            cellOf(c) shouldBe GridCell(0, 1)
            cellOf(d) shouldBe GridCell(1, 1)
        }
    })
