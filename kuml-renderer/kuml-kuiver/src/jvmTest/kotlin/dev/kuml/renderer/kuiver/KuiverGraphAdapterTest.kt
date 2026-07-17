package dev.kuml.renderer.kuiver

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class KuiverGraphAdapterTest {
    private fun layoutResult(
        nodes: Map<NodeId, NodeLayout> = emptyMap(),
        edges: Map<EdgeId, EdgeRoute> = emptyMap(),
    ) = LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(800f, 600f),
        nodes = nodes,
        edges = edges,
        groups = emptyMap(),
    )

    private fun nodeLayout(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) = NodeLayout(
        bounds = Rect(origin = Point(x, y), size = Size(w, h)),
    )

    @Test
    fun `KuiverGraphAdapter copies all nodes from LayoutResult`() {
        val result =
            layoutResult(
                nodes =
                    mapOf(
                        NodeId("A") to nodeLayout(0f, 0f, 100f, 40f),
                        NodeId("B") to nodeLayout(200f, 0f, 100f, 40f),
                        NodeId("C") to nodeLayout(400f, 0f, 100f, 40f),
                    ),
            )
        val kuiver = KuiverGraphAdapter.toKuiver(result)
        kuiver.nodes.size shouldBe 3
        kuiver.nodes shouldContainKey "A"
        kuiver.nodes shouldContainKey "B"
        kuiver.nodes shouldContainKey "C"
    }

    @Test
    fun `KuiverGraphAdapter copies all edges from LayoutResult`() {
        val nodeMap =
            mapOf(
                NodeId("X") to nodeLayout(0f, 0f, 80f, 30f),
                NodeId("Y") to nodeLayout(100f, 0f, 80f, 30f),
            )
        val edgeMap =
            mapOf(
                EdgeId("X--Y") to EdgeRoute.Direct(Point(80f, 15f), Point(100f, 15f)),
            )
        val result = layoutResult(nodes = nodeMap, edges = edgeMap)
        val kuiver = KuiverGraphAdapter.toKuiver(result)
        kuiver.edges.size shouldBe 1
        val edge = kuiver.edges.first()
        edge.fromId shouldBe "X"
        edge.toId shouldBe "Y"
    }

    @Test
    fun `KuiverGraphAdapter preserves node dimensions and positions`() {
        val result =
            layoutResult(
                nodes =
                    mapOf(
                        NodeId("N1") to nodeLayout(10f, 20f, 120f, 60f),
                    ),
            )
        val kuiver = KuiverGraphAdapter.toKuiver(result)
        val node = kuiver.nodes["N1"]
        node shouldNotBe null
        node!!.position.x shouldBe 10f
        node.position.y shouldBe 20f
        node.dimensions shouldNotBe null
        node.dimensions!!.width.value shouldBe 120f
        node.dimensions!!.height.value shouldBe 60f
    }

    @Test
    fun `Custom LayoutConfig returns LayoutResult positions for each node`() {
        val result =
            layoutResult(
                nodes =
                    mapOf(
                        NodeId("P1") to nodeLayout(50f, 75f, 80f, 40f),
                        NodeId("P2") to nodeLayout(200f, 75f, 80f, 40f),
                    ),
            )
        val config = KuiverGraphAdapter.layoutConfig(result)
        // Build the same Kuiver as toKuiver() would, then apply the provider
        val original = KuiverGraphAdapter.toKuiver(result)
        val positioned = config.provider.invoke(original, config)
        positioned.nodes["P1"] shouldNotBe null
        positioned.nodes["P1"]!!.position.x shouldBe 50f
        positioned.nodes["P1"]!!.position.y shouldBe 75f
        positioned.nodes["P2"]!!.position.x shouldBe 200f
        positioned.nodes["P2"]!!.position.y shouldBe 75f
    }
}
