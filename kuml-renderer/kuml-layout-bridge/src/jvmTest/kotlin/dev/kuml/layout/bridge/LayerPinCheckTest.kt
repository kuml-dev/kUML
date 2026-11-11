package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun node(id: String) = LayoutNode(id = NodeId(id), intrinsicSize = Size(width = 100f, height = 50f))

private fun edge(
    id: String,
    from: String,
    to: String,
) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(from)), target = EndpointRef(nodeId = NodeId(to)))

/** A -> B, B -> C, plus a self-loop on C and an edge to an id that is not a node. */
private val GRAPH =
    LayoutGraph(
        nodes = listOf(node("A"), node("B"), node("C")),
        edges =
            listOf(
                edge(id = "e1", from = "A", to = "B"),
                edge(id = "e2", from = "B", to = "C"),
                edge(id = "e3", from = "C", to = "C"),
                edge(id = "e4", from = "A", to = "ghost"),
            ),
    )

class LayerPinCheckTest :
    FunSpec({
        test("conflict truth table: last conflicts with outgoing edges, first with incoming ones") {
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = EdgeDegree(incoming = 0, outgoing = 0)) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = EdgeDegree(incoming = 3, outgoing = 0)) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = EdgeDegree(incoming = 0, outgoing = 1)) shouldBe true
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = EdgeDegree(incoming = 2, outgoing = 1)) shouldBe true
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = EdgeDegree(incoming = 0, outgoing = 0)) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = EdgeDegree(incoming = 0, outgoing = 3)) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = EdgeDegree(incoming = 1, outgoing = 0)) shouldBe true
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = EdgeDegree(incoming = 1, outgoing = 2)) shouldBe true
        }

        test("edge degrees follow source to target and skip self-loops and non-node endpoints") {
            val d = LayerPinCheck.edgeDegrees(GRAPH)
            d["A"] shouldBe EdgeDegree(incoming = 0, outgoing = 1)
            d["B"] shouldBe EdgeDegree(incoming = 1, outgoing = 1)
            d["C"] shouldBe EdgeDegree(incoming = 1, outgoing = 0)
        }

        test("last on a node with an outgoing edge reports W008 with the node and the edge count") {
            val f = LayerPinCheck.findings(spec = LayoutSpec(last = listOf("A")), degrees = LayerPinCheck.edgeDegrees(GRAPH))
            f shouldHaveSize 1
            f[0].code shouldBe LayoutCodes.LAYER_PIN_CONFLICT
            f[0].isError shouldBe false
            f[0].message shouldContain "last(A)"
            f[0].message shouldContain "1 outgoing edge(s)"
        }

        test("first on a node with an incoming edge reports W008") {
            val f = LayerPinCheck.findings(spec = LayoutSpec(first = listOf("B")), degrees = LayerPinCheck.edgeDegrees(GRAPH))
            f shouldHaveSize 1
            f[0].message shouldContain "first(B)"
            f[0].message shouldContain "1 incoming edge(s)"
        }

        test("valid pins (last on a sink, first on a source), unknown ids and unknown degrees report nothing") {
            val d = LayerPinCheck.edgeDegrees(GRAPH)
            LayerPinCheck.findings(spec = LayoutSpec(first = listOf("A"), last = listOf("C", "ghost")), degrees = d).shouldBeEmpty()
            LayerPinCheck.findings(spec = LayoutSpec(last = listOf("A")), degrees = null).shouldBeEmpty()
            LayerPinCheck.findings(spec = null, degrees = d).shouldBeEmpty()
        }

        test("LayoutSpecRunner.validate adds W008 only when degrees are given") {
            val spec = LayoutSpec(last = listOf("A"))
            val d = LayerPinCheck.edgeDegrees(GRAPH)
            LayoutSpecRunner
                .validate(kind = SpecDiagramKind.UML_CLASS, spec = spec, knownIds = setOf("A", "B", "C"), edgeDegrees = d)
                .count { it.code == LayoutCodes.LAYER_PIN_CONFLICT } shouldBe 1
            LayoutSpecRunner
                .validate(kind = SpecDiagramKind.UML_CLASS, spec = spec, knownIds = setOf("A", "B", "C"))
                .count { it.code == LayoutCodes.LAYER_PIN_CONFLICT } shouldBe 0
        }
    })
