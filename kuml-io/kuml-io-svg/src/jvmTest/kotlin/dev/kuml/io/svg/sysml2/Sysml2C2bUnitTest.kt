@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.ActObstacleRouting
import dev.kuml.layout.bridge.port.PortAttachment
import dev.kuml.layout.bridge.port.PortEdgeClamp
import dev.kuml.layout.bridge.port.PortFace
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs

private fun direct(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): EdgeRoute.Direct = EdgeRoute.Direct(source = Point(x = x1, y = y1), target = Point(x = x2, y = y2))

/** A pin square whose [face] border point is exactly [point] (the pin is 10 px wide, like the drawn one). */
private fun attachmentAt(
    point: Point,
    face: PortFace,
): PortAttachment {
    val origin =
        when (face) {
            PortFace.Left -> Point(x = point.x, y = point.y - 5f)
            PortFace.Right -> Point(x = point.x - 10f, y = point.y - 5f)
            PortFace.Top -> Point(x = point.x - 5f, y = point.y)
            PortFace.Bottom -> Point(x = point.x - 5f, y = point.y - 10f)
        }
    return PortAttachment(rect = Rect(origin = origin, size = Size(width = 10f, height = 10f)), face = face)
}

private fun PinSide.face(isOutput: Boolean): PortFace =
    when (this) {
        PinSide.Left -> PortFace.Left
        PinSide.Right -> PortFace.Right
        PinSide.Top -> PortFace.Top
        PinSide.Bottom -> PortFace.Bottom
        PinSide.Auto -> if (isOutput) PortFace.Right else PortFace.Left
    }

private fun pointsOf(route: EdgeRoute): List<Point> =
    when (route) {
        is EdgeRoute.Direct -> listOf(route.source, route.target)
        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
        is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
        is EdgeRoute.Bezier -> listOf(route.source, route.target)
    }

private fun segLen(
    a: Point,
    b: Point,
): Float = abs(b.x - a.x) + abs(b.y - a.y)

private fun actionWith(vararg pins: ActionPin): ActionDefinition = ActionDefinition(id = "A", name = "A", pins = pins.toList())

class Sysml2C2bUnitTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        test("clipper passes rectangle end points through unchanged") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 10f, y = 0f),
                    target = Point(x = 10f, y = 100f),
                    waypoints = listOf(Point(x = 10f, y = 30f), Point(x = 40f, y = 60f)),
                    cornerRadiusPx = 4f,
                )
            val shape = Sysml2ActivityEdgeClipper.Shape.Rectangle(rect(0f, 100f, 80f, 40f))
            val out = Sysml2ActivityEdgeClipper.clip(route = route, sourceShape = null, targetShape = shape)
            out.target shouldBe route.target
        }

        test("diamond keeps the cross coordinate of an axis-parallel segment") {
            val d = Sysml2ActivityEdgeClipper.Shape.Diamond(rect(0f, 0f, 40f, 40f))
            val p =
                Sysml2ActivityEdgeClipper.axisAligned(
                    shape = d,
                    original = Point(x = 0f, y = 10f),
                    neighbor = Point(x = -50f, y = 10f),
                )!!
            p.y shouldBe 10f
            // diamond border at y offset -10: halfW * (1 - 10/20) = 10 left of the centre x=20
            p.x shouldBe 10f
        }

        test("circle snaps along a vertical segment") {
            val c = Sysml2ActivityEdgeClipper.Shape.Circle(bounds = rect(0f, 0f, 28f, 28f), radiusPx = 12f)
            val p =
                Sysml2ActivityEdgeClipper.axisAligned(
                    shape = c,
                    original = Point(x = 14f, y = 0f),
                    neighbor = Point(x = 14f, y = -40f),
                )!!
            p.x shouldBe 14f
            p.y shouldBe 2f
        }

        test("fork anchors are spread evenly and at least 16 px apart on a sized bar") {
            val bar = rect(0f, 0f, Sysml2ForkJoinAnchors.requiredWidth(3), 10f)
            val xs =
                Sysml2ForkJoinAnchors
                    .distribute(
                        bar = bar,
                        arriving =
                            listOf(
                                BarAttachment(edgeId = "a", atTarget = true, neighborX = 5f),
                                BarAttachment(edgeId = "b", atTarget = true, neighborX = 1f),
                                BarAttachment(edgeId = "c", atTarget = true, neighborX = 9f),
                            ),
                        leaving = emptyList(),
                    )
            xs.getValue("b" to true) shouldBe 16f
            xs.getValue("a" to true) shouldBe 32f
            xs.getValue("c" to true) shouldBe 48f
            (xs.getValue("a" to true) - xs.getValue("b" to true)).shouldBeGreaterThanOrEqual(16f)
        }

        test("placeFrameLabel: free band keeps the label left") {
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = emptyList())
            p.x shouldBe 0f
            p.fallbackOccluded shouldBe false
        }

        test("placeFrameLabel: an edge through the left spot moves the label into the next gap") {
            val edge = listOf(Point(x = 40f, y = -10f), Point(x = 40f, y = 100f))
            val p = placeFrameLabel(frame = rect(0f, 0f, 400f, 200f), labelW = 80f, bandH = 20f, edges = listOf(edge))
            p.fallbackOccluded shouldBe false
            (p.x > 40f) shouldBe true
        }

        test("placeFrameLabel: the label finds the only free gap at the right") {
            val edges = (0..10).map { listOf(Point(x = it * 20f + 2f, y = -10f), Point(x = it * 20f + 2f, y = 100f)) }
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = edges)
            p.fallbackOccluded shouldBe false
            (p.x > 208f) shouldBe true
            (p.x + 80f <= 300f) shouldBe true
        }

        test("placeFrameLabel: fully occluded band reports the fallback") {
            val edges = (0..30).map { listOf(Point(x = it * 10f, y = -10f), Point(x = it * 10f, y = 100f)) }
            val p = placeFrameLabel(frame = rect(0f, 0f, 300f, 200f), labelW = 80f, bandH = 20f, edges = edges)
            p.fallbackOccluded shouldBe true
            p.x shouldBe 0f
        }

        test("orthogonalize: diagonal pin edge becomes axis parallel with perpendicular stubs") {
            val route = EdgeRoute.Direct(source = Point(x = 100f, y = 100f), target = Point(x = 40f, y = 200f))
            val out =
                Sysml2PinRouting.orthogonalize(
                    route = route,
                    source = attachmentAt(route.source, PortFace.Bottom),
                    target = attachmentAt(route.target, PortFace.Top),
                ) as EdgeRoute.OrthogonalRounded
            val pts = listOf(out.source) + out.waypoints + listOf(out.target)
            for (i in 0 until pts.size - 1) (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
            // first segment leaves downward, last arrives downward, both at least the stub length
            abs(pts[1].x - pts[0].x) shouldBe 0f
            (pts[1].y - pts[0].y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
            abs(pts.last().x - pts[pts.size - 2].x) shouldBe 0f
            (pts.last().y - pts[pts.size - 2].y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
        }

        test("orthogonalize: L bend for vertical exit and horizontal entry") {
            val route = EdgeRoute.Direct(source = Point(x = 100f, y = 100f), target = Point(x = 300f, y = 200f))
            val out =
                Sysml2PinRouting.orthogonalize(
                    route = route,
                    source = attachmentAt(route.source, PortFace.Bottom),
                    target = attachmentAt(route.target, PortFace.Left),
                ) as EdgeRoute.OrthogonalRounded
            val pts = listOf(out.source) + out.waypoints + listOf(out.target)
            for (i in 0 until pts.size - 1) (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
            (pts.last().x - pts[pts.size - 2].x).shouldBeGreaterThanOrEqual(12f)
        }

        test("ACT pin stub is derived from the shared arrowhead length") {
            Sysml2PinRouting.STUB_PX shouldBe PortEdgeClamp.ARROW_LEN_PX + Sysml2PinRouting.CORNER_RADIUS_PX
            Sysml2PinRouting.STUB_PX shouldBe 16f
            Sysml2PinRouting.STUB_PX shouldBe PortEdgeClamp.arrowStubPx(Sysml2PinRouting.CORNER_RADIUS_PX)
        }

        // Guards for "ACT keeps its own router": each case is a place where PortEdgeClamp.clamp gives a different
        // route. If someone swaps the router for clamp, these fail and the image change has to be decided on purpose.
        test("ACT keeps the full stub at a source end without arrowhead, clamp would use the corner radius") {
            val route = direct(200f, 200f, 80f, 80f)
            val src = attachmentAt(route.source, PortFace.Top)
            val tgt = attachmentAt(route.target, PortFace.Top)
            val act = pointsOf(Sysml2PinRouting.orthogonalize(route = route, source = src, target = tgt))
            val clamped =
                pointsOf(PortEdgeClamp.clamp(route = route, source = src, target = tgt, sourceArrowhead = false, targetArrowhead = true))
            abs(act[1].y - act[0].y) shouldBe Sysml2PinRouting.STUB_PX
            abs(clamped[1].y - clamped[0].y) shouldBe Sysml2PinRouting.CORNER_RADIUS_PX
        }

        test("ACT drops inner way points of a non orthogonal route, clamp keeps them") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 200f, y = 200f),
                    target = Point(x = 80f, y = 80f),
                    waypoints = listOf(Point(x = 200f, y = 150f), Point(x = 140f, y = 150f), Point(x = 140f, y = 100f)),
                    cornerRadiusPx = 4f,
                )
            val src = attachmentAt(route.source, PortFace.Top)
            val tgt = attachmentAt(route.target, PortFace.Bottom)
            val act = pointsOf(Sysml2PinRouting.orthogonalize(route = route, source = src, target = tgt))
            val clamped =
                pointsOf(PortEdgeClamp.clamp(route = route, source = src, target = tgt, sourceArrowhead = false, targetArrowhead = true))
            act.none { it.y == 150f } shouldBe true
            clamped.any { it.y == 150f } shouldBe true
        }

        test("ACT and clamp differ for pins on the same row, clamp adds a detour") {
            val route = direct(200f, 200f, 80f, 200f)
            val src = attachmentAt(route.source, PortFace.Top)
            val tgt = attachmentAt(route.target, PortFace.Top)
            val act = pointsOf(Sysml2PinRouting.orthogonalize(route = route, source = src, target = tgt))
            val clamped =
                pointsOf(PortEdgeClamp.clamp(route = route, source = src, target = tgt, sourceArrowhead = false, targetArrowhead = true))
            act shouldNotBe clamped
            act.size shouldBe 4
            clamped.size shouldBe 6
        }

        test("orthogonalize: last segment keeps the arrowhead on a straight run after the bend") {
            val cases =
                listOf(
                    // L: vertical exit, horizontal entry
                    Triple(direct(x1 = 100f, y1 = 100f, x2 = 300f, y2 = 200f), PinSide.Bottom, PinSide.Left),
                    // Z: vertical exit, vertical entry with horizontal offset
                    Triple(direct(x1 = 100f, y1 = 100f, x2 = 40f, y2 = 200f), PinSide.Bottom, PinSide.Top),
                    // S: opposing sides, target left of the source
                    Triple(direct(x1 = 300f, y1 = 100f, x2 = 100f, y2 = 200f), PinSide.Right, PinSide.Left),
                )
            for ((route, srcSide, tgtSide) in cases) {
                val out =
                    Sysml2PinRouting.orthogonalize(
                        route = route,
                        source = attachmentAt(route.source, srcSide.face(isOutput = true)),
                        target = attachmentAt(route.target, tgtSide.face(isOutput = false)),
                    ) as EdgeRoute.OrthogonalRounded
                val pts = listOf(out.source) + out.waypoints + listOf(out.target)
                val a = pts[pts.size - 2]
                val b = pts.last()
                val last = abs(b.x - a.x) + abs(b.y - a.y)
                (last - Sysml2PinRouting.CORNER_RADIUS_PX).shouldBeGreaterThanOrEqual(PortEdgeClamp.ARROW_LEN_PX - 0.5f)
            }
        }

        test("orthogonalize: already orthogonal perpendicular route stays unchanged") {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 50f, y = 60f),
                    target = Point(x = 50f, y = 160f),
                    waypoints = emptyList(),
                    cornerRadiusPx = 4f,
                )
            Sysml2PinRouting.orthogonalize(
                route = route,
                source = attachmentAt(route.source, PortFace.Bottom),
                target = attachmentAt(route.target, PortFace.Top),
            ) shouldBe route
        }

        test("orthogonalize: no pin at either end leaves the route alone") {
            val route = EdgeRoute.Direct(source = Point(x = 0f, y = 0f), target = Point(x = 30f, y = 40f))
            Sysml2PinRouting.orthogonalize(route = route, source = null, target = null) shouldBe route
        }

        // ---- Nacharbeit 3: ACT routes derived from the pin rectangles -------------------------------------------------

        // Literals computed from the pre-refactor formula `bounds + centre + (+-PIN_SIZE/2)` for a 120x80 box at (100, 50).
        val bounds = rect(100f, 50f, 120f, 80f)
        val oldAnchors =
            listOf(
                Triple(PinSide.Left, Point(x = 95f, y = 90f), PortFace.Left),
                Triple(PinSide.Right, Point(x = 225f, y = 90f), PortFace.Right),
                Triple(PinSide.Top, Point(x = 160f, y = 45f), PortFace.Top),
                Triple(PinSide.Bottom, Point(x = 160f, y = 135f), PortFace.Bottom),
            )

        test("actionPinAttachment: rect is the drawn pin square and the face is the pin's outward face") {
            for ((side, _, face) in oldAnchors) {
                for (dir in PinDirection.entries) {
                    val def = actionWith(ActionPin(name = "p", direction = dir, side = side))
                    val att = actionPinAttachment(def = def, bounds = bounds, pinName = "p", direction = dir)!!
                    val (cx, cy) = pinPositions(count = 1, side = side, w = 120f, h = 80f)[0]
                    abs(att.rect.origin.x - (100f + cx - PIN_SIZE / 2f)) shouldBe 0f
                    abs(att.rect.origin.y - (50f + cy - PIN_SIZE / 2f)) shouldBe 0f
                    att.rect.size.width shouldBe PIN_SIZE
                    att.rect.size.height shouldBe PIN_SIZE
                    att.face shouldBe face
                }
            }
        }

        test("actionPinAttachment: border point equals the former pin anchor on all sides and directions") {
            for ((side, anchor, _) in oldAnchors) {
                for (dir in PinDirection.entries) {
                    val def = actionWith(ActionPin(name = "p", direction = dir, side = side))
                    val p = PortEdgeClamp.borderPoint(actionPinAttachment(def = def, bounds = bounds, pinName = "p", direction = dir)!!)
                    abs(p.x - anchor.x) shouldBe 0f
                    abs(p.y - anchor.y) shouldBe 0f
                }
            }
        }

        test("actionPinAttachment: Auto side resolves by direction; unknown pin gives null") {
            val def =
                actionWith(
                    ActionPin(name = "in", direction = PinDirection.Input),
                    ActionPin(name = "out", direction = PinDirection.Output),
                )
            actionPinAttachment(def, bounds, "in", PinDirection.Input)!!.face shouldBe PortFace.Left
            actionPinAttachment(def, bounds, "out", PinDirection.Output)!!.face shouldBe PortFace.Right
            actionPinAttachment(def, bounds, "nope", PinDirection.Input) shouldBe null
            actionPinAttachment(def, bounds, "in", PinDirection.Output) shouldBe null
        }

        val faces = PortFace.entries
        val srcP = Point(x = 300f, y = 300f)

        test("orthogonalize: every face as source and as target ends on the border, perpendicular, with a full stub") {
            val others = listOf(Point(x = 100f, y = 100f), Point(x = 520f, y = 120f), Point(x = 140f, y = 560f), Point(x = 520f, y = 540f))
            for (face in faces) {
                for (other in others) {
                    for (asSource in listOf(true, false)) {
                        val mine = attachmentAt(srcP, face)
                        val theirs = attachmentAt(other, PortFace.Top)
                        val route = EdgeRoute.Direct(source = if (asSource) srcP else other, target = if (asSource) other else srcP)
                        val out =
                            Sysml2PinRouting.orthogonalize(
                                route = route,
                                source = if (asSource) mine else theirs,
                                target = if (asSource) theirs else mine,
                            )
                        val pts = pointsOf(out)
                        for (i in 0 until pts.size - 1) {
                            (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
                        }
                        val (pin, nb) = if (asSource) pts[0] to pts[1] else pts.last() to pts[pts.size - 2]
                        abs(pin.x - srcP.x) shouldBe 0f
                        abs(pin.y - srcP.y) shouldBe 0f
                        val n = PortEdgeClamp.normal(face)
                        // perpendicular and coming from outside the pin
                        ((nb.x - pin.x) * n.x + (nb.y - pin.y) * n.y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
                        (abs(nb.x - pin.x) * abs(n.y) + abs(nb.y - pin.y) * abs(n.x)) shouldBe 0f
                    }
                }
            }
        }

        test("orthogonalize: same face at both ends yields a U route without a backward step at the pin") {
            for (face in faces) {
                val a = Point(x = 100f, y = 100f)
                val b = Point(x = 300f, y = 220f)
                val out =
                    Sysml2PinRouting.orthogonalize(
                        route = EdgeRoute.Direct(source = a, target = b),
                        source = attachmentAt(a, face),
                        target = attachmentAt(b, face),
                    )
                val pts = pointsOf(out)
                for (i in 0 until pts.size - 1) {
                    (abs(pts[i + 1].x - pts[i].x) <= 0.5f || abs(pts[i + 1].y - pts[i].y) <= 0.5f) shouldBe true
                }
                val n = PortEdgeClamp.normal(face)
                ((pts[1].x - a.x) * n.x + (pts[1].y - a.y) * n.y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
                val e = pts.last()
                val l = pts[pts.size - 2]
                ((l.x - e.x) * n.x + (l.y - e.y) * n.y).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
            }
        }

        test("actionPinAttachment: overlapping pins on a tiny box stay finite and deterministic") {
            val pins = (0 until 3).map { ActionPin(name = "p$it", direction = PinDirection.Output, side = PinSide.Right) }
            val def = actionWith(*pins.toTypedArray())
            val tiny = rect(0f, 0f, 60f, 20f)

            fun run(): List<Point> =
                pins.flatMap { p ->
                    val att = actionPinAttachment(def, tiny, p.name, PinDirection.Output)!!
                    val bp = PortEdgeClamp.borderPoint(att)
                    val out =
                        Sysml2PinRouting.orthogonalize(
                            route = EdgeRoute.Direct(source = bp, target = Point(x = 200f, y = 100f)),
                            source = att,
                            target = attachmentAt(Point(x = 200f, y = 100f), PortFace.Left),
                        )
                    // the end lies on the border of its own rect
                    abs(pointsOf(out).first().x - (att.rect.origin.x + att.rect.size.width)) shouldBe 0f
                    pointsOf(out)
                }
            val first = run()
            first.all { it.x.isFinite() && it.y.isFinite() } shouldBe true
            run() shouldBe first
        }

        test("orthogonalize: an end without a pin keeps the route's own end point") {
            val route = EdgeRoute.Direct(source = Point(x = 11f, y = 22f), target = Point(x = 300f, y = 200f))
            val tgt = attachmentAt(route.target, PortFace.Left)
            val onlyTarget = Sysml2PinRouting.orthogonalize(route = route, source = null, target = tgt)
            pointsOf(onlyTarget).first() shouldBe route.source
            pointsOf(onlyTarget).last() shouldBe route.target
            val src = attachmentAt(route.source, PortFace.Right)
            val onlySource = Sysml2PinRouting.orthogonalize(route = route, source = src, target = null)
            pointsOf(onlySource).first() shouldBe route.source
            pointsOf(onlySource).last() shouldBe route.target
        }

        test("orthogonalize: obstacle between the pins keeps the stubs and avoids the obstacle") {
            val a = Point(x = 100f, y = 100f)
            val b = Point(x = 400f, y = 100f)
            val obstacle = rect(200f, 60f, 100f, 80f)
            val out =
                Sysml2PinRouting.orthogonalize(
                    route = EdgeRoute.Direct(source = a, target = b),
                    source = attachmentAt(a, PortFace.Right),
                    target = attachmentAt(b, PortFace.Left),
                    obstacles = listOf(obstacle),
                )
            val pts = pointsOf(out)
            ActObstacleRouting.hits(path = pts, obstacles = listOf(obstacle)) shouldBe false
            segLen(pts[0], pts[1]).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
            segLen(pts[pts.size - 2], pts.last()).shouldBeGreaterThanOrEqual(Sysml2PinRouting.STUB_PX)
            pts[0] shouldNotBe pts.last()
        }
    })
