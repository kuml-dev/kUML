@file:Suppress("RequireNamedArguments") // geometry literals in tests

package dev.kuml.io.svg.blueprint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs

/** Geometry of the blueprint note anchors, independent of any font: no pixel is shared, lane lines are crossed at right angles only. */
class BlueprintNoteRouterTest :
    StringSpec({
        fun target(
            id: String,
            col: Int,
            lane: Int,
        ): BlueprintNoteRouter.Target {
            val laneTop = 100.0 + lane * 112.0
            val left = 150.0 + col * 200.0 + 8.0
            return BlueprintNoteRouter.Target(
                linkId = id,
                laneTop = laneTop,
                cardLeft = left,
                cardTop = laneTop + 8.0,
                cardRight = left + 184.0,
                cardBottom = laneTop + 112.0 - 24.0,
            )
        }

        fun route(vararg targets: BlueprintNoteRouter.Target) =
            BlueprintNoteRouter.route(
                noteX = 600.0,
                noteY = 140.0,
                noteH = 80.0,
                targets = targets.toList(),
                laneSlots = HashMap(),
                cardEntries = HashMap(),
            )

        fun segments(pts: List<Pair<Double, Double>>) = pts.zipWithNext()

        /** Length over which two axis-parallel segments lie on the same line (within 0.5 px). */
        fun shared(
            a: Pair<Pair<Double, Double>, Pair<Double, Double>>,
            b: Pair<Pair<Double, Double>, Pair<Double, Double>>,
        ): Double {
            val (a1, a2) = a
            val (b1, b2) = b
            if (abs(a1.second - a2.second) < 0.01 && abs(b1.second - b2.second) < 0.01 && abs(a1.second - b1.second) < 0.5) {
                return maxOf(
                    0.0,
                    minOf(maxOf(a1.first, a2.first), maxOf(b1.first, b2.first)) -
                        maxOf(minOf(a1.first, a2.first), minOf(b1.first, b2.first)),
                )
            }
            if (abs(a1.first - a2.first) < 0.01 && abs(b1.first - b2.first) < 0.01 && abs(a1.first - b1.first) < 0.5) {
                return maxOf(
                    0.0,
                    minOf(maxOf(a1.second, a2.second), maxOf(b1.second, b2.second)) -
                        maxOf(minOf(a1.second, a2.second), minOf(b1.second, b2.second)),
                )
            }
            return 0.0
        }

        "two anchors give two different polylines with exit points at least 16 px apart" {
            val r = route(target("a", col = 0, lane = 0), target("b", col = 0, lane = 2))
            r.size shouldBe 2
            (r[0].second == r[1].second) shouldBe false
            abs(r[0].second.first().second - r[1].second.first().second) shouldBeGreaterThanOrEqualTo 16.0
        }

        "no two segments of different lines lie on the same stretch" {
            val r = route(target("a", 0, 0), target("b", 1, 0), target("c", 0, 2))
            for (i in r.indices) {
                for (j in i + 1 until r.size) {
                    for (s1 in segments(r[i].second)) for (s2 in segments(r[j].second)) shared(s1, s2) shouldBe 0.0
                }
            }
        }

        "a line has at most four segments, all axis parallel, the first leaves the note border" {
            val r = route(target("a", 0, 0), target("b", 1, 1), target("c", 0, 2))
            r.forEach { (_, pts) ->
                (pts.size - 1) shouldBeGreaterThanOrEqualTo 1
                (pts.size - 1 <= 4) shouldBe true
                segments(pts).forEach { (p, q) -> (abs(p.first - q.first) < 0.01 || abs(p.second - q.second) < 0.01) shouldBe true }
                pts.first().first shouldBe 600.0
            }
        }

        "tracks run in the margin zone of the lane, never on a card border or a lane boundary" {
            val t = target("a", 0, 1)
            val pts = route(t).single().second
            val track = pts[2].second
            val laneTop = t.laneTop
            // not on the lane top (4 px clearance), not on the card top/bottom
            abs(track - laneTop) shouldBeGreaterThanOrEqualTo 3.5
            abs(track - t.cardTop) shouldBeGreaterThanOrEqualTo 3.5
            // the vertical part stays right of the lanes: lane lines are crossed only by the track itself, which lies inside its lane
            (pts[1].first < 600.0) shouldBe true
            (pts[1].first > 600.0 - BlueprintNoteRouter.gutterFor(1) - 0.1) shouldBe true
        }

        "a second line into the same lane uses another track and a second line into the same card another entry" {
            val laneSlots = HashMap<Double, Int>()
            val entries = HashMap<Pair<Double, Double>, Int>()
            val r1 = BlueprintNoteRouter.route(600.0, 140.0, 80.0, listOf(target("a", 0, 0)), laneSlots, entries)
            val r2 = BlueprintNoteRouter.route(600.0, 240.0, 80.0, listOf(target("b", 0, 0)), laneSlots, entries)
            r1.single().second[2].second shouldNotBe r2.single().second[2].second
            r1
                .single()
                .second
                .last()
                .first shouldNotBe
                r2
                    .single()
                    .second
                    .last()
                    .first
        }

        "the gutter grows with the number of anchors and the note grows to fit its exit points" {
            BlueprintNoteRouter.gutterFor(1) shouldBe 16.0
            (BlueprintNoteRouter.gutterFor(4) > BlueprintNoteRouter.gutterFor(2)) shouldBe true
            BlueprintNoteRouter.minNoteHeight(3) shouldBeGreaterThanOrEqualTo 52.0
        }
    })
