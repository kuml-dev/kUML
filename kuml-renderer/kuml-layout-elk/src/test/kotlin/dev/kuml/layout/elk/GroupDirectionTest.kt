package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Nacharbeit 106: [dev.kuml.layout.LayoutGroup.direction] through the public engine API. */
class GroupDirectionTest :
    FunSpec({
        val engine = ElkLayoutEngine()
        val g = GroupId("G")
        val padding = Insets(top = 36f, right = 20f, bottom = 20f, left = 20f)

        fun node(
            id: String,
            inGroup: Boolean,
        ) = LayoutNode(id = NodeId(id), intrinsicSize = Size(width = 80f, height = 40f), groupId = if (inGroup) g else null)

        fun edge(
            id: String,
            s: String,
            t: String,
        ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))

        fun graph(
            direction: LayoutDirection?,
            extraEdges: List<LayoutEdge> = emptyList(),
            minSize: Size? = null,
        ) = LayoutGraph(
            nodes = listOf(node("A", true), node("B", true), node("C", true), node("X", false)),
            edges = listOf(edge("ab", "A", "B"), edge("bc", "B", "C")) + extraEdges,
            groups = listOf(LayoutGroup(id = g, padding = padding, layoutAsCompound = true, minSize = minSize, direction = direction)),
        )

        val hints = LayoutHints(direction = LayoutDirection.TopToBottom)

        test("self-contained group lays its members out in its own direction") {
            val r = engine.layout(graph = graph(LayoutDirection.LeftToRight), hints = hints)
            val b = listOf("A", "B", "C").map { r.nodes.getValue(NodeId(it)).bounds }
            (b[0].origin.x < b[1].origin.x && b[1].origin.x < b[2].origin.x) shouldBe true
            b.all { abs(it.origin.y - b[0].origin.y) < 0.5f } shouldBe true
            r.warnings.filter { it.code == "group.direction.crossEdges" } shouldHaveSize 0
            val frame = r.groups.getValue(g).bounds
            for (m in b) {
                (m.origin.x >= frame.origin.x - 0.5f && m.origin.x + m.size.width <= frame.origin.x + frame.size.width + 0.5f) shouldBe true
                (m.origin.y >= frame.origin.y - 0.5f && m.origin.y + m.size.height <= frame.origin.y + frame.size.height + 0.5f) shouldBe
                    true
            }
        }

        test("minSize larger than the content is still met with an own direction") {
            val r = engine.layout(graph = graph(LayoutDirection.LeftToRight, minSize = Size(width = 600f, height = 300f)), hints = hints)
            val frame = r.groups.getValue(g).bounds
            (frame.size.width >= 600f - 0.5f && frame.size.height >= 300f - 0.5f) shouldBe true
            r.warnings.none { it.code == "group.minSize.unresolved" } shouldBe true
            val a = r.nodes.getValue(NodeId("A")).bounds
            val b = r.nodes.getValue(NodeId("B")).bounds
            (a.origin.x < b.origin.x) shouldBe true
        }

        test("crossing edges: picture equals the one without direction, one warning for the first crossing edge") {
            val crossing = listOf(edge("xa", "X", "A"), edge("cx", "C", "X"))
            val with = engine.layout(graph = graph(LayoutDirection.LeftToRight, crossing), hints = hints)
            val without = engine.layout(graph = graph(null, crossing), hints = hints)
            with.nodes shouldBe without.nodes
            with.edges shouldBe without.edges
            with.groups shouldBe without.groups
            val w = with.warnings.filter { it.code == "group.direction.crossEdges" }
            w shouldHaveSize 1
            w.single().affectedEdges shouldBe listOf(EdgeId("xa"))
            without.warnings.filter { it.code == "group.direction.crossEdges" } shouldHaveSize 0
        }

        test("an edge at the group's own anchor crosses its border, so the direction is not applied") {
            val r = engine.layout(graph = graph(LayoutDirection.LeftToRight, listOf(edge("ga", "G", "A"))), hints = hints)
            r.warnings
                .filter { it.code == "group.direction.crossEdges" }
                .single()
                .affectedEdges shouldBe listOf(EdgeId("ga"))
            val a = r.nodes.getValue(NodeId("A")).bounds
            val b = r.nodes.getValue(NodeId("B")).bounds
            (a.origin.y < b.origin.y) shouldBe true
        }

        test("without a direction the result equals the former behaviour and has no warnings") {
            val r = engine.layout(graph = graph(null), hints = hints)
            r.warnings.shouldHaveSize(0)
            val a = r.nodes.getValue(NodeId("A")).bounds
            val b = r.nodes.getValue(NodeId("B")).bounds
            (a.origin.y < b.origin.y) shouldBe true
        }
    })
