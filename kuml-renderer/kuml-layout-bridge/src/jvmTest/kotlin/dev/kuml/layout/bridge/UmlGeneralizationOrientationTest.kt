package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeLessThanOrEqual as floatLe

/** Nacharbeit 85: the general class of a UML generalisation is drawn above its specialisations. */
private fun cls(id: String) = LayoutNode(id = NodeId(id), intrinsicSize = Size(width = 120f, height = 60f))

private fun gen(
    sub: String,
    sup: String,
) = LayoutEdge(
    id = EdgeId("gen::$sub-|>$sup"),
    source = EndpointRef(nodeId = NodeId(sub)),
    target = EndpointRef(nodeId = NodeId(sup)),
)

private fun assoc(
    a: String,
    b: String,
) = LayoutEdge(id = EdgeId("assoc::$a-$b"), source = EndpointRef(nodeId = NodeId(a)), target = EndpointRef(nodeId = NodeId(b)))

private fun fan(n: Int): LayoutGraph {
    val subs = (1..n).map { "Sub$it" }
    return LayoutGraph(nodes = listOf(cls("Base")) + subs.map(::cls), edges = subs.map { gen(sub = it, sup = "Base") })
}

private fun run(
    graph: LayoutGraph,
    spec: LayoutSpec?,
): LayoutResult =
    LayoutSpecRunner
        .layout(engine = ElkLayoutEngineProvider().engine(), graph = graph, hints = LayoutHints.DEFAULT, spec = spec)
        .result

private fun LayoutResult.top(id: String) =
    nodes
        .getValue(NodeId(id))
        .bounds.origin.y

private fun LayoutResult.bottom(id: String) = nodes.getValue(NodeId(id)).bounds.let { it.origin.y + it.size.height }

private fun routePoints(route: EdgeRoute): List<Point> =
    when (route) {
        is EdgeRoute.Direct -> listOf(route.source, route.target)
        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + route.target
        is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + route.target
        is EdgeRoute.Bezier -> listOf(route.source) + route.controlPoints + route.target
    }

class UmlGeneralizationOrientationTest :
    FunSpec({
        for (n in listOf(3, 6, 9)) {
            test("direction Down, wrapColumns 4, $n subclasses: the general class is above all of them") {
                val r = run(graph = fan(n), spec = LayoutSpec(direction = LayoutFlow.Down, wrapColumns = 4))
                val minSub = (1..n).minOf { r.top("Sub$it") }
                r.bottom("Base").floatLe(minSub)
            }
        }

        test("default (no layout block): the general class is on top") {
            val r = run(graph = fan(3), spec = null)
            r.bottom("Base").floatLe((1..3).minOf { r.top("Sub$it") })
        }

        test("direction Up: the general class is at the bottom") {
            val r = run(graph = fan(3), spec = LayoutSpec(direction = LayoutFlow.Up))
            r.bottom("Sub1").floatLe(r.top("Base"))
        }

        test("routes keep the orientation specific -> general: start at the subclass, end at the general class") {
            val r = run(graph = fan(6), spec = LayoutSpec(direction = LayoutFlow.Down, wrapColumns = 4))
            for (i in 1..6) {
                val pts = routePoints(r.edges.getValue(EdgeId("gen::Sub$i-|>Base")))
                val start = pts.first()
                val end = pts.last()
                // The arrowhead sits on the general class (above), the tail on the specialisation (below).
                (end.y <= r.bottom("Base") + 1f) shouldBe true
                (start.y >= r.top("Sub$i") - 1f) shouldBe true
                (end.y < start.y) shouldBe true
            }
        }

        test("multi-level hierarchy A <- B <- C: A on top, C at the bottom") {
            val g =
                LayoutGraph(
                    nodes = listOf(cls("A"), cls("B"), cls("C")),
                    edges = listOf(gen(sub = "B", sup = "A"), gen(sub = "C", sup = "B")),
                )
            val r = run(graph = g, spec = null)
            r.bottom("A").floatLe(r.top("B"))
            r.bottom("B").floatLe(r.top("C"))
        }

        test("mixed association and generalisation lays out without overlap") {
            val g =
                LayoutGraph(
                    nodes = listOf(cls("Base"), cls("S1"), cls("S2"), cls("Other")),
                    edges = listOf(gen(sub = "S1", sup = "Base"), gen(sub = "S2", sup = "Base"), assoc(a = "S1", b = "Other")),
                )
            val r = run(graph = g, spec = null)
            r.nodes.size shouldBe 4
            r.bottom("Base").floatLe(r.top("S1"))
        }

        test("reverseGeneralizations flips only gen:: edges and restoreGeneralizations undoes the route flip") {
            val g = LayoutGraph(nodes = listOf(cls("A"), cls("B")), edges = listOf(gen(sub = "B", sup = "A"), assoc(a = "A", b = "B")))
            val rev = LayoutSpecRunner.reverseGeneralizations(g)
            rev.edges[0]
                .source.nodeId.value shouldBe "A"
            rev.edges[1]
                .source.nodeId.value shouldBe "A"
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 0f, y = 10f),
                    waypoints = listOf(Point(x = 0f, y = 3f), Point(x = 0f, y = 7f)),
                    cornerRadiusPx = 4f,
                )
            val res = run(graph = g, spec = null).copy(edges = mapOf(g.edges[0].id to route, g.edges[1].id to route))
            val back = LayoutSpecRunner.restoreGeneralizations(result = res, original = g).edges
            (back.getValue(g.edges[0].id) as EdgeRoute.OrthogonalRounded).source shouldBe Point(x = 0f, y = 10f)
            (back.getValue(g.edges[1].id) as EdgeRoute.OrthogonalRounded).source shouldBe Point(x = 0f, y = 0f)
        }

        test("layer pins count generalisations general -> specific: first(Super) and last(Sub) are conflict free") {
            val d = LayerPinCheck.edgeDegrees(fan(2))
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = d.getValue("Base")) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = d.getValue("Sub1")) shouldBe false
            LayerPinCheck.conflicts(side = PinSide.LAST, degree = d.getValue("Base")) shouldBe true
            LayerPinCheck.conflicts(side = PinSide.FIRST, degree = d.getValue("Sub1")) shouldBe true
        }
    })
