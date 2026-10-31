@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.port

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x, y), size = Size(w, h))

private fun orthogonal(path: List<Point>): Boolean =
    (0 until path.size - 1).all { abs(path[it].x - path[it + 1].x) < 0.01f || abs(path[it].y - path[it + 1].y) < 0.01f }

class IbdObstacleRouterTest :
    FunSpec({
        test("candidates come in a fixed order: L horizontal-first, L vertical-first, Z with 50 % vertical, Z with 50 % horizontal") {
            val c = IbdObstacleRouter.candidates(from = Point(0f, 0f), to = Point(100f, 50f))
            c shouldHaveSize 4
            c[0] shouldBe listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 50f))
            c[1] shouldBe listOf(Point(0f, 0f), Point(0f, 50f), Point(100f, 50f))
            c[2] shouldBe listOf(Point(0f, 0f), Point(50f, 0f), Point(50f, 50f), Point(100f, 50f))
            c[3] shouldBe listOf(Point(0f, 0f), Point(0f, 25f), Point(100f, 25f), Point(100f, 50f))
        }

        test("aligned points have the straight line as only candidate") {
            IbdObstacleRouter.candidates(from = Point(0f, 10f), to = Point(80f, 10f)) shouldBe
                listOf(listOf(Point(0f, 10f), Point(80f, 10f)))
        }

        test("a free route takes the candidate with the fewest bends") {
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 0f),
                    fromDir = Point(1f, 0f),
                    to = Point(100f, 50f),
                    toDir = Point(-1f, 0f),
                    obstacles = emptyList(),
                )
            // facing ports: the Z shape (3 segments) has fewer bends than an L that has to turn into the target sideways
            p shouldBe listOf(Point(0f, 0f), Point(50f, 0f), Point(50f, 50f), Point(100f, 50f))
        }

        test("A* detours around a part that blocks the only (straight) candidate") {
            val blocker = box(80f, 0f, 40f, 100f)
            val from = Point(0f, 50f)
            val to = Point(200f, 50f)
            val p =
                IbdObstacleRouter.route(
                    from = from,
                    fromDir = Point(1f, 0f),
                    to = to,
                    toDir = Point(-1f, 0f),
                    obstacles = listOf(blocker),
                )
            p.first() shouldBe from
            p.last() shouldBe to
            orthogonal(p) shouldBe true
            IbdObstacleRouter.collisions(path = p, obstacles = listOf(blocker)) shouldBe 0
            (p.size > 2) shouldBe true
        }

        test("the first segment never runs against the exit direction, the last never along the entry normal") {
            val blocker = box(80f, 0f, 40f, 100f)
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 50f),
                    fromDir = Point(1f, 0f),
                    to = Point(200f, 50f),
                    toDir = Point(-1f, 0f),
                    obstacles = listOf(blocker),
                )
            (p[1].x - p[0].x >= 0f) shouldBe true
            (p[p.size - 1].x - p[p.size - 2].x >= 0f) shouldBe true
        }

        test("an unreachable target yields the least-colliding candidate and does not throw") {
            val wall = box(150f, 0f, 120f, 120f)
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 50f),
                    fromDir = Point(1f, 0f),
                    to = Point(200f, 50f),
                    toDir = Point(-1f, 0f),
                    obstacles = listOf(wall),
                )
            p.first() shouldBe Point(0f, 50f)
            p.last() shouldBe Point(200f, 50f)
        }

        test("the search is bounded: a dense field of parts terminates with a path") {
            val obstacles = buildList { for (i in 0 until 12) for (j in 0 until 12) add(box(60f + i * 40f, 60f + j * 40f, 28f, 28f)) }
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 0f),
                    fromDir = Point(1f, 0f),
                    to = Point(560f, 560f),
                    toDir = Point(0f, -1f),
                    obstacles = obstacles,
                )
            p.first() shouldBe Point(0f, 0f)
            p.last() shouldBe Point(560f, 560f)
        }

        test("routing is deterministic") {
            val obstacles = listOf(box(80f, 0f, 40f, 100f), box(150f, 60f, 30f, 80f), box(40f, 120f, 120f, 30f))

            fun run() =
                IbdObstacleRouter.route(
                    from = Point(0f, 50f),
                    fromDir = Point(1f, 0f),
                    to = Point(220f, 130f),
                    toDir = Point(-1f, 0f),
                    obstacles = obstacles,
                )
            run() shouldBe run()
        }

        test("segmentHitsInterior: touching the border is no hit, crossing the interior is") {
            val r = box(10f, 10f, 20f, 20f)
            IbdObstacleRouter.segmentHitsInterior(Point(0f, 10f), Point(40f, 10f), r) shouldBe false
            IbdObstacleRouter.segmentHitsInterior(Point(0f, 20f), Point(40f, 20f), r) shouldBe true
            IbdObstacleRouter.segmentHitsInterior(Point(20f, 0f), Point(20f, 5f), r) shouldBe false
        }

        test("extra obstacles (labels) are avoided; those containing an end point are ignored") {
            val label = box(40f, 30f, 60f, 20f)
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 40f),
                    fromDir = Point(1f, 0f),
                    to = Point(140f, 40f),
                    toDir = Point(-1f, 0f),
                    obstacles = emptyList(),
                    extraObstacles = listOf(label),
                )
            orthogonal(p) shouldBe true
            IbdObstacleRouter.collisions(p, listOf(label)) shouldBe 0
            // an extra obstacle around the start point does not block the route
            val around =
                IbdObstacleRouter.route(
                    from = Point(0f, 40f),
                    fromDir = Point(1f, 0f),
                    to = Point(140f, 40f),
                    toDir = Point(-1f, 0f),
                    obstacles = emptyList(),
                    extraObstacles = listOf(box(-10f, 30f, 30f, 20f)),
                )
            around shouldBe listOf(Point(0f, 40f), Point(140f, 40f))
        }

        test("a huge obstacle count does not allocate a dense grid; the least-colliding candidate is returned") {
            val many = (0 until 1500).map { box(10f + it * 3f, 5f + (it % 7) * 9f, 2f, 4f) }
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 0f),
                    fromDir = Point(1f, 0f),
                    to = Point(5000f, 100f),
                    toDir = Point(-1f, 0f),
                    obstacles = many,
                    forceSearch = true,
                )
            (p.size >= 2) shouldBe true
            IbdObstacleRouter.aStar(Point(0f, 0f), Point(5000f, 100f), many) shouldBe null
        }

        test("far-away extra obstacles do not exhaust the grid: the route still detours around the wall") {
            val wall = box(100f, -100f, 40f, 200f)
            val far = (0 until 200).map { box(400f + it * 37f, 300f + it * 19f, 30f, 12f) }
            val p =
                IbdObstacleRouter.route(
                    from = Point(0f, 0f),
                    fromDir = Point(1f, 0f),
                    to = Point(300f, 0f),
                    toDir = Point(-1f, 0f),
                    obstacles = listOf(wall),
                    extraObstacles = far,
                )
            orthogonal(p) shouldBe true
            IbdObstacleRouter.collisions(p, listOf(wall)) shouldBe 0
        }
    })
