@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** PAL wave T: the IBD lane spread pulls apart connectors that share a corridor, and touches nothing else. */
class IbdLaneSpreadTest :
    FunSpec({
        timeout = 30_000L

        fun route(vararg p: Pair<Float, Float>): EdgeRoute {
            val pts = p.map { Point(x = it.first, y = it.second) }
            return EdgeRoute.OrthogonalRounded(
                source = pts.first(),
                target = pts.last(),
                waypoints = pts.drop(1).dropLast(1),
                cornerRadiusPx = 6f,
            )
        }

        fun result(edges: Map<String, EdgeRoute>) =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 600f, height = 600f),
                nodes = emptyMap(),
                edges = edges.mapKeys { EdgeId(it.key) },
                groups = emptyMap(),
            )

        fun ref(
            node: String,
            port: String,
        ) = EndpointRef(nodeId = NodeId(node), portId = PortId(port))

        fun wire(
            id: String,
            from: EndpointRef,
            to: EndpointRef,
            pinned: Boolean = false,
        ) = IbdLaneSpread.Wire(edgeId = EdgeId(id), source = from, target = to, pinned = pinned)

        // two connectors from two ports of one part to two ports of another part, sharing one corridor of 100 px
        fun twoLanes(): Pair<LayoutResult, List<IbdLaneSpread.Wire>> =
            result(
                mapOf(
                    "a" to route(100f to 50f, 100f to 100f, 300f to 100f, 300f to 200f),
                    "b" to route(200f to 50f, 200f to 100f, 400f to 100f, 400f to 200f),
                ),
            ) to
                listOf(
                    wire("a", ref("src", "p1"), ref("dst", "q1")),
                    wire("b", ref("src", "p2"), ref("dst", "q2")),
                )

        fun points(
            r: LayoutResult,
            id: String,
        ): List<Point> = polylinePoints(r.edges.getValue(EdgeId(id)))!!

        test("two connectors in one corridor are spread to at least 16 px, the end points stay bit-identical") {
            val (r, w) = twoLanes()
            val out = IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null)
            val a = points(out, "a")
            val b = points(out, "b")
            (abs(a[1].y - b[1].y) >= EDGE_EDGE_TARGET_GAP_PX) shouldBe true
            a.first() shouldBe points(r, "a").first()
            a.last() shouldBe points(r, "a").last()
            b.first() shouldBe points(r, "b").first()
            b.last() shouldBe points(r, "b").last()
            // rectangular: the inner segment stays horizontal
            (a[1].y == a[2].y && b[1].y == b[2].y) shouldBe true
        }

        test("connectors of the same port stay on one line (fan-out stem)") {
            val (r, _) = twoLanes()
            val same =
                listOf(
                    wire("a", ref("src", "p1"), ref("dst", "q1")),
                    wire("b", ref("src", "p1"), ref("dst", "q2")),
                )
            IbdLaneSpread.apply(result = r, wires = same, portSquares = emptyList(), frame = null) shouldBe r
        }

        test("collinear end stubs of two ports cannot be separated, the layout is returned unchanged") {
            val r =
                result(
                    mapOf(
                        "a" to route(100f to 50f, 100f to 200f),
                        "b" to route(104f to 60f, 104f to 220f),
                    ),
                )
            val w =
                listOf(
                    wire("a", ref("src", "p1"), ref("dst", "q1")),
                    wire("b", ref("src", "p2"), ref("dst", "q2")),
                )
            IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null) shouldBe r
        }

        test("a layout without a violation comes back as the same object") {
            val r =
                result(
                    mapOf(
                        "a" to route(100f to 50f, 100f to 100f, 300f to 100f, 300f to 200f),
                        "b" to route(200f to 50f, 200f to 160f, 400f to 160f, 400f to 200f),
                    ),
                )
            val (_, w) = twoLanes()
            (IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null) === r) shouldBe true
        }

        test("a move that would hit a foreign port square, in either direction, is rejected") {
            val (r, w) = twoLanes()
            // a square right of the corridor, hit by the lower lane on either side
            val squares =
                listOf(
                    Rect(origin = Point(x = 240f, y = 106f), size = Size(width = 40f, height = 12f)),
                    Rect(origin = Point(x = 240f, y = 82f), size = Size(width = 40f, height = 12f)),
                )
            val out = IbdLaneSpread.apply(result = r, wires = w, portSquares = squares, frame = null)
            (out === r) shouldBe true
        }

        test("the first direction would add a crossing, so the other one is taken") {
            val (r, w) = twoLanes()
            val out = IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null)
            // a moves down, b moves up: no crossing between a and b
            (points(out, "a")[1].y > points(out, "b")[1].y) shouldBe true
            countCrossings(out.edges.values.mapNotNull { polylinePoints(it) }) shouldBe 0
        }

        test("a pinned connector (gate) never moves, the other one takes the whole distance") {
            val (r, _) = twoLanes()
            val w =
                listOf(
                    wire("a", ref("src", "p1"), ref("dst", "q1"), pinned = true),
                    wire("b", ref("src", "p2"), ref("dst", "q2")),
                )
            val out = IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null)
            out.edges.getValue(EdgeId("a")) shouldBe r.edges.getValue(EdgeId("a"))
            (abs(points(out, "a")[1].y - points(out, "b")[1].y) >= EDGE_EDGE_TARGET_GAP_PX) shouldBe true
        }

        test("two runs give the same result") {
            val (r, w) = twoLanes()
            IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null) shouldBe
                IbdLaneSpread.apply(result = r, wires = w, portSquares = emptyList(), frame = null)
        }

        test("at most 64 pair fixes are made, however many pairs violate") {
            val edges = LinkedHashMap<String, EdgeRoute>()
            val wires = ArrayList<IbdLaneSpread.Wire>()
            for (k in 0 until 70) {
                val y = 100f + k * 120f
                edges["a$k"] = route(100f to y, 100f to y + 50f, 300f to y + 50f, 300f to y + 100f)
                edges["b$k"] = route(200f to y, 200f to y + 50f, 400f to y + 50f, 400f to y + 100f)
                wires += wire("a$k", ref("s$k", "p1"), ref("d$k", "q1"))
                wires += wire("b$k", ref("s$k", "p2"), ref("d$k", "q2"))
            }
            val r = result(edges)
            val (out, snaps) =
                IbdLaneSpreadProbe.collecting(spread = true) {
                    IbdLaneSpread.apply(result = r, wires = wires, portSquares = emptyList(), frame = null)
                }
            val changed = r.edges.keys.count { out.edges[it] != r.edges[it] }
            (changed <= 128) shouldBe true
            (snaps.single().after < snaps.single().before) shouldBe true
        }
    })
