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
            GridCellResolver.resolve(grid = grid, xPx = 150f, yPx = 25f) shouldBe GridCell(col = 1, row = 0)
        }

        test("resolves the origin to cell (0, 0)") {
            GridCellResolver.resolve(grid = grid, xPx = 0f, yPx = 0f) shouldBe GridCell(col = 0, row = 0)
        }

        test("resolves the last valid point to the last cell (2, 1)") {
            GridCellResolver.resolve(grid = grid, xPx = 299f, yPx = 99f) shouldBe GridCell(col = 2, row = 1)
        }

        test("clamps a point below the origin to (0, 0)") {
            GridCellResolver.resolve(grid = grid, xPx = -40f, yPx = -10f) shouldBe GridCell(col = 0, row = 0)
        }

        test("clamps a point beyond the grid's extent to the last cell") {
            GridCellResolver.resolve(grid = grid, xPx = 9999f, yPx = 9999f) shouldBe GridCell(col = 2, row = 1)
        }

        test("degenerate cellW resolves col to 0 regardless of x") {
            val degenerate = grid.copy(cellW = 0f)
            GridCellResolver.resolve(grid = degenerate, xPx = 12345f, yPx = 25f).col shouldBe 0
        }

        test("degenerate cellH resolves row to 0 regardless of y") {
            val degenerate = grid.copy(cellH = 0f)
            GridCellResolver.resolve(grid = degenerate, xPx = 150f, yPx = 12345f).row shouldBe 0
        }

        test("round-trip: resolving each node's center of a 2x2 layout returns that node's band") {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = 1L,
                    canvas = Size(width = 1000f, height = 1000f),
                    nodes =
                        mapOf(
                            NodeId("A") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 100f, height = 50f))),
                            NodeId("B") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 0f), size = Size(width = 100f, height = 50f))),
                            NodeId("C") to
                                NodeLayout(bounds = Rect(origin = Point(x = 0f, y = 200f), size = Size(width = 100f, height = 50f))),
                            NodeId("D") to
                                NodeLayout(bounds = Rect(origin = Point(x = 300f, y = 200f), size = Size(width = 100f, height = 50f))),
                        ),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val geometry = NodeGeometryExtractor.extract(diagramType = DiagramType.CLASS, layoutResult = layout, paddingPx = 0f)
            val derivedGrid = requireNotNull(geometry.grid)

            val a = geometry.nodes.first { it.id == "A" }
            val b = geometry.nodes.first { it.id == "B" }
            val c = geometry.nodes.first { it.id == "C" }
            val d = geometry.nodes.first { it.id == "D" }

            val cellOf = { box: NodeBox ->
                GridCellResolver.resolve(grid = derivedGrid, xPx = box.x + box.w / 2f, yPx = box.y + box.h / 2f)
            }

            cellOf(a) shouldBe GridCell(col = 0, row = 0)
            cellOf(b) shouldBe GridCell(col = 1, row = 0)
            cellOf(c) shouldBe GridCell(col = 0, row = 1)
            cellOf(d) shouldBe GridCell(col = 1, row = 1)
        }
    })
