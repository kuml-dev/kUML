package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.Sentiment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * PAL 2B-c: an anchor line never lies on a lane boundary (Line of Interaction / Visibility / Internal Interaction) or a
 * card edge, and two anchor tracks keep 6 px. Pure geometry from [BlueprintGeometry], independent of fonts.
 *
 * Geometry note (read from BlueprintGridConstants): ROW_HEIGHT 112, CARD_MARGIN_TOP 8, CARD_MARGIN_BOTTOM 24. The 8 px
 * zone above a card cannot hold a track with 6 px lane-line clearance; the 24 px zone below holds three.
 */
class BlueprintAnchorOverlapInvariantTest :
    StringSpec({
        val model =
            blueprint(name = "Service") {
                val staff = actor(name = "Staff", role = ActorRole.STAFF)
                lateinit var a: String
                lateinit var b: String
                lateinit var c: String
                lateinit var d: String
                lateinit var e: String
                phase(name = "One") {
                    a = customer(name = "Fills in", sentiment = Sentiment.NEUTRAL)
                    b = backstage(name = "Checks", actor = staff)
                    c = frontstage(name = "Greets", actor = staff)
                }
                phase(name = "Two") {
                    d = customer(name = "Waits", sentiment = Sentiment.NEGATIVE)
                    e = backstage(name = "Approves", actor = staff)
                }
                note(text = "First note", a, b, c, id = "n1")
                note(text = "Second note", d, e, b, id = "n2")
                note(text = "Third note", a, d, id = "n3")
                blueprintDiagram(name = "Blueprint", emotionCurve = false)
            }

        "no anchor track lies within 3 px of a lane boundary or card edge, tracks keep 6 px" {
            val geo = BlueprintGeometry(model = model, visibleLayers = BlueprintLayer.entries.toSet(), showEmotionCurve = false)
            val lines = geo.layers.flatMap { l -> geo.bandY(l).let { listOf(it.start, it.endInclusive) } }
            val horizontals = ArrayList<Triple<String, Pair<Double, Double>, Double>>() // route id, x range, y
            for (n in geo.placedNotes) {
                for ((id, pts) in n.routes) {
                    for ((p, q) in pts.zipWithNext()) {
                        // tracks run inside the lanes; the short exit segment note -> gutter lies right of them
                        if (abs(p.second - q.second) < 0.01 && abs(p.first - q.first) > 6.0 && minOf(p.first, q.first) < geo.contentRight) {
                            horizontals += Triple(id, minOf(p.first, q.first) to maxOf(p.first, q.first), p.second)
                        }
                    }
                }
            }
            (horizontals.size >= 6) shouldBe true
            val bad = ArrayList<String>()
            for ((id, _, y) in horizontals) {
                if (lines.any { abs(it - y) < 3.0 }) bad += "$id track at y=$y lies on a lane boundary"
            }
            // the right-aligned captions of the separator lines (centre 16 px above the boundary, up to 210 px wide)
            for ((id, range, y) in horizontals) {
                for (layer in geo.layers.dropLast(1)) {
                    val centre = geo.bandY(layer).endInclusive - 16.0
                    if (abs(y - centre) < 9.0 &&
                        range.second > geo.contentRight - 210.0
                    ) {
                        bad += "$id track at y=$y crosses a separator caption"
                    }
                }
            }
            for (i in horizontals.indices) {
                for (j in i + 1 until horizontals.size) {
                    val (ia, ra, ya) = horizontals[i]
                    val (ib, rb, yb) = horizontals[j]
                    val overlap = minOf(ra.second, rb.second) - maxOf(ra.first, rb.first)
                    if (ia != ib &&
                        overlap > 0.0 &&
                        abs(ya - yb) < 6.0
                    ) {
                        bad += "$ia and $ib tracks $ya / $yb overlap over ${overlap.toInt()} px"
                    }
                }
            }
            bad.joinToString("\n") shouldBe ""
        }
    })
