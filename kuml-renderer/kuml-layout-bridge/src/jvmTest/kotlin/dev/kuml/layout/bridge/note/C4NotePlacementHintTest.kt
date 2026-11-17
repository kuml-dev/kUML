@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun layoutOf(nodes: Map<String, Rect>) =
    LayoutResult(
        engineId = LayoutEngineId("test"),
        seed = null,
        canvas = Size(width = 900f, height = 700f),
        nodes = nodes.entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
        edges = emptyMap(),
        groups = emptyMap(),
    )

private fun modelWith(placement: NotePlacement?) =
    C4Model(
        id = "m",
        name = "Model",
        elements =
            listOf(
                C4Person(id = "a", name = "Node A"),
                C4SoftwareSystem(id = "b", name = "Node B"),
            ),
        notes = listOf(KumlNote(id = "n1", body = "Note text", sourceLine = 7, placement = placement)),
        noteLinks = listOf(KumlNoteLink(id = "l1", noteId = "n1", targetId = "a")),
    )

private val diagram = SystemContextDiagram(id = "d", name = "Context", elements = listOf("a", "b"))

private fun NotePlacementResult.noteRect(): Rect = layout.nodes.getValue(NodeId("n1")).bounds

class C4NotePlacementHintTest :
    FunSpec({
        val a = rect(x = 400f, y = 300f, w = 120f, h = 60f)
        val free = layoutOf(mapOf("a" to a, "b" to rect(x = 100f, y = 600f, w = 120f, h = 60f)))
        val blocked =
            layoutOf(mapOf("a" to a, "b" to rect(x = 100f, y = 600f, w = 120f, h = 60f), "roof" to rect(-400f, -400f, 1800f, 676f)))

        fun run(
            placement: NotePlacement?,
            layout: LayoutResult = free,
        ): Pair<NotePlacementResult, List<PlacementHintMiss>> =
            NotePlacementMissCollector.collecting {
                C4NotePlacement.place(model = modelWith(placement), diagram = diagram, layout = layout)
            }

        test("each side hint puts the note on that side of the anchor and reports nothing") {
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val (res, misses) = run(side)
                val n = res.noteRect()
                res.hintNotHonoured.shouldBeEmpty()
                misses.shouldBeEmpty()
                when (side) {
                    NotePlacement.ABOVE -> (n.bottom <= a.top + 0.5f) shouldBe true
                    NotePlacement.BELOW -> (n.top >= a.bottom - 0.5f) shouldBe true
                    NotePlacement.LEFT -> (n.right <= a.left + 0.5f) shouldBe true
                    else -> (n.left >= a.right - 0.5f) shouldBe true
                }
            }
        }

        test("an impossible side falls back to the default placement with exactly one W004 miss carrying the source line") {
            val plain = run(null, blocked).first
            val (hinted, misses) = run(NotePlacement.ABOVE, blocked)
            hinted.noteRect() shouldBe plain.noteRect()
            hinted.hintNotHonoured shouldHaveSize 1
            misses shouldHaveSize 1
            val miss = misses.single()
            miss.noteId shouldBe "n1"
            miss.sourceLine shouldBe 7
            miss.wanted shouldBe NotePlacement.ABOVE
        }

        test("two identical runs give equal results") {
            run(NotePlacement.LEFT).first shouldBe run(NotePlacement.LEFT).first
        }

        test("no hint and NEAR give exactly the same layout and never warn") {
            val (plain, plainMisses) = run(null)
            val (near, nearMisses) = run(NotePlacement.NEAR)
            near.layout shouldBe plain.layout
            near.hintNotHonoured.shouldBeEmpty()
            plainMisses.shouldBeEmpty()
            nearMisses.shouldBeEmpty()
        }

        test("a missed hint is reported once per note, and the bridge never reports W005") {
            val (_, misses) = run(NotePlacement.ABOVE, blocked)
            misses.map { it.noteId } shouldBe listOf("n1")
        }
    })
