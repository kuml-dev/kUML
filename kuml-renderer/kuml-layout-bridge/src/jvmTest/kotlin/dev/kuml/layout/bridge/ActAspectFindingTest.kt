package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 71 (i): the aspect-ratio report of an activity diagram is measured on the final image. Pure function, no
 * engine and no worker process. (j): note text of an activity diagram stays clear of the folded corner.
 */
class ActAspectFindingTest :
    FunSpec(body = {
        test("a target missed on the final canvas yields exactly one W003 with target, actual value and the note-column hint")
            .config(timeout = 30.seconds) {
                val f = actAspectFinding(canvasWidth = 1212.0, canvasHeight = 764.0, target = 3.0, hasNoteColumns = true)!!
                f.code shouldBe LayoutCodes.ASPECT_UNREACHED
                f.message shouldContain "layout.aspectRatio = 3.0"
                f.message shouldContain "actual 1.59"
                f.message shouldContain "note columns beside the swimlanes widen the diagram"
            }

        test("without notes the message carries no note-column hint")
            .config(timeout = 30.seconds) {
                val f = actAspectFinding(canvasWidth = 1000.0, canvasHeight = 1000.0, target = 3.0, hasNoteColumns = false)!!
                f.message shouldNotContain "note columns"
                f.message shouldContain "actual 1.0"
            }

        test("a target reached on the final canvas emits no finding, on either side")
            .config(timeout = 30.seconds) {
                actAspectFinding(canvasWidth = 1212.0, canvasHeight = 764.0, target = 1.6, hasNoteColumns = true) shouldBe null
                actAspectFinding(canvasWidth = 764.0, canvasHeight = 1212.0, target = 0.63, hasNoteColumns = false) shouldBe null
            }

        test("a too tall final image is reported as well (explicit target is missed on both sides)")
            .config(timeout = 30.seconds) {
                actAspectFinding(canvasWidth = 500.0, canvasHeight = 1800.0, target = 1.6, hasNoteColumns = false)!!.code shouldBe
                    LayoutCodes.ASPECT_UNREACHED
            }

        test("degenerate canvases and targets emit nothing")
            .config(timeout = 30.seconds) {
                actAspectFinding(canvasWidth = 0.0, canvasHeight = 10.0, target = 1.6, hasNoteColumns = false) shouldBe null
                actAspectFinding(canvasWidth = 10.0, canvasHeight = 10.0, target = 0.0, hasNoteColumns = false) shouldBe null
            }

        val actBodies =
            listOf(
                NoteKind.WARNING to "WARNING: Warnung",
                NoteKind.NOTE to "First line of the note\nsecond line of the note\nTHIRD LINE IN CAPITALS ONLY",
                NoteKind.NOTE to
                    "A rather long note text that has to be wrapped over several lines because it is far too long for one line",
            )
        for ((kind, body) in actBodies) {
            test("activity note $kind '${body.take(16)}': text stays left of the fold column, height >= MIN_H")
                .config(timeout = 30.seconds) {
                    val size = NoteLayout.sizeOf(body = body, frame = NoteFrameStyle.DOG_EAR, kind = kind)
                    val inset = NoteLayout.textInset(frame = NoteFrameStyle.DOG_EAR, kind = kind)
                    val text =
                        NoteLayout.displayText(
                            body = body,
                            maxInnerWidth = size.width - NoteLayout.horizontalPadding(NoteFrameStyle.DOG_EAR) + 0.5f,
                            kind = kind,
                        )
                    val widest =
                        text.styledLines?.maxOfOrNull { NoteLayout.lineWidth(it) }
                            ?: ((text.lines.maxOfOrNull { it.length } ?: 0) * NoteLayout.BODY_CHAR_PX)
                    (inset + widest <= size.width - NoteLayout.DOG_EAR_SIZE - 8f + 0.6f) shouldBe true
                    (size.height >= NoteLayout.MIN_H) shouldBe true
                }
        }
    })
