@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.port

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun rectAt(
    cx: Float,
    cy: Float,
    size: Float = 12f,
): Rect = Rect(origin = Point(x = cx - size / 2f, y = cy - size / 2f), size = Size(width = size, height = size))

private fun pts(route: EdgeRoute): List<Point> =
    when (route) {
        is EdgeRoute.Direct -> listOf(route.source, route.target)
        is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
        is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
        is EdgeRoute.Bezier -> error("unexpected")
    }

private fun distToFace(
    p: Point,
    att: PortAttachment,
): Float {
    val r = att.rect
    return when (att.face) {
        PortFace.Top -> abs(p.y - r.origin.y)
        PortFace.Bottom -> abs(p.y - (r.origin.y + r.size.height))
        PortFace.Left -> abs(p.x - r.origin.x)
        PortFace.Right -> abs(p.x - (r.origin.x + r.size.width))
    }
}

private fun withinSpan(
    p: Point,
    att: PortAttachment,
): Boolean {
    val r = att.rect
    return when (att.face) {
        PortFace.Top, PortFace.Bottom -> p.x >= r.origin.x - 0.01f && p.x <= r.origin.x + r.size.width + 0.01f
        PortFace.Left, PortFace.Right -> p.y >= r.origin.y - 0.01f && p.y <= r.origin.y + r.size.height + 0.01f
    }
}

private fun insideInterior(
    p: Point,
    r: Rect,
): Boolean =
    p.x > r.origin.x + 0.01f &&
        p.x < r.origin.x + r.size.width - 0.01f &&
        p.y > r.origin.y + 0.01f &&
        p.y < r.origin.y + r.size.height - 0.01f

private fun checkEnd(
    route: EdgeRoute,
    att: PortAttachment,
    atStart: Boolean,
    minStub: Float,
) {
    val p = pts(route)
    val end = if (atStart) p.first() else p.last()
    val next = if (atStart) p[1] else p[p.size - 2]
    distToFace(end, att) shouldBeLessThanOrEqual 0.01f
    withinSpan(end, att) shouldBe true
    insideInterior(end, att.rect) shouldBe false
    val n = PortEdgeClamp.normal(att.face)
    if (abs(n.y) > 0f) {
        abs(next.x - end.x) shouldBeLessThanOrEqual 0.5f
        ((next.y - end.y) * n.y) shouldBeGreaterThanOrEqual minStub - 0.5f
    } else {
        abs(next.y - end.y) shouldBeLessThanOrEqual 0.5f
        ((next.x - end.x) * n.x) shouldBeGreaterThanOrEqual minStub - 0.5f
    }
}

private fun assertNoBacktrack(route: EdgeRoute) {
    val p = pts(route)
    for (i in 0 until p.size - 2) {
        val a = p[i]
        val b = p[i + 1]
        val c = p[i + 2]
        if (abs(a.x - b.x) <= 0.5f && abs(b.x - c.x) <= 0.5f) (((b.y - a.y) * (c.y - b.y)) >= 0f) shouldBe true
        if (abs(a.y - b.y) <= 0.5f && abs(b.y - c.y) <= 0.5f) (((b.x - a.x) * (c.x - b.x)) >= 0f) shouldBe true
    }
}

private fun assertOrthogonal(route: EdgeRoute) {
    val p = pts(route)
    for (i in 0 until p.size - 1) {
        (abs(p[i].x - p[i + 1].x) <= 0.5f || abs(p[i].y - p[i + 1].y) <= 0.5f) shouldBe true
    }
}

class PortEdgeClampTest :
    FunSpec(body = {
        val far = Point(x = 300f, y = 260f)
        val minStub = PortEdgeClamp.ARROW_LEN_PX

        for (face in PortFace.entries) {
            val n = PortEdgeClamp.normal(face)
            val att = PortAttachment(rect = rectAt(100f, 100f), face = face)
            val outerPoint = Point(x = 100f + n.x * 80f, y = 100f + n.y * 80f)

            test("source $face: diagonal Direct route ends on the face with a perpendicular stub") {
                val r =
                    PortEdgeClamp.clamp(
                        route = EdgeRoute.Direct(source = Point(100f, 100f), target = far),
                        source = att,
                        target = null,
                        sourceArrowhead = true,
                        targetArrowhead = true,
                    )
                checkEnd(route = r, att = att, atStart = true, minStub = minStub)
            }

            test("target $face: diagonal Direct route ends on the face with a perpendicular stub") {
                val r =
                    PortEdgeClamp.clamp(
                        route = EdgeRoute.Direct(source = far, target = Point(100f, 100f)),
                        source = null,
                        target = att,
                        sourceArrowhead = true,
                        targetArrowhead = true,
                    )
                checkEnd(route = r, att = att, atStart = false, minStub = minStub)
            }

            test("source $face: kink directly at the port gets a stub") {
                val route =
                    EdgeRoute.OrthogonalRounded(
                        source = Point(100f, 100f),
                        target = far,
                        waypoints =
                            listOf(
                                Point(
                                    x =
                                        100f + n.y * 0f + (
                                            if (n.y !=
                                                0f
                                            ) {
                                                60f
                                            } else {
                                                0f
                                            }
                                        ),
                                    y = 100f + (if (n.y == 0f) 60f else 0f),
                                ),
                            ),
                        cornerRadiusPx = 4f,
                    )
                val r = PortEdgeClamp.clamp(route = route, source = att, target = null, sourceArrowhead = true, targetArrowhead = true)
                checkEnd(route = r, att = att, atStart = true, minStub = minStub)
            }

            test("target $face: too short last segment is lengthened to the stub") {
                val tip = Point(x = outerPoint.x - n.x * 70f, y = outerPoint.y - n.y * 70f)
                val route =
                    EdgeRoute.OrthogonalRounded(
                        source = far,
                        target = Point(100f, 100f),
                        waypoints =
                            listOf(
                                Point(x = far.x, y = if (n.y != 0f) 100f + n.y * 4f else far.y),
                                Point(x = if (n.y != 0f) 100f else 100f + n.x * 4f, y = if (n.y != 0f) 100f + n.y * 4f else far.y),
                            ),
                        cornerRadiusPx = 4f,
                    )
                val r = PortEdgeClamp.clamp(route = route, source = null, target = att, sourceArrowhead = true, targetArrowhead = true)
                checkEnd(route = r, att = att, atStart = false, minStub = minStub)
                tip.x.isNaN() shouldBe false
            }
        }

        test("no attachment leaves the route identical") {
            val route = EdgeRoute.Direct(source = Point(1f, 2f), target = Point(30f, 40f))
            PortEdgeClamp.clamp(route = route, source = null, target = null, sourceArrowhead = true, targetArrowhead = true) shouldBe route
        }

        test("a route that already ends on the border with a long stub is only normalised, not reshaped") {
            val att = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Bottom)
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(100f, 106f),
                    target = Point(100f, 300f),
                    waypoints = emptyList(),
                    cornerRadiusPx = 4f,
                )
            PortEdgeClamp.clamp(route = route, source = att, target = null, sourceArrowhead = true, targetArrowhead = true) shouldBe route
        }

        test("faceTowards picks the face looking at the neighbour") {
            val r = rectAt(100f, 100f)
            PortEdgeClamp.faceTowards(rect = r, horizontalAxis = true, neighbor = Point(200f, 100f)) shouldBe PortFace.Right
            PortEdgeClamp.faceTowards(rect = r, horizontalAxis = true, neighbor = Point(0f, 100f)) shouldBe PortFace.Left
            PortEdgeClamp.faceTowards(rect = r, horizontalAxis = false, neighbor = Point(100f, 0f)) shouldBe PortFace.Top
        }

        test("both ends attached (port to port): perpendicular stubs on both faces, orthogonal, no backtrack") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Right)
            val tgt = PortAttachment(rect = rectAt(300f, 220f), face = PortFace.Left)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(300f, 220f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("both ends attached, vertical faces (pin to pin): L/Z rebuild with two stubs") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Bottom)
            val tgt = PortAttachment(rect = rectAt(220f, 300f), face = PortFace.Top)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(220f, 300f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("both ends attached on perpendicular faces yields an L route") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Right)
            val tgt = PortAttachment(rect = rectAt(300f, 300f), face = PortFace.Top)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(300f, 300f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("back-to-back faces on the same row do not fold back through the ports") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Right)
            val tgt = PortAttachment(rect = rectAt(50f, 100f), face = PortFace.Left)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(50f, 100f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("back-to-back faces on the same column do not fold back through the ports") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Bottom)
            val tgt = PortAttachment(rect = rectAt(100f, 50f), face = PortFace.Top)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(100f, 50f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("a first way point behind the face does not make the edge run back over its own port") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Right)
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(100f, 100f),
                    target = Point(60f, 200f),
                    waypoints = listOf(Point(60f, 100f)),
                    cornerRadiusPx = 4f,
                )
            val r = PortEdgeClamp.clamp(route = route, source = src, target = null, sourceArrowhead = true, targetArrowhead = true)
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            assertOrthogonal(r)
            assertNoBacktrack(r)
        }

        test("the inner face flip (opposite face) is honoured at both ends") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Right)
            val tgt = PortAttachment(rect = rectAt(200f, 100f), face = PortFace.Right)
            val r =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(200f, 100f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = true,
                    targetArrowhead = true,
                )
            checkEnd(route = r, att = src, atStart = true, minStub = minStub)
            checkEnd(route = r, att = tgt, atStart = false, minStub = minStub)
            assertNoBacktrack(r)
        }

        // PAL 2C-b1: the stub is only required where an arrowhead is drawn.
        for (face in PortFace.entries) {
            val n = PortEdgeClamp.normal(face)
            val att = PortAttachment(rect = rectAt(100f, 100f), face = face)

            test("source $face, with arrowhead: ends on the border, perpendicular, last segment at least the arrow length") {
                val r =
                    PortEdgeClamp.clamp(
                        route = EdgeRoute.Direct(source = Point(100f, 100f), target = far),
                        source = att,
                        target = null,
                        sourceArrowhead = true,
                        targetArrowhead = false,
                    )
                checkEnd(route = r, att = att, atStart = true, minStub = PortEdgeClamp.ARROW_LEN_PX)
                val p = pts(r)
                val seg = abs((p[1].x - p[0].x) * n.x + (p[1].y - p[0].y) * n.y)
                seg shouldBeGreaterThanOrEqual PortEdgeClamp.ARROW_LEN_PX - 0.5f
            }

            test("source $face, without arrowhead: ends on the border and leaves perpendicular, no forced stub") {
                val r =
                    PortEdgeClamp.clamp(
                        route = EdgeRoute.Direct(source = Point(100f, 100f), target = far),
                        source = att,
                        target = null,
                        sourceArrowhead = false,
                        targetArrowhead = false,
                    )
                checkEnd(route = r, att = att, atStart = true, minStub = 0f)
                assertOrthogonal(r)
                assertNoBacktrack(r)
            }

            test("target $face, without arrowhead: a straight route ends on the border without extra points") {
                val bp = PortEdgeClamp.borderPoint(att)
                val startPt = Point(x = bp.x + n.x * 80f, y = bp.y + n.y * 80f)
                val route =
                    EdgeRoute.OrthogonalRounded(
                        source = startPt,
                        target = Point(100f, 100f),
                        waypoints = emptyList(),
                        cornerRadiusPx = 4f,
                    )
                val r = PortEdgeClamp.clamp(route = route, source = null, target = att, sourceArrowhead = false, targetArrowhead = false)
                val p = pts(r)
                p shouldBe listOf(startPt, bp)
            }
        }

        test("arrowless end followed by a bend gets a stub of at least the corner radius, not a flush sub-pixel stub") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Top)
            val tgt = PortAttachment(rect = rectAt(160f, 40f), face = PortFace.Bottom)
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(100f, 100f),
                    target = Point(160f, 40f),
                    waypoints = listOf(Point(100f, 93.5f), Point(160f, 93.5f)),
                    cornerRadiusPx = 4f,
                )
            val out =
                PortEdgeClamp.clamp(
                    route = route,
                    source = src,
                    target = tgt,
                    sourceArrowhead = false,
                    targetArrowhead = false,
                )
            checkEnd(out, src, atStart = true, minStub = 4f)
            checkEnd(out, tgt, atStart = false, minStub = 4f)
            assertNoBacktrack(out)
        }

        test("an arrowless straight single segment may still touch the port directly") {
            val src = PortAttachment(rect = rectAt(100f, 100f), face = PortFace.Top)
            val tgt = PortAttachment(rect = rectAt(100f, 40f), face = PortFace.Bottom)
            val out =
                PortEdgeClamp.clamp(
                    route = EdgeRoute.Direct(source = Point(100f, 100f), target = Point(100f, 40f)),
                    source = src,
                    target = tgt,
                    sourceArrowhead = false,
                    targetArrowhead = false,
                )
            pts(out).size shouldBe 2
        }
    })
