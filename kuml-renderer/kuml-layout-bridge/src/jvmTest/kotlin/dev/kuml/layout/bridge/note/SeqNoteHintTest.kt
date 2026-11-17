package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 102: the placement hint of notes in sequence diagrams. Hand-built scenes with a fixed note size, so nothing
 * depends on font metrics, fonts or absolute paths.
 */
class SeqNoteHintTest :
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
                canvas = Size(width = 1000f, height = 1200f),
                nodes = emptyMap(),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        val xs = mapOf("a" to 100f, "b" to 300f, "c" to 500f, "d" to 700f)

        /** Lifelines [ids]; the stem of each starts at its entry in [tops] (default 300), heads sit right above the stem. */
        fun scene(
            ids: List<String> = listOf("a", "b", "c"),
            tops: Map<String, Float> = emptyMap(),
            blockers: List<Rect> = emptyList(),
        ) = SeqNoteScene(
            stems = ids.associateWith { SeqStem(x = xs.getValue(it), top = tops[it] ?: 300f, bottom = 1000f) },
            heads =
                ids.associateWith {
                    Rect(origin = Point(x = xs.getValue(it) - 50f, y = (tops[it] ?: 300f) - 40f), size = Size(width = 100f, height = 20f))
                },
            blockers = blockers,
        )

        fun note(
            id: String,
            vararg targets: String,
            placement: NotePlacement? = null,
        ) = NotePlacementInput(
            noteId = id,
            body = id,
            anchors = targets.map { NoteAnchorRef(linkId = "l-$id-$it", targetId = it) },
            placement = placement,
        )

        fun place(
            notes: List<NotePlacementInput>,
            scene: SeqNoteScene = scene(),
        ) = SeqNotePlacer.place(layout = layout(), notes = notes, scene = scene, measure = fixed)

        fun NotePlacementResult.rect(id: String): Rect = layout.nodes.getValue(NodeId(id)).bounds

        fun Rect.anchorY(top: Float = 300f): Float = top + 24f

        test("RIGHT lands right of the stem, LEFT left of it").config(timeout = 60.seconds) {
            val r = place(listOf(note("Note 1", "b", placement = NotePlacement.RIGHT)))
            r.rect("Note 1").left shouldBeGreaterThanOrEqualTo 316f
            val l = place(listOf(note("Note 1", "b", placement = NotePlacement.LEFT)))
            l.rect("Note 1").right shouldBeLessThanOrEqualTo 284f
            r.hintNotHonoured.shouldBeEmpty()
            l.hintNotHonoured.shouldBeEmpty()
        }

        test(
            "a hint overrides the sticky side of a sibling note, the sticky entry stays with the first note",
        ).config(timeout = 60.seconds) {
            val r =
                place(
                    listOf(
                        note("Note 1", "b"),
                        note("Note 2", "b", placement = NotePlacement.LEFT),
                        note("Note 3", "b"),
                    ),
                )
            r.rect("Note 1").left shouldBeGreaterThanOrEqualTo 316f
            r.rect("Note 2").right shouldBeLessThanOrEqualTo 284f
            r.rect("Note 3").left shouldBeGreaterThanOrEqualTo 316f
            r.hintNotHonoured.shouldBeEmpty()
        }

        test("RIGHT wins over the side with fewer crossings").config(timeout = 60.seconds) {
            // anchors a and b: the unhinted note takes the left margin (one stem crossed), RIGHT goes right (more crossings)
            val plain = place(listOf(note("Note 1", "a", "b")))
            plain.rect("Note 1").left shouldBe 0f // left margin: the layout is shifted right by the overhang
            val hinted = place(listOf(note("Note 1", "a", "b", placement = NotePlacement.RIGHT)))
            hinted.rect("Note 1").left shouldBeGreaterThanOrEqualTo 574f
            (hinted.crossings > plain.crossings) shouldBe true
            hinted.hintNotHonoured.shouldBeEmpty()
        }

        test("ABOVE ends above the anchor row, BELOW starts below it").config(timeout = 60.seconds) {
            val above = place(listOf(note("Note 1", "b", placement = NotePlacement.ABOVE)))
            above.rect("Note 1").bottom shouldBeLessThanOrEqualTo 324f - 12f
            above.hintNotHonoured.shouldBeEmpty()
            val below = place(listOf(note("Note 1", "b", placement = NotePlacement.BELOW)))
            below.rect("Note 1").top shouldBeGreaterThanOrEqualTo 324f + 12f
            below.hintNotHonoured.shouldBeEmpty()
        }

        test("a tall note is still placed above and below, the window starts at the minimum shift").config(timeout = 60.seconds) {
            val tall =
                object : NoteMeasure {
                    override fun measure(
                        body: String,
                        maxWidth: Float,
                    ): Size = Size(width = 100f, height = 120f)
                }
            for (hint in listOf(NotePlacement.ABOVE, NotePlacement.BELOW)) {
                val r =
                    SeqNotePlacer.place(
                        layout = layout(),
                        notes = listOf(note("Note 1", "b", placement = hint)),
                        scene = scene(),
                        measure = tall,
                    )
                if (hint == NotePlacement.ABOVE) {
                    r.rect("Note 1").bottom shouldBeLessThanOrEqualTo 312f
                } else {
                    r.rect("Note 1").top shouldBeGreaterThanOrEqualTo 336f
                }
                r.hintNotHonoured.shouldBeEmpty()
            }
        }

        test(
            "several anchors: LEFT goes to the left margin although the right is closer, RIGHT to the right margin",
        ).config(timeout = 60.seconds) {
            val s = scene(ids = listOf("a", "b", "c", "d"))
            val plain = place(listOf(note("Note 1", "c", "d")), s)
            (plain.rect("Note 1").left > 700f) shouldBe true
            val left = place(listOf(note("Note 1", "c", "d", placement = NotePlacement.LEFT)), s)
            left.rect("Note 1").left shouldBe 0f
            left.hintNotHonoured.shouldBeEmpty()
            val right = place(listOf(note("Note 1", "c", "d", placement = NotePlacement.RIGHT)), s)
            (right.rect("Note 1").left > 700f) shouldBe true
        }

        test("several anchors: BELOW lies under the lowest anchor").config(timeout = 60.seconds) {
            val s = scene(tops = mapOf("c" to 500f))
            val r = place(listOf(note("Note 1", "a", "c", placement = NotePlacement.BELOW)), s)
            r.rect("Note 1").top shouldBeGreaterThanOrEqualTo (500f + 24f + 12f)
            r.hintNotHonoured.shouldBeEmpty()
        }

        test("an impossible hint equals the unhinted placement and reports one miss with honest sides").config(timeout = 60.seconds) {
            // one huge wall across everything above the anchor row: no anchor line can reach the stem from above
            val wall = Rect(origin = Point(x = -5000f, y = -5000f), size = Size(width = 10000f, height = 5310f))
            val s = scene(blockers = listOf(wall))
            val plain = place(listOf(note("Note 1", "b")), s)
            val hinted = place(listOf(note("Note 1", "b", placement = NotePlacement.ABOVE)), s)
            hinted.rect("Note 1") shouldBe plain.rect("Note 1")
            val miss = hinted.hintNotHonoured.single()
            miss.noteId shouldBe "Note 1"
            miss.wanted shouldBe NotePlacement.ABOVE
            miss.placed shouldBe (if (hinted.rect("Note 1").top >= 336f) NotePlacement.BELOW else NotePlacement.NEAR)
            // a message anchor can never be reached from a note above it: the miss reports the real side
            val withMessage =
                SeqNotePlacer.place(
                    layout = layout(),
                    notes = listOf(note("Note 1", "m", placement = NotePlacement.ABOVE)),
                    scene = scene().copy(messages = mapOf("m" to listOf(Point(x = 100f, y = 500f), Point(x = 300f, y = 500f)))),
                    measure = fixed,
                )
            withMessage.hintNotHonoured.shouldHaveSize(1)
        }

        test("a note on both sides blocked reports the real side for LEFT/RIGHT").config(timeout = 60.seconds) {
            val r = place(listOf(note("Note 1", "b", placement = NotePlacement.RIGHT)))
            r.hintNotHonoured.shouldBeEmpty()
            val loose =
                place(listOf(note("Note 1", "zzz", placement = NotePlacement.LEFT), note("Note 2", "zzz", placement = NotePlacement.RIGHT)))
            loose.hintNotHonoured.single().noteId shouldBe "Note 1"
            loose.hintNotHonoured.single().placed shouldBe NotePlacement.RIGHT
        }

        test("two runs give equal results").config(timeout = 60.seconds) {
            val notes = listOf(note("Note 1", "b", placement = NotePlacement.BELOW), note("Note 2", "a", placement = NotePlacement.LEFT))
            place(notes) shouldBe place(notes)
        }

        test("no hint and NEAR place exactly like no placement").config(timeout = 60.seconds) {
            val base = place(listOf(note("Note 1", "b"), note("Note 2", "a", "c")))
            val near =
                place(
                    listOf(
                        note("Note 1", "b", placement = NotePlacement.NEAR),
                        note("Note 2", "a", "c", placement = NotePlacement.NEAR),
                    ),
                )
            near shouldBe base
            base.hintNotHonoured.shouldBeEmpty()
        }
    })
