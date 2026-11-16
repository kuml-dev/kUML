package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 92: the margin column with the fewest anchor-line crossings wins over the first admissible one. Synthetic
 * scene with a fixed note size, so nothing depends on font metrics.
 */
class SeqNoteAnchorCrossingTest :
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
                canvas = Size(width = 900f, height = 800f),
                nodes = emptyMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        val stemXs = mapOf("a" to 100f, "b" to 300f, "c" to 500f, "d" to 700f)

        /** Four lifelines; messages every 40 px across all of them block every inner column. */
        fun busyScene(ids: List<String>): SeqNoteScene {
            val xs = ids.map { stemXs.getValue(it) }
            return SeqNoteScene(
                stems = ids.associateWith { SeqStem(x = stemXs.getValue(it), top = 40f, bottom = 700f) },
                heads =
                    ids.associateWith {
                        Rect(
                            origin = Point(x = stemXs.getValue(it) - 50f, y = 0f),
                            size = Size(width = 100f, height = 20f),
                        )
                    },
                messageLines =
                    (0..15).map { i ->
                        listOf(Point(x = xs.min(), y = 80f + i * 40f), Point(x = xs.max(), y = 80f + i * 40f))
                    },
            )
        }

        fun note(
            id: String,
            target: String,
        ) = NotePlacementInput(noteId = id, body = id, anchors = listOf(NoteAnchorRef(linkId = "l-$id", targetId = target)))

        fun place(
            scene: SeqNoteScene,
            vararg notes: NotePlacementInput,
        ) = SeqNotePlacer.place(layout = layout(), notes = notes.toList(), scene = scene, measure = fixed)

        fun rectOf(
            result: NotePlacementResult,
            id: String,
        ) = result.layout.nodes
            .getValue(NodeId(id))
            .bounds

        test(
            "an anchor on the second of four lifelines takes the left margin (1 crossing) instead of the right one (2)",
        ).config(timeout = 60.seconds) {
            val result = place(busyScene(listOf("a", "b", "c", "d")), note("N", "b"))
            val r = rectOf(result, "N")
            // left of the first stem, not right of the last one
            (r.origin.x + r.size.width < 100f + result.dx) shouldBe true
            result.crossings shouldBe 1
        }

        test("an anchor on the third of four lifelines takes the right margin (1 crossing)").config(timeout = 60.seconds) {
            val result = place(busyScene(listOf("a", "b", "c", "d")), note("N", "c"))
            val r = rectOf(result, "N")
            (r.origin.x > 700f + result.dx) shouldBe true
            result.crossings shouldBe 1
        }

        test("equal crossings keep the column order: the right margin wins a tie").config(timeout = 60.seconds) {
            // b in the middle of a, b, c: one crossing on either side
            val result = place(busyScene(listOf("a", "b", "c")), note("N", "b"))
            val r = rectOf(result, "N")
            (r.origin.x > 500f + result.dx) shouldBe true
            result.crossings shouldBe 1
        }

        test("a free anchor keeps its inner column and crosses nothing").config(timeout = 60.seconds) {
            val scene =
                SeqNoteScene(
                    stems = mapOf("a" to SeqStem(x = 100f, top = 40f, bottom = 700f), "b" to SeqStem(x = 500f, top = 40f, bottom = 700f)),
                    heads =
                        mapOf(
                            "a" to Rect(origin = Point(x = 50f, y = 0f), size = Size(width = 100f, height = 20f)),
                            "b" to Rect(origin = Point(x = 450f, y = 0f), size = Size(width = 100f, height = 20f)),
                        ),
                )
            val result = place(scene, note("N", "a"))
            val r = rectOf(result, "N")
            r.origin.x shouldBe 116f + result.dx
            result.crossings shouldBe 0
        }
    })
