@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe

class NoteKindLayoutTest :
    FunSpec(body = {
        val long = "The quick brown fox jumps over the lazy dog and keeps running through the whole text field"

        test("NOTE keeps the measures of before the kinds were added") {
            NoteLayout.textInset(NoteFrameStyle.DOG_EAR, NoteKind.NOTE) shouldBe NoteLayout.H_PADDING / 2f
            NoteLayout.textInset(NoteFrameStyle.BRACKET, NoteKind.NOTE) shouldBe NoteLayout.H_PADDING / 2f + 8f
            NoteLayout.sizeOf(body = long) shouldBe NoteLayout.sizeOf(body = long, kind = NoteKind.NOTE)
            NoteLayout.sizeOf(body = "hi").width shouldBe 140f
            NoteLayout.sizeOf(body = "hi").height shouldBe 60f
            NoteLayout.displayText(body = long) shouldBe NoteLayout.displayText(body = long, kind = NoteKind.NOTE)
        }

        test("a symbol moves the text right by the icon column and the symbol sits directly left of it") {
            for (frame in NoteFrameStyle.entries) {
                NoteLayout.textInset(frame, NoteKind.INFO) shouldBe NoteLayout.textInset(frame, NoteKind.NOTE) + NoteLayout.ICON_COL
                NoteLayout.iconX(frame, NoteKind.INFO) shouldBe NoteLayout.textInset(frame, NoteKind.NOTE)
            }
        }

        test("with a symbol the text wraps narrower, so the box stays within the maximum and holds every line") {
            val plain = NoteLayout.displayText(body = long)
            val kinded = NoteLayout.displayText(body = long, kind = NoteKind.WARNING)
            (kinded.lines.size >= plain.lines.size) shouldBe true
            for (maxWidth in listOf(NoteLayout.MAX_W, NoteLayout.STM_MAX_W)) {
                val size = NoteLayout.sizeOf(body = long, maxWidth = maxWidth, kind = NoteKind.WARNING)
                size.width shouldBeLessThanOrEqualTo maxWidth
                val widest =
                    NoteLayout
                        .displayText(body = long, maxInnerWidth = maxWidth - NoteLayout.H_PADDING, kind = NoteKind.WARNING)
                        .lines
                        .maxOf { it.length * NoteLayout.BODY_CHAR_PX }
                (widest + NoteLayout.H_PADDING + NoteLayout.ICON_COL) shouldBeLessThanOrEqualTo maxWidth + 0.01f
            }
        }

        test("a kinded note is at least as tall as the symbol") {
            NoteLayout.sizeOf(body = "", kind = NoteKind.INFO).height shouldBeGreaterThan
                (NoteLayout.ICON_SIZE + NoteLayout.V_PADDING - 0.01f)
        }

        test("the renderer wrap formula matches the sizing: the drawn lines are the lines the box was sized for") {
            val size = NoteLayout.sizeOf(body = long, kind = NoteKind.RISK)
            val eps = 0.5f
            val inner = minOf(size.width - NoteLayout.H_PADDING + eps, NoteLayout.MAX_W - NoteLayout.H_PADDING)
            NoteLayout.displayText(body = long, maxInnerWidth = inner, kind = NoteKind.RISK).lines shouldBe
                NoteLayout.displayText(body = long, kind = NoteKind.RISK).lines
        }

        test("truncation diagnostics use the kinded width") {
            val body = "word ".repeat(2000)
            NoteLayout.isTruncated(body, NoteKind.INFO) shouldBe true
        }
    })
