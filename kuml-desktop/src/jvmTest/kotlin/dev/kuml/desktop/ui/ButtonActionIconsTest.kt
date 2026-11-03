package dev.kuml.desktop.ui

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class ButtonActionIconsTest :
    FunSpec({
        test("every button action has a 24x24 icon") {
            ButtonAction.entries.forEach { action ->
                withClue(action.name) {
                    val icon = buttonIcon(action)
                    icon.viewportWidth shouldBe 24f
                    icon.viewportHeight shouldBe 24f
                }
            }
        }

        test("every exception has a reason") {
            ButtonIconException.entries.forEach { e -> withClue(e.name) { e.reason.shouldNotBeBlank() } }
        }

        test("the same meaning shares one symbol") {
            buttonIcon(ButtonAction.CLOSE) shouldBe buttonIcon(ButtonAction.CANCEL)
            buttonIcon(ButtonAction.CLOSE) shouldBe buttonIcon(ButtonAction.DECLINE)
            buttonIcon(ButtonAction.REGENERATE) shouldBe buttonIcon(ButtonAction.RELOAD)
        }
    })
