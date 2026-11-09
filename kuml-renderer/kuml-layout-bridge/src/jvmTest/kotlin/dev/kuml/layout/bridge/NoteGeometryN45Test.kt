package dev.kuml.layout.bridge

import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 45: the text area of a note never reaches into the folded corner, and a one-line note is not taller than
 * its text needs. The text rectangle is `x from H_PAD_LEFT + icon column to w - H_PAD_RIGHT`, `y from 8 to h - 8`.
 */
class NoteGeometryN45Test :
    FunSpec(body = {
        val bodies =
            mapOf(
                "one char" to "x",
                "full line" to "word ".repeat(30).trim(),
                "three lines" to "alpha beta gamma\ndelta epsilon zeta\neta theta iota",
                "over-long word" to "A".repeat(200),
            )

        for (kind in NoteKind.entries) {
            for ((label, body) in bodies) {
                test("dog-ear $kind / $label: the widest text line stays left of the fold column, the height is at least MIN_H") {
                    val size = NoteLayout.sizeOf(body = body, frame = NoteFrameStyle.DOG_EAR, kind = kind)
                    val inset = NoteLayout.textInset(frame = NoteFrameStyle.DOG_EAR, kind = kind)
                    val text =
                        NoteLayout.displayText(
                            body = body,
                            maxInnerWidth =
                                size.width - NoteLayout.horizontalPadding(NoteFrameStyle.DOG_EAR) + 0.5f,
                            kind = kind,
                        )
                    val widest =
                        text.styledLines?.maxOfOrNull { NoteLayout.lineWidth(it) }
                            ?: ((text.lines.maxOfOrNull { it.length } ?: 0) * NoteLayout.BODY_CHAR_PX)
                    // Right text edge <= box width - right padding (the fold column is DOG_EAR_SIZE wide plus air).
                    (inset + widest) shouldBeAtMost (size.width - NoteLayout.H_PAD_RIGHT_DOG_EAR + 0.6f)
                    (size.height >= NoteLayout.MIN_H) shouldBe true
                    // Vertical: lines plus 8 px above and below fit.
                    (size.height >= text.lines.size * NoteLayout.LINE_H + NoteLayout.V_PADDING - 0.01f) shouldBe true
                }
            }
        }

        test("a one-line note is 32 px high, with and without symbol") {
            NoteLayout.sizeOf(body = "Short").height shouldBe 32f
            NoteLayout.sizeOf(body = "Short", kind = NoteKind.INFO).height shouldBe 32f
        }

        test("bracket notes keep their 8 px right padding and the bracket text inset") {
            NoteLayout.H_PAD_RIGHT_BRACKET shouldBe 8f
            NoteLayout.textInset(frame = NoteFrameStyle.BRACKET, kind = NoteKind.NOTE) shouldBe 16f
            NoteLayout.horizontalPadding(NoteFrameStyle.BRACKET) shouldBe 24f
            NoteLayout.horizontalPadding(NoteFrameStyle.DOG_EAR) shouldBe 30f
        }

        test("the dog-ear wraps 14 px earlier than before the fix") {
            val body = "word ".repeat(60).trim()
            val size = NoteLayout.sizeOf(body = body, frame = NoteFrameStyle.DOG_EAR)
            val lines =
                NoteLayout
                    .displayText(
                        body = body,
                        maxInnerWidth =
                            size.width - NoteLayout.horizontalPadding(NoteFrameStyle.DOG_EAR) + 0.5f,
                    ).lines
            (lines.maxOf { it.length } * NoteLayout.BODY_CHAR_PX <= size.width - 30f + 0.6f) shouldBe true
        }
    })

private infix fun Float.shouldBeAtMost(other: Float) {
    (this <= other) shouldBe true
}
