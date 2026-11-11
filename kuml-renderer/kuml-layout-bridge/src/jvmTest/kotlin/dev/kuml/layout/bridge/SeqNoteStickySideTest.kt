package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.NoteAnchorRef
import dev.kuml.layout.bridge.note.NoteMeasure
import dev.kuml.layout.bridge.note.NotePlacementInput
import dev.kuml.layout.bridge.note.SeqNotePlacer
import dev.kuml.layout.bridge.note.SeqNoteScene
import dev.kuml.layout.bridge.note.SeqStem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 68 (i): several notes on the same single anchor stay on one side of the stem. Synthetic scene with a
 * fixed note size, so nothing depends on font metrics.
 */
class SeqNoteStickySideTest :
    FunSpec(body = {
        val fixed =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size = Size(width = 100f, height = 40f)
            }

        fun layout() =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(width = 600f, height = 800f),
                nodes = emptyMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        /** Stem of `a` at x = 100 (head directly above, blocking upward search), `b` at x = 300; a busy band right of `a`. */
        fun scene(withBusyBand: Boolean) =
            SeqNoteScene(
                stems = mapOf("a" to SeqStem(x = 100f, top = 40f, bottom = 700f), "b" to SeqStem(x = 300f, top = 40f, bottom = 700f)),
                heads =
                    mapOf(
                        "a" to Rect(origin = Point(x = 50f, y = 0f), size = Size(width = 100f, height = 20f)),
                        "b" to Rect(origin = Point(x = 250f, y = 0f), size = Size(width = 100f, height = 20f)),
                    ),
                blockers =
                    if (withBusyBand) {
                        listOf(Rect(origin = Point(x = 116f, y = 120f), size = Size(width = 150f, height = 80f)))
                    } else {
                        emptyList()
                    },
            )

        fun note(id: String) = NotePlacementInput(noteId = id, body = id, anchors = listOf(NoteAnchorRef(linkId = "l-$id", targetId = "a")))

        fun overlap(
            p: Rect,
            q: Rect,
        ): Boolean =
            p.origin.x < q.origin.x + q.size.width &&
                q.origin.x < p.origin.x + p.size.width &&
                p.origin.y < q.origin.y + q.size.height &&
                q.origin.y < p.origin.y + p.size.height

        fun rectOf(
            result: dev.kuml.layout.bridge.note.NotePlacementResult,
            id: String,
        ) = result.layout.nodes
            .getValue(NodeId(id))
            .bounds

        test("two notes on one anchor land on the same side of the stem and do not overlap").config(timeout = 60.seconds) {
            val result =
                SeqNotePlacer.place(
                    layout = layout(),
                    notes = listOf(note("Note A"), note("Note B")),
                    scene = scene(withBusyBand = true),
                    measure = fixed,
                )
            val a = rectOf(result, "Note A")
            val b = rectOf(result, "Note B")
            val stemX = 100f + result.dx
            (a.origin.x > stemX) shouldBe true
            (b.origin.x > stemX) shouldBe true
            overlap(a, b) shouldBe false
        }

        test("a single note keeps the first inner column (right of the stem)").config(timeout = 60.seconds) {
            val result =
                SeqNotePlacer.place(
                    layout = layout(),
                    notes = listOf(note("Note A")),
                    scene = scene(withBusyBand = true),
                    measure = fixed,
                )
            val a = rectOf(result, "Note A")
            a.origin.x shouldBe 116f + result.dx
            a.origin.y shouldBe 44f + result.dy
        }
    })
