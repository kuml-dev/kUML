package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
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

        test("compound group minSize floors width beyond the children-derived size") {
            // Single small child inside a compound group whose declared minSize
            // (simulating a BPMN expanded-SubProcess title estimate) is far wider
            // than the child's own bounding box + padding would produce.
            val groupId = GroupId("compound-min-size")
            val childId = NodeId("child")
            val child =
                LayoutNode(
                    id = childId,
                    intrinsicSize = Size(80f, 40f),
                    groupId = groupId,
                )
            val group =
                LayoutGroup(
                    id = groupId,
                    layoutAsCompound = true,
                    minSize = Size(480f, 60f),
                )
            val graph =
                LayoutGraph(
                    nodes = listOf(child),
                    edges = emptyList(),
                    groups = listOf(group),
                )

            val result = engine.layout(graph)

            val groupLayout = result.groups[groupId]
            groupLayout shouldNotBe null
            val gb = groupLayout!!.bounds

            // The children-derived box (80 + default padding on both sides) is far
            // narrower than 480px — the floor must win.
            gb.size.width shouldBe 480f
            // Height default padding already exceeds 60px (child height 40 + padding),
            // so the height floor should be a no-op here — just assert it's not
            // shrunk below the declared minimum.
            (gb.size.height >= 60f) shouldBe true

            // The child itself must stay at its own laid-out position/size — the
            // floor widens only the reported frame box, not the child node.
            val childBounds = result.nodes[childId]!!.bounds
            childBounds.size.width shouldBe 80f
            childBounds.size.height shouldBe 40f

            // The widened frame must still (symmetrically) enclose the child.
            (gb.origin.x <= childBounds.origin.x) shouldBe true
            (gb.origin.x + gb.size.width >= childBounds.origin.x + childBounds.size.width) shouldBe true
        }

        test("compound group minSize floor does not overlap a sibling branch (gateway split)") {
            // Regression test for a sibling/frame overlap: a gateway splits into two
            // parallel branches — one is an expanded SubProcess (compound group) with a
            // long name and a single small child (so its children-derived width is far
            // below its declared minSize), the other is a plain sibling task. Before the
            // fix, the minSize floor was enforced purely *after* layout by symmetrically
            // widening the group's reported bounds around ELK's computed center — ELK
            // itself never reserved room for the wider box, so it could overlap the
            // sibling branch that ELK placed right next to it in the same layer.
            val gatewayId = NodeId("gateway")
            val gateway = LayoutNode(id = gatewayId, intrinsicSize = Size(50f, 70f))

            val groupId = GroupId("subprocess")
            val subChildId = NodeId("subChild")
            val subChild =
                LayoutNode(
                    id = subChildId,
                    intrinsicSize = Size(80f, 40f),
                    groupId = groupId,
                )
            val group =
                LayoutGroup(
                    id = groupId,
                    layoutAsCompound = true,
                    padding = Insets(top = 20f, right = 20f, bottom = 20f, left = 20f),
                    // Simulates a long expanded-SubProcess title estimate — far wider than
                    // the single small child (+ padding) would otherwise produce.
                    minSize = Size(480f, 60f),
                )

            val siblingId = NodeId("sibling")
            val sibling = LayoutNode(id = siblingId, intrinsicSize = Size(120f, 60f))

            // Edge target uses the group's own id value (same convention as
            // BpmnLayoutBridge's outer-flow-to-expanded-SubProcess wiring): the ELK graph
            // builder resolves an edge endpoint via nodeMap first, then falls back to
            // groupMap[GroupId(id)].
            val gatewayToSubProcess =
                LayoutEdge(
                    id = EdgeId("gateway-to-subprocess"),
                    source = EndpointRef(nodeId = gatewayId),
                    target = EndpointRef(nodeId = NodeId(groupId.value)),
                )
            val gatewayToSibling =
                LayoutEdge(
                    id = EdgeId("gateway-to-sibling"),
                    source = EndpointRef(nodeId = gatewayId),
                    target = EndpointRef(nodeId = siblingId),
                )

            val graph =
                LayoutGraph(
                    nodes = listOf(gateway, subChild, sibling),
                    edges = listOf(gatewayToSubProcess, gatewayToSibling),
                    groups = listOf(group),
                )

            val result = engine.layout(graph)

            val groupLayout = result.groups[groupId]
            groupLayout shouldNotBe null
            val gb = groupLayout!!.bounds
            // The floor must still win — this is not a relaxation of the existing test.
            gb.size.width shouldBe 480f

            val siblingBounds = result.nodes[siblingId]!!.bounds

            // Axis-aligned bounding boxes must not overlap: either the x-ranges or the
            // y-ranges must be disjoint.
            val xOverlap =
                gb.origin.x < siblingBounds.origin.x + siblingBounds.size.width &&
                    gb.origin.x + gb.size.width > siblingBounds.origin.x
            val yOverlap =
                gb.origin.y < siblingBounds.origin.y + siblingBounds.size.height &&
                    gb.origin.y + gb.size.height > siblingBounds.origin.y
            (xOverlap && yOverlap) shouldBe false
        }

        test("compound group without minSize is unaffected (existing behaviour)") {
            val groupId = GroupId("compound-no-min")
            val child =
                LayoutNode(
                    id = NodeId("child2"),
                    intrinsicSize = Size(80f, 40f),
                    groupId = groupId,
                )
            val group = LayoutGroup(id = groupId, layoutAsCompound = true)
            val graph =
                LayoutGraph(
                    nodes = listOf(child),
                    edges = emptyList(),
                    groups = listOf(group),
                )

            val result = engine.layout(graph)

            val gb = result.groups[groupId]!!.bounds
            // No minSize declared → width stays governed purely by the child + padding,
            // well below the 480px floor used in the sibling test above.
            (gb.size.width < 480f) shouldBe true
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
