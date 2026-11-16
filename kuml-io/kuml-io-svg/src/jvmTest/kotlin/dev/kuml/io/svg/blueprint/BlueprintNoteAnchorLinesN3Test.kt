package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.Sentiment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * Nacharbeit 92: an anchor line never runs through a foreign step card, and parallel stretches of different anchor
 * lines keep 6 px. Pure geometry from [BlueprintGeometry], independent of fonts.
 *
 * The 24 px zone below a card holds three tracks; the fourth anchor into one lane takes the outer channel and must then
 * enter through a column gap, not straight up through the cards of the lanes below. The shared note gutter (right of
 * the lanes) is excluded from the spacing check: verticals of different notes can still overlap there (documented in
 * the CHANGELOG).
 */
class BlueprintNoteAnchorLinesN3Test :
    StringSpec({
        val model =
            blueprint(name = "Service") {
                val staff = actor(name = "Staff", role = ActorRole.STAFF)
                lateinit var c1: String
                lateinit var c2: String
                lateinit var c3: String
                lateinit var c4: String
                lateinit var b1: String
                lateinit var b2: String
                lateinit var b3: String
                lateinit var b4: String
                phase(name = "Request") {
                    c1 = customer(name = "Fills in the form", sentiment = Sentiment.NEUTRAL)
                    b1 = backstage(name = "Checks the data", actor = staff)
                }
                phase(name = "Admission") {
                    c2 = customer(name = "Waits", sentiment = Sentiment.NEGATIVE)
                    b2 = backstage(name = "Approves", actor = staff)
                }
                phase(name = "Delivery") {
                    c3 = customer(name = "Receives", sentiment = Sentiment.POSITIVE)
                    b3 = backstage(name = "Ships", actor = staff)
                }
                phase(name = "Closing") {
                    c4 = customer(name = "Rates", sentiment = Sentiment.POSITIVE)
                    b4 = backstage(name = "Archives", actor = staff)
                }
                note(text = "Two lanes", c1, b4, id = "n1")
                note(text = "Same column A", c2, b2, id = "n2")
                note(text = "Same column B", c3, b3, id = "n3")
                note(text = "Single", c4, id = "n4")
                blueprintDiagram(name = "Blueprint", emotionCurve = false)
            }

        val geo = BlueprintGeometry(model = model, visibleLayers = BlueprintLayer.entries.toSet(), showEmotionCurve = false)

        fun segments() =
            geo.placedNotes.flatMap { n ->
                n.routes.map { (id, pts) -> id to pts.zipWithNext() }
            }

        "no anchor line runs through a step card (1 px inside the card counts)" {
            val cards = model.steps.mapNotNull { geo.cardRect(it.id) }
            val bad = ArrayList<String>()
            for ((id, segs) in segments()) {
                for ((p, q) in segs) {
                    for (c in cards) {
                        val l = c.x + 1.0
                        val r = c.x + c.w - 1.0
                        val t = c.y + 1.0
                        val b = c.y + c.h - 1.0
                        val hit =
                            if (abs(p.first - q.first) < 0.01) {
                                p.first in l..r && minOf(p.second, q.second) < b && maxOf(p.second, q.second) > t
                            } else {
                                p.second in t..b && minOf(p.first, q.first) < r && maxOf(p.first, q.first) > l
                            }
                        if (hit) bad += "$id segment $p -> $q runs through the card ${c.step.id}"
                    }
                }
            }
            bad.joinToString("\n") shouldBe ""
        }

        "parallel stretches of different anchor lines left of the gutter keep 6 px" {
            val flat = segments().flatMap { (id, segs) -> segs.map { id to it } }
            val bad = ArrayList<String>()
            for (i in flat.indices) {
                for (j in i + 1 until flat.size) {
                    val (ia, a) = flat[i]
                    val (ib, b) = flat[j]
                    if (ia == ib) continue
                    val (a1, a2) = a
                    val (b1, b2) = b
                    val bothH = abs(a1.second - a2.second) < 0.01 && abs(b1.second - b2.second) < 0.01
                    val bothV = abs(a1.first - a2.first) < 0.01 && abs(b1.first - b2.first) < 0.01
                    if (bothV && a1.first > geo.contentRight) continue // the shared gutter
                    val dist: Double
                    val overlap: Double
                    if (bothH) {
                        dist = abs(a1.second - b1.second)
                        overlap =
                            minOf(maxOf(a1.first, a2.first), maxOf(b1.first, b2.first)) -
                            maxOf(minOf(a1.first, a2.first), minOf(b1.first, b2.first))
                    } else if (bothV) {
                        dist = abs(a1.first - b1.first)
                        overlap =
                            minOf(maxOf(a1.second, a2.second), maxOf(b1.second, b2.second)) -
                            maxOf(minOf(a1.second, a2.second), minOf(b1.second, b2.second))
                    } else {
                        continue
                    }
                    if (overlap > 2.0 && dist < 6.0) bad += "$ia and $ib are $dist px apart over ${overlap.toInt()} px"
                }
            }
            bad.joinToString("\n") shouldBe ""
        }

        "every anchor line still ends on its card" {
            for ((id, pts) in geo.placedNotes.flatMap { it.routes }) {
                val end = pts.last()
                val onCard =
                    model.steps.mapNotNull { geo.cardRect(it.id) }.any { c ->
                        end.first in (c.x - 0.01)..(c.x + c.w + 0.01) && end.second in (c.y - 0.01)..(c.y + c.h + 0.01)
                    }
                (id to onCard) shouldBe (id to true)
            }
        }
    })
