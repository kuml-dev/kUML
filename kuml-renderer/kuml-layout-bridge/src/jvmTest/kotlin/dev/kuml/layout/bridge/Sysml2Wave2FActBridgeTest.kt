package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.InterruptibleRegionDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs

/** PAL wave 2F — text wrap, region frames, lane columns, corridor separation (pure geometry, no engine). */
class Sysml2Wave2FActBridgeTest :
    FunSpec({
        fun box(
            x: Float,
            y: Float,
            w: Float = 160f,
            h: Float = 60f,
        ) = NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h)))

        fun result(nodes: Map<String, NodeLayout>) =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 1000f, height = 1000f),
                nodes = nodes.mapKeys { NodeId(it.key) },
                edges = emptyMap(),
                groups = emptyMap(),
            )

        test("ActTextWrap keeps all 50 characters, uses at most 4 lines and never adds an ellipsis") {
            val name = "Rückmeldung der Gebäudeleittechnik zur Türöffnung."
            name.length shouldBe 50
            val w = ActTextWrap.wrap(text = name, maxWidthPx = 200f, maxLines = 4)
            (w.lines.size <= 4) shouldBe true
            w.lines.joinToString(" ") shouldBe name
            w.lines.none { it.contains("…") } shouldBe true
        }

        test("ActTextWrap widens instead of truncating and splits a word wider than the line") {
            val long = "Donaudampfschifffahrtsgesellschaftskapitän ".repeat(6).trim()
            val w = ActTextWrap.wrap(text = long, maxWidthPx = 100f, maxLines = 4)
            (w.lines.size <= 4) shouldBe true
            (w.widthPx > 100f) shouldBe true
            w.lines.joinToString(" ").replace(" ", "") shouldBe long.replace(" ", "")
            ActTextWrap.wrap(text = "x".repeat(60), maxWidthPx = 100f, maxLines = 6).lines.joinToString("") shouldBe "x".repeat(60)
        }

        test("ActRegionFrames: padding 12, a nested frame lies at least 8 px inside, a cycle terminates") {
            val outer = InterruptibleRegionDefinition(id = "o", name = "O")
            val inner = InterruptibleRegionDefinition(id = "i", name = "I", parentRegionId = "o")
            val frames =
                ActRegionFrames.compute(
                    regions = listOf(outer, inner),
                    members = mapOf("a" to "o", "b" to "i"),
                    nodeBounds =
                        mapOf(
                            "a" to Rect(origin = Point(x = 0f, y = 100f), size = Size(width = 100f, height = 40f)),
                            "b" to Rect(origin = Point(x = 20f, y = 200f), size = Size(width = 100f, height = 40f)),
                        ),
                )
            val o = frames.first { it.regionId == "o" }.rect
            val i = frames.first { it.regionId == "i" }.rect
            (i.origin.x - o.origin.x >= ActRegionFrames.NEST_INDENT) shouldBe true
            ((o.origin.y + o.size.height) - (i.origin.y + i.size.height) >= ActRegionFrames.NEST_INDENT) shouldBe true
            val loop1 = InterruptibleRegionDefinition(id = "x", name = "X", parentRegionId = "y")
            val loop2 = InterruptibleRegionDefinition(id = "y", name = "Y", parentRegionId = "x")
            ActRegionFrames
                .compute(
                    regions = listOf(loop1, loop2),
                    members = mapOf("n" to "x"),
                    nodeBounds =
                        mapOf(
                            "n" to Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 10f, height = 10f)),
                        ),
                ).shouldHaveSize(1)
        }

        test("lane pass: bands of equal height, nodes inside their lane, sameLayer shares the centre, back edge found") {
            val engine =
                result(
                    mapOf(
                        "a" to box(0f, 0f),
                        "b" to box(0f, 100f),
                        "c" to box(300f, 100f, h = 90f),
                        "d" to box(0f, 300f),
                    ),
                )
            val infos =
                listOf(
                    ActNodeInfo(id = "a", laneId = "L1"),
                    ActNodeInfo(id = "b", laneId = "L1"),
                    ActNodeInfo(id = "c", laneId = "L2"),
                    ActNodeInfo(id = "d", laneId = "L1"),
                )
            val flows =
                listOf(
                    ActFlow(id = "ab", source = "a", target = "b"),
                    ActFlow(id = "ac", source = "a", target = "c"),
                    ActFlow(id = "bd", source = "b", target = "d"),
                    ActFlow(id = "cd", source = "c", target = "d"),
                    ActFlow(id = "back", source = "d", target = "a"),
                )
            val lanes = listOf(ActLane(id = "L1", title = "One"), ActLane(id = "L2", title = "Two"))
            val out =
                ActLaneColumnPass.apply(
                    result = engine,
                    nodes = infos,
                    flows = flows,
                    lanes = lanes,
                    sameLayerGroups = listOf(listOf("b", "c")),
                )
            out.backEdges shouldBe setOf("back")
            out.laneRects.values
                .map { it.origin.y }
                .distinct() shouldHaveSize 1
            out.laneRects.values
                .map { it.size.height }
                .distinct() shouldHaveSize 1
            val nodes = out.result.nodes
            for (i in infos) {
                val lane = out.laneRects.getValue(i.laneId!!)
                val b = nodes.getValue(NodeId(i.id)).bounds
                (b.origin.x >= lane.origin.x && b.origin.x + b.size.width <= lane.origin.x + lane.size.width) shouldBe true
            }
            val bb = nodes.getValue(NodeId("b")).bounds
            val cb = nodes.getValue(NodeId("c")).bounds
            abs((bb.origin.y + bb.size.height / 2f) - (cb.origin.y + cb.size.height / 2f)) shouldBe 0f
            // flow runs downwards
            (out.layerOf.getValue("d") > out.layerOf.getValue("b")) shouldBe true
            // L1 holds the corridor of the back edge, L2 does not
            out.corridorX.keys shouldBe setOf("L1")
            // deterministic
            ActLaneColumnPass
                .apply(
                    result = engine,
                    nodes = infos,
                    flows = flows,
                    lanes = lanes,
                    sameLayerGroups = listOf(listOf("b", "c")),
                ).result shouldBe
                out.result
        }

        test("corridor separation moves a collinear interior segment at least 12 px away") {
            fun route(vararg p: Point) =
                EdgeRoute.OrthogonalRounded(source = p.first(), target = p.last(), waypoints = p.drop(1).dropLast(1), cornerRadiusPx = 0f)
            val a = route(Point(x = 0f, y = 0f), Point(x = 0f, y = 10f), Point(x = 0f, y = 100f), Point(x = 50f, y = 100f))
            val b =
                route(
                    Point(x = 40f, y = 0f),
                    Point(x = 40f, y = 20f),
                    Point(x = 0f, y = 20f),
                    Point(x = 0f, y = 90f),
                    Point(x = 0f, y = 120f),
                    Point(x = 40f, y = 120f),
                )
            val out = ActCorridorSeparation.apply(mapOf(EdgeId("a") to a, EdgeId("b") to b))
            out.getValue(EdgeId("b")) shouldNotBe b
            val moved = out.getValue(EdgeId("b")) as EdgeRoute.OrthogonalRounded
            val xs = moved.waypoints.map { it.x }
            (xs.any { it >= 12f }) shouldBe true
        }
    })
