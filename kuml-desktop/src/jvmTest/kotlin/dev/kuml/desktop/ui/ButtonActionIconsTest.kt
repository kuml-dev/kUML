package dev.kuml.desktop.ui

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
            buttonIcon(ButtonAction.ACCEPT) shouldBe buttonIcon(ButtonAction.ACKNOWLEDGE)
            buttonIcon(ButtonAction.ACCEPT) shouldBe KumlIcons.Check
        }

        test("reject has its own symbol and never looks like close") {
            buttonIcon(ButtonAction.REJECT) shouldNotBe buttonIcon(ButtonAction.CLOSE)
            buttonIcon(ButtonAction.REJECT) shouldBe KumlIcons.Reject
        }

        test("the part b2b actions map to their symbols") {
            buttonIcon(ButtonAction.MANAGE_PROVIDERS) shouldBe KumlIcons.Settings
            buttonIcon(ButtonAction.NEW_SESSION) shouldBe KumlIcons.ChatNew
            buttonIcon(ButtonAction.INSPECT) shouldBe KumlIcons.Search
            buttonIcon(ButtonAction.EDIT) shouldBe KumlIcons.Edit
            buttonIcon(ButtonAction.DELETE) shouldBe KumlIcons.Delete
            buttonIcon(ButtonAction.SEND) shouldBe KumlIcons.Send
            buttonIcon(ButtonAction.ADD) shouldBe KumlIcons.Add
        }
    })
