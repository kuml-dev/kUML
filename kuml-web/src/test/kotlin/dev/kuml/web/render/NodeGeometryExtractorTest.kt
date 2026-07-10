package dev.kuml.web.render

import dev.kuml.core.model.DiagramType
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun layoutResultOf(nodes: Map<String, Rect>): LayoutResult =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = 1L,
        canvas = Size(1000f, 1000f),
        nodes = nodes.mapKeys { (id, _) -> NodeId(id) }.mapValues { (_, rect) -> NodeLayout(bounds = rect) },
        edges = emptyMap(),
        groups = emptyMap(),
    )

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
): Rect = Rect(origin = Point(x, y), size = Size(w, h))

class NodeGeometryExtractorTest :
    FunSpec({

        test("two nodes, CLASS diagram - returns padded NodeBoxes with preserved ids") {
            val layout =
                layoutResultOf(
                    mapOf(
                        "Alpha" to rect(0f, 0f, 100f, 50f),
                        "Beta" to rect(200f, 0f, 100f, 50f),
                    ),
                )
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout, paddingPx = 16f)

            geometry.nodes.map { it.id } shouldBe listOf("Alpha", "Beta")
            val alpha = geometry.nodes.first { it.id == "Alpha" }
            alpha.x shouldBe 16f
            alpha.y shouldBe 16f
            alpha.w shouldBe 100f
            alpha.h shouldBe 50f
            val beta = geometry.nodes.first { it.id == "Beta" }
            beta.x shouldBe 216f
            beta.y shouldBe 16f
        }

        test("two nodes side-by-side (distinct centerX, same centerY) - grid is 2 cols x 1 row") {
            val layout =
                layoutResultOf(
                    mapOf(
                        "Alpha" to rect(0f, 0f, 100f, 50f),
                        "Beta" to rect(300f, 0f, 100f, 50f),
                    ),
                )
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout)

            val grid = requireNotNull(geometry.grid)
            grid.cols shouldBe 2
            grid.rows shouldBe 1
        }

        test("two-by-two arrangement - grid is 2 cols x 2 rows with correct origin") {
            val layout =
                layoutResultOf(
                    mapOf(
                        "A" to rect(0f, 0f, 100f, 50f),
                        "B" to rect(300f, 0f, 100f, 50f),
                        "C" to rect(0f, 200f, 100f, 50f),
                        "D" to rect(300f, 200f, 100f, 50f),
                    ),
                )
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout, paddingPx = 0f)

            val grid = requireNotNull(geometry.grid)
            grid.cols shouldBe 2
            grid.rows shouldBe 2
            grid.originX shouldBe 0f
            grid.originY shouldBe 0f
            (grid.cellW > 0f) shouldBe true
            (grid.cellH > 0f) shouldBe true
        }

        test("single node - grid is 1x1 and cellW/cellH fall back to the node's own size") {
            val layout = layoutResultOf(mapOf("Solo" to rect(10f, 10f, 120f, 80f)))
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout, paddingPx = 0f)

            val grid = requireNotNull(geometry.grid)
            grid.cols shouldBe 1
            grid.rows shouldBe 1
            grid.cellW shouldBe 120f
            grid.cellH shouldBe 80f
        }

        test("non-CLASS diagram type - nodes populated but grid is null") {
            val layout = layoutResultOf(mapOf("S1" to rect(0f, 0f, 24f, 24f)))
            val geometry = NodeGeometryExtractor.extract(DiagramType.STATE, layout)

            geometry.nodes.size shouldBe 1
            geometry.grid shouldBe null
        }

        test("empty layout - nodes and grid are both empty/null") {
            val layout = layoutResultOf(emptyMap())
            val geometry = NodeGeometryExtractor.extract(DiagramType.CLASS, layout)

            geometry.nodes shouldBe emptyList()
            geometry.grid shouldBe null
        }
    })
