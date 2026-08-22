package dev.kuml.desktop.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the V3.7.4 compact-icon-button design decision (32/18) against silent rollback -- no
 * Compose UI harness needed, these are plain value comparisons on the module-level constants.
 */
class IconTooltipButtonTest :
    FunSpec({

        test("COMPACT_ICON_BUTTON_SIZE is 32.dp") {
            COMPACT_ICON_BUTTON_SIZE.value shouldBe 32f
        }

        test("COMPACT_ICON_SIZE is 18.dp") {
            COMPACT_ICON_SIZE.value shouldBe 18f
        }

        test("the button is bigger than the icon it contains") {
            (COMPACT_ICON_BUTTON_SIZE.value > COMPACT_ICON_SIZE.value) shouldBe true
        }
    })
