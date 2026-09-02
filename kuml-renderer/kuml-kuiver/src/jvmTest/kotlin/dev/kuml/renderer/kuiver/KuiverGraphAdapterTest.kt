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
        canvas = Size(width = 800f, height = 600f),
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
        bounds = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)),
    )

    @Test
    fun `KuiverGraphAdapter copies all nodes from LayoutResult`() {
        val result =
            layoutResult(
                nodes =
                    mapOf(
                        NodeId("A") to nodeLayout(x = 0f, y = 0f, w = 100f, h = 40f),
                        NodeId("B") to nodeLayout(x = 200f, y = 0f, w = 100f, h = 40f),
                        NodeId("C") to nodeLayout(x = 400f, y = 0f, w = 100f, h = 40f),
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
                NodeId("X") to nodeLayout(x = 0f, y = 0f, w = 80f, h = 30f),
                NodeId("Y") to nodeLayout(x = 100f, y = 0f, w = 80f, h = 30f),
            )
        val edgeMap =
            mapOf(
                EdgeId("X--Y") to EdgeRoute.Direct(source = Point(x = 80f, y = 15f), target = Point(x = 100f, y = 15f)),
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
                        NodeId("N1") to nodeLayout(x = 10f, y = 20f, w = 120f, h = 60f),
                    ),
            )
        val kuiver = KuiverGraphAdapter.toKuiver(result)
        val node = kuiver.nodes["N1"]
        node shouldNotBe null
        node!!.position.x.value shouldBe 10f
        node.position.y.value shouldBe 20f
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
                        NodeId("P1") to nodeLayout(x = 50f, y = 75f, w = 80f, h = 40f),
                        NodeId("P2") to nodeLayout(x = 200f, y = 75f, w = 80f, h = 40f),
                    ),
            )
        val config = KuiverGraphAdapter.layoutConfig(result)
        // Build the same Kuiver as toKuiver() would, then apply the provider
        val original = KuiverGraphAdapter.toKuiver(result)
        val positioned = config.provider.invoke(original, config)
        positioned.nodes["P1"] shouldNotBe null
        positioned.nodes["P1"]!!
            .position.x.value shouldBe 50f
        positioned.nodes["P1"]!!
            .position.y.value shouldBe 75f
        positioned.nodes["P2"]!!
            .position.x.value shouldBe 200f
        positioned.nodes["P2"]!!
            .position.y.value shouldBe 75f
    }
}
