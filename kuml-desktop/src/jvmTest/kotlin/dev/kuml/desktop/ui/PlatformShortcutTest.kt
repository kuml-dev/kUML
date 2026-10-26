package dev.kuml.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlatformShortcutTest :
    FunSpec({

        test("macOS uses the command key (meta), not ctrl") {
            platformShortcut(key = Key.S, os = "Mac OS X") shouldBe KeyShortcut(key = Key.S, meta = true)
        }

        test("Windows and Linux use ctrl") {
            platformShortcut(key = Key.S, os = "Windows 11") shouldBe KeyShortcut(key = Key.S, ctrl = true)
            platformShortcut(key = Key.S, os = "Linux") shouldBe KeyShortcut(key = Key.S, ctrl = true)
        }

        test("shift and alt are carried over on every platform") {
            platformShortcut(key = Key.Z, shift = true, os = "Mac OS X") shouldBe KeyShortcut(key = Key.Z, meta = true, shift = true)
            platformShortcut(key = Key.Z, shift = true, os = "Linux") shouldBe KeyShortcut(key = Key.Z, ctrl = true, shift = true)
            platformShortcut(key = Key.One, alt = true, os = "Windows 10") shouldBe KeyShortcut(key = Key.One, ctrl = true, alt = true)
        }

        test("platform detection and label") {
            isMacOs("Mac OS X") shouldBe true
            isMacOs("Darwin") shouldBe true
            isMacOs("Windows 11") shouldBe false
            isMacOs("Linux") shouldBe false
            commandKeyLabel("Mac OS X") shouldBe "⌘"
            commandKeyLabel("Linux") shouldBe "Ctrl"
        }

        test("isSendShortcut needs the command modifier of the platform plus Enter on key down") {
            val down = androidx.compose.ui.input.key.KeyEventType.KeyDown
            val up = androidx.compose.ui.input.key.KeyEventType.KeyUp
            isSendShortcut(key = Key.Enter, isMetaPressed = true, isCtrlPressed = false, type = down, os = "Mac OS X") shouldBe true
            isSendShortcut(key = Key.Enter, isMetaPressed = false, isCtrlPressed = true, type = down, os = "Mac OS X") shouldBe false
            isSendShortcut(key = Key.Enter, isMetaPressed = false, isCtrlPressed = true, type = down, os = "Linux") shouldBe true
            isSendShortcut(key = Key.Enter, isMetaPressed = true, isCtrlPressed = false, type = down, os = "Windows 11") shouldBe false
            isSendShortcut(key = Key.NumPadEnter, isMetaPressed = false, isCtrlPressed = true, type = down, os = "Linux") shouldBe true
        }

        test("isSendShortcut ignores plain Enter, other keys and key up") {
            val down = androidx.compose.ui.input.key.KeyEventType.KeyDown
            val up = androidx.compose.ui.input.key.KeyEventType.KeyUp
            isSendShortcut(key = Key.Enter, isMetaPressed = false, isCtrlPressed = false, type = down, os = "Linux") shouldBe false
            isSendShortcut(key = Key.A, isMetaPressed = true, isCtrlPressed = true, type = down, os = "Linux") shouldBe false
            isSendShortcut(key = Key.Enter, isMetaPressed = true, isCtrlPressed = true, type = up, os = "Linux") shouldBe false
        }
    })
