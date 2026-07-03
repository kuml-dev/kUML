package dev.kuml.layout.grid

import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * Property-Tests für die Grid-Engine — prüfen Invarianten, die für JEDEN
 * akzeptierten Input gelten müssen:
 *
 *  - **Keine Knotenüberlappung**: Bounds zweier verschiedener Knoten dürfen
 *    sich nicht schneiden.
 *  - **Alle Knoten platziert**: für jeden Input-Knoten gibt es ein NodeLayout.
 *  - **Bounds innerhalb des Canvas**: jeder Knoten liegt innerhalb der
 *    berechneten Canvas-Fläche.
 *  - **Canvas-Dimensionen positiv**.
 *
 * Channel-Routing-Property "keine Kante durch fremde Knoten" ist V1.2 — die
 * V1.1.12-Engine routet ohne Kollisionserkennung; das wird im
 * `engine.performance.large_graph`-Tradeoff explizit dokumentiert.
 */
private fun overlaps(
    a: Rect,
    b: Rect,
): Boolean {
    val aRight = a.origin.x + a.size.width
    val aBottom = a.origin.y + a.size.height
    val bRight = b.origin.x + b.size.width
    val bBottom = b.origin.y + b.size.height
    // Strict overlap, allow edge-touching (gap could be exactly 0 in degenerate inputs).
    return a.origin.x < bRight &&
        b.origin.x < aRight &&
        a.origin.y < bBottom &&
        b.origin.y < aBottom
}

@OptIn(ExperimentalKotest::class)
class GridLayoutPropertyTest :
    FunSpec({

        val engine = GridLayoutEngine()
        val config = PropTestConfig(iterations = 50)

        test("no two nodes' bounds overlap — for any 1..30 unhinted nodes") {
            checkAll(config, Arb.list(Arb.float(min = 20f, max = 200f), 2..30)) { widths ->
                val nodes =
                    widths.mapIndexed { idx, w ->
                        LayoutNode(NodeId("n$idx"), Size(w, w * 0.6f))
                    }
                val result = engine.layout(LayoutGraph(nodes, emptyList()))
                val bounds = result.nodes.values.map { it.bounds }
                for (i in bounds.indices) {
                    for (j in i + 1 until bounds.size) {
                        overlaps(bounds[i], bounds[j]) shouldBe false
                    }
                }
            }
        }

        test("every input node is present in the layout result") {
            checkAll(config, Arb.int(0..50)) { count ->
                val nodes = (0 until count).map { LayoutNode(NodeId("n$it"), Size(80f, 40f)) }
                val result = engine.layout(LayoutGraph(nodes, emptyList()))
                result.nodes.size shouldBe count
                for (n in nodes) {
                    (n.id in result.nodes) shouldBe true
                }
            }
        }

        test("every node bound lies within the canvas") {
            checkAll(config, Arb.int(1..40)) { count ->
                val nodes = (0 until count).map { LayoutNode(NodeId("n$it"), Size(80f, 40f)) }
                val result = engine.layout(LayoutGraph(nodes, emptyList()))
                val canvasW = result.canvas.width
                val canvasH = result.canvas.height
                for ((_, layout) in result.nodes) {
                    val b = layout.bounds
                    (b.origin.x >= 0f) shouldBe true
                    (b.origin.y >= 0f) shouldBe true
                    (b.origin.x + b.size.width <= canvasW + 0.001f) shouldBe true
                    (b.origin.y + b.size.height <= canvasH + 0.001f) shouldBe true
                }
            }
        }

        test("seed-respecting determinism: same seed yields equal results across two runs") {
            checkAll(config, Arb.int(2..20)) { count ->
                val nodes = (0 until count).map { LayoutNode(NodeId("n$it"), Size(80f, 40f)) }
                val graph = LayoutGraph(nodes, emptyList())
                val hints = LayoutHints(deterministicSeed = 7L)
                val r1 = engine.layout(graph, hints)
                val r2 = engine.layout(graph, hints)
                r1 shouldBe r2
            }
        }
    })
