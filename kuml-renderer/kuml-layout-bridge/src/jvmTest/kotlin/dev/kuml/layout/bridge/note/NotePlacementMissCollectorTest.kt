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
    })
