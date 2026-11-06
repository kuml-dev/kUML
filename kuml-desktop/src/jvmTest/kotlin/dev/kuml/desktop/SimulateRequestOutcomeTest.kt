package dev.kuml.desktop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SimulateRequestOutcomeTest :
    FunSpec({

        test("a rendered diagram that is not simulatable is refused") {
            simulateRequestOutcome(lastSvg = "<svg/>", simulatable = false) shouldBe SimulateRequestOutcome.REFUSE_UNSUPPORTED
        }

        test("a rendered simulatable diagram starts") {
            simulateRequestOutcome(lastSvg = "<svg/>", simulatable = true) shouldBe SimulateRequestOutcome.START
        }

        test("without a rendered diagram the request goes through to the session start") {
            simulateRequestOutcome(lastSvg = "", simulatable = false) shouldBe SimulateRequestOutcome.START
        }

        test("an empty svg and simulatable starts") {
            simulateRequestOutcome(lastSvg = "", simulatable = true) shouldBe SimulateRequestOutcome.START
        }

        test("a whitespace-only svg counts as no diagram") {
            simulateRequestOutcome(lastSvg = "  \n\t ", simulatable = false) shouldBe SimulateRequestOutcome.START
        }
    })
