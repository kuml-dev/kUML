package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun Rect.overlaps(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

private val horizontal = listOf(Point(x = 100f, y = 100f), Point(x = 300f, y = 100f))

class LabelPlacerTest :
    FunSpec({
        test("an edge label on an empty canvas sits next to its line, centred, within 12 px") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e1#center",
                                role = LabelRole.EDGE_CENTER,
                                ownerId = "e1",
                                size = Size(width = 60f, height = 14f),
                                polyline = horizontal,
                            ),
                        ),
                    index = index,
                )
            val p = out.getValue("e1#center")
            p.onLine shouldBe false
            (LabelPlacer.rectDistanceToPolyline(r = p.rect, pts = horizontal) <= 12f) shouldBe true
            // centred on the segment (t = 0.5)
            (kotlin.math.abs(p.rect.origin.x + p.rect.size.width / 2f - 200f) < 0.01f) shouldBe true
        }

        test("a blocked first candidate moves the label to a free one; the result is deterministic") {
            fun run(): Map<String, PlacedLabel> {
                val index = ObstacleIndex()
                index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
                // a node right above the line where the best candidate would sit
                index.addRect(kind = ObstacleKind.NODE, r = rect(x = 160f, y = 60f, w = 80f, h = 34f), ownerId = "n1")
                return LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e1#center",
                                role = LabelRole.EDGE_CENTER,
                                ownerId = "e1",
                                size = Size(width = 60f, height = 14f),
                                polyline = horizontal,
                            ),
                        ),
                    index = index,
                )
            }
            val a = run()
            val b = run()
            a shouldBe b
            a.getValue("e1#center").rect.overlaps(rect(x = 160f, y = 60f, w = 80f, h = 34f)) shouldBe false
        }

        test("the emergency on-line candidate is only chosen when every regular one collides") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
            // Block every regular position with nodes above and below the whole line.
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 90f, y = 20f, w = 220f, h = 70f), ownerId = "up")
            index.addRect(kind = ObstacleKind.NODE, r = rect(x = 90f, y = 110f, w = 220f, h = 60f), ownerId = "down")
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e1#center",
                                role = LabelRole.EDGE_CENTER,
                                ownerId = "e1",
                                size = Size(width = 60f, height = 14f),
                                polyline = horizontal,
                            ),
                        ),
                    index = index,
                )
            out.getValue("e1#center").onLine shouldBe true
        }

        test("multiplicity labels sit close to their edge end, not mid-line") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e1#src-mult",
                                role = LabelRole.MULTIPLICITY,
                                ownerId = "e1",
                                size = Size(width = 20f, height = 14f),
                                polyline = horizontal,
                                end = LabelEnd.SOURCE,
                            ),
                            LabelRequest(
                                id = "e1#tgt-mult",
                                role = LabelRole.MULTIPLICITY,
                                ownerId = "e1",
                                size = Size(width = 20f, height = 14f),
                                polyline = horizontal,
                                end = LabelEnd.TARGET,
                            ),
                        ),
                    index = index,
                )
            val src = out.getValue("e1#src-mult").rect
            val tgt = out.getValue("e1#tgt-mult").rect
            (src.origin.x - 100f < 20f) shouldBe true
            (300f - (tgt.origin.x + tgt.size.width) < 20f) shouldBe true
        }

        test("multiplicity and role at the same end lie on opposite sides of the line") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "e1#src-role",
                                role = LabelRole.ROLE,
                                ownerId = "e1",
                                size = Size(width = 40f, height = 14f),
                                polyline = horizontal,
                                end = LabelEnd.SOURCE,
                            ),
                            LabelRequest(
                                id = "e1#src-mult",
                                role = LabelRole.MULTIPLICITY,
                                ownerId = "e1",
                                size = Size(width = 20f, height = 14f),
                                polyline = horizontal,
                                end = LabelEnd.SOURCE,
                            ),
                        ),
                    index = index,
                )
            val m = out.getValue("e1#src-mult").rect
            val r = out.getValue("e1#src-role").rect
            val mAbove = m.origin.y + m.size.height / 2f < 100f
            val rAbove = r.origin.y + r.size.height / 2f < 100f
            (mAbove != rAbove) shouldBe true
            m.overlaps(r) shouldBe false
            (LabelPlacer.rectDistanceToPolyline(r = m, pts = horizontal) <= 12f) shouldBe true
            (LabelPlacer.rectDistanceToPolyline(r = r, pts = horizontal) <= 12f) shouldBe true
        }

        test("a port label avoids the side where its edge docks and does not cover the port") {
            val index = ObstacleIndex()
            val port = rect(x = 100f, y = 100f, w = 10f, h = 10f)
            index.addRect(kind = ObstacleKind.PORT, r = port, ownerId = "p1")
            val out =
                LabelPlacer.place(
                    requests =
                        listOf(
                            LabelRequest(
                                id = "p1#label",
                                role = LabelRole.PORT,
                                ownerId = "p1",
                                size = Size(width = 30f, height = 12f),
                                anchorRect = port,
                                excludedSide = LabelSide.WEST,
                            ),
                        ),
                    index = index,
                )
            val l = out.getValue("p1#label").rect
            l.overlaps(port) shouldBe false
            (l.origin.x + l.size.width <= port.origin.x) shouldBe false // not on the excluded west side
        }

        test("labels placed earlier are obstacles for later ones") {
            val index = ObstacleIndex()
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e1")
            index.addPolyline(kind = ObstacleKind.EDGE, pts = horizontal, ownerId = "e2")
            val reqs =
                listOf("e1", "e2").map {
                    LabelRequest(
                        id = "$it#center",
                        role = LabelRole.EDGE_CENTER,
                        ownerId = it,
                        size = Size(width = 60f, height = 14f),
                        polyline = horizontal,
                    )
                }
            val out = LabelPlacer.place(requests = reqs, index = index)
            out.getValue("e1#center").rect.overlaps(out.getValue("e2#center").rect) shouldBe false
        }

        test("a dense hub terminates inside the budget and stays deterministic") {
            fun run(): Map<String, PlacedLabel> {
                val index = ObstacleIndex()
                val reqs = ArrayList<LabelRequest>()
                for (i in 0 until 40) {
                    val line =
                        listOf(
                            Point(x = 200f, y = 200f),
                            Point(
                                x = 200f + 150f * kotlin.math.cos(i * 0.157f),
                                y = 200f + 150f * kotlin.math.sin(i * 0.157f),
                            ),
                        )
                    index.addPolyline(kind = ObstacleKind.EDGE, pts = line, ownerId = "e$i")
                    reqs +=
                        LabelRequest(
                            id = "e$i#c",
                            role = LabelRole.EDGE_CENTER,
                            ownerId = "e$i",
                            size = Size(width = 50f, height = 14f),
                            polyline = line,
                        )
                    reqs +=
                        LabelRequest(
                            id = "e$i#m",
                            role = LabelRole.MULTIPLICITY,
                            ownerId = "e$i",
                            size = Size(width = 20f, height = 14f),
                            polyline = line,
                            end = LabelEnd.SOURCE,
                        )
                }
                return LabelPlacer.place(requests = reqs, index = index)
            }
            val a = run()
            a.size shouldBe 80
            a shouldBe run()
        }
    })
