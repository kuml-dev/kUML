package dev.kuml.desktop.simulation

import dev.kuml.widget.compose.GuardEditAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The save flow of a guard edit is a pure mapping, so the confirmation step needs no window to be tested. */
class GuardSaveStepTest :
    FunSpec({
        test("a plain transition is applied right away") {
            guardSaveStep(GuardEditAction.Apply) shouldBe GuardSaveStep.ApplyNow
        }

        test("a protected transition asks for confirmation first") {
            guardSaveStep(GuardEditAction.Confirm) shouldBe GuardSaveStep.AskConfirmation
        }

        test("a denied edit is ignored") {
            guardSaveStep(GuardEditAction.Denied) shouldBe GuardSaveStep.Ignore
        }
    })
