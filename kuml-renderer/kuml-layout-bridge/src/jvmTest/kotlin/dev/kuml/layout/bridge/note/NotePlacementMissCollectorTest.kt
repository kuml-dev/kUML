@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class NotePlacementMissCollectorTest :
    FunSpec(body = {
        val miss = PlacementHintMiss(noteId = "n", wanted = NotePlacement.ABOVE, placed = NotePlacement.LEFT, sourceLine = 7)

        test("report outside a collecting scope does nothing") {
            NotePlacementMissCollector.report(listOf(miss))
            NotePlacementMissCollector.collecting { 1 }.second.shouldBeEmpty()
        }

        test("collecting returns the value and each note once") {
            val (value, misses) =
                NotePlacementMissCollector.collecting {
                    NotePlacementMissCollector.report(listOf(miss))
                    NotePlacementMissCollector.report(listOf(miss, miss.copy(noteId = "m")))
                    42
                }
            value shouldBe 42
            misses.map { it.noteId } shouldBe listOf("n", "m")
        }

        test("the scope is restored after an exception, nested scopes do not leak, other threads do not see the scope") {
            runCatching { NotePlacementMissCollector.collecting { error("boom") } }
            NotePlacementMissCollector.report(listOf(miss))
            val (inner, outer) =
                NotePlacementMissCollector.collecting {
                    NotePlacementMissCollector.collecting { NotePlacementMissCollector.report(listOf(miss)) }.second.size
                }
            inner shouldBe 1
            outer.shouldBeEmpty()
            val (_, other) =
                NotePlacementMissCollector.collecting {
                    val t = Thread { NotePlacementMissCollector.report(listOf(miss)) }
                    t.start()
                    t.join()
                }
            other.shouldBeEmpty()
        }

        test("the message names the note and the sides, never a body") {
            miss.message() shouldBe "placement hint not honoured: n, wanted ABOVE, placed LEFT"
        }

        test("Sysml2NotePlacement reports a blocked hint to the collector with the script line, and nothing when honoured") {
            val model =
                sysml2Model("M") {
                    val a = partDef("A")
                    bdd("D") {
                        include(a)
                        comment("SECRET-BODY", a, id = "hinted", placement = NotePlacement.ABOVE)
                    }
                }
            val diagram = model.diagrams.filterIsInstance<BdDiagram>().single()
            val aId = model.definitions.first().id

            fun layout(withRoof: Boolean): LayoutResult {
                val nodes = mutableMapOf(NodeId(aId) to NodeLayout(bounds = Rect(Point(400f, 300f), Size(120f, 60f))))
                if (withRoof) nodes[NodeId("roof")] = NodeLayout(bounds = Rect(Point(-400f, -400f), Size(1800f, 676f)))
                return LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(900f, 700f),
                    nodes = nodes,
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            }

            val (blocked, misses) =
                NotePlacementMissCollector.collecting {
                    Sysml2NotePlacement.place(model = model, diagram = diagram, layout = layout(true))
                }
            misses.map { it.noteId } shouldBe listOf("hinted")
            blocked.hintNotHonoured.single().sourceLine shouldBe misses.single().sourceLine
            misses.single().message().contains("SECRET") shouldBe false

            val (_, none) =
                NotePlacementMissCollector.collecting {
                    Sysml2NotePlacement.place(model = model, diagram = diagram, layout = layout(false))
                }
            none.shouldBeEmpty()
        }

        // PAL Welle X4: W004 is also reported when the note sits on the wished side but beside the anchor.
        fun hintedLayout(
            hint: NotePlacement,
            blockers: Map<String, Rect>,
        ): Triple<dev.kuml.sysml2.Sysml2Model, BdDiagram, LayoutResult> {
            val model =
                sysml2Model("M") {
                    val a = partDef("A")
                    bdd("D") {
                        include(a)
                        comment("note", a, id = "hinted", placement = hint)
                    }
                }
            val diagram = model.diagrams.filterIsInstance<BdDiagram>().single()
            val aId = model.definitions.first().id
            val nodes = mutableMapOf(NodeId(aId) to NodeLayout(bounds = Rect(Point(400f, 300f), Size(120f, 60f))))
            blockers.forEach { (k, r) -> nodes[NodeId(k)] = NodeLayout(bounds = r) }
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(900f, 700f),
                    nodes = nodes,
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            return Triple(model, diagram, layout)
        }

        test("a hint the placer cannot honour above the anchor is reported, and the note is not on the wished side beside the anchor") {
            val (m0, d0, l0) = hintedLayout(NotePlacement.ABOVE, emptyMap())
            val (_, free) = NotePlacementMissCollector.collecting { Sysml2NotePlacement.place(model = m0, diagram = d0, layout = l0) }
            free.shouldBeEmpty()
            val (m1, d1, l1) = hintedLayout(NotePlacement.ABOVE, mapOf("roof" to Rect(Point(430f, -400f), Size(60f, 696f))))
            val (res, side) = NotePlacementMissCollector.collecting { Sysml2NotePlacement.place(model = m1, diagram = d1, layout = l1) }
            val rect =
                res.layout.nodes
                    .getValue(NodeId("hinted"))
                    .bounds
            val box = Rect(Point(400f, 300f), Size(120f, 60f))
            side.map { it.noteId } shouldBe listOf("hinted")
            side.single().placed shouldBe NotePlacer.sideOfStrict(rect = rect, box = box)
            NotePlacer.onSideStrict(rect = rect, box = box, side = NotePlacement.ABOVE) shouldBe false
        }

        test("strict side: the note must also overlap the anchor box along the axis by 1 px, a point anchor only has to lie inside") {
            val box = Rect(Point(400f, 300f), Size(120f, 60f))

            fun note(
                x: Float,
                y: Float,
            ) = Rect(Point(x, y), Size(100f, 40f))
            // ABOVE: on the wished side and overlapping in x
            NotePlacer.onSideStrict(note(x = 450f, y = 200f), box, NotePlacement.ABOVE) shouldBe true
            NotePlacer.onSideStrict(note(x = 330f, y = 200f), box, NotePlacement.ABOVE) shouldBe true // 30 px overlap
            NotePlacer.onSideStrict(note(x = 419.5f, y = 200f), box, NotePlacement.ABOVE) shouldBe true // 0.5 px of the box overlaps
            NotePlacer.onSideStrict(note(x = 300f, y = 200f), box, NotePlacement.ABOVE) shouldBe false // right edge 400: touches only
            NotePlacer.onSideStrict(note(x = 100f, y = 200f), box, NotePlacement.ABOVE) shouldBe false // far left, still above
            NotePlacer.onSideStrict(note(x = 450f, y = 280f), box, NotePlacement.ABOVE) shouldBe false // not above (bottom 320)
            // BELOW
            NotePlacer.onSideStrict(note(x = 450f, y = 380f), box, NotePlacement.BELOW) shouldBe true
            NotePlacer.onSideStrict(note(x = 700f, y = 380f), box, NotePlacement.BELOW) shouldBe false
            // LEFT / RIGHT: vertical overlap
            NotePlacer.onSideStrict(note(x = 250f, y = 280f), box, NotePlacement.LEFT) shouldBe true
            NotePlacer.onSideStrict(note(x = 250f, y = 100f), box, NotePlacement.LEFT) shouldBe false
            NotePlacer.onSideStrict(note(x = 560f, y = 330f), box, NotePlacement.RIGHT) shouldBe true
            NotePlacer.onSideStrict(note(x = 560f, y = 420f), box, NotePlacement.RIGHT) shouldBe false
            // a point anchor lies inside the range, or it does not
            val point = Rect(Point(450f, 340f), Size(0f, 0f))
            NotePlacer.onSideStrict(note(x = 400f, y = 200f), point, NotePlacement.ABOVE) shouldBe true
            NotePlacer.onSideStrict(note(x = 500f, y = 200f), point, NotePlacement.ABOVE) shouldBe false
            // NEAR is always fine; sideOfStrict names NEAR when no side with overlap exists
            NotePlacer.onSideStrict(note(x = 0f, y = 0f), box, NotePlacement.NEAR) shouldBe true
            NotePlacer.sideOfStrict(note(x = 450f, y = 200f), box) shouldBe NotePlacement.ABOVE
            NotePlacer.sideOfStrict(note(x = 100f, y = 200f), box) shouldBe NotePlacement.NEAR
        }
    })
