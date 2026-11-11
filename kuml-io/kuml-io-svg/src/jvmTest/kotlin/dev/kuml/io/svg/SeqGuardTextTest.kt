package dev.kuml.io.svg

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 68 (f): the text shown for a sequence-fragment guard. */
class SeqGuardTextTest :
    StringSpec({
        "empty, blank and bracketed-empty guards show nothing" {
            for (raw in listOf(null, "", "  ", "[]", "[ ]", " [  ] ")) {
                seqGuardDisplay(raw = raw) shouldBe null
            }
        }

        "real guards are wrapped in brackets exactly once" {
            seqGuardDisplay(raw = "x") shouldBe "[x]"
            seqGuardDisplay(raw = "[x]") shouldBe "[x]"
            seqGuardDisplay(raw = " [x] ") shouldBe "[x]"
        }
    })
