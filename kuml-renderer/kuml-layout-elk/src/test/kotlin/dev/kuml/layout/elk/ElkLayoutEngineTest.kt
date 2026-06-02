package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ElkLayoutEngineTest :
    FunSpec({
        val engine = ElkLayoutEngine()

        test("two nodes one edge produces valid layout") {
            val nodeA = LayoutNode(id = NodeId("A"), intrinsicSize = Size(100f, 50f))
            val nodeB = LayoutNode(id = NodeId("B"), intrinsicSize = Size(100f, 50f))
            val edge =
                LayoutEdge(
                    id = EdgeId("A-B"),
                    source = EndpointRef(nodeId = NodeId("A")),
                    target = EndpointRef(nodeId = NodeId("B")),
                )
            val graph = LayoutGraph(nodes = listOf(nodeA, nodeB), edges = listOf(edge))

            val result = engine.layout(graph)

            // Both nodes must have non-zero bounds
            val boundsA = result.nodes[NodeId("A")]!!.bounds
            val boundsB = result.nodes[NodeId("B")]!!.bounds
            boundsA.size.width shouldBeGreaterThan 0f
            boundsA.size.height shouldBeGreaterThan 0f
            boundsB.size.width shouldBeGreaterThan 0f
            boundsB.size.height shouldBeGreaterThan 0f

            // Exactly one edge route
            result.edges.size shouldBe 1
            val route = result.edges[EdgeId("A-B")]
            route shouldNotBe null
            route!!.shouldBeInstanceOf<EdgeRoute>()
        }

        test("group bounds enclose children") {
            val groupId = GroupId("G1")
            val nodeA = LayoutNode(id = NodeId("A"), intrinsicSize = Size(80f, 40f), groupId = groupId)
            val nodeB = LayoutNode(id = NodeId("B"), intrinsicSize = Size(80f, 40f), groupId = groupId)
            val nodeC = LayoutNode(id = NodeId("C"), intrinsicSize = Size(80f, 40f), groupId = groupId)
            val group = LayoutGroup(id = groupId)
            val graph =
                LayoutGraph(
                    nodes = listOf(nodeA, nodeB, nodeC),
                    edges = emptyList(),
                    groups = listOf(group),
                )

            val result = engine.layout(graph)

            val groupLayout = result.groups[groupId]
            groupLayout shouldNotBe null
            val gb = groupLayout!!.bounds

            // Group must have positive size (it contains 3 children)
            gb.size.width shouldBeGreaterThan 0f
            gb.size.height shouldBeGreaterThan 0f

            // All children must have been laid out with positive size
            for (nodeId in listOf(NodeId("A"), NodeId("B"), NodeId("C"))) {
                val nb = result.nodes[nodeId]!!.bounds
                nb.size.width shouldBeGreaterThan 0f
                nb.size.height shouldBeGreaterThan 0f
            }
        }

        test("grid hints produce warning") {
            val node =
                LayoutNode(
                    id = NodeId("N1"),
                    intrinsicSize = Size(100f, 50f),
                    hints = NodeHints(gridCol = 1),
                )
            val graph = LayoutGraph(nodes = listOf(node), edges = emptyList())

            val result = engine.layout(graph)

            val gridWarnings = result.warnings.filter { it.code == "hint.ignored.grid" }
            gridWarnings shouldHaveSize 1
            gridWarnings.first().affectedNodes shouldBe listOf(NodeId("N1"))
        }

        test("direction LeftToRight orders nodes by x") {
            // Three nodes in a chain A → B → C
            val nodeA = LayoutNode(id = NodeId("A"), intrinsicSize = Size(80f, 40f))
            val nodeB = LayoutNode(id = NodeId("B"), intrinsicSize = Size(80f, 40f))
            val nodeC = LayoutNode(id = NodeId("C"), intrinsicSize = Size(80f, 40f))
            val edgeAB =
                LayoutEdge(
                    id = EdgeId("AB"),
                    source = EndpointRef(nodeId = NodeId("A")),
                    target = EndpointRef(nodeId = NodeId("B")),
                )
            val edgeBC =
                LayoutEdge(
                    id = EdgeId("BC"),
                    source = EndpointRef(nodeId = NodeId("B")),
                    target = EndpointRef(nodeId = NodeId("C")),
                )
            val graph =
                LayoutGraph(
                    nodes = listOf(nodeA, nodeB, nodeC),
                    edges = listOf(edgeAB, edgeBC),
                )
            val hints = LayoutHints(direction = LayoutDirection.LeftToRight)

            val result = engine.layout(graph, hints)

            val xA =
                result.nodes[NodeId("A")]!!
                    .bounds.origin.x
            val xB =
                result.nodes[NodeId("B")]!!
                    .bounds.origin.x
            val xC =
                result.nodes[NodeId("C")]!!
                    .bounds.origin.x

            // In LeftToRight layout, A comes before B which comes before C on the x-axis
            (xA < xB) shouldBe true
            (xB < xC) shouldBe true
        }

        test("result round-trips through json") {
            val nodeA = LayoutNode(id = NodeId("A"), intrinsicSize = Size(100f, 50f))
            val nodeB = LayoutNode(id = NodeId("B"), intrinsicSize = Size(100f, 50f))
            val edge =
                LayoutEdge(
                    id = EdgeId("AB"),
                    source = EndpointRef(nodeId = NodeId("A")),
                    target = EndpointRef(nodeId = NodeId("B")),
                )
            val graph = LayoutGraph(nodes = listOf(nodeA, nodeB), edges = listOf(edge))

            val result = engine.layout(graph)

            val json = Json { prettyPrint = false }
            val encoded = json.encodeToString(result)

            // encoded must contain the engine id
            encoded shouldContain "elk.layered"

            val decoded = json.decodeFromString<LayoutResult>(encoded)

            decoded.engineId shouldBe result.engineId
            decoded.canvas shouldBe result.canvas
            decoded.nodes.keys.toSet() shouldBe result.nodes.keys.toSet()
            decoded.edges.keys.toSet() shouldBe result.edges.keys.toSet()
        }
    })
