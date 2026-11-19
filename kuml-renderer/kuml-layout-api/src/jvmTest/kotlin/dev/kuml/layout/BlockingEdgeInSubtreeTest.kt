package dev.kuml.layout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 119: [blockingEdgeInSubtree] reports an edge crossing the border of a group or of any group nested in it. */
class BlockingEdgeInSubtreeTest :
    FunSpec({
        fun group(
            id: String,
            parent: String? = null,
        ) = LayoutGroup(id = GroupId(id), layoutAsCompound = true, parent = parent?.let { GroupId(it) })

        fun node(
            id: String,
            inGroup: String?,
        ) = LayoutNode(id = NodeId(id), intrinsicSize = Size(width = 80f, height = 40f), groupId = inGroup?.let { GroupId(it) })

        fun edge(
            id: String,
            s: String,
            t: String,
        ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))

        val groups = listOf(group("C"), group("N", parent = "C"), group("M", parent = "N"))
        val nodes =
            listOf(node("c", "C"), node("n", "N"), node("n2", "N"), node("m", "M"), node("x", null))

        fun graph(vararg edges: LayoutEdge) = LayoutGraph(nodes = nodes, edges = edges.toList(), groups = groups)

        fun LayoutGraph.of(id: String) = this.groups.first { it.id.value == id }

        test("no edges: the subtree is self-contained") {
            val g = graph()
            g.blockingEdgeInSubtree(g.of("C")) shouldBe null
        }

        test("edges inside one nested group do not block the outer group") {
            val g = graph(edge("e1", "n", "n2"))
            g.blockingEdgeInSubtree(g.of("C")) shouldBe null
            g.blockingEdgeInSubtree(g.of("N")) shouldBe null
        }

        test("an edge from a direct member to a nested member crosses the nested border") {
            val g = graph(edge("e1", "c", "n"))
            g.crossingEdgeOf(g.of("C")) shouldBe null
            g.blockingEdgeInSubtree(g.of("N"))?.value shouldBe "e1"
        }

        test("an edge across the group's own border blocks and names that group") {
            val g = graph(edge("out", "c", "x"))
            g.blockingEdgeInSubtree(g.of("C"))?.value shouldBe "out"
            g.blockingEdgeInSubtreeDetailed(g.of("C"))?.group shouldBe GroupId("C")
        }

        test("an edge from a nested member to a direct member crosses the nested border") {
            val g = graph(edge("nested", "n", "c"))
            g.crossingEdgeOf(g.of("C")) shouldBe null
            g.blockingEdgeInSubtree(g.of("C"))?.value shouldBe "nested"
            g.blockingEdgeInSubtreeDetailed(g.of("C"))?.group shouldBe GroupId("N")
        }

        test("three levels: an edge across the innermost border blocks the outermost group") {
            val g = graph(edge("deep", "m", "n"))
            g.blockingEdgeInSubtreeDetailed(g.of("C"))?.group shouldBe GroupId("M")
        }

        test("an edge to a group id counts like an edge to a member") {
            val g = graph(edge("toN", "c", "N"))
            g.blockingEdgeInSubtree(g.of("C")) shouldBe null
            g.blockingEdgeInSubtreeDetailed(g.of("C")) shouldBe null
            val outside = graph(edge("toN2", "x", "N"))
            outside.blockingEdgeInSubtree(outside.of("C"))?.value shouldBe "toN2"
        }

        test("edges with unknown endpoints are ignored") {
            val g = graph(edge("u", "c", "ghost"))
            g.blockingEdgeInSubtree(g.of("C")) shouldBe null
        }

        test("a cyclic parent chain terminates") {
            val cyc = LayoutGraph(nodes = nodes, edges = emptyList(), groups = listOf(group("A", "B"), group("B", "A")))
            cyc.blockingEdgeInSubtree(cyc.groups.first()) shouldBe null
        }
    })
